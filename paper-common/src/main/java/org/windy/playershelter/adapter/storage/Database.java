package org.windy.playershelter.adapter.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接（决策 #49 SQLite 默认可切 MySQL；可插拔存储铁律，见 [[guildshelter-storage-pluggable]] 同款）。
 * 只暴露 {@link #connection()} 与 {@link #isSqlite()}，上层仓库写【可移植 SQL】，不写死后端。
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource ds;
    private final boolean sqlite;

    private Database(HikariDataSource ds, boolean sqlite) {
        this.ds = ds;
        this.sqlite = sqlite;
    }

    /** SQLite：文件库（默认）。单写者 → 池大小 1，开 WAL + busy_timeout 防锁。 */
    public static Database sqlite(File dbFile) {
        dbFile.getParentFile().mkdirs();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hc.setMaximumPoolSize(1);
        hc.setPoolName("PlayerShelter-SQLite");
        hc.setConnectionInitSql("PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
        return new Database(new HikariDataSource(hc), true);
    }

    /** MySQL：大服/跨服用（决策 #49/#59）。 */
    public static Database mysql(String host, int port, String db, String user, String pass) {
        HikariConfig hc = new HikariConfig();
        // characterEncoding 必须写 Java 字符集名 UTF-8（Connector/J 把它映射到服务端 utf8mb4）；
        // 写 utf8mb4 会抛 Unsupported character encoding。旧值 utf8 在服务端是 utf8mb3（最多 3 字节），
        // 玩家昵称/留言里的 emoji 会写不进去（Incorrect string value）。建表侧已同步钉 utf8mb4（见 Migrations）。
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(10);
        hc.setPoolName("PlayerShelter-MySQL");
        return new Database(new HikariDataSource(hc), false);
    }

    public Connection connection() throws SQLException {
        return ds.getConnection();
    }

    public boolean isSqlite() {
        return sqlite;
    }

    @Override
    public void close() {
        ds.close();
    }
}
