package org.windy.playershelter.runtime;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.windy.playershelter.service.EntityLimits;
import org.windy.playershelter.service.ShelterConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginConfigLevelsTest {

    @Test
    void readsModernPerLevelBundle() {
        YamlConfiguration config = new YamlConfiguration();
        YamlConfiguration levels = new YamlConfiguration();
        levels.set("levels.1.side-chunks", 6);
        levels.set("levels.1.caps.admin", 3);
        levels.set("levels.1.caps.trust", 5);
        levels.set("levels.1.limits.mobs", 60);
        levels.set("levels.1.limits.tiles", 200);
        levels.set("levels.1.limits.drops", 100);
        levels.set("levels.1.limits.vehicles", 20);
        levels.set("levels.1.limits.machines.minecraft:hopper", 32);
        levels.set("levels.2.side-chunks", 8);
        levels.set("levels.2.caps.admin", 4);
        levels.set("levels.2.caps.trust", 8);
        levels.set("levels.2.upgrade.money", 1234.5D);
        levels.set("levels.2.upgrade.items", java.util.List.of("minecraft:oak_log:64"));
        levels.set("levels.2.limits.mobs", 70);
        levels.set("levels.2.limits.tiles", 240);
        levels.set("levels.2.limits.drops", 120);
        levels.set("levels.2.limits.vehicles", 24);
        levels.set("levels.2.limits.machines.minecraft:hopper", 40);
        levels.set("levels.3.side-chunks", 9);

        PluginConfig pluginConfig = new PluginConfig(config, levels);
        ShelterConfig shelter = pluginConfig.shelterConfig();
        EntityLimits limits = pluginConfig.entityLimits();

        assertEquals(3, shelter.layout().maxLevel());
        assertEquals(6, shelter.layout().initialChunks());
        assertEquals(9, shelter.layout().maxChunks());
        assertEquals(8, shelter.layout().sideChunksAtLevel(2));
        assertEquals(4, shelter.adminCapAt(2));
        assertEquals(8, shelter.trustCapAt(2));
        assertEquals(1234.5D, shelter.upgradeCost(1));
        assertEquals("minecraft:oak_log", shelter.upgradeItems(1).get(0).itemId());
        assertEquals(64, shelter.upgradeItems(1).get(0).amount());
        assertEquals(70, limits.mobCap(2));
        assertEquals(240, limits.tileCap(2));
        assertEquals(120, limits.dropCap(2));
        assertEquals(24, limits.vehicleCap(2));
        assertEquals(40, limits.machineCap("minecraft:hopper", 2));
    }
}
