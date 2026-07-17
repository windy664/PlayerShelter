package org.windy.playershelter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.domain.port.WorldControl;
import org.windy.playershelter.service.ShelterConfig;
import org.windy.playershelter.service.ShelterService;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterServiceTest {

    private Fakes.MemRepo repo;
    private Fakes.FakeWorld world;
    private ShelterConfig config;
    private final PlayerRef owner = PlayerRef.of(UUID.randomUUID());
    private final PlayerRef other = PlayerRef.of(UUID.randomUUID());
    private static final ShelterLayout LAYOUT = new ShelterLayout(6, 15, 20, Map.of(
            1, 6,
            2, 7,
            3, 8,
            20, 15));

    @BeforeEach
    void setup() {
        repo = new Fakes.MemRepo();
        world = new Fakes.FakeWorld();
        config = new ShelterConfig(LAYOUT,
                10, 8, 30, 7, 12, 2, 2, 3, 1000, 1.5, "");
    }

    private ShelterService svc(Fakes.FakeEconomy eco) {
        return new ShelterService(repo, world, eco, config, Clock.systemUTC());
    }

    @Test
    void createOnlyOncePerPlayer() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        AtomicReference<Shelter> created = new AtomicReference<>();
        s.create(owner, GenerationType.NATURAL, null, created::set, () -> {});
        assertNotNull(created.get());
        assertTrue(repo.find(owner).isPresent());
        assertTrue(world.isLoaded(created.get().worldName()));

        // 第二次应触发 onError，不再建。
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        s.create(owner, GenerationType.FLAT, null, x -> {}, () -> failed.set(true));
        assertTrue(failed.get());
    }

    @Test
    void upgradeChargesAndExpands() {
        ShelterService s = svc(new Fakes.FakeEconomy(true, 5000));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        Shelter before = repo.find(owner).orElseThrow();

        ShelterService.UpgradeResult r = s.upgrade(owner);
        assertEquals(ShelterService.UpgradeResult.OK, r);
        Shelter after = repo.find(owner).orElseThrow();
        assertEquals(2, after.level());
        assertEquals(6, before.sideChunks());
        assertEquals(7, after.sideChunks());
        assertEquals(112, after.borderSize());
    }

    @Test
    void upgradeBlockedWhenBroke() {
        ShelterService s = svc(new Fakes.FakeEconomy(true, 10)); // 余额不足 1000
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertEquals(ShelterService.UpgradeResult.NO_FUNDS, s.upgrade(owner));
        assertEquals(1, repo.find(owner).orElseThrow().level()); // 没升
    }

    @Test
    void upgradeFreeWhenEconomyDisabled() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertEquals(ShelterService.UpgradeResult.OK, s.upgrade(owner)); // 经济关 → 免费成功
    }

    @Test
    void upgradeBlockedWhenMaterialsMissing() {
        ShelterConfig cfg = new ShelterConfig(LAYOUT,
                10, 8, 30, 7, 12, 2, 2, 0, 5, 1, 3, 0, 1.5, "",
                Map.of(2, 0.0), Map.of(2, List.of(new ItemCost("minecraft:oak_log", 64))), Map.of(), Map.of());
        Fakes.FakeItemCosts items = new Fakes.FakeItemCosts().with("minecraft:oak_log", 32);
        ShelterService s = new ShelterService(repo, world, new Fakes.FakeEconomy(false, 0),
                items, cfg, Clock.systemUTC(), new org.windy.playershelter.domain.port.ResetLogStore.InMemory());

        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertEquals(ShelterService.UpgradeResult.NO_MATERIALS, s.upgrade(owner));
        assertEquals(1, repo.find(owner).orElseThrow().level());
        assertEquals(32, items.inventory.get("minecraft:oak_log"));
    }

    @Test
    void upgradeWithdrawsMaterials() {
        ShelterConfig cfg = new ShelterConfig(LAYOUT,
                10, 8, 30, 7, 12, 2, 2, 0, 5, 1, 3, 0, 1.5, "",
                Map.of(2, 0.0), Map.of(2, List.of(new ItemCost("minecraft:oak_log", 64))), Map.of(), Map.of());
        Fakes.FakeItemCosts items = new Fakes.FakeItemCosts().with("minecraft:oak_log", 80);
        ShelterService s = new ShelterService(repo, world, new Fakes.FakeEconomy(false, 0),
                items, cfg, Clock.systemUTC(), new org.windy.playershelter.domain.port.ResetLogStore.InMemory());

        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertEquals(ShelterService.UpgradeResult.OK, s.upgrade(owner));
        assertEquals(2, repo.find(owner).orElseThrow().level());
        assertEquals(16, items.inventory.get("minecraft:oak_log"));
    }

    @Test
    void adminCapEnforced() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        // cap=2
        assertTrue(s.addAdmin(owner, PlayerRef.of(UUID.randomUUID())));
        assertTrue(s.addAdmin(owner, PlayerRef.of(UUID.randomUUID())));
        assertFalse(s.addAdmin(owner, PlayerRef.of(UUID.randomUUID()))); // 第三个超限
        assertEquals(2, repo.find(owner).orElseThrow().admins().size());
    }

    @Test
    void rolesAreMutuallyExclusive() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        s.addTrusted(owner, other);
        assertEquals(ShelterRole.TRUSTED, repo.find(owner).orElseThrow().resolveRole(other));
        // 改判黑名单 → 应从 trusted 移除、进 denied。
        s.deny(owner, other);
        Shelter sh = repo.find(owner).orElseThrow();
        assertEquals(ShelterRole.DENIED, sh.resolveRole(other));
        assertFalse(sh.trusted().contains(other));
        // 清除 → 回访客（私密世界为 DENIED）。
        s.clearRole(owner, other);
        assertEquals(ShelterRole.DENIED, repo.find(owner).orElseThrow().resolveRole(other)); // 私密默认拒陌生人
    }

    @Test
    void publicGateBlocksLowLevel() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {}); // level 1 < publicMinLevel 3
        s.setVisibility(owner, ShelterVisibility.PUBLIC);
        assertEquals(ShelterVisibility.PRIVATE, repo.find(owner).orElseThrow().visibility()); // 被门槛挡，未改

        // 升到 3 级后可公开。
        s.upgrade(owner);
        s.upgrade(owner);
        s.setVisibility(owner, ShelterVisibility.PUBLIC);
        assertEquals(ShelterVisibility.PUBLIC, repo.find(owner).orElseThrow().visibility());
    }

    @Test
    void resetRespectsCooldown() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        String worldName = repo.find(owner).orElseThrow().worldName();

        ShelterService.ResetResult first = s.reset(owner, true, null, x -> {}, () -> {});
        assertEquals(ShelterService.ResetResult.STARTED, first);
        assertTrue(world.deleted.contains(worldName)); // 旧世界被删重建

        // 立刻再 reset → 冷却拦截。
        ShelterService.ResetResult second = s.reset(owner, true, null, x -> {}, () -> {});
        assertEquals(ShelterService.ResetResult.COOLDOWN, second);
    }

    @Test
    void resetCooldownSurvivesRestart() {
        // 共享一个持久化 store，模拟重启（新建 ShelterService 实例但 store 不变）。
        org.windy.playershelter.domain.port.ResetLogStore store =
                new org.windy.playershelter.domain.port.ResetLogStore.InMemory();
        ShelterService s1 = new ShelterService(repo, world, new Fakes.FakeEconomy(false, 0),
                config, java.time.Clock.systemUTC(), store);
        s1.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertEquals(ShelterService.ResetResult.STARTED, s1.reset(owner, true, null, x -> {}, () -> {}));

        // “重启”：新 service 实例，同一 store → 冷却仍生效。
        ShelterService s2 = new ShelterService(repo, world, new Fakes.FakeEconomy(false, 0),
                config, java.time.Clock.systemUTC(), store);
        assertEquals(ShelterService.ResetResult.COOLDOWN, s2.reset(owner, true, null, x -> {}, () -> {}));
    }

    @Test
    void concurrentCreateGuardedAgainstDoubleBuild() {
        // 用「挂起 onReady」的假世界模拟 Iris 异步窗口：第一次 create 的回调先不触发。
        final java.util.List<Runnable> pending = new java.util.ArrayList<>();
        WorldControl deferred = new Fakes.FakeWorld() {
            @Override
            public void ensureWorldAsync(Shelter sh, UUID a, java.util.function.Consumer<Shelter> ready, Runnable err) {
                pending.add(() -> { ensureWorld(sh); ready.accept(sh); });
            }
        };
        ShelterService s = new ShelterService(repo, deferred, new Fakes.FakeEconomy(false, 0),
                config, java.time.Clock.systemUTC());
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger err = new java.util.concurrent.atomic.AtomicInteger();

        s.create(owner, GenerationType.NATURAL, null, x -> ok.incrementAndGet(), err::incrementAndGet);
        // 第一次还没完成（回调挂起），此时连点第二次 → 应被守卫挡住。
        s.create(owner, GenerationType.NATURAL, null, x -> ok.incrementAndGet(), err::incrementAndGet);
        assertEquals(1, pending.size());  // 只发起了一次真正的建世界
        assertEquals(1, err.get());       // 第二次立即 onError

        pending.get(0).run();             // 完成第一次
        assertEquals(1, ok.get());
        assertTrue(repo.find(owner).isPresent());
    }

    @Test
    void resetPreservesMembersAndSettings() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        // 设置一堆社交资产。
        s.addTrusted(owner, other);
        s.setFlag(owner, "pvp", true);
        s.upgrade(owner);
        s.upgrade(owner); // 3 级
        s.setVisibility(owner, ShelterVisibility.PUBLIC);
        Shelter before = repo.find(owner).orElseThrow();
        assertEquals(ShelterVisibility.PUBLIC, before.visibility());

        // reset 保留等级 → 成员/flag/可见性/等级都应保留，只地形重生成。
        s.reset(owner, true, null, x -> {}, () -> {});
        Shelter after = repo.find(owner).orElseThrow();
        assertEquals(3, after.level());
        assertTrue(after.trusted().contains(other));
        assertEquals("true", after.flags().get("pvp"));
        assertEquals(ShelterVisibility.PUBLIC, after.visibility());
        assertNotEquals(before.seed(), after.seed()); // 种子变了（地形重生成）
    }

    @Test
    void adminRegenPreservesStateNoLimit() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        s.addTrusted(owner, other);
        s.upgrade(owner); // 2 级
        long seedBefore = repo.find(owner).orElseThrow().seed();

        // adminRegen 无冷却/限额，保留等级+成员，换种子。
        assertTrue(s.adminRegen(owner, null, x -> {}, () -> {}));
        Shelter after = repo.find(owner).orElseThrow();
        assertEquals(2, after.level());
        assertTrue(after.trusted().contains(other));
        assertNotEquals(seedBefore, after.seed());

        // 无庇护所时 adminRegen 返回 false + onError。
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        assertFalse(s.adminRegen(PlayerRef.of(UUID.randomUUID()), null, x -> {}, () -> failed.set(true)));
        assertTrue(failed.get());
    }

    @Test
    void resetKeepLevelOption() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        s.upgrade(owner);
        s.upgrade(owner); // level 3
        s.reset(owner, true, null, x -> {}, () -> {});
        assertEquals(3, repo.find(owner).orElseThrow().level()); // 保留等级
    }

    @Test
    void trustCapScalesWithLevel() {
        // trust 基础 5 每级 +1；用带名额随等级的完整构造。
        ShelterConfig cfg = new ShelterConfig(LAYOUT,
                10, 8, 30, 7, 12, 2, 3, 0, 2, 1, 3, 0, 1.5, ""); // adminBase3/perLv0, trustBase2/perLv1
        ShelterService s = new ShelterService(repo, world, new Fakes.FakeEconomy(false, 0),
                cfg, java.time.Clock.systemUTC());
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        // 1 级 trust 上限 = 2
        assertTrue(s.addTrusted(owner, PlayerRef.of(UUID.randomUUID())));
        assertTrue(s.addTrusted(owner, PlayerRef.of(UUID.randomUUID())));
        assertFalse(s.addTrusted(owner, PlayerRef.of(UUID.randomUUID()))); // 第三个超 1 级上限
        // 升到 3 级 → 上限 4，可再加
        s.upgrade(owner);
        s.upgrade(owner);
        assertTrue(s.addTrusted(owner, PlayerRef.of(UUID.randomUUID())));
        assertEquals(3, repo.find(owner).orElseThrow().trusted().size());
    }

    @Test
    void setAndClearFlag() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        s.create(owner, GenerationType.NATURAL, null, x -> {}, () -> {});
        assertTrue(s.setFlag(owner, "pvp", true));
        assertEquals("true", repo.find(owner).orElseThrow().flags().get("pvp"));
        assertTrue(s.setFlag(owner, "pvp", false));
        assertEquals("false", repo.find(owner).orElseThrow().flags().get("pvp"));
        assertTrue(s.clearFlag(owner, "pvp"));
        assertFalse(repo.find(owner).orElseThrow().flags().containsKey("pvp"));
        assertFalse(s.clearFlag(owner, "pvp")); // 已无该键 → false
    }

    @Test
    void setFlagFailsWithoutShelter() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        assertFalse(s.setFlag(owner, "pvp", true));
    }

    @Test
    void worldNameDeterministic() {
        ShelterService s = svc(new Fakes.FakeEconomy(false, 0));
        assertEquals("shelter_" + owner.uuid(), s.worldNameFor(owner));
        assertNull(repo.find(owner).orElse(null));
    }
}
