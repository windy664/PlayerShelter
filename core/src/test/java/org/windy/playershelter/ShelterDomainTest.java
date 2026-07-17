package org.windy.playershelter;

import org.junit.jupiter.api.Test;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.domain.model.ShelterVisibility;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterDomainTest {

    private static final ShelterLayout LAYOUT = new ShelterLayout(6, 15, 20, Map.of(
            1, 6,
            2, 7,
            20, 15));
    private final PlayerRef owner = PlayerRef.of(UUID.randomUUID());
    private final PlayerRef other = PlayerRef.of(UUID.randomUUID());

    private Shelter fresh() {
        return Shelter.create(owner, "shelter_" + owner.uuid(), 123L,
                GenerationType.NATURAL, LAYOUT, Instant.now());
    }

    @Test
    void borderAndSideGrowWithLevel() {
        Shelter s = fresh();
        assertEquals(96, s.borderSize());
        assertEquals(6, s.sideChunks());
        assertEquals(36, s.areaChunks());
        Shelter level2 = s.withLevel(2).withAutoUnlockedChunksForLevel();
        assertEquals(112, level2.borderSize());
        assertEquals(7, level2.sideChunks());
        assertEquals(49, level2.areaChunks());

        assertEquals(0, level2.layout().originX());
        assertEquals(0, level2.layout().originZ());
        assertEquals(56.0D, level2.layout().borderCenterXAtLevel(2));
        assertEquals(56.0D, level2.layout().borderCenterZAtLevel(2));
    }

    @Test
    void sideCapsAtMaxLevel() {
        Shelter s = fresh().withLevel(999);          // 超上限
        assertEquals(15, s.sideChunks());
        assertEquals(240, s.borderSize());
    }

    @Test
    void privateWorldDeniesStrangers() {
        Shelter s = fresh(); // 默认私密
        assertEquals(ShelterRole.OWNER, s.resolveRole(owner));
        assertEquals(ShelterRole.DENIED, s.resolveRole(other)); // 陌生人进不来
    }

    @Test
    void publicWorldLetsStrangersVisit() {
        Shelter s = fresh().withVisibility(ShelterVisibility.PUBLIC);
        assertEquals(ShelterRole.VISITOR, s.resolveRole(other));
        assertTrue(s.resolveRole(other).canEnter());
        assertFalse(s.resolveRole(other).canBuild()); // 决策 #69 只能看不能动
    }

    @Test
    void deniedHardOverridesPublic() {
        Shelter s = fresh()
                .withVisibility(ShelterVisibility.PUBLIC)
                .withDenied(Set.of(other));
        assertEquals(ShelterRole.DENIED, s.resolveRole(other)); // 决策 #36 硬拦
        assertFalse(s.resolveRole(other).canEnter());
    }

    @Test
    void trustedCanBuildAdminCanManage() {
        Shelter s = fresh().withTrusted(Set.of(other));
        assertEquals(ShelterRole.TRUSTED, s.resolveRole(other));
        assertTrue(s.resolveRole(other).canBuild());
        assertFalse(s.resolveRole(other).canManage());

        Shelter s2 = fresh().withAdmins(Set.of(other));
        assertEquals(ShelterRole.ADMIN, s2.resolveRole(other));
        assertTrue(s2.resolveRole(other).canManage()); // 副主人可管理
        assertFalse(s2.resolveRole(other).isOwner());  // 但非所有权（不可转让/删世界）
    }
}
