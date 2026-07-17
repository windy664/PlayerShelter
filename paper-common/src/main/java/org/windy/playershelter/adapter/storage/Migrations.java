package org.windy.playershelter.adapter.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 版本化自动迁移（决策 #50；同 [[guildshelter-db-migrations]] 铁律：改表必须配套版本化迁移，别裸 ALTER）。
 *
 * <p>{@code ps_schema_history(version, applied_at)} 记录已应用版本；启动时把 &gt; 当前版本的迁移逐个在
 * <b>事务</b>里应用并登记。SQL 力求两后端通用（SQLite/MySQL）；类型差异在此抹平。
 */
public final class Migrations {

    private final Database db;
    private final Logger log;

    public Migrations(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    /** 一个迁移版本：版本号 + 若干 DDL 语句。 */
    private record Step(int version, List<String> statements) {}

    private record LegacyLayoutBackfill(String ownerUuid, int initialChunks, int maxChunks,
                                        String sideLevels, int originX, int originZ) {}

    /** 全部迁移定义。新增表/列时<b>追加新版本</b>，绝不改旧版本。 */
    private List<Step> steps() {
        boolean sqlite = db.isSqlite();
        // 跨后端类型：自增主键/大整数/文本。SQLite 用 INTEGER；MySQL 用 BIGINT。
        String big = sqlite ? "INTEGER" : "BIGINT";

        List<Step> all = new ArrayList<>();

        // v1：核心三表。
        all.add(new Step(1, List.of(
                // 庇护所主表（一人一世界，owner 为主键）。layout 三列即冻结布局快照（决策：随世界冻结）。
                "CREATE TABLE IF NOT EXISTS ps_shelter (" +
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
                        "likes INT NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS idx_ps_shelter_world ON ps_shelter(world_name)",
                "CREATE INDEX IF NOT EXISTS idx_ps_shelter_server ON ps_shelter(server_name)",
                "CREATE INDEX IF NOT EXISTS idx_ps_shelter_vis ON ps_shelter(visibility)",
                // 四级身份成员表（决策 #16）：tier ∈ ADMIN/TRUSTED/ACCESS/DENIED。
                "CREATE TABLE IF NOT EXISTS ps_member (" +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "member_uuid VARCHAR(36) NOT NULL," +
                        "tier VARCHAR(16) NOT NULL," +
                        "PRIMARY KEY (owner_uuid, member_uuid))",
                // 点赞去重表（决策 #39 每人一次）。liked_at 记点赞时刻，供 HOT 近期热度（决策 #40）。
                "CREATE TABLE IF NOT EXISTS ps_like (" +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "liker_uuid VARCHAR(36) NOT NULL," +
                        "liked_at " + big + " NOT NULL DEFAULT 0," +
                        "PRIMARY KEY (owner_uuid, liker_uuid))",
                "CREATE INDEX IF NOT EXISTS idx_ps_like_owner_time ON ps_like(owner_uuid, liked_at)"
        )));

        // v2：重置冷却/限额持久化（决策 #77，重启不清零）。
        all.add(new Step(2, List.of(
                "CREATE TABLE IF NOT EXISTS ps_reset_log (" +
                        "owner_uuid VARCHAR(36) PRIMARY KEY," +
                        "last_reset_at " + big + " NOT NULL," +
                        "day_count INT NOT NULL," +
                        "epoch_day " + big + " NOT NULL)"
        )));

        // v3：跨服待办交接（决策 #57/#59，到站后进目标庇护所）。
        all.add(new Step(3, List.of(
                "CREATE TABLE IF NOT EXISTS ps_pending (" +
                        "player_uuid VARCHAR(36) PRIMARY KEY," +
                        "target_owner VARCHAR(36) NOT NULL," +
                        "created_at " + big + " NOT NULL)"
        )));

        // v4：标签（决策 P3）。owner + tag 联合主键，一庇护所多标签。
        all.add(new Step(4, List.of(
                "CREATE TABLE IF NOT EXISTS ps_tag (" +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "tag VARCHAR(32) NOT NULL," +
                        "PRIMARY KEY (owner_uuid, tag))",
                "CREATE INDEX IF NOT EXISTS idx_ps_tag_tag ON ps_tag(tag)"
        )));

        // v5：访客留言板（决策 P3）。自增 id + owner 索引。
        String autoId = sqlite
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT PRIMARY KEY AUTO_INCREMENT";
        all.add(new Step(5, List.of(
                "CREATE TABLE IF NOT EXISTS ps_message (" +
                        autoId + "," +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "author_uuid VARCHAR(36) NOT NULL," +
                        "author_name VARCHAR(32)," +
                        "text VARCHAR(256) NOT NULL," +
                        "created_at " + big + " NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_ps_message_owner ON ps_message(owner_uuid, created_at)"
        )));

        // v6：角落锚定边长模式。layout 追加可用正方形边长参数，等级表随世界冻结。
        all.add(new Step(6, List.of(
                "ALTER TABLE ps_shelter ADD COLUMN initial_chunks INT NOT NULL DEFAULT 0",
                "ALTER TABLE ps_shelter ADD COLUMN max_chunks INT NOT NULL DEFAULT 0",
                "ALTER TABLE ps_shelter ADD COLUMN side_levels TEXT"
        )));

        all.add(new Step(7, List.of(
                "ALTER TABLE ps_shelter ADD COLUMN origin_x INT NOT NULL DEFAULT 0",
                "ALTER TABLE ps_shelter ADD COLUMN origin_z INT NOT NULL DEFAULT 0"
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
        c.setAutoCommit(false);
        try {
            try (Statement st = c.createStatement()) {
                for (String sql : step.statements()) {
                    st.execute(sql);
                }
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
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prevAuto);
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
