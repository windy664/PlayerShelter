package org.windy.playershelter.adapter.storage;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.ShelterRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写穿缓存装饰器（同 [[guildshelter-performance]] 热路径思想）：ClaimGuard 在每次方块/交互事件里按世界名取庇护所，
 * 直查 DB 会拖垮热路径——故 {@link #find}/{@link #findByWorldName} 命中内存，{@link #save}/{@link #delete} 写穿同步缓存。
 *
 * <p>单服正确（所有写都过 save）；跨服多点写的缓存失效留 M8（Redis 信道广播）。
 */
public final class CachedShelterRepository implements ShelterRepository {

    private final ShelterRepository delegate;
    private record Entry(Shelter shelter, long loadedAt) {}

    private final ConcurrentHashMap<PlayerRef, Entry> byOwner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerRef> worldToOwner = new ConcurrentHashMap<>();
    /** 记一个「确认无庇护所」的负缓存键集合，避免对没庇护所的玩家反复查库。 */
    private final ConcurrentHashMap<PlayerRef, Long> knownAbsent = new ConcurrentHashMap<>();
    private final long positiveTtlMillis;
    private final long negativeTtlMillis;

    public CachedShelterRepository(ShelterRepository delegate) {
        this(delegate, 15_000L, 5_000L);
    }

    public CachedShelterRepository(ShelterRepository delegate, long positiveTtlMillis, long negativeTtlMillis) {
        this.delegate = delegate;
        this.positiveTtlMillis = Math.max(1_000L, positiveTtlMillis);
        this.negativeTtlMillis = Math.max(500L, negativeTtlMillis);
    }

    @Override
    public Optional<Shelter> find(PlayerRef owner) {
        Entry cached = byOwner.get(owner);
        if (fresh(cached)) {
            return Optional.of(cached.shelter());
        }
        Long absentAt = knownAbsent.get(owner);
        if (absentAt != null && System.currentTimeMillis() - absentAt < negativeTtlMillis) {
            return Optional.empty();
        }
        Optional<Shelter> loaded = delegate.find(owner);
        loaded.ifPresentOrElse(this::cache, () -> knownAbsent.put(owner, System.currentTimeMillis()));
        return loaded;
    }

    @Override
    public Optional<Shelter> findByWorldName(String worldName) {
        PlayerRef owner = worldToOwner.get(worldName);
        if (owner != null) {
            Entry s = byOwner.get(owner);
            if (fresh(s)) {
                return Optional.of(s.shelter());
            }
        }
        Optional<Shelter> loaded = delegate.findByWorldName(worldName);
        loaded.ifPresent(this::cache);
        return loaded;
    }

    @Override
    public void save(Shelter shelter) {
        delegate.save(shelter);
        cache(shelter);
    }

    @Override
    public void delete(PlayerRef owner) {
        delegate.delete(owner);
        Entry s = byOwner.remove(owner);
        if (s != null) {
            worldToOwner.remove(s.shelter().worldName());
        }
        knownAbsent.put(owner, System.currentTimeMillis());
    }

    @Override
    public List<Shelter> all() {
        List<Shelter> list = delegate.all();
        list.forEach(this::cache); // 顺手刷新缓存
        return list;
    }

    @Override
    public List<Shelter> ownedByServer(String serverName) {
        List<Shelter> list = delegate.ownedByServer(serverName);
        list.forEach(this::cache);
        return list;
    }

    private void cache(Shelter s) {
        byOwner.put(s.owner(), new Entry(s, System.currentTimeMillis()));
        worldToOwner.put(s.worldName(), s.owner());
        knownAbsent.remove(s.owner());
    }

    private boolean fresh(Entry entry) {
        return entry != null && System.currentTimeMillis() - entry.loadedAt() < positiveTtlMillis;
    }
}
