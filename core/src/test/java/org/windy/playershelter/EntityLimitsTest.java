package org.windy.playershelter;

import org.junit.jupiter.api.Test;
import org.windy.playershelter.service.EntityLimits;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLimitsTest {

    private EntityLimits sample() {
        return new EntityLimits(
                60, 4,      // mobs
                200, 20,    // tiles
                100, 8,     // drops
                20, 2,      // vehicles
                50,         // maxLevel
                Map.of("minecraft:hopper", 32, "ftbic:wind_turbine", 4), 1);
    }

    @Test
    void capsScaleLinearlyWithLevel() {
        EntityLimits l = sample();
        assertEquals(60, l.mobCap(1));
        assertEquals(60 + 4 * 4, l.mobCap(5));   // 76
        assertEquals(200, l.tileCap(1));
        assertEquals(200 + 20 * 9, l.tileCap(10)); // 380
        assertEquals(20, l.vehicleCap(1));
    }

    @Test
    void capsClampAtMaxLevel() {
        EntityLimits l = sample();
        assertEquals(60 + 4 * 49, l.mobCap(999)); // 钳到 50 级
    }

    @Test
    void machineQuotaByBlockId() {
        EntityLimits l = sample();
        assertTrue(l.isMachine("minecraft:hopper"));
        assertFalse(l.isMachine("minecraft:stone"));
        assertEquals(32, l.machineCap("minecraft:hopper", 1));
        assertEquals(32 + 1 * 4, l.machineCap("minecraft:hopper", 5)); // 每级 +1
        assertEquals(4, l.machineCap("ftbic:wind_turbine", 1));
        assertEquals(-1, l.machineCap("minecraft:stone", 1)); // 非机器 = 不限
    }

    @Test
    void unlimitedSentinelIsHuge() {
        EntityLimits u = EntityLimits.unlimited();
        assertTrue(u.mobCap(1) > 1_000_000);
        assertFalse(u.isMachine("minecraft:hopper"));
    }

    @Test
    void configCapsScaleWithLevel() {
        // 名额随等级（决策 P1）：admin 基础 3 每级 +1、trust 基础 5 每级 +2。
        org.windy.playershelter.service.ShelterConfig c = new org.windy.playershelter.service.ShelterConfig(
                new org.windy.playershelter.domain.model.ShelterLayout(64, 16, 10),
                10, 8, 30, 7, 12, 2, 3, 1, 5, 2, 3, 0, 1.5, "");
        assertEquals(3, c.adminCapAt(1));
        assertEquals(3 + 1 * 4, c.adminCapAt(5));   // 7
        assertEquals(5, c.trustCapAt(1));
        assertEquals(5 + 2 * 4, c.trustCapAt(5));   // 13
        assertEquals(3 + 1 * 9, c.adminCapAt(999)); // 钳到 maxLevel 10 → 12
    }
}
