package org.windy.playershelter.service;

import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ItemCost;

import java.util.List;
import java.util.Map;

/**
 * 全部可调参数的不可变快照（决策：可调数值一律 config 键 + 默认值，core 不挑魔数）。
 * 适配层从 config.yml 读出后构造，传给各 service。默认值见 {@link #defaults()}。
 *
 * @param layout              固定物理地块 + 每级解锁额度（新世界冻结这份）
 * @param idleUnloadMinutes   世界内零玩家后多久 save+unload（决策 #7，默认 10）
 * @param maxLoadedWorlds     同时加载世界数上限，超出 LRU 淘汰（决策 #53）
 * @param inactiveDeleteDays  玩家多少天不上线进入「存续期」（决策 #6）
 * @param inactiveGraceDays   存续期时长；期间任意登录重置计时，过期才删（决策 #6/#28）
 * @param resetCooldownHours  reset/重建冷却（决策 #77）
 * @param resetMaxPerDay      每天最多 reset 次数（决策 #77）
 * @param adminCap            副主人人数<b>基础</b>上限（决策 #34；实际上限随等级 {@link #adminCapAt}）
 * @param adminPerLevel       副主人名额每级增量（决策 P1 升级权益接线）
 * @param trustCapBase        共建 trust 名额<b>基础</b>上限（决策 P1）
 * @param trustPerLevel       共建名额每级增量（决策 P1）
 * @param publicMinLevel      上公开目录所需最低等级门槛（决策 #71）
 * @param upgradeBaseCost     升级基础费用（决策 #41，0 或经济关闭=免费 #17/#42）
 * @param upgradeCostFactor   递增系数：第 L→L+1 级费用 = base * factor^(L-1)（决策 #41 递增曲线）
 * @param defaultServerName   本后端服名（决策 #59 世界绑服；单服填本服名）
 */
public record ShelterConfig(ShelterLayout layout,
                            int idleUnloadMinutes,
                            int maxLoadedWorlds,
                            int inactiveDeleteDays,
                            int inactiveGraceDays,
                            int resetCooldownHours,
                            int resetMaxPerDay,
                            int adminCap,
                            int adminPerLevel,
                            int trustCapBase,
                            int trustPerLevel,
                            int publicMinLevel,
                            double upgradeBaseCost,
                            double upgradeCostFactor,
                            String defaultServerName,
                            Map<Integer, Double> upgradeCostsByTargetLevel,
                            Map<Integer, List<ItemCost>> upgradeItemsByTargetLevel,
                            Map<Integer, Integer> adminCapsByLevel,
                            Map<Integer, Integer> trustCapsByLevel) {

    public ShelterConfig {
        upgradeCostsByTargetLevel = upgradeCostsByTargetLevel == null ? Map.of() : Map.copyOf(upgradeCostsByTargetLevel);
        upgradeItemsByTargetLevel = upgradeItemsByTargetLevel == null ? Map.of() : Map.copyOf(upgradeItemsByTargetLevel);
        adminCapsByLevel = adminCapsByLevel == null ? Map.of() : Map.copyOf(adminCapsByLevel);
        trustCapsByLevel = trustCapsByLevel == null ? Map.of() : Map.copyOf(trustCapsByLevel);
    }

    public ShelterConfig(ShelterLayout layout,
                         int idleUnloadMinutes,
                         int maxLoadedWorlds,
                         int inactiveDeleteDays,
                         int inactiveGraceDays,
                         int resetCooldownHours,
                         int resetMaxPerDay,
                         int adminCap,
                         int adminPerLevel,
                         int trustCapBase,
                         int trustPerLevel,
                         int publicMinLevel,
                         double upgradeBaseCost,
                         double upgradeCostFactor,
                         String defaultServerName) {
        this(layout, idleUnloadMinutes, maxLoadedWorlds, inactiveDeleteDays, inactiveGraceDays,
                resetCooldownHours, resetMaxPerDay, adminCap, adminPerLevel, trustCapBase, trustPerLevel,
                publicMinLevel, upgradeBaseCost, upgradeCostFactor, defaultServerName,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** 兼容构造（旧 12 参，无名额随等级 → adminPerLevel=0、trust 基础 5/每级 1）。保持既有调用/测试不变。 */
    public ShelterConfig(ShelterLayout layout, int idleUnloadMinutes, int maxLoadedWorlds,
                         int inactiveDeleteDays, int inactiveGraceDays, int resetCooldownHours,
                         int resetMaxPerDay, int adminCap, int publicMinLevel,
                         double upgradeBaseCost, double upgradeCostFactor, String defaultServerName) {
        this(layout, idleUnloadMinutes, maxLoadedWorlds, inactiveDeleteDays, inactiveGraceDays,
                resetCooldownHours, resetMaxPerDay, adminCap, 0, 5, 1, publicMinLevel,
                upgradeBaseCost, upgradeCostFactor, defaultServerName);
    }

    private int clampLevel(int level) {
        return Math.max(1, Math.min(level, layout.maxLevel()));
    }

    /** 给定等级的副主人名额上限（决策 P1 随等级增长）。 */
    public int adminCapAt(int level) {
        Integer explicit = adminCapsByLevel.get(clampLevel(level));
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return Math.max(0, adminCap + adminPerLevel * (clampLevel(level) - 1));
    }

    /** 给定等级的共建 trust 名额上限（决策 P1 随等级增长）。 */
    public int trustCapAt(int level) {
        Integer explicit = trustCapsByLevel.get(clampLevel(level));
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return Math.max(0, trustCapBase + trustPerLevel * (clampLevel(level) - 1));
    }

    /** 第 {@code fromLevel} → {@code fromLevel+1} 级的升级费用（递增曲线，决策 #41）。 */
    public double upgradeCost(int fromLevel) {
        Double explicit = upgradeCostsByTargetLevel.get(clampLevel(fromLevel + 1));
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        if (upgradeBaseCost <= 0) {
            return 0;
        }
        return upgradeBaseCost * Math.pow(upgradeCostFactor, Math.max(0, fromLevel - 1));
    }

    /** Items required for {@code fromLevel -> fromLevel + 1}. */
    public List<ItemCost> upgradeItems(int fromLevel) {
        return upgradeItemsByTargetLevel.getOrDefault(clampLevel(fromLevel + 1), List.of());
    }

    /** 合理默认值（仅兜底/测试；正式值由 config.yml 覆盖）。 */
    public static ShelterConfig defaults() {
        return new ShelterConfig(
                defaultLayout(),
                10,   // idle 10 分钟
                8,    // 同时最多 8 个世界加载
                30,   // 30 天不上线进存续期
                7,    // 存续期 7 天
                12,   // reset 冷却 12 小时
                2,    // 每天最多 reset 2 次
                3,    // 副主人基础上限 3
                0,    // 副主人每级 +0（默认不随等级；服主想开改 config）
                5,    // 共建 trust 基础 5
                1,    // 共建每级 +1
                3,    // 公开需 ≥3 级
                0,    // 默认免费（无经济）
                1.5,  // 递增系数
                ""    // 单服默认空服名
        );
    }

    private static ShelterLayout defaultLayout() {
        Map<Integer, Integer> sides = new java.util.LinkedHashMap<>();
        sides.put(1, 6);
        sides.put(2, 7);
        sides.put(3, 8);
        sides.put(4, 8);
        sides.put(5, 9);
        sides.put(6, 9);
        sides.put(7, 10);
        sides.put(8, 10);
        sides.put(9, 11);
        sides.put(10, 11);
        sides.put(11, 12);
        sides.put(12, 12);
        sides.put(13, 12);
        sides.put(14, 13);
        sides.put(15, 13);
        sides.put(16, 13);
        sides.put(17, 14);
        sides.put(18, 14);
        sides.put(19, 14);
        sides.put(20, 15);
        return new ShelterLayout(6, 15, 20, sides);
    }
}
