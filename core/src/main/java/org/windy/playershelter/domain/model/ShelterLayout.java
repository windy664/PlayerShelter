package org.windy.playershelter.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 庇护所角落锚定的方形边界参数。
 *
 * <p><b>随世界冻结</b>（同 GuildShelter 冻结布局思想）：这是该庇护所<b>创建时</b>的布局参数快照。
 * 服主改 config 只影响<b>新建</b>的世界，已存在世界保持原参数——否则改配置会让旧世界边界跳变、
 * 界外已建建筑突然失去保护。
 *
 * <p>新模型：等级决定“可用正方形边长”，单位是 chunk。WorldBorder 从锚定角落向 +X/+Z 方向扩大，
 * 例如 1 级 {@code 6 chunk} 表示可用范围为 {@code 6×6 chunk}，满级 {@code 15 chunk} 表示
 * {@code 15×15 chunk}。
 *
 * @param startBorder          旧模型兼容字段；默认等于初始解锁边长 * 16
 * @param growthPerLevel       旧模型兼容字段；新模型中通常为 0
 * @param maxLevel             等级上限
 * @param initialChunks      1 级初始可用正方形边长，单位 chunk
 * @param maxChunks          单个庇护所最大边长，单位 chunk
 * @param sideChunksByLevel  每级可用正方形边长；没写的等级沿用最近较低等级
 */
public record ShelterLayout(int startBorder, int growthPerLevel, int maxLevel,
                            int initialChunks, int maxChunks,
                            Map<Integer, Integer> sideChunksByLevel,
                            int originX, int originZ) {

    public ShelterLayout(int startBorder, int growthPerLevel, int maxLevel) {
        this(startBorder, growthPerLevel, maxLevel,
                roundChunks(startBorder),
                roundChunks(startBorder + Math.max(0, growthPerLevel) * Math.max(0, maxLevel - 1)),
                Map.of(),
                -(roundChunks(startBorder) * 16) / 2,
                -(roundChunks(startBorder) * 16) / 2);
    }

    public ShelterLayout(int initialChunks, int maxChunks, int maxLevel, Map<Integer, Integer> sideChunksByLevel) {
        this(initialChunks, maxChunks, maxLevel, sideChunksByLevel, 0, 0);
    }

    public ShelterLayout(int initialChunks, int maxChunks, int maxLevel,
                         Map<Integer, Integer> sideChunksByLevel, int originX, int originZ) {
        this(initialChunks * 16, 0, maxLevel, initialChunks, maxChunks, sideChunksByLevel, originX, originZ);
    }

    public ShelterLayout {
        if (startBorder < 1) {
            throw new IllegalArgumentException("startBorder 必须 ≥1");
        }
        if (growthPerLevel < 0) {
            throw new IllegalArgumentException("growthPerLevel 必须 ≥0");
        }
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel 必须 ≥1");
        }
        if (initialChunks < 1) {
            throw new IllegalArgumentException("initialChunks 必须 ≥1");
        }
        if (maxChunks < initialChunks) {
            throw new IllegalArgumentException("maxChunks 必须 ≥ initialChunks");
        }
        Map<Integer, Integer> cleaned = new TreeMap<>();
        if (sideChunksByLevel != null) {
            for (Map.Entry<Integer, Integer> e : sideChunksByLevel.entrySet()) {
                if (e.getKey() != null && e.getKey() >= 1 && e.getValue() != null) {
                    cleaned.put(Math.min(e.getKey(), maxLevel), Math.max(1, Math.min(e.getValue(), maxChunks)));
                }
            }
        }
        if (!cleaned.containsKey(1)) {
            cleaned.put(1, initialChunks);
        }
        if (!cleaned.containsKey(maxLevel)) {
            cleaned.put(maxLevel, maxChunks);
        }
        sideChunksByLevel = Map.copyOf(cleaned);
    }

    /** 给定等级的 WorldBorder 边长（方块）。 */
    public int borderSizeAtLevel(int level) {
        return sideChunksAtLevel(level) * 16;
    }

    public double borderCenterXAtLevel(int level) {
        return originX + borderSizeAtLevel(level) / 2.0;
    }

    public double borderCenterZAtLevel(int level) {
        return originZ + borderSizeAtLevel(level) / 2.0;
    }

    /** 给定等级的可用正方形边长，单位 chunk。 */
    public int sideChunksAtLevel(int level) {
        int lv = Math.max(1, Math.min(level, maxLevel));
        int bestLevel = 1;
        int bestValue = initialChunks;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(sideChunksByLevel).entrySet()) {
            if (e.getKey() <= lv && e.getKey() >= bestLevel) {
                bestLevel = e.getKey();
                bestValue = e.getValue();
            }
        }
        return Math.max(1, Math.min(bestValue, maxChunks));
    }

    /** 给定等级的可用面积，单位 chunk 数。 */
    public int areaChunksAtLevel(int level) {
        int side = sideChunksAtLevel(level);
        return side * side;
    }

    public boolean containsOffset(int dx, int dz) {
        return dx >= 0 && dz >= 0 && dx < maxChunks && dz < maxChunks;
    }

    public static int packOffset(int dx, int dz) {
        if (dx < 0 || dz < 0 || dx >= 1024 || dz >= 1024) {
            throw new IllegalArgumentException("chunk offset 必须在 0..1023 内");
        }
        return (dx << 10) | dz;
    }

    public static int unpackDx(int packed) {
        return (packed >> 10) & 1023;
    }

    public static int unpackDz(int packed) {
        return packed & 1023;
    }

    public Map<Integer, Integer> sortedSideLevels() {
        return new LinkedHashMap<>(new TreeMap<>(sideChunksByLevel));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int roundChunks(int borderSize) {
        return Math.max(1, Math.round(borderSize / 16.0f));
    }
}
