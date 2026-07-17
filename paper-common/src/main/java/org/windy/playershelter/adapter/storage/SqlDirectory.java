package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.DirectoryPort;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.service.ShelterConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link DirectoryPort} 的 JDBC 实现（决策 #14/#38/#39/#40）。
 *
 * <p>排序在 SQL 侧分页（仅取 PUBLIC 且过等级门槛 #71 的世界）；点赞去重落 {@code ps_like}（决策 #39 每人一次）。
 * 热度（HOT，决策 #40 纯算法精选）= 近 {@link #HOT_WINDOW_DAYS} 天内的点赞数，按其降序，总赞数兜底。
 */
public final class SqlDirectory implements DirectoryPort {

    /** HOT 近期热度窗口（天）。窗口内点赞越多越热，超窗口的赞不计入热度（但仍计总赞）。 */
    private static final int HOT_WINDOW_DAYS = 7;

    private final Database db;
    private final ShelterRepository repo;
    private final ShelterConfig config;
    private final Logger log;

    public SqlDirectory(Database db, ShelterRepository repo, ShelterConfig config, Logger log) {
        this.db = db;
        this.repo = repo;
        this.config = config;
        this.log = log;
    }

    @Override
    public List<Shelter> list(Sort sort, int offset, int limit) {
        if (sort == Sort.HOT) {
            return listHot(offset, limit);
        }
        String order = switch (sort) {
            case LIKES -> "likes DESC, last_active DESC";
            case NEWEST -> "created_at DESC";
            case RANDOM -> db.isSqlite() ? "RANDOM()" : "RAND()";
            case HOT -> "likes DESC"; // 不会走到（上面已分流）
        };
        String sql = "SELECT owner_uuid FROM ps_shelter WHERE visibility = 'PUBLIC' AND level >= ? "
                + "ORDER BY " + order + " LIMIT ? OFFSET ?";
        List<String> owners = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, config.publicMinLevel());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString("owner_uuid"));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 目录查询失败", e);
        }
        return hydrate(owners);
    }

    /**
     * 把 owner_uuid 列表水合成 Shelter（经 {@link ShelterRepository#find}）。
     * <b>务必先关闭目录查询的连接再调本方法</b>——否则在持有连接时回 repo.find 会向同一池二次取连接，
     * SQLite 池大小为 1 时自死锁（集成测试已暴露）。
     */
    private List<Shelter> hydrate(List<String> owners) {
        List<Shelter> out = new ArrayList<>(owners.size());
        for (String o : owners) {
            repo.find(PlayerRef.of(java.util.UUID.fromString(o))).ifPresent(out::add);
        }
        return out;
    }

    /** HOT：按近窗口点赞数降序（决策 #40）。LEFT JOIN 统计窗口内赞，总赞兜底。 */
    private List<Shelter> listHot(int offset, int limit) {
        long since = System.currentTimeMillis() - HOT_WINDOW_DAYS * 86400_000L;
        String sql = "SELECT s.owner_uuid, COUNT(l.liker_uuid) AS recent "
                + "FROM ps_shelter s LEFT JOIN ps_like l "
                + "ON l.owner_uuid = s.owner_uuid AND l.liked_at >= ? "
                + "WHERE s.visibility = 'PUBLIC' AND s.level >= ? "
                + "GROUP BY s.owner_uuid, s.likes "
                + "ORDER BY recent DESC, s.likes DESC "
                + "LIMIT ? OFFSET ?";
        List<String> owners = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, since);
            ps.setInt(2, config.publicMinLevel());
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString("owner_uuid"));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 热度目录查询失败", e);
        }
        return hydrate(owners);
    }

    @Override
    public boolean like(PlayerRef target, PlayerRef byPlayer) {
        if (hasLiked(target, byPlayer)) {
            return false;
        }
        String ins = db.isSqlite()
                ? "INSERT OR IGNORE INTO ps_like(owner_uuid, liker_uuid, liked_at) VALUES (?,?,?)"
                : "INSERT IGNORE INTO ps_like(owner_uuid, liker_uuid, liked_at) VALUES (?,?,?)";
        try (Connection c = db.connection()) {
            int changed;
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                ps.setString(1, target.uuid().toString());
                ps.setString(2, byPlayer.uuid().toString());
                ps.setLong(3, System.currentTimeMillis());
                changed = ps.executeUpdate();
            }
            if (changed > 0) {
                bumpLikes(c, target, +1);
                return true;
            }
            return false;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 点赞失败", e);
            return false;
        }
    }

    @Override
    public boolean unlike(PlayerRef target, PlayerRef byPlayer) {
        try (Connection c = db.connection()) {
            int changed;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM ps_like WHERE owner_uuid = ? AND liker_uuid = ?")) {
                ps.setString(1, target.uuid().toString());
                ps.setString(2, byPlayer.uuid().toString());
                changed = ps.executeUpdate();
            }
            if (changed > 0) {
                bumpLikes(c, target, -1);
                return true;
            }
            return false;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 取消点赞失败", e);
            return false;
        }
    }

    /** 同步主表 likes 计数（与 ps_like 行数一致，避免漂移用 clamp ≥0）。 */
    private void bumpLikes(Connection c, PlayerRef target, int delta) throws SQLException {
        String sql = db.isSqlite()
                ? "UPDATE ps_shelter SET likes = MAX(0, likes + ?) WHERE owner_uuid = ?"
                : "UPDATE ps_shelter SET likes = GREATEST(0, likes + ?) WHERE owner_uuid = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setString(2, target.uuid().toString());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean hasLiked(PlayerRef target, PlayerRef byPlayer) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM ps_like WHERE owner_uuid = ? AND liker_uuid = ?")) {
            ps.setString(1, target.uuid().toString());
            ps.setString(2, byPlayer.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 查询点赞状态失败", e);
            return false;
        }
    }

    @Override
    public List<Shelter> featured(int n) {
        if (n <= 0) {
            return Collections.emptyList();
        }
        return list(Sort.HOT, 0, n); // 决策 #40 纯算法：按热度前 n
    }

    @Override
    public List<Shelter> topByLevel(int n) {
        // 等级榜不限可见性（等级+名字无隐私顾虑，决策 #23 攻比激励）。
        return hydrate(collectOwners(
                "SELECT owner_uuid FROM ps_shelter ORDER BY level DESC, likes DESC LIMIT ?",
                ps -> ps.setInt(1, Math.max(1, n))));
    }

    @Override
    public List<Shelter> topByLikes(int n) {
        return hydrate(collectOwners(
                "SELECT owner_uuid FROM ps_shelter WHERE visibility = 'PUBLIC' ORDER BY likes DESC, level DESC LIMIT ?",
                ps -> ps.setInt(1, Math.max(1, n))));
    }

    @Override
    public List<Shelter> searchByTag(String tag, int offset, int limit) {
        String t = tag == null ? "" : tag.trim().toLowerCase().replaceFirst("^#", "");
        return hydrate(collectOwners(
                "SELECT s.owner_uuid FROM ps_shelter s JOIN ps_tag t ON t.owner_uuid = s.owner_uuid "
                        + "WHERE t.tag = ? AND s.visibility = 'PUBLIC' AND s.level >= ? "
                        + "ORDER BY s.likes DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setString(1, t);
                    ps.setInt(2, config.publicMinLevel());
                    ps.setInt(3, Math.max(1, limit));
                    ps.setInt(4, Math.max(0, offset));
                }));
    }

    /** 小工具：跑一条只 select owner_uuid 的查询，收集为字符串列表（先关连接再 hydrate，避免嵌套连接自死锁）。 */
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<String> collectOwners(String sql, Binder binder) {
        List<String> owners = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 榜单/标签查询失败", e);
        }
        return owners;
    }
}
