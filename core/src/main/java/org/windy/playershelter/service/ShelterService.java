package org.windy.playershelter.service;

import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.domain.port.EconomyPort;
import org.windy.playershelter.domain.port.ItemCostPort;
import org.windy.playershelter.domain.port.ResetLogStore;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.domain.port.WorldControl;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 庇护所核心业务（决策 M2/M3/M5）：建 / 升级 / 重置 / 可见性 / 四级身份管理。
 * 纯领域逻辑，全部经端口落地；建/重置世界走 {@link WorldControl} 异步管线（决策 #26 进度条 / #55 串行）。
 */
public final class ShelterService {

    private final ShelterRepository repo;
    private final WorldControl world;
    private final EconomyPort economy;
    private final ItemCostPort itemCosts;
    private final ShelterConfig config;
    private final Clock clock;
    /** reset 冷却/限额记录（决策 #77，持久化 → 重启不清零）。 */
    private final ResetLogStore resetLog;
    /** 正在异步建世界的 owner（竞态守卫，防连点 /ps create 重复建）。 */
    private final java.util.Set<UUID> creating = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ShelterService(ShelterRepository repo, WorldControl world, EconomyPort economy,
                          ItemCostPort itemCosts, ShelterConfig config, Clock clock, ResetLogStore resetLog) {
        this.repo = Objects.requireNonNull(repo);
        this.world = Objects.requireNonNull(world);
        this.economy = Objects.requireNonNull(economy);
        this.itemCosts = Objects.requireNonNull(itemCosts);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.resetLog = Objects.requireNonNull(resetLog);
    }

    public ShelterService(ShelterRepository repo, WorldControl world, EconomyPort economy,
                          ShelterConfig config, Clock clock, ResetLogStore resetLog) {
        this(repo, world, economy, ItemCostPort.DISABLED, config, clock, resetLog);
    }

    /** 兼容构造（无持久化 → 内存态 reset 记录，重启清零）。 */
    public ShelterService(ShelterRepository repo, WorldControl world, EconomyPort economy,
                          ShelterConfig config, Clock clock) {
        this(repo, world, economy, ItemCostPort.DISABLED, config, clock, new ResetLogStore.InMemory());
    }

    /** {@code shelter_<uuid>}（决策 #52）。 */
    public String worldNameFor(PlayerRef owner) {
        return "shelter_" + owner.uuid();
    }

    public Optional<Shelter> get(PlayerRef owner) {
        return repo.find(owner);
    }

    /**
     * 建庇护所（决策 #5 严格 1 个 / #25 命令带类型 / #42 免费 / #26 进度条异步）。
     * 已有则回调 {@code onError}（"你已经有一个庇护所了"在适配层文案化）；否则异步建世界、成功后落库回调 {@code onReady}。
     *
     * @param audience 触发玩家 UUID（在线显示生成进度条），可为 null
     */
    public void create(PlayerRef owner, GenerationType genType, UUID audience,
                       Consumer<Shelter> onReady, Runnable onError) {
        // 竞态守卫：Iris 异步建世界耗时数秒，其间 repo.find 仍为空；不加锁会因玩家连点 /ps create 重复建世界。
        // creating.add 成功=首次；建完/失败都移除。已有庇护所或正在建 → onError。
        if (repo.find(owner).isPresent() || !creating.add(owner.uuid())) {
            onError.run();
            return;
        }
        Instant now = clock.instant();
        long seed = new java.util.Random().nextLong();
        Shelter seedShelter = Shelter.create(owner, worldNameFor(owner), seed, genType, config.layout(), now)
                .withServerName(config.defaultServerName());
        world.ensureWorldAsync(seedShelter, audience, ready -> {
            repo.save(ready);
            creating.remove(owner.uuid());
            onReady.accept(ready);
        }, () -> {
            creating.remove(owner.uuid());
            onError.run();
        });
    }

    /**
     * 升级一级（等级提高可用地块边长 / #41 递增收费 / #17 经济可选）。
     * 已满级 → 返回 {@link UpgradeResult#MAX_LEVEL}；钱不够 → {@link UpgradeResult#NO_FUNDS}；
     * 成功 → 等级 +1、按新等级同步角落锚定 WorldBorder、落库。
     */
    public UpgradeResult upgrade(PlayerRef owner) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            return UpgradeResult.NO_SHELTER;
        }
        if (s.isMaxLevel()) {
            return UpgradeResult.MAX_LEVEL;
        }
        double cost = config.upgradeCost(s.level());
        java.util.List<org.windy.playershelter.domain.model.ItemCost> items = config.upgradeItems(s.level());
        if (cost > 0 && economy.enabled()) {
            if (!economy.has(owner, cost)) {
                return UpgradeResult.NO_FUNDS;
            }
        }
        if (!items.isEmpty() && !itemCosts.has(owner, items)) {
            return UpgradeResult.NO_MATERIALS;
        }
        if (cost > 0 && economy.enabled()) {
            if (!economy.withdraw(owner, cost)) {
                return UpgradeResult.NO_FUNDS;
            }
        }
        if (!items.isEmpty() && !itemCosts.withdraw(owner, items)) {
            return UpgradeResult.NO_MATERIALS;
        }
        Shelter up = s.withLevel(s.level() + 1).withAutoUnlockedChunksForLevel().withLastActive(clock.instant());
        repo.save(up);
        if (world.isLoaded(up.worldName())) {
            world.applyBorder(up);
        }
        return UpgradeResult.OK;
    }

    /**
     * 重置：抹地形重生成（决策 #20 双确认在适配层做 / #77 冷却限额 / #43 不退费）。
     * {@code keepLevel} 决定是否保留等级与边界（决策 #20 可选保留边界等级）。须主线程调（删世界文件）。
     */
    public ResetResult reset(PlayerRef owner, boolean keepLevel, UUID audience,
                             Consumer<Shelter> onReady, Runnable onError) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            onError.run();
            return ResetResult.NO_SHELTER;
        }
        Instant now = clock.instant();
        long today = now.getEpochSecond() / 86400L;
        ResetLogStore.ResetRecord rec = resetLog.find(owner).orElse(null);
        int todayCount = (rec != null && rec.epochDay() == today) ? rec.dayCount() : 0;
        if (todayCount >= config.resetMaxPerDay()) {
            return ResetResult.DAILY_LIMIT;
        }
        if (rec != null && now.toEpochMilli() < rec.lastResetAt() + config.resetCooldownHours() * 3600_000L) {
            return ResetResult.COOLDOWN;
        }
        int newLevel = keepLevel ? s.level() : 1;
        final int newCount = todayCount + 1;
        rebuildTerrain(s, newLevel, audience, ready -> {
            resetLog.save(owner, new ResetLogStore.ResetRecord(now.toEpochMilli(), newCount, today));
            onReady.accept(ready);
        }, onError);
        return ResetResult.STARTED;
    }

    /**
     * 管理员强制重建某人世界（决策 P4 regen）：无冷却/限额，等级保留。给 {@code AdminCommand} 用，与玩家 reset
     * 共用 {@link #rebuildTerrain} 的「保留非地形状态」逻辑，避免两处各写一遍漏改。
     */
    public boolean adminRegen(PlayerRef owner, UUID audience, Consumer<Shelter> onReady, Runnable onError) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            onError.run();
            return false;
        }
        rebuildTerrain(s, s.level(), audience, onReady, onError);
        return true;
    }

    /**
     * 抹地形重生成的<b>公共核心</b>（reset 与 admin regen 共用）：删旧世界文件、换新种子重建，
     * <b>保留全部非地形状态</b>——成员(admin/trust/access/denied)、flag、可见性、公告、点赞、冻结布局。
     * 标签/留言 keyed by owner 自动保留。须主线程调（删世界文件）。
     */
    private void rebuildTerrain(Shelter s, int newLevel, UUID audience,
                               Consumer<Shelter> onReady, Runnable onError) {
        world.deleteWorld(s.worldName());
        long newSeed = new java.util.Random().nextLong();
        Shelter rebuilt = new Shelter(s.owner(), s.worldName(), newSeed, s.genType(), newLevel, s.layout(),
                s.visibility(), s.admins(), s.trusted(), s.access(), s.denied(), s.flags(),
                s.bulletin(), s.serverName(), s.createdAt(), clock.instant(), s.likes());
        world.ensureWorldAsync(rebuilt, audience, ready -> {
            repo.save(ready);
            onReady.accept(ready);
        }, onError);
    }

    // —— 可见性 & 四级身份管理（决策 #13/#16/#34）——

    public void setVisibility(PlayerRef owner, ShelterVisibility v) {
        repo.find(owner).ifPresent(s -> {
            if (v.isPublic() && s.level() < config.publicMinLevel()) {
                return; // 未达门槛（决策 #71），静默不改；文案由适配层 publicMinLevel 提示
            }
            repo.save(s.withVisibility(v));
        });
    }

    /** 是否满足上公开目录门槛（决策 #71）。 */
    public boolean canGoPublic(Shelter s) {
        return s.level() >= config.publicMinLevel();
    }

    /**
     * 设置/覆盖某个 flag（决策 #33）。{@code flagId} 的合法性由适配层校验（core 不认识 Flag 枚举，只存字符串）。
     * 返回是否生效（owner 无庇护所则 false）。
     */
    public boolean setFlag(PlayerRef owner, String flagId, boolean value) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            return false;
        }
        Map<String, String> f = new HashMap<>(s.flags());
        f.put(flagId, Boolean.toString(value));
        repo.save(s.withFlags(f));
        return true;
    }

    /** 清除某个 flag 的显式设置（回退到该 flag 默认值）。 */
    public boolean clearFlag(PlayerRef owner, String flagId) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null || !s.flags().containsKey(flagId)) {
            return false;
        }
        Map<String, String> f = new HashMap<>(s.flags());
        f.remove(flagId);
        repo.save(s.withFlags(f));
        return true;
    }

    public boolean addAdmin(PlayerRef owner, PlayerRef target) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            return false;
        }
        // 副主人名额随等级（决策 P1）；已在名单内不占新额（改判即视为已存在）。
        if (!s.admins().contains(target) && s.admins().size() >= config.adminCapAt(s.level())) {
            return false;
        }
        repo.save(mutate(s, target, Tier.ADMIN));
        return true;
    }

    /** 加共建 trust；名额随等级（决策 P1）。超额返回 false。 */
    public boolean addTrusted(PlayerRef owner, PlayerRef target) {
        Shelter s = repo.find(owner).orElse(null);
        if (s == null) {
            return false;
        }
        if (!s.trusted().contains(target) && s.trusted().size() >= config.trustCapAt(s.level())) {
            return false;
        }
        repo.save(mutate(s, target, Tier.TRUSTED));
        return true;
    }

    public void addAccess(PlayerRef owner, PlayerRef target) {
        repo.find(owner).ifPresent(s -> repo.save(mutate(s, target, Tier.ACCESS)));
    }

    public void deny(PlayerRef owner, PlayerRef target) {
        repo.find(owner).ifPresent(s -> repo.save(mutate(s, target, Tier.DENIED)));
    }

    /** 从所有身份集合移除（取消信任/解封）。 */
    public void clearRole(PlayerRef owner, PlayerRef target) {
        repo.find(owner).ifPresent(s -> repo.save(mutate(s, target, Tier.NONE)));
    }

    private enum Tier { ADMIN, TRUSTED, ACCESS, DENIED, NONE }

    /** 把 target 移到唯一一个目标层（先从所有层移除再加入，保证互斥）。 */
    private Shelter mutate(Shelter s, PlayerRef target, Tier tier) {
        Set<PlayerRef> admins = new HashSet<>(s.admins());
        Set<PlayerRef> trusted = new HashSet<>(s.trusted());
        Set<PlayerRef> access = new HashSet<>(s.access());
        Set<PlayerRef> denied = new HashSet<>(s.denied());
        admins.remove(target);
        trusted.remove(target);
        access.remove(target);
        denied.remove(target);
        switch (tier) {
            case ADMIN -> admins.add(target);
            case TRUSTED -> trusted.add(target);
            case ACCESS -> access.add(target);
            case DENIED -> denied.add(target);
            case NONE -> { /* 仅移除 */ }
        }
        return s.withAdmins(admins).withTrusted(trusted).withAccess(access).withDenied(denied);
    }

    public ShelterConfig config() {
        return config;
    }

    // —— 结果枚举 ——

    public enum UpgradeResult { OK, NO_SHELTER, MAX_LEVEL, NO_FUNDS, NO_MATERIALS }

    public enum ResetResult { STARTED, NO_SHELTER, COOLDOWN, DAILY_LIMIT }
}
