package org.windy.playershelter.adapter.migration;

import org.bukkit.Bukkit;
import org.windy.playershelter.domain.port.RegionMover;

import java.util.logging.Logger;

/**
 * 延迟加载 WorldEdit 复制器，避免未安装 WorldEdit/FAWE 时影响 PlayerShelter 主插件启动。
 */
public final class LazyWorldEditRegionMover implements RegionMover {

    private final Logger logger;
    private RegionMover delegate;
    private boolean unavailableLogged;

    public LazyWorldEditRegionMover(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean copyRegion(String fromWorld, int fromCX, int fromCZ, int sizeChunks,
                              String toWorld, int toCX, int toCZ, ProgressListener progress) {
        RegionMover mover = delegate;
        if (mover == null) {
            mover = loadDelegate();
            if (mover == null) {
                return false;
            }
            delegate = mover;
        }
        return mover.copyRegion(fromWorld, fromCX, fromCZ, sizeChunks, toWorld, toCX, toCZ, progress);
    }

    private RegionMover loadDelegate() {
        boolean pluginPresent = Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
                || Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
        if (!pluginPresent) {
            logUnavailable("未检测到 WorldEdit/FastAsyncWorldEdit，迁移区域复制不可用。");
            return null;
        }
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit", false, LazyWorldEditRegionMover.class.getClassLoader());
            return new WorldEditRegionMover();
        } catch (Throwable t) {
            logUnavailable("WorldEdit API 不可用，迁移区域复制不可用: " + t.getMessage());
            return null;
        }
    }

    private void logUnavailable(String message) {
        if (!unavailableLogged) {
            unavailableLogged = true;
            logger.warning("[PlayerShelter] " + message);
        }
    }
}
