package org.windy.playershelter.service;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.SchedulerPort;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.domain.port.WorldControl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ 生命周期核心（决策 #7 空闲卸载 / #27 有人不卸载 / #53 LRU 上限 / #54 无人不留加载区 / #6+#28 不活跃删除）。
 *
 * <p>这是 PlayerShelter 成败所在：玩家可能上千 → 不可能把所有世界常驻加载。本服务统管：
 * <ol>
 *   <li><b>进入即活跃</b>：玩家进某庇护所世界 → {@link #markActive} 刷新 lastActive、登记加载。</li>
 *   <li><b>空闲卸载</b>：周期巡检 {@link #tick}，世界内零玩家且空闲超 {@code idleUnloadMinutes} → save+unload。</li>
 *   <li><b>LRU 淘汰</b>：加载世界数超 {@code maxLoadedWorlds} → 淘汰最久未活跃的空世界。</li>
 *   <li><b>不活跃治理</b>：{@link #evaluateInactive} 给登录监听器判存续期/删除（决策 #6/#28）。</li>
 * </ol>
 *
 * <p>所有卸载/删除都经 {@link WorldControl} 在主线程做；{@link #start()} 注册周期巡检。
 */
public final class WorldLifecycleService {

    private final ShelterRepository repo;
    private final WorldControl world;
    private final SchedulerPort scheduler;
    private final ShelterConfig config;
    private final Clock clock;

    /** worldName → 该世界变空的时刻（用于空闲计时）；非空时移除。 */
    private final Map<String, Instant> emptySince = new ConcurrentHashMap<>();
    /** worldName → 最近一次有玩家活跃的时刻（LRU 淘汰依据）。 */
    private final Map<String, Instant> lastActiveByWorld = new ConcurrentHashMap<>();

    private SchedulerPort.Cancellable tickHandle;

    public WorldLifecycleService(ShelterRepository repo, WorldControl world,
                                 SchedulerPort scheduler, ShelterConfig config, Clock clock) {
        this.repo = Objects.requireNonNull(repo);
        this.world = Objects.requireNonNull(world);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 注册周期巡检（每 10 秒一跳，主线程）。在插件 onEnable 调。 */
    public void start() {
        start(200L);
    }

    public void start(long periodTicks) {
        tickHandle = scheduler.repeatMain(this::tick, Math.max(1L, periodTicks));
    }

    public void stop() {
        if (tickHandle != null) {
            tickHandle.cancel();
            tickHandle = null;
        }
    }

    /** 玩家进入某庇护所世界（监听 PlayerChangedWorld / join）：刷新活跃、解除待卸载、持久化 lastActive。 */
    public void markActive(Shelter shelter) {
        Instant now = clock.instant();
        emptySince.remove(shelter.worldName());
        lastActiveByWorld.put(shelter.worldName(), now);
        repo.save(shelter.withLastActive(now));
    }

    /** 玩家离开某世界 / 世界可能变空时调（监听 quit / changedWorld 的离开侧）：若已空则起空闲计时。 */
    public void touchMaybeEmpty(String worldName) {
        if (world.isLoaded(worldName) && world.playerCount(worldName) == 0) {
            emptySince.putIfAbsent(worldName, clock.instant());
        } else {
            emptySince.remove(worldName);
        }
    }

    /**
     * 周期巡检：空闲卸载 + LRU 淘汰。决策 #27 有人不卸载——只动 {@code playerCount==0} 的世界。
     */
    void tick() {
        Instant now = clock.instant();
        long idleMinutes = config.idleUnloadMinutes();

        // 1) 空闲卸载（决策 #7）；idleUnloadMinutes <= 0 表示禁用。
        if (idleMinutes > 0) {
            long idleSeconds = idleMinutes * 60L;
            for (Map.Entry<String, Instant> e : new ArrayList<>(emptySince.entrySet())) {
                String wn = e.getKey();
                if (!world.isLoaded(wn) || world.playerCount(wn) > 0) {
                    emptySince.remove(wn);
                    continue;
                }
                if (Duration.between(e.getValue(), now).getSeconds() >= idleSeconds) {
                    if (world.unload(wn)) {
                        emptySince.remove(wn);
                    }
                }
            }
        }

        // 2) LRU 上限淘汰（决策 #53）：maxLoadedWorlds <= 0 表示不限。
        if (config.maxLoadedWorlds() > 0) {
            enforceLruCap();
        }
    }

    private void enforceLruCap() {
        // 只问 WorldControl 当前已加载的庇护所世界（内存级，不打 DB，决策 #53 海量世界关键）。
        List<String> loaded = world.loadedShelterWorlds();
        int over = loaded.size() - config.maxLoadedWorlds();
        if (over <= 0) {
            return;
        }
        // 淘汰池 = 已加载里的【空】世界（有人世界永不淘汰，决策 #27）。
        List<String> loadedEmpty = new ArrayList<>();
        for (String wn : loaded) {
            if (world.playerCount(wn) == 0) {
                loadedEmpty.add(wn);
            }
        }
        // 按最近活跃从旧到新排，先淘汰最久未活跃的。
        loadedEmpty.sort(Comparator.comparing(
                wn -> lastActiveByWorld.getOrDefault(wn, Instant.EPOCH)));
        for (int i = 0; i < over && i < loadedEmpty.size(); i++) {
            String wn = loadedEmpty.get(i);
            if (world.unload(wn)) {
                emptySince.remove(wn);
            }
        }
    }

    /** 不活跃治理裁决（决策 #6/#28），由登录监听器对该玩家的庇护所调用。 */
    public enum InactiveVerdict {
        OK,           // 活跃，无事
        IN_GRACE,     // 已进存续期：提示玩家+本次登录已重置计时（监听器据此发提示并 markActive）
        SHOULD_DELETE // 超存续期仍不上线：可删（监听器/巡检删世界+repo）
    }

    /**
     * 判定某庇护所的不活跃状态。{@code lastSeen} 一般取 {@link Shelter#lastActive()}。
     * 进存续期 = 超 {@code inactiveDeleteDays}；存续期满 = 再超 {@code inactiveGraceDays} → 可删。
     *
     * <p>注意：玩家本次登录时，监听器应在调用后 {@link #markActive} 重置计时（决策 #28 任意登录重置）。
     */
    public InactiveVerdict evaluateInactive(Shelter shelter, Instant now) {
        long days = Duration.between(shelter.lastActive(), now).toDays();
        if (days < config.inactiveDeleteDays()) {
            return InactiveVerdict.OK;
        }
        if (days < (long) config.inactiveDeleteDays() + config.inactiveGraceDays()) {
            return InactiveVerdict.IN_GRACE;
        }
        return InactiveVerdict.SHOULD_DELETE;
    }

    /**
     * 离线扫描：找出存续期已满、可删的庇护所（决策 #6）。给后台清理任务用（玩家不在线时）。
     * 实际删除（world.deleteWorld + repo.delete）由调用方在主线程执行，便于发通知/记日志。
     *
     * <p>⚠️ 会触发 {@code repo.all()}（可能慢），<b>应在异步线程</b>调用；删除再回主线程。
     */
    public List<Shelter> findDeletable() {
        Instant now = clock.instant();
        List<Shelter> out = new ArrayList<>();
        for (Shelter s : repo.all()) {
            if (evaluateInactive(s, now) == InactiveVerdict.SHOULD_DELETE) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 删除一个不活跃庇护所（决策 #6）：先复核仍可删 + 世界内无人（避免误删刚回来的人），再删世界 + 库。
     * <b>须在主线程调用</b>（操作 Bukkit 世界）。返回是否真的删了。
     */
    public boolean deleteInactive(Shelter s) {
        if (evaluateInactive(s, clock.instant()) != InactiveVerdict.SHOULD_DELETE) {
            return false; // 复核：可能玩家刚登录重置了计时
        }
        if (world.playerCount(s.worldName()) > 0) {
            return false; // 有人在里面，跳过
        }
        world.deleteWorld(s.worldName());
        repo.delete(s.owner());
        emptySince.remove(s.worldName());
        lastActiveByWorld.remove(s.worldName());
        return true;
    }
}
