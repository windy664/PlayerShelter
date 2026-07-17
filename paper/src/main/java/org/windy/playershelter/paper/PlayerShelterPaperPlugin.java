package org.windy.playershelter.paper;

import org.bukkit.Material;
import org.windy.playershelter.adapter.migration.LazyWorldEditRegionMover;
import org.windy.playershelter.adapter.world.PaperWorldControl;
import org.windy.playershelter.domain.port.RegionMover;
import org.windy.playershelter.domain.port.WorldControl;
import org.windy.playershelter.platform.PlatformBindings;
import org.windy.playershelter.runtime.AbstractPlayerShelterPlugin;
import org.windy.playershelter.runtime.PluginConfig;

/**
 * 普通版载体入口（薄）。全部装配在 {@link AbstractPlayerShelterPlugin}，本类只提供平台实现：
 * Paper 版 {@link WorldControl}（普通 Paper 上有效；混合端 Youer 上 VOID/FLAT 生成另由增强版处理）。
 */
public final class PlayerShelterPaperPlugin extends AbstractPlayerShelterPlugin {

    @Override
    protected PlatformBindings createBindings(PluginConfig config) {
        Material platform = platformMaterialOf(config);
        WorldControl wc = new PaperWorldControl(this, getLogger(), platform,
                config.irisEnabled(), config.irisDimension(), config.gamerules(),
                config.shelterWorldViewDistance(), config.shelterWorldSimulationDistance());
        RegionMover mover = new LazyWorldEditRegionMover(getLogger());
        return new PlatformBindings() {
            @Override
            public WorldControl worldControl() {
                return wc;
            }

            @Override
            public RegionMover regionMover() {
                return mover;
            }
        };
    }
}
