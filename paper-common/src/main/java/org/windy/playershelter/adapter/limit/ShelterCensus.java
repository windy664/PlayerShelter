package org.windy.playershelter.adapter.limit;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统计一个庇护所世界（已加载区块）里的实体 / 方块实体 / 机器数量（决策 P1）。
 *
 * <p>放置/生成事件里频繁调用 → 带 <b>TTL 缓存</b>（默认 3 秒），避免每次都遍历世界（热路径成本）。
 * 因为庇护所世界人走即卸载（决策 #54），世界通常很小，遍历已加载区块开销可控。
 */
public final class ShelterCensus {

    /** 一次统计快照。 */
    public record Census(int mobs, int tiles, int drops, int vehicles, Map<String, Integer> machines) {
        public int machineCount(String blockId) {
            return machines.getOrDefault(blockId, 0);
        }
    }

    private record Cached(Census census, long at) {}

    private static final long TTL_MILLIS = 3000;

    /** 空快照：非主线程（如区块生成）无法遍历实体时的放行用兜底（决策 P1 数量额只在主线程 enforce）。 */
    private static final Census EMPTY = new Census(0, 0, 0, 0, Map.of());

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 带缓存的统计（TTL 内复用）。 */
    public Census countCached(World world) {
        // World#getEntities/#getLoadedChunks 严禁在主线程外调用（AsyncCatcher 会崩服，见 2026-07-04 海底遗迹 worldgen 崩溃）。
        // 区块生成阶段的 FinalizeSpawn/CreatureSpawn 可能在异步工作线程触发 → 此时不遍历世界：有缓存用缓存，否则放行（返 EMPTY）。
        if (!Bukkit.isPrimaryThread()) {
            Cached off = cache.get(world.getName());
            return off != null ? off.census() : EMPTY;
        }
        Cached c = cache.get(world.getName());
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MILLIS) {
            return c.census();
        }
        Census fresh = count(world);
        cache.put(world.getName(), new Cached(fresh, now));
        return fresh;
    }

    /** 使某世界缓存失效（放置成功后调，让下次读到最新计数；也可不调，等 TTL 过期）。 */
    public void invalidate(World world) {
        cache.remove(world.getName());
    }

    /** 现算：遍历已加载区块统计。<b>必须在主线程调用</b>（getEntities 非线程安全，异步会崩服）——非主线程直接返 EMPTY 放行。 */
    public Census count(World world) {
        if (!Bukkit.isPrimaryThread()) {
            return EMPTY;
        }
        int mobs = 0, drops = 0, vehicles = 0;
        for (Entity e : world.getEntities()) {
            if (e instanceof Item) {
                drops++;
            } else if (e instanceof Vehicle) {
                vehicles++;
            } else if (e instanceof Monster || e instanceof Animals
                    || (e instanceof LivingEntity && !(e instanceof org.bukkit.entity.Player))) {
                mobs++;
            }
        }
        int tiles = 0;
        Map<String, Integer> machines = new HashMap<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            for (BlockState ts : chunk.getTileEntities()) {
                tiles++;
                // 机器计数按方块 id（含命名空间）。用 Material key 近似（模组方块 Bukkit 侧亦有 key）。
                String id = ts.getType().getKey().toString();
                machines.merge(id, 1, Integer::sum);
            }
        }
        return new Census(mobs, tiles, drops, vehicles, machines);
    }
}
