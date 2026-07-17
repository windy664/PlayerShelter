package org.windy.playershelter.service;

import java.util.Map;

/**
 * 实体/机器限额（决策 P1：自动化开放但防卡）。按<b>等级线性</b>算上限，config 可调；特定机器按方块 id 单独配额。
 * 纯数据/算法，平台无关；计数与拦截在适配层（{@code ShelterCensus} / 监听器）。
 *
 * <p>上限 = {@code base + perLevel*(level-1)}，钳到 [0, ∞)。机器配额：只有 {@link #machineBaseCaps} 里列出的
 * 方块 id 才受单独限制（其余只计入方块实体总额）；机器上限同样按等级线性（{@code machineBase + machinePerLevel*(lv-1)}）。
 *
 * @param mobsBase/PerLevel      生物（含敌对/动物）总数上限
 * @param tilesBase/PerLevel     方块实体（箱子/熔炉/机器…）总数上限
 * @param dropsBase/PerLevel     掉落物上限
 * @param vehiclesBase/PerLevel  载具（船/矿车）上限
 * @param maxLevel               等级上限（钳制）
 * @param machineBaseCaps        机器方块 id → 1 级基础配额
 * @param machinePerLevel        机器配额每级增量（对所有列出的机器统一）
 */
public record EntityLimits(int mobsBase, int mobsPerLevel,
                           int tilesBase, int tilesPerLevel,
                           int dropsBase, int dropsPerLevel,
                           int vehiclesBase, int vehiclesPerLevel,
                           int maxLevel,
                           Map<String, Integer> machineBaseCaps, int machinePerLevel,
                           Map<Integer, Caps> capsByLevel,
                           Map<Integer, Map<String, Integer>> machineCapsByLevel) {

    public record Caps(Integer mobs, Integer tiles, Integer drops, Integer vehicles) {}

    public EntityLimits {
        machineBaseCaps = machineBaseCaps == null ? Map.of() : Map.copyOf(machineBaseCaps);
        capsByLevel = capsByLevel == null ? Map.of() : Map.copyOf(capsByLevel);
        machineCapsByLevel = machineCapsByLevel == null ? Map.of() : Map.copyOf(machineCapsByLevel);
    }

    public EntityLimits(int mobsBase, int mobsPerLevel,
                        int tilesBase, int tilesPerLevel,
                        int dropsBase, int dropsPerLevel,
                        int vehiclesBase, int vehiclesPerLevel,
                        int maxLevel,
                        Map<String, Integer> machineBaseCaps, int machinePerLevel) {
        this(mobsBase, mobsPerLevel, tilesBase, tilesPerLevel, dropsBase, dropsPerLevel,
                vehiclesBase, vehiclesPerLevel, maxLevel, machineBaseCaps, machinePerLevel, Map.of(), Map.of());
    }

    private int linear(int base, int perLevel, int level) {
        int lv = Math.max(1, Math.min(level, maxLevel));
        return Math.max(0, base + perLevel * (lv - 1));
    }

    public int mobCap(int level) {
        Integer explicit = explicitCaps(level).mobs();
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return linear(mobsBase, mobsPerLevel, level);
    }

    public int tileCap(int level) {
        Integer explicit = explicitCaps(level).tiles();
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return linear(tilesBase, tilesPerLevel, level);
    }

    public int dropCap(int level) {
        Integer explicit = explicitCaps(level).drops();
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return linear(dropsBase, dropsPerLevel, level);
    }

    public int vehicleCap(int level) {
        Integer explicit = explicitCaps(level).vehicles();
        if (explicit != null) {
            return Math.max(0, explicit);
        }
        return linear(vehiclesBase, vehiclesPerLevel, level);
    }

    /** 该方块 id 是否受单独机器配额约束。 */
    public boolean isMachine(String blockId) {
        if (machineBaseCaps.containsKey(blockId)) {
            return true;
        }
        return machineCapsByLevel.values().stream().anyMatch(m -> m.containsKey(blockId));
    }

    /** 该机器在给定等级的配额；非机器返回 {@code -1}（不单独限制）。 */
    public int machineCap(String blockId, int level) {
        Map<String, Integer> explicit = explicitMachineCaps(level);
        Integer explicitCap = explicit.get(blockId);
        if (explicitCap != null) {
            return Math.max(0, explicitCap);
        }
        Integer base = machineBaseCaps.get(blockId);
        if (base == null) {
            return -1;
        }
        return linear(base, machinePerLevel, level);
    }

    private Caps explicitCaps(int level) {
        int lv = Math.max(1, Math.min(level, maxLevel));
        Caps exact = capsByLevel.get(lv);
        if (exact != null) {
            return exact;
        }
        int best = -1;
        Caps found = null;
        for (Map.Entry<Integer, Caps> e : capsByLevel.entrySet()) {
            int k = e.getKey();
            if (k <= lv && k > best) {
                best = k;
                found = e.getValue();
            }
        }
        return found == null ? new Caps(null, null, null, null) : found;
    }

    private Map<String, Integer> explicitMachineCaps(int level) {
        int lv = Math.max(1, Math.min(level, maxLevel));
        Map<String, Integer> exact = machineCapsByLevel.get(lv);
        if (exact != null) {
            return exact;
        }
        int best = -1;
        Map<String, Integer> found = Map.of();
        for (Map.Entry<Integer, Map<String, Integer>> e : machineCapsByLevel.entrySet()) {
            int k = e.getKey();
            if (k <= lv && k > best) {
                best = k;
                found = e.getValue();
            }
        }
        return found;
    }

    /** 关闭全部限额的哨兵（各项极大值 + 无机器配额）；测试/默认兜底用。 */
    public static EntityLimits unlimited() {
        int inf = Integer.MAX_VALUE / 4;
        return new EntityLimits(inf, 0, inf, 0, inf, 0, inf, 0, 1, Map.of(), 0);
    }
}
