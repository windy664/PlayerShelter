package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.domain.port.ShelterRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ShelterRepository} 的 JDBC 实现（决策 #49/#50）。可移植 SQL，SQLite/MySQL 通用。
 *
 * <p>成员四级集合存 {@code ps_member}，flags 序列化进 {@code ps_shelter.flags}（{@code k=v} 行）。
 * 成员按需 join 加载（{@link #loadMembers}）。读失败时记日志并降级（返回空/Optional.empty），不抛穿上层。
 */
public final class SqlShelterRepository implements ShelterRepository {

    private final Database db;
    private final Logger log;

    public SqlShelterRepository(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public Optional<Shelter> find(PlayerRef owner) {
        return queryOne("SELECT * FROM ps_shelter WHERE owner_uuid = ?", owner.uuid().toString());
    }

    @Override
    public Optional<Shelter> findByWorldName(String worldName) {
        return queryOne("SELECT * FROM ps_shelter WHERE world_name = ?", worldName);
    }

    private Optional<Shelter> queryOne(String sql, String param) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(c, rs));
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 查询庇护所失败: " + param, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(Shelter s) {
        // upsert 主表 + 全量重写成员表（成员量小，简单稳妥）。
        String upsert = db.isSqlite()
                ? "INSERT INTO ps_shelter(owner_uuid,world_name,seed,gen_type,level,start_border,growth_per_level,max_level,initial_chunks,max_chunks,side_levels,origin_x,origin_z,visibility,flags,bulletin,server_name,created_at,last_active,likes) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(owner_uuid) DO UPDATE SET world_name=excluded.world_name,seed=excluded.seed,gen_type=excluded.gen_type,level=excluded.level," +
                "start_border=excluded.start_border,growth_per_level=excluded.growth_per_level,max_level=excluded.max_level," +
                "initial_chunks=excluded.initial_chunks,max_chunks=excluded.max_chunks,side_levels=excluded.side_levels,origin_x=excluded.origin_x,origin_z=excluded.origin_z,visibility=excluded.visibility," +
                "flags=excluded.flags,bulletin=excluded.bulletin,server_name=excluded.server_name,created_at=excluded.created_at,last_active=excluded.last_active,likes=excluded.likes"
                : "INSERT INTO ps_shelter(owner_uuid,world_name,seed,gen_type,level,start_border,growth_per_level,max_level,initial_chunks,max_chunks,side_levels,origin_x,origin_z,visibility,flags,bulletin,server_name,created_at,last_active,likes) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE world_name=VALUES(world_name),seed=VALUES(seed),gen_type=VALUES(gen_type),level=VALUES(level)," +
                "start_border=VALUES(start_border),growth_per_level=VALUES(growth_per_level),max_level=VALUES(max_level)," +
                "initial_chunks=VALUES(initial_chunks),max_chunks=VALUES(max_chunks),side_levels=VALUES(side_levels),origin_x=VALUES(origin_x),origin_z=VALUES(origin_z),visibility=VALUES(visibility)," +
                "flags=VALUES(flags),bulletin=VALUES(bulletin),server_name=VALUES(server_name),created_at=VALUES(created_at),last_active=VALUES(last_active),likes=VALUES(likes)";
        try (Connection c = db.connection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    ps.setString(1, s.owner().uuid().toString());
                    ps.setString(2, s.worldName());
                    ps.setLong(3, s.seed());
                    ps.setString(4, s.genType().name());
                    ps.setInt(5, s.level());
                    ps.setInt(6, s.layout().startBorder());
                    ps.setInt(7, s.layout().growthPerLevel());
                    ps.setInt(8, s.layout().maxLevel());
                    ps.setInt(9, s.layout().initialChunks());
                    ps.setInt(10, s.layout().maxChunks());
                    ps.setString(11, encodeIntMap(s.layout().sortedSideLevels()));
                    ps.setInt(12, s.layout().originX()); // 写入 originX
                    ps.setInt(13, s.layout().originZ()); // 写入 originZ
                    ps.setString(14, s.visibility().name());
                    ps.setString(15, encodeFlags(s.flags()));
                    ps.setString(16, s.bulletin());
                    ps.setString(17, s.serverName());
                    ps.setLong(18, s.createdAt().toEpochMilli());
                    ps.setLong(19, s.lastActive().toEpochMilli());
                    ps.setInt(20, s.likes());
                    ps.executeUpdate();
                }
                rewriteMembers(c, s);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 保存庇护所失败: " + s.owner().uuid(), e);
        }
    }

    private void rewriteMembers(Connection c, Shelter s) throws SQLException {
        String owner = s.owner().uuid().toString();
        try (PreparedStatement del = c.prepareStatement("DELETE FROM ps_member WHERE owner_uuid = ?")) {
            del.setString(1, owner);
            del.executeUpdate();
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO ps_member(owner_uuid, member_uuid, tier) VALUES (?,?,?)")) {
            addTier(ins, owner, s.admins(), "ADMIN");
            addTier(ins, owner, s.trusted(), "TRUSTED");
            addTier(ins, owner, s.access(), "ACCESS");
            addTier(ins, owner, s.denied(), "DENIED");
            ins.executeBatch();
        }
    }

    private void addTier(PreparedStatement ins, String owner, Set<PlayerRef> set, String tier) throws SQLException {
        for (PlayerRef p : set) {
            ins.setString(1, owner);
            ins.setString(2, p.uuid().toString());
            ins.setString(3, tier);
            ins.addBatch();
        }
    }

    @Override
    public void delete(PlayerRef owner) {
        String o = owner.uuid().toString();
        try (Connection c = db.connection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                exec(c, "DELETE FROM ps_member WHERE owner_uuid = ?", o);
                exec(c, "DELETE FROM ps_like WHERE owner_uuid = ?", o);
                exec(c, "DELETE FROM ps_shelter WHERE owner_uuid = ?", o);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除庇护所失败: " + owner.uuid(), e);
        }
    }

    private void exec(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }

    @Override
    public List<Shelter> all() {
        return queryMany("SELECT * FROM ps_shelter", null);
    }

    @Override
    public List<Shelter> ownedByServer(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return all(); // 单服：服名留空时即全部
        }
        return queryMany("SELECT * FROM ps_shelter WHERE server_name = ?", serverName);
    }

    private List<Shelter> queryMany(String sql, String param) {
        List<Shelter> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRow(c, rs));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 批量查询庇护所失败", e);
        }
        return out;
    }

    /** 读一行 + 加载成员。 */
    private Shelter readRow(Connection c, ResultSet rs) throws SQLException {
        PlayerRef owner = PlayerRef.of(UUID.fromString(rs.getString("owner_uuid")));
        int maxLevel = rs.getInt("max_level");
        int initialChunks = intOrZero(rs, "initial_chunks");
        int maxChunks = intOrZero(rs, "max_chunks");

        int originX = intOrDefault(rs, "origin_x", 0);
        int originZ = intOrDefault(rs, "origin_z", 0);

        ShelterLayout layout = initialChunks > 0 && maxChunks > 0
                ? new ShelterLayout(initialChunks, maxChunks, maxLevel,
                decodeIntMap(stringOrEmpty(rs, "side_levels")), originX, originZ)
                : new ShelterLayout(rs.getInt("start_border"), rs.getInt("growth_per_level"), maxLevel);

        Map<String, String> flags = decodeFlags(rs.getString("flags"));
        Members m = loadMembers(c, owner.uuid().toString());
        return new Shelter(
                owner,
                rs.getString("world_name"),
                rs.getLong("seed"),
                GenerationType.valueOf(rs.getString("gen_type")),
                rs.getInt("level"),
                layout,
                ShelterVisibility.valueOf(rs.getString("visibility")),
                m.admins, m.trusted, m.access, m.denied,
                flags,
                rs.getString("bulletin"),
                rs.getString("server_name"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("last_active")),
                rs.getInt("likes"));
    }

    private int intOrZero(ResultSet rs, String column) {
        return intOrDefault(rs, column, 0);
    }

    private int intOrDefault(ResultSet rs, String column, int def) {
        try {
            return rs.getInt(column);
        } catch (SQLException ignored) {
            return def;
        }
    }

    private String stringOrEmpty(ResultSet rs, String column) {
        try {
            String value = rs.getString(column);
            return value == null ? "" : value;
        } catch (SQLException ignored) {
            return "";
        }
    }

    private record Members(Set<PlayerRef> admins, Set<PlayerRef> trusted, Set<PlayerRef> access, Set<PlayerRef> denied) {}

    private Members loadMembers(Connection c, String owner) throws SQLException {
        Set<PlayerRef> admins = new HashSet<>();
        Set<PlayerRef> trusted = new HashSet<>();
        Set<PlayerRef> access = new HashSet<>();
        Set<PlayerRef> denied = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT member_uuid, tier FROM ps_member WHERE owner_uuid = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerRef p = PlayerRef.of(UUID.fromString(rs.getString("member_uuid")));
                    switch (rs.getString("tier")) {
                        case "ADMIN" -> admins.add(p);
                        case "TRUSTED" -> trusted.add(p);
                        case "ACCESS" -> access.add(p);
                        case "DENIED" -> denied.add(p);
                        default -> { /* 忽略未知层 */ }
                    }
                }
            }
        }
        return new Members(admins, trusted, access, denied);
    }

    // —— flags 序列化（k=v 行；值不含换行）——

    private static String encodeFlags(Map<String, String> flags) {
        if (flags == null || flags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : flags.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String encodeIntMap(Map<Integer, Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<Integer, Integer> decodeIntMap(String raw) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(Integer.parseInt(line.substring(0, eq)), Integer.parseInt(line.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // ignore malformed line
            }
        }
        return out;
    }

    private static Map<String, String> decodeFlags(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return out;
    }
}
