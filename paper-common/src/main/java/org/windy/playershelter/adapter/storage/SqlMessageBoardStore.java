package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.MessageBoardStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link MessageBoardStore} 的 JDBC 实现（决策 P3 访客留言板）。 */
public final class SqlMessageBoardStore implements MessageBoardStore {

    private final Database db;
    private final Logger log;

    public SqlMessageBoardStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    @Override
    public long post(PlayerRef owner, PlayerRef author, String authorName, String text) {
        String t = text == null ? "" : (text.length() > 256 ? text.substring(0, 256) : text);
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ps_message(owner_uuid, author_uuid, author_name, text, created_at) VALUES (?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.uuid().toString());
            ps.setString(2, author.uuid().toString());
            ps.setString(3, authorName == null ? "?" : authorName);
            ps.setString(4, t);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 留言失败", e);
            return -1;
        }
    }

    @Override
    public List<Message> list(PlayerRef owner, int offset, int limit) {
        List<Message> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, author_uuid, author_name, text, created_at FROM ps_message "
                             + "WHERE owner_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, owner.uuid().toString());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(
                            rs.getLong("id"),
                            PlayerRef.of(UUID.fromString(rs.getString("author_uuid"))),
                            rs.getString("author_name"),
                            rs.getString("text"),
                            rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 读取留言失败", e);
        }
        return out;
    }

    @Override
    public boolean delete(PlayerRef owner, long messageId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ps_message WHERE owner_uuid = ? AND id = ?")) {
            ps.setString(1, owner.uuid().toString());
            ps.setLong(2, messageId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除留言失败", e);
            return false;
        }
    }

    @Override
    public void clear(PlayerRef owner) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ps_message WHERE owner_uuid = ?")) {
            ps.setString(1, owner.uuid().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 清空留言失败", e);
        }
    }

    @Override
    public int count(PlayerRef owner) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ps_message WHERE owner_uuid = ?")) {
            ps.setString(1, owner.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 统计留言失败", e);
            return 0;
        }
    }
}
