package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.ResetLogStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ResetLogStore} 的 JDBC 实现（决策 #77）。重置冷却/限额落 {@code ps_reset_log}，重启不清零。
 * 读写失败降级（返回空/静默），不让一次 IO 异常阻断重置流程。
 */
public final class SqlResetLogStore implements ResetLogStore {

    private final Database db;
    private final Logger log;

    public SqlResetLogStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public Optional<ResetRecord> find(PlayerRef owner) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_reset_at, day_count, epoch_day FROM ps_reset_log WHERE owner_uuid = ?")) {
            ps.setString(1, owner.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ResetRecord(
                            rs.getLong("last_reset_at"), rs.getInt("day_count"), rs.getLong("epoch_day")));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 读取重置记录失败", e);
        }
        return Optional.empty();
    }

    @Override
    public void save(PlayerRef owner, ResetRecord r) {
        String sql = db.isSqlite()
                ? "INSERT INTO ps_reset_log(owner_uuid,last_reset_at,day_count,epoch_day) VALUES(?,?,?,?) "
                  + "ON CONFLICT(owner_uuid) DO UPDATE SET last_reset_at=excluded.last_reset_at,"
                  + "day_count=excluded.day_count,epoch_day=excluded.epoch_day"
                : "INSERT INTO ps_reset_log(owner_uuid,last_reset_at,day_count,epoch_day) VALUES(?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE last_reset_at=VALUES(last_reset_at),"
                  + "day_count=VALUES(day_count),epoch_day=VALUES(epoch_day)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.uuid().toString());
            ps.setLong(2, r.lastResetAt());
            ps.setInt(3, r.dayCount());
            ps.setLong(4, r.epochDay());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 保存重置记录失败", e);
        }
    }
}
