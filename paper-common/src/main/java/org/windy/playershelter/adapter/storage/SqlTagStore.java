package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.TagStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link TagStore} 的 JDBC 实现（决策 P3）。标签统一小写、不带 #、限长 32。 */
public final class SqlTagStore implements TagStore {

    private final Database db;
    private final Logger log;

    public SqlTagStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    static String normalize(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim().toLowerCase();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() > 32) {
            t = t.substring(0, 32);
        }
        return t;
    }

    @Override
    public List<String> tagsOf(PlayerRef owner) {
        List<String> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT tag FROM ps_tag WHERE owner_uuid = ? ORDER BY tag")) {
            ps.setString(1, owner.uuid().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("tag"));
                }
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 读取标签失败", e);
        }
        return out;
    }

    @Override
    public boolean add(PlayerRef owner, String tag) {
        String t = normalize(tag);
        if (t.isEmpty()) {
            return false;
        }
        String sql = db.isSqlite()
                ? "INSERT OR IGNORE INTO ps_tag(owner_uuid, tag) VALUES (?,?)"
                : "INSERT IGNORE INTO ps_tag(owner_uuid, tag) VALUES (?,?)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.uuid().toString());
            ps.setString(2, t);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 添加标签失败", e);
            return false;
        }
    }

    @Override
    public boolean remove(PlayerRef owner, String tag) {
        String t = normalize(tag);
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ps_tag WHERE owner_uuid = ? AND tag = ?")) {
            ps.setString(1, owner.uuid().toString());
            ps.setString(2, t);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除标签失败", e);
            return false;
        }
    }

    @Override
    public void clear(PlayerRef owner) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ps_tag WHERE owner_uuid = ?")) {
            ps.setString(1, owner.uuid().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 清空标签失败", e);
        }
    }
}
