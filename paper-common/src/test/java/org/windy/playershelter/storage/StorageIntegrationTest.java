package org.windy.playershelter.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.windy.playershelter.adapter.storage.Database;
import org.windy.playershelter.adapter.storage.Migrations;
import org.windy.playershelter.adapter.storage.SqlDirectory;
import org.windy.playershelter.adapter.storage.SqlResetLogStore;
import org.windy.playershelter.adapter.storage.SqlShelterRepository;
import org.windy.playershelter.domain.port.ResetLogStore;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.domain.port.DirectoryPort;
import org.windy.playershelter.service.ShelterConfig;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真 SQLite 临时库验证存储层（迁移 + 仓库往返 + 目录/点赞/热度）。这是唯一能在无服务器下
 * 把实际 SQL 跑通的地方——抓 SQL 方言/序列化 bug，免得上线才暴露。
 */
class StorageIntegrationTest {

    private File dbFile;
    private Database db;
    private SqlShelterRepository repo;
    private final Logger log = Logger.getLogger("test");
    private static final ShelterLayout LAYOUT = new ShelterLayout(6, 15, 20, Map.of(
            1, 6,
            2, 7,
            5, 9,
            20, 15));
    private final ShelterConfig config = new ShelterConfig(LAYOUT,
            10, 8, 30, 7, 12, 2, 3, 3, 0, 1.5, "");

    @BeforeEach
    void setup() throws Exception {
        dbFile = File.createTempFile("ps-test", ".db");
        dbFile.delete(); // 让 SQLite 自己建
        db = Database.sqlite(dbFile);
        new Migrations(db, log).migrate();
        repo = new SqlShelterRepository(db, log);
    }

    @AfterEach
    void teardown() throws Exception {
        db.close();
        Files.deleteIfExists(dbFile.toPath());
        Files.deleteIfExists(new File(dbFile.getAbsolutePath() + "-wal").toPath());
        Files.deleteIfExists(new File(dbFile.getAbsolutePath() + "-shm").toPath());
    }

    private Shelter sample(UUID owner, ShelterVisibility vis, int level) {
        return new Shelter(PlayerRef.of(owner), "shelter_" + owner, 42L, GenerationType.NATURAL,
                level, LAYOUT, vis,
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), "", "", Instant.now(), Instant.now(), 0);
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        // 再跑一次迁移不应报错（schema_history 拦截已应用版本）。
        new Migrations(db, log).migrate();
        assertTrue(repo.all().isEmpty());
    }

    @Test
    void migrationUpgradesV5DatabaseToV6() throws Exception {
        File legacyFile = File.createTempFile("ps-v5", ".db");
        Database legacyDb = Database.sqlite(legacyFile);
        try {
            try (var c = legacyDb.connection(); var st = c.createStatement()) {
                st.execute("CREATE TABLE ps_schema_history (version INT PRIMARY KEY, applied_at INTEGER NOT NULL)");
                for (int version = 1; version <= 5; version++) {
                    st.execute("INSERT INTO ps_schema_history(version, applied_at) VALUES (" + version + ", 1)");
                }
                st.execute("CREATE TABLE ps_shelter (" +
                        "owner_uuid VARCHAR(36) PRIMARY KEY," +
                        "world_name VARCHAR(64) NOT NULL," +
                        "seed INTEGER NOT NULL," +
                        "gen_type VARCHAR(16) NOT NULL," +
                        "level INT NOT NULL," +
                        "start_border INT NOT NULL," +
                        "growth_per_level INT NOT NULL," +
                        "max_level INT NOT NULL," +
                        "visibility VARCHAR(16) NOT NULL," +
                        "flags TEXT," +
                        "bulletin TEXT," +
                        "server_name VARCHAR(64)," +
                        "created_at INTEGER NOT NULL," +
                        "last_active INTEGER NOT NULL," +
                        "likes INT NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE ps_member (" +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "member_uuid VARCHAR(36) NOT NULL," +
                        "tier VARCHAR(16) NOT NULL," +
                        "PRIMARY KEY (owner_uuid, member_uuid))");
                st.execute("INSERT INTO ps_shelter(owner_uuid,world_name,seed,gen_type,level," +
                        "start_border,growth_per_level,max_level,visibility,created_at,last_active,likes) VALUES (" +
                        "'00000000-0000-0000-0000-000000000001','legacy_world',1,'NATURAL',4," +
                        "100,25,5,'PRIVATE',1,1,0)");
                st.execute("INSERT INTO ps_shelter(owner_uuid,world_name,seed,gen_type,level," +
                        "start_border,growth_per_level,max_level,visibility,created_at,last_active,likes) VALUES (" +
                        "'00000000-0000-0000-0000-000000000002','legacy_fixed_world',1,'NATURAL',3," +
                        "96,0,5,'PRIVATE',1,1,0)");
            }

            new Migrations(legacyDb, log).migrate();

            try (var c = legacyDb.connection();
                 var cols = c.createStatement().executeQuery("PRAGMA table_info(ps_shelter)")) {
                Set<String> names = new java.util.HashSet<>();
                while (cols.next()) {
                    names.add(cols.getString("name"));
                }
                assertTrue(names.contains("initial_chunks"));
                assertTrue(names.contains("max_chunks"));
                assertTrue(names.contains("side_levels"));
                assertTrue(names.contains("origin_x"));
                assertTrue(names.contains("origin_z"));
            }
            try (var c = legacyDb.connection();
                 var rs = c.createStatement().executeQuery("SELECT MAX(version) FROM ps_schema_history")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
            Shelter legacy = new SqlShelterRepository(legacyDb, log)
                    .find(PlayerRef.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                    .orElseThrow();
            assertEquals(176, legacy.borderSize());
            assertEquals(11, legacy.sideChunks());
            assertEquals(96, legacy.layout().startBorder());
            assertEquals(0, legacy.layout().growthPerLevel());
// 验证反推出来的原点：11 chunk * 16 = 176 边长，原点应为 -(176/2) = -88
            assertEquals(-88, legacy.layout().originX());
            assertEquals(-88, legacy.layout().originZ());
            assertEquals(0.0D, legacy.layout().borderCenterXAtLevel(legacy.level()));
            assertEquals(0.0D, legacy.layout().borderCenterZAtLevel(legacy.level()));

            Shelter fixed = new SqlShelterRepository(legacyDb, log)
                    .find(PlayerRef.of(UUID.fromString("00000000-0000-0000-0000-000000000002")))
                    .orElseThrow();
            assertEquals(96, fixed.borderSize());
            assertEquals(6, fixed.sideChunks());
            assertEquals(96, fixed.layout().startBorder());
            assertEquals(0, fixed.layout().growthPerLevel());
            assertEquals(6, fixed.layout().initialChunks());
            assertEquals(6, fixed.layout().maxChunks());
            assertEquals(-48, fixed.layout().originX());
            assertEquals(-48, fixed.layout().originZ());
            assertEquals(0.0D, fixed.layout().borderCenterXAtLevel(fixed.level()));
            assertEquals(0.0D, fixed.layout().borderCenterZAtLevel(fixed.level()));
        } finally {
            legacyDb.close();
            Files.deleteIfExists(legacyFile.toPath());
            Files.deleteIfExists(new File(legacyFile.getAbsolutePath() + "-wal").toPath());
            Files.deleteIfExists(new File(legacyFile.getAbsolutePath() + "-shm").toPath());
        }
    }

    @Test
    void shelterRoundTripWithMembersAndFlags() {
        UUID owner = UUID.randomUUID();
        PlayerRef admin = PlayerRef.of(UUID.randomUUID());
        PlayerRef trusted = PlayerRef.of(UUID.randomUUID());
        PlayerRef denied = PlayerRef.of(UUID.randomUUID());
        Shelter s = sample(owner, ShelterVisibility.PUBLIC, 5)
                .withAdmins(Set.of(admin))
                .withTrusted(Set.of(trusted))
                .withDenied(Set.of(denied))
                .withFlags(Map.of("pvp", "true", "mob-spawning", "false"))
                .withBulletin("欢迎光临");
        repo.save(s);

        Shelter loaded = repo.find(PlayerRef.of(owner)).orElseThrow();
        assertEquals(5, loaded.level());
        assertEquals(ShelterVisibility.PUBLIC, loaded.visibility());
        assertEquals(Set.of(admin), loaded.admins());
        assertEquals(Set.of(trusted), loaded.trusted());
        assertEquals(Set.of(denied), loaded.denied());
        assertEquals("true", loaded.flags().get("pvp"));
        assertEquals("false", loaded.flags().get("mob-spawning"));
        assertEquals("欢迎光临", loaded.bulletin());
        assertEquals(9, loaded.sideChunks());
        assertEquals(81, loaded.areaChunks());
        // 反查世界名。
        assertTrue(repo.findByWorldName("shelter_" + owner).isPresent());
    }

    @Test
    void memberRewriteOnUpdate() {
        UUID owner = UUID.randomUUID();
        PlayerRef a = PlayerRef.of(UUID.randomUUID());
        PlayerRef b = PlayerRef.of(UUID.randomUUID());
        repo.save(sample(owner, ShelterVisibility.PRIVATE, 1).withTrusted(Set.of(a, b)));
        // 改成只剩 a → 重写后 b 应被清掉。
        Shelter s = repo.find(PlayerRef.of(owner)).orElseThrow();
        repo.save(s.withTrusted(Set.of(a)));
        Shelter loaded = repo.find(PlayerRef.of(owner)).orElseThrow();
        assertEquals(Set.of(a), loaded.trusted());
    }

    @Test
    void deleteRemovesEverything() {
        UUID owner = UUID.randomUUID();
        repo.save(sample(owner, ShelterVisibility.PRIVATE, 1).withTrusted(Set.of(PlayerRef.of(UUID.randomUUID()))));
        repo.delete(PlayerRef.of(owner));
        assertFalse(repo.find(PlayerRef.of(owner)).isPresent());
        assertTrue(repo.all().isEmpty());
    }

    @Test
    void directoryListsOnlyPublicAboveThreshold() {
        UUID lowPub = UUID.randomUUID();   // PUBLIC 但等级 2 < 门槛 3
        UUID okPub = UUID.randomUUID();    // PUBLIC 等级 5 ≥ 门槛
        UUID priv = UUID.randomUUID();     // PRIVATE
        repo.save(sample(lowPub, ShelterVisibility.PUBLIC, 2));
        repo.save(sample(okPub, ShelterVisibility.PUBLIC, 5));
        repo.save(sample(priv, ShelterVisibility.PRIVATE, 9));

        SqlDirectory dir = new SqlDirectory(db, repo, config, log);
        var list = dir.list(DirectoryPort.Sort.NEWEST, 0, 50);
        assertEquals(1, list.size());
        assertEquals(okPub, list.get(0).owner().uuid());
    }

    @Test
    void likeDedupAndCount() {
        UUID owner = UUID.randomUUID();
        repo.save(sample(owner, ShelterVisibility.PUBLIC, 5));
        SqlDirectory dir = new SqlDirectory(db, repo, config, log);
        PlayerRef liker = PlayerRef.of(UUID.randomUUID());
        PlayerRef target = PlayerRef.of(owner);

        assertTrue(dir.like(target, liker));      // 首次成功
        assertFalse(dir.like(target, liker));     // 重复无效（决策 #39）
        assertTrue(dir.hasLiked(target, liker));
        assertEquals(1, repo.find(target).orElseThrow().likes()); // 计数 +1

        assertTrue(dir.unlike(target, liker));     // 取消
        assertFalse(dir.hasLiked(target, liker));
        assertEquals(0, repo.find(target).orElseThrow().likes());
    }

    @Test
    void hotSortByRecentLikes() {
        UUID hot = UUID.randomUUID();
        UUID cold = UUID.randomUUID();
        repo.save(sample(hot, ShelterVisibility.PUBLIC, 5));
        repo.save(sample(cold, ShelterVisibility.PUBLIC, 5));
        SqlDirectory dir = new SqlDirectory(db, repo, config, log);
        // hot 拿两个近期赞，cold 零赞。
        dir.like(PlayerRef.of(hot), PlayerRef.of(UUID.randomUUID()));
        dir.like(PlayerRef.of(hot), PlayerRef.of(UUID.randomUUID()));

        var list = dir.list(DirectoryPort.Sort.HOT, 0, 50);
        assertEquals(2, list.size());
        assertEquals(hot, list.get(0).owner().uuid()); // 热度高的排前
    }

    @Test
    void ownedByServerEmptyMeansAll() {
        repo.save(sample(UUID.randomUUID(), ShelterVisibility.PRIVATE, 1));
        repo.save(sample(UUID.randomUUID(), ShelterVisibility.PRIVATE, 1));
        assertEquals(2, repo.ownedByServer("").size()); // 单服服名空 → 全部
    }

    @Test
    void pendingActionRoundTripAndConsume() {
        org.windy.playershelter.adapter.storage.SqlPendingActionStore store =
                new org.windy.playershelter.adapter.storage.SqlPendingActionStore(db, log);
        PlayerRef player = PlayerRef.of(UUID.randomUUID());
        PlayerRef targetOwner = PlayerRef.of(UUID.randomUUID());
        assertTrue(store.findTargetOwner(player).isEmpty());

        store.save(player, targetOwner);
        assertEquals(targetOwner, store.findTargetOwner(player).orElseThrow());

        store.delete(player); // 消费后删
        assertTrue(store.findTargetOwner(player).isEmpty());
    }

    @Test
    void tagStoreRoundTrip() {
        org.windy.playershelter.adapter.storage.SqlTagStore store =
                new org.windy.playershelter.adapter.storage.SqlTagStore(db, log);
        PlayerRef owner = PlayerRef.of(UUID.randomUUID());
        assertTrue(store.add(owner, "#现代"));      // 带 # 应被规范化
        assertTrue(store.add(owner, "Modern"));      // 大写应转小写
        assertFalse(store.add(owner, "modern"));     // 重复无效
        var tags = store.tagsOf(owner);
        assertEquals(2, tags.size());
        assertTrue(tags.contains("现代"));
        assertTrue(tags.contains("modern"));
        assertTrue(store.remove(owner, "MODERN"));   // 大小写不敏感删除
        assertEquals(1, store.tagsOf(owner).size());
        store.clear(owner);
        assertTrue(store.tagsOf(owner).isEmpty());
    }

    @Test
    void messageBoardRoundTrip() {
        org.windy.playershelter.adapter.storage.SqlMessageBoardStore store =
                new org.windy.playershelter.adapter.storage.SqlMessageBoardStore(db, log);
        PlayerRef owner = PlayerRef.of(UUID.randomUUID());
        PlayerRef author = PlayerRef.of(UUID.randomUUID());
        long id1 = store.post(owner, author, "Alice", "好漂亮的家！");
        store.post(owner, author, "Alice", "第二条");
        assertTrue(id1 > 0);
        assertEquals(2, store.count(owner));
        var msgs = store.list(owner, 0, 10);
        assertEquals(2, msgs.size());
        assertEquals("第二条", msgs.get(0).text()); // 最新在前
        assertTrue(store.delete(owner, id1));
        assertEquals(1, store.count(owner));
        store.clear(owner);
        assertEquals(0, store.count(owner));
    }

    @Test
    void topByLevelAndSearchByTag() {
        org.windy.playershelter.adapter.storage.SqlDirectory dir =
                new org.windy.playershelter.adapter.storage.SqlDirectory(db, repo, config, log);
        org.windy.playershelter.adapter.storage.SqlTagStore tags =
                new org.windy.playershelter.adapter.storage.SqlTagStore(db, log);
        UUID hi = UUID.randomUUID();
        UUID lo = UUID.randomUUID();
        repo.save(sample(hi, ShelterVisibility.PUBLIC, 9));
        repo.save(sample(lo, ShelterVisibility.PUBLIC, 4));
        tags.add(PlayerRef.of(hi), "现代");

        var top = dir.topByLevel(10);
        assertEquals(hi, top.get(0).owner().uuid()); // 9 级排前

        var found = dir.searchByTag("现代", 0, 10);
        assertEquals(1, found.size());
        assertEquals(hi, found.get(0).owner().uuid());
        assertTrue(dir.searchByTag("不存在", 0, 10).isEmpty());
    }

    @Test
    void resetLogPersistsAndUpserts() {
        SqlResetLogStore store = new SqlResetLogStore(db, log);
        PlayerRef owner = PlayerRef.of(UUID.randomUUID());
        assertTrue(store.find(owner).isEmpty());

        store.save(owner, new ResetLogStore.ResetRecord(1000L, 1, 5));
        ResetLogStore.ResetRecord r = store.find(owner).orElseThrow();
        assertEquals(1000L, r.lastResetAt());
        assertEquals(1, r.dayCount());
        assertEquals(5, r.epochDay());

        // upsert：同 owner 覆盖。
        store.save(owner, new ResetLogStore.ResetRecord(2000L, 2, 5));
        ResetLogStore.ResetRecord r2 = store.find(owner).orElseThrow();
        assertEquals(2000L, r2.lastResetAt());
        assertEquals(2, r2.dayCount());
    }
}
