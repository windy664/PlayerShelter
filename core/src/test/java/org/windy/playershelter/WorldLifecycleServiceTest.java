package org.windy.playershelter;

import org.junit.jupiter.api.Test;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.port.SchedulerPort;
import org.windy.playershelter.service.ShelterConfig;
import org.windy.playershelter.service.WorldLifecycleService;
import org.windy.playershelter.service.WorldLifecycleService.InactiveVerdict;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLifecycleServiceTest {

    private final ShelterConfig config = new ShelterConfig(new ShelterLayout(64, 16, 50),
            10, 8, 30, 7, 12, 2, 3, 3, 0, 1.5, "");

    /** 不调度的假调度器（测试只验判定逻辑，不跑 tick）。 */
    private final SchedulerPort noop = new SchedulerPort() {
        @Override public void runMain(Runnable t) { }
        @Override public void runMainLater(Runnable t, long ticks) { }
        @Override public void runAsync(Runnable t) { }
        @Override public Cancellable repeatMain(Runnable t, long p) { return () -> {}; }
    };

    private Shelter shelterLastActive(Instant when) {
        PlayerRef owner = PlayerRef.of(UUID.randomUUID());
        return new Shelter(owner, "shelter_" + owner.uuid(), 1L, GenerationType.NATURAL,
                1, new ShelterLayout(64, 16, 50), org.windy.playershelter.domain.model.ShelterVisibility.PRIVATE,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Map.of(), "", "", when, when, 0);
    }

    @Test
    void inactiveVerdictBoundaries() {
        Fakes.MemRepo repo = new Fakes.MemRepo();
        Fakes.FakeWorld world = new Fakes.FakeWorld();
        WorldLifecycleService svc = new WorldLifecycleService(repo, world, noop, config, Clock.systemUTC());
        Instant now = Instant.now();

        // 活跃（10 天前）→ OK（< 30 天）
        assertEquals(InactiveVerdict.OK,
                svc.evaluateInactive(shelterLastActive(now.minus(10, ChronoUnit.DAYS)), now));
        // 35 天前 → 进存续期（30~37 天）
        assertEquals(InactiveVerdict.IN_GRACE,
                svc.evaluateInactive(shelterLastActive(now.minus(35, ChronoUnit.DAYS)), now));
        // 40 天前 → 可删（> 30+7）
        assertEquals(InactiveVerdict.SHOULD_DELETE,
                svc.evaluateInactive(shelterLastActive(now.minus(40, ChronoUnit.DAYS)), now));
    }

    @Test
    void findDeletablePicksOnlyExpired() {
        Fakes.MemRepo repo = new Fakes.MemRepo();
        Fakes.FakeWorld world = new Fakes.FakeWorld();
        WorldLifecycleService svc = new WorldLifecycleService(repo, world, noop, config, Clock.systemUTC());
        Instant now = Instant.now();
        Shelter fresh = shelterLastActive(now.minus(1, ChronoUnit.DAYS));
        Shelter expired = shelterLastActive(now.minus(50, ChronoUnit.DAYS));
        repo.save(fresh);
        repo.save(expired);

        var deletable = svc.findDeletable();
        assertEquals(1, deletable.size());
        assertEquals(expired.owner(), deletable.get(0).owner());
    }

    @Test
    void deleteInactiveRemovesWorldAndRecord() {
        Fakes.MemRepo repo = new Fakes.MemRepo();
        Fakes.FakeWorld world = new Fakes.FakeWorld();
        WorldLifecycleService svc = new WorldLifecycleService(repo, world, noop, config, Clock.systemUTC());
        Shelter expired = shelterLastActive(Instant.now().minus(50, ChronoUnit.DAYS));
        repo.save(expired);
        world.ensureWorld(expired); // 标记加载

        assertTrue(svc.deleteInactive(expired));
        assertTrue(world.deleted.contains(expired.worldName()));
        assertFalse(repo.find(expired.owner()).isPresent());
    }

    @Test
    void deleteInactiveSkipsRecentlyActive() {
        Fakes.MemRepo repo = new Fakes.MemRepo();
        Fakes.FakeWorld world = new Fakes.FakeWorld();
        WorldLifecycleService svc = new WorldLifecycleService(repo, world, noop, config, Clock.systemUTC());
        Shelter fresh = shelterLastActive(Instant.now().minus(2, ChronoUnit.DAYS));
        repo.save(fresh);
        assertFalse(svc.deleteInactive(fresh)); // 复核未过期 → 不删
    }
}
