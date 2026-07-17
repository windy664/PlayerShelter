package org.windy.playershelter.runtime;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitTask;
import org.windy.playershelter.domain.model.Shelter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 掉落物清理（决策 P1）——放置侧拦不住掉落物（它们是自然产生的，不走 BlockPlace），故周期扫描：
 * 每个已加载的庇护所世界，若掉落物数超该世界等级的 {@link org.windy.playershelter.service.EntityLimits#dropCap}，
 * 清掉<b>最老的</b>若干个（保留新的，玩家刚扔的更可能有用）。仅清庇护所世界，不碰主服公共世界。
 *
 * <p>周期主线程跑（清实体须主线程）；世界少、掉落物计数是内存操作，成本可控。
 */
public final class DropSweeper {

    private final PsCore core;
    private final long periodTicks;
    private BukkitTask task;

    public DropSweeper(PsCore core, int intervalSeconds) {
        this.core = core;
        this.periodTicks = Math.max(1, intervalSeconds) * 20L;
    }

    public void start() {
        if (!core.limits().enabled()) {
            return; // 限额关则不跑
        }
        task = Bukkit.getScheduler().runTaskTimer(core.plugin(), this::sweep, periodTicks, periodTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        for (String worldName : core.world().loadedShelterWorlds()) {
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                continue;
            }
            Shelter s = core.repo().findByWorldName(worldName).orElse(null);
            if (s == null) {
                continue;
            }
            int cap = core.limits().dropCap(s);
            List<Item> drops = new ArrayList<>();
            for (var e : w.getEntitiesByClass(Item.class)) {
                drops.add(e);
            }
            if (drops.size() <= cap) {
                continue;
            }
            // 按存在时间从老到新排，清掉最老的超额部分（ticksLived 越大越老）。
            drops.sort(Comparator.comparingInt(Item::getTicksLived).reversed());
            int toRemove = drops.size() - cap;
            for (int i = 0; i < toRemove; i++) {
                drops.get(i).remove();
            }
            core.plugin().getLogger().fine("[PlayerShelter] " + worldName + " 掉落物超额，已清理 "
                    + toRemove + " 个（上限 " + cap + "）。");
        }
    }
}
