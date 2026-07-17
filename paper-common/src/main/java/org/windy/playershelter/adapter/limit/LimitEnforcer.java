package org.windy.playershelter.adapter.limit;

import org.bukkit.World;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.service.EntityLimits;

/**
 * 限额裁决（决策 P1）——Bukkit 监听器与 NeoForge 原生监听器共用。返回<b>拒绝提示</b>（{@code null}=放行）。
 * 计数走 {@link ShelterCensus}（带 TTL 缓存），上限走 {@link EntityLimits}（按庇护所等级线性）。
 */
public final class LimitEnforcer {

    private final EntityLimits limits;
    private final ShelterCensus census;
    private final boolean enabled;

    public LimitEnforcer(EntityLimits limits, ShelterCensus census, boolean enabled) {
        this.limits = limits;
        this.census = census;
        this.enabled = enabled;
    }

    public ShelterCensus census() {
        return census;
    }

    /** 放置带方块实体的方块：先查机器配额（若是机器），再查方块实体总额。 */
    public String checkPlaceTile(Shelter s, World w, String blockId) {
        if (!enabled) {
            return null;
        }
        String id = blockId == null ? "" : blockId.toLowerCase();
        ShelterCensus.Census c = census.countCached(w);
        return checkPlaceTileCounts(s, id, c.tiles(), c.machineCount(id));
    }

    /**
     * 原生端可传入更准确的已加载方块实体/机器计数，避免混合端 Bukkit 兼容层漏掉模组机器。
     */
    public String checkPlaceTileCounts(Shelter s, String blockId, int existingTiles, int existingMachineCount) {
        if (!enabled) {
            return null;
        }
        String id = blockId == null ? "" : blockId.toLowerCase();
        if (limits.isMachine(id)) {
            int cap = limits.machineCap(id, s.level());
            if (existingMachineCount >= cap) {
                return Messages.format("limit.machine-cap", "这种机器在你的庇护所已达上限（{cap}），升级可提升。",
                        "cap", cap);
            }
        }
        int tileCap = limits.tileCap(s.level());
        if (existingTiles >= tileCap) {
            return Messages.format("limit.tile-cap", "方块实体（箱子/机器等）数量已达上限（{cap}），升级可提升。",
                    "cap", tileCap);
        }
        return null;
    }

    /** 生物生成：查生物总额。 */
    public String checkSpawn(Shelter s, World w) {
        if (!enabled) {
            return null;
        }
        int cap = limits.mobCap(s.level());
        if (census.countCached(w).mobs() >= cap) {
            return Messages.format("limit.mob-cap", "生物数量已达上限（{cap}）。", "cap", cap);
        }
        return null;
    }

    /** 载具放置：查载具总额。 */
    public String checkVehicle(Shelter s, World w) {
        if (!enabled) {
            return null;
        }
        int cap = limits.vehicleCap(s.level());
        if (census.countCached(w).vehicles() >= cap) {
            return Messages.format("limit.vehicle-cap", "载具数量已达上限（{cap}），升级可提升。", "cap", cap);
        }
        return null;
    }

    /** 掉落物（周期清理任务用；放置侧一般不查）。 */
    public int dropCap(Shelter s) {
        return limits.dropCap(s.level());
    }

    public int mobCap(Shelter s) {
        return limits.mobCap(s.level());
    }

    public int tileCap(Shelter s) {
        return limits.tileCap(s.level());
    }

    public int vehicleCap(Shelter s) {
        return limits.vehicleCap(s.level());
    }

    public boolean enabled() {
        return enabled;
    }
}
