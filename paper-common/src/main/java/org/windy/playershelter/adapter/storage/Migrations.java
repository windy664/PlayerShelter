package org.windy.playershelter.adapter.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * 版本化自动迁移（决策 #50；同 [[guildshelter-db-migrations]] 铁律：改表必须配套版本化迁移，别裸 ALTER）。
 *
 * <p>{@code ps_schema_history(version, applied_at)} 记录已应用版本；启动时把 &gt; 当前版本的迁移逐个
 * 应用并登记。SQL 力求两后端通用（SQLite/MySQL）；类型差异在此抹平。
 *
 * <p><b>幂等靠 Java 侧判断，不靠方言语法</b>：建表用 {@code IF NOT EXISTS}（两端都支持），但建索引与
 * 加列先查元数据确认不存在再执行。原因：MySQL 8 没有 {@code CREATE INDEX IF NOT EXISTS}（直接 1064
 * 语法错误，会在启动阶段把插件打死）；且 MySQL 的 DDL 隐式提交、事务回滚不了，一旦某步中途失败，
 * 下次启动就会撞上「索引/列已存在」——所以重放必须能自行接上。
 */
public final class Migrations {

    private final Database db;
    private final Logger log;

    public Migrations(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    /** 索引定义：表名 + 索引名 + 列（多列用逗号分隔）。 */
    record Index(String table, String name, String columns) {}

    /** 列定义：表名 + 列名 + 列类型/默认值（即 {@code ADD COLUMN} 后半段）。 */
    record Column(String table, String name, String definition) {}

    /** 一个迁移版本：建表语句 + 要保证存在的索引 + 要保证存在的列。 */
    record Step(int version, List<String> statements, List<Index> indices, List<Column> columns) {
        Step(int version, List<String> statements) {
            this(version, statements, List.of(), List.of());
        }

        Step(int version, List<String> statements, List<Index> indices) {
            this(version, statements, indices, List.of());
        }
    }

    private record LegacyLayoutBackfill(String ownerUuid, int initialChunks, int maxChunks,
                                        String sideLevels, int originX, int originZ) {}

    /** 全部迁移定义。新增表/列时<b>追加新版本</b>，绝不改旧版本。 */
    private List<Step> steps() {
        return stepsFor(db.isSqlite());
    }

    /**
     * 按方言生成迁移定义。抽成静态方法是为了让单测能<b>不经数据库</b>同时审查两种方言产出的 SQL
     * （MySQL 路径以前没有覆盖，才让 {@code CREATE INDEX IF NOT EXISTS} 这类方言问题漏过去）。
     */
    static List<Step> stepsFor(boolean sqlite) {
        // 跨后端类型：自增主键/大整数/文本。SQLite 用 INTEGER；MySQL 用 BIGINT。
        String big = sqlite ? "INTEGER" : "BIGINT";
        // MySQL 建表显式钉 utf8mb4：玩家昵称/留言/标签都可能带 emoji，不少服务器实例默认仍是 latin1；
        // SQLite 无字符集子句。
        String charset = sqlite ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        BiFunction<String, String, String> createTable =
                (table, columns) -> "CREATE TABLE IF NOT EXISTS " + table + " (" + columns + ")" + charset;

        List<Step> all = new ArrayList<>();

        // v1：核心三表。
        all.add(new Step(1, List.of(
                        // 庇护所主表（一人一世界，owner 为主键）。layout 三列即冻结布局快照（决策：随世界冻结）。
                        createTable.apply("ps_shelter",
                                "owner_uuid VARCHAR(36) PRIMARY KEY," +
                                        "world_name VARCHAR(64) NOT NULL," +
                                        "seed " + big + " NOT NULL," +
                                        "gen_type VARCHAR(16) NOT NULL," +
                                        "level INT NOT NULL," +
                                        "start_border INT NOT NULL," +
                                        "growth_per_level INT NOT NULL," +
                                        "max_level INT NOT NULL," +
                                        "visibility VARCHAR(16) NOT NULL," +
                                        "flags TEXT," +
                                        "bulletin TEXT," +
                                        "server_name VARCHAR(64)," +
                                        "created_at " + big + " NOT NULL," +
                                        "last_active " + big + " NOT NULL," +
                                        "likes INT NOT NULL DEFAULT 0"),
                        // 四级身份成员表（决策 #16）：tier ∈ ADMIN/TRUSTED/ACCESS/DENIED。
                        createTable.apply("ps_member",
                                "owner_uuid VARCHAR(36) NOT NULL," +
                                        "member_uuid VARCHAR(36) NOT NULL," +
                                        "tier VARCHAR(16) NOT NULL," +
                                        "PRIMARY KEY (owner_uuid, member_uuid)"),
                        // 点赞去重表（决策 #39 每人一次）。liked_at 记点赞时刻，供 HOT 近期热度（决策 #40）。
                        createTable.apply("ps_like",
                                "owner_uuid VARCHAR(36) NOT NULL," +
                                        "liker_uuid VARCHAR(36) NOT NULL," +
                                        "liked_at " + big + " NOT NULL DEFAULT 0," +
                                        "PRIMARY KEY (owner_uuid, liker_uuid)")
                ),
                List.of(
                        new Index("ps_shelter", "idx_ps_shelter_world", "world_name"),
                        new Index("ps_shelter", "idx_ps_shelter_server", "server_name"),
                        new Index("ps_shelter", "idx_ps_shelter_vis", "visibility"),
                        new Index("ps_like", "idx_ps_like_owner_time", "owner_uuid, liked_at")
                )));

        // v2：重置冷却/限额持久化（决策 #77，重启不清零）。
        all.add(new Step(2, List.of(
                createTable.apply("ps_reset_log",
                        "owner_uuid VARCHAR(36) PRIMARY KEY," +
                                "last_reset_at " + big + " NOT NULL," +
                                "day_count INT NOT NULL," +
                                "epoch_day " + big + " NOT NULL")
        )));

        // v3：跨服待办交接（决策 #57/#59，到站后进目标庇护所）。
        all.add(new Step(3, List.of(
                createTable.apply("ps_pending",
                        "player_uuid VARCHAR(36) PRIMARY KEY," +
                                "target_owner VARCHAR(36) NOT NULL," +
                                "created_at " + big + " NOT NULL")
        )));

        // v4：标签（决策 P3）。owner + tag 联合主键，一庇护所多标签。
        all.add(new Step(4, List.of(
                        createTable.apply("ps_tag",
                                "owner_uuid VARCHAR(36) NOT NULL," +
                                        "tag VARCHAR(32) NOT NULL," +
                                        "PRIMARY KEY (owner_uuid, tag)")
                ),
                List.of(new Index("ps_tag", "idx_ps_tag_tag", "tag"))));

        // v5：访客留言板（决策 P3）。自增 id + owner 索引。
        String autoId = sqlite
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT PRIMARY KEY AUTO_INCREMENT";
        all.add(new Step(5, List.of(
                        createTable.apply("ps_message",
                                autoId + "," +
                                        "owner_uuid VARCHAR(36) NOT NULL," +
                                        "author_uuid VARCHAR(36) NOT NULL," +
                                        "author_name VARCHAR(32)," +
                                        // text 在 MySQL 关键字表里是非保留字，沿用旧名不做破坏性重命名。
                                        "text VARCHAR(256) NOT NULL," +
                                        "created_at " + big + " NOT NULL")
                ),
                List.of(new Index("ps_message", "idx_ps_message_owner", "owner_uuid, created_at"))));

        // v6：角落锚定边长模式。layout 追加可用正方形边长参数，等级表随世界冻结。
        all.add(new Step(6, List.of(), List.of(), List.of(
                new Column("ps_shelter", "initial_chunks", "INT NOT NULL DEFAULT 0"),
                new Column("ps_shelter", "max_chunks", "INT NOT NULL DEFAULT 0"),
                new Column("ps_shelter", "side_levels", "TEXT")
        )));

        all.add(new Step(7, List.of(), List.of(), List.of(
                new Column("ps_shelter", "origin_x", "INT NOT NULL DEFAULT 0"),
                new Column("ps_shelter", "origin_z", "INT NOT NULL DEFAULT 0")
        )));

        // v8：限额数值入库（levels.yml 仅作默认值）。limits 为 k=v 行序列化（同 flags）。
        all.add(new Step(8, List.of(), List.of(), List.of(
                new Column("ps_shelter", "limits", "TEXT")
        )));

        return all;
    }

    /** 应用所有未应用的迁移。须在插件启动早期、建仓库前调用。 */
    public void migrate() throws SQLException {
        try (Connection c = db.connection()) {
            ensureHistory(c);
            int current = currentVersion(c);
            for (Step step : steps()) {
                if (step.version() <= current) {
                    continue;
                }
                applyStep(c, step);
                log.info("[PlayerShelter] 已应用数据库迁移 v" + step.version());
            }
        }
    }

    private void ensureHistory(Connection c) throws SQLException {
        String big = db.isSqlite() ? "INTEGER" : "BIGINT";
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ps_schema_history (" +
                    "version INT PRIMARY KEY, applied_at " + big + " NOT NULL)");
        }
    }

    private int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM ps_schema_history")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applyStep(Connection c, Step step) throws SQLException {
        boolean prevAuto = c.getAutoCommit();
        // MySQL 的 DDL 隐式提交、事务对它无效；SQLite 则真正受事务保护。统一按事务包：
        // SQLite 出错能回滚；MySQL 出错至少不登记版本号，下轮重试靠自身幂等性接上。
        c.setAutoCommit(false);
        try {
            try (Statement st = c.createStatement()) {
                for (String sql : step.statements()) {
                    st.execute(sql);
                }
            }
            for (Index index : step.indices()) {
                ensureIndex(c, index);
            }
            for (Column column : step.columns()) {
                ensureColumn(c, column);
            }
            if (step.version() == 7) {
                backfillLegacyChunkLayouts(c);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ps_schema_history(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, step.version());
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            // 带上版本号再抛：只抛裸语法错误的话，无从定位是哪个迁移炸的。
            throw new SQLException("迁移 v" + step.version() + " 失败: " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        } finally {
            c.setAutoCommit(prevAuto);
        }
    }

    /** 建索引（不存在才建）：MySQL 8 不认 {@code CREATE INDEX IF NOT EXISTS}，统一先查后建。 */
    private void ensureIndex(Connection c, Index index) throws SQLException {
        if (indexExists(c, index)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX " + index.name() + " ON " + index.table() + "(" + index.columns() + ")");
        }
    }

    private boolean indexExists(Connection c, Index index) throws SQLException {
        if (db.isSqlite()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
                ps.setString(1, index.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, index.table());
            ps.setString(2, index.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 加列（不存在才加）：MySQL 的 ALTER 无法回滚，重试时会撞「列已存在」，先查元数据。 */
    private void ensureColumn(Connection c, Column column) throws SQLException {
        if (columnExists(c, column)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + column.table() + " ADD COLUMN " + column.name() + " " + column.definition());
        }
    }

    private boolean columnExists(Connection c, Column column) throws SQLException {
        if (db.isSqlite()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(" + column.table() + ")")) {
                while (rs.next()) {
                    if (column.name().equalsIgnoreCase(rs.getString("name"))) {
                        return true;
                    }
                }
                return false;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, column.table());
            ps.setString(2, column.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void backfillLegacyChunkLayouts(Connection c) throws SQLException {
        List<LegacyLayoutBackfill> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner_uuid,start_border,growth_per_level,level,max_level " +
                     "FROM ps_shelter WHERE growth_per_level <> 0 OR initial_chunks <= 0 OR max_chunks <= 0")) {
            while (rs.next()) {
                String owner = rs.getString("owner_uuid");
                int startBorder = rs.getInt("start_border");
                int growth = rs.getInt("growth_per_level");
                int currentLevel = Math.max(1, rs.getInt("level"));
                int maxLevel = Math.max(1, rs.getInt("max_level"));
                int initialChunks = roundChunks(startBorder);
                int maxChunks = initialChunks;
                StringBuilder sideLevels = new StringBuilder();
                for (int level = 1; level <= maxLevel; level++) {
                    int chunks = roundChunks(startBorder + growth * (level - 1));
                    maxChunks = Math.max(maxChunks, chunks);
                    if (sideLevels.length() > 0) {
                        sideLevels.append('\n');
                    }
                    sideLevels.append(level).append('=').append(chunks);
                }
                int currentChunks = roundChunks(startBorder + growth * (Math.min(currentLevel, maxLevel) - 1));
                int origin = -(currentChunks * 16) / 2;
                rows.add(new LegacyLayoutBackfill(owner, initialChunks, maxChunks, sideLevels.toString(), origin, origin));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE ps_shelter SET " +
                "start_border=?,growth_per_level=0,initial_chunks=?,max_chunks=?,side_levels=?,origin_x=?,origin_z=? " +
                "WHERE owner_uuid=?")) {
            for (LegacyLayoutBackfill row : rows) {
                ps.setInt(1, row.initialChunks() * 16);
                ps.setInt(2, row.initialChunks());
                ps.setInt(3, row.maxChunks());
                ps.setString(4, row.sideLevels());
                ps.setInt(5, row.originX());
                ps.setInt(6, row.originZ());
                ps.setString(7, row.ownerUuid());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int roundChunks(int borderSize) {
        return Math.max(1, Math.round(borderSize / 16.0f));
    }
}
