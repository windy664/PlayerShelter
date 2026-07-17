package org.windy.playershelter.runtime.listener;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.windy.playershelter.runtime.PsCore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper/Bukkit 侧世界生命周期监控。NeoForge 增强版还有原生监听兜底；
 * 这里只做世界级日志和 chunk 统计缓存失效，避免 chunk 事件刷屏。
 */
public final class PaperWorldMonitor implements Listener {

    private final PsCore core;
    private final Map<String, Integer> loadedChunks = new ConcurrentHashMap<>();

    public PaperWorldMonitor(PsCore core) {
        this.core = core;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        if (!isShelterWorld(world)) {
            return;
        }
        int chunks = world.getLoadedChunks().length;
        loadedChunks.put(world.getName(), chunks);
        core.plugin().getLogger().info("[PlayerShelter] Paper world load: "
                + world.getName() + " loadedChunks~" + chunks);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        if (!isShelterWorld(world)) {
            return;
        }
        int chunks = loadedChunks.getOrDefault(world.getName(), world.getLoadedChunks().length);
        core.plugin().getLogger().info("[PlayerShelter] Paper world unload: "
                + world.getName() + " loadedChunks~" + chunks);
        loadedChunks.remove(world.getName());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!isShelterWorld(world)) {
            return;
        }
        core.limits().census().invalidate(world);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        World world = event.getWorld();
        if (!isShelterWorld(world)) {
            return;
        }
        core.limits().census().invalidate(world);
    }

    private boolean isShelterWorld(World world) {
        return world != null && world.getName().startsWith("shelter_");
    }
}
