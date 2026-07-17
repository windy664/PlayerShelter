package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.PendingActionStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link PendingActionStore} 的 JDBC 实现（决策 #57/#59 跨服交接）。落 {@code ps_pending}，
 * 各后端共享同一库（MySQL）即可完成"代理送达后进对应庇护所"的交接。读写失败降级，不阻断流程。
 *
 * <p>读取时顺带做<b>过期清理</b>：超过 {@link #TTL_MILLIS} 的陈旧待办视为无效并删除（避免代理掉线留下脏数据）。
 */
public final class SqlPendingActionStore implements PendingActionStore {

    /** 待办有效期：到站没消费掉就算过期（玩家可能取消跳转/代理失败）。 */
    private static final long TTL_MILLIS = 60_000L;

    private final Database db;
    private final Logger log;

    public SqlPendingActionStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public Optional<PlayerRef> findTargetOwner(PlayerRef player) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target_owner, created_at FROM ps_pending WHERE player_uuid = ?")) {
            ps.setString(1, player.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (System.currentTimeMillis() - rs.getLong("created_at") > TTL_MILLIS) {
                        delete(player); // 过期 → 清掉
                        return Optional.empty();
                    }
                    return Optional.of(PlayerRef.of(UUID.fromString(rs.getString("target_owner"))));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 读取跨服待办失败", e);
        }
        return Optional.empty();
    }

    @Override
    public void save(PlayerRef player, PlayerRef targetOwner) {
        String sql = db.isSqlite()
                ? "INSERT INTO ps_pending(player_uuid,target_owner,created_at) VALUES(?,?,?) "
                  + "ON CONFLICT(player_uuid) DO UPDATE SET target_owner=excluded.target_owner,created_at=excluded.created_at"
                : "INSERT INTO ps_pending(player_uuid,target_owner,created_at) VALUES(?,?,?) "
                  + "ON DUPLICATE KEY UPDATE target_owner=VALUES(target_owner),created_at=VALUES(created_at)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.uuid().toString());
            ps.setString(2, targetOwner.uuid().toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 保存跨服待办失败", e);
        }
    }

    @Override
    public void delete(PlayerRef player) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ps_pending WHERE player_uuid = ?")) {
            ps.setString(1, player.uuid().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除跨服待办失败", e);
        }
    }
}
