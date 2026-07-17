package org.windy.playershelter.runtime.api;

import org.windy.playershelter.api.PlayerShelterApi;
import org.windy.playershelter.api.BuildCheckProvider;
import org.windy.playershelter.api.PlayerShelterMigrationRegion;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.runtime.PsCore;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link PlayerShelterApi} 实现（决策 #79）。只读查询 + 决策对齐，第三方附属经 ServicesManager 取本实例。
 */
public final class ApiImpl implements PlayerShelterApi {

    /** API 版本号（破坏性变更 +1）。 */
    public static final int VERSION = 5; // v5：migration region copy API

    private final PsCore core;

    public ApiImpl(PsCore core) {
        this.core = core;
    }

    @Override
    public int apiVersion() {
        return VERSION;
    }

    @Override
    public boolean hasShelter(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).isPresent();
    }

    @Override
    public String worldNameOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(Shelter::worldName).orElse(null);
    }

    @Override
    public int levelOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(Shelter::level).orElse(0);
    }

    @Override
    public boolean canBuild(String worldName, UUID actor) {
        return core.repo().findByWorldName(worldName)
                .map(s -> s.resolveRole(PlayerRef.of(actor)).canBuild())
                .orElse(false);
    }

    @Override
    public boolean canEnter(String worldName, UUID actor) {
        return core.repo().findByWorldName(worldName)
                .map(s -> s.resolveRole(PlayerRef.of(actor)).canEnter())
                .orElse(false);
    }

    @Override
    public String visibilityOf(UUID owner) {
        return core.repo().find(PlayerRef.of(owner))
                .map(s -> s.visibility().name().toLowerCase())
                .orElse(null);
    }

    @Override
    public java.util.List<String> tagsOf(UUID owner) {
        return core.tags().tagsOf(PlayerRef.of(owner));
    }

    @Override
    public Optional<PlayerShelterMigrationRegion> exportRegion(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(shelter -> regionOf(shelter, "playershelter:export"));
    }

    @Override
    public Optional<PlayerShelterMigrationRegion> prepareImport(UUID owner, int requiredSideChunks) {
        return core.repo().find(PlayerRef.of(owner)).flatMap(shelter -> {
            int side = shelter.sideChunks();
            if (side < Math.max(1, requiredSideChunks)) {
                return Optional.empty();
            }
            Shelter ready = core.world().ensureWorld(shelter);
            core.world().applyBorder(ready);
            return Optional.of(regionOf(ready, "playershelter:import"));
        });
    }

    @Override
    public boolean copyMigrationRegion(String fromWorld, int fromMinChunkX, int fromMinChunkZ, int sizeChunks,
                                       String toWorld, int toMinChunkX, int toMinChunkZ) {
        if (fromWorld == null || fromWorld.isBlank() || toWorld == null || toWorld.isBlank() || sizeChunks <= 0) {
            return false;
        }
        return core.regionMover().copyRegion(fromWorld, fromMinChunkX, fromMinChunkZ, sizeChunks,
                toWorld, toMinChunkX, toMinChunkZ);
    }

    @Override
    public void registerBuildCheck(org.bukkit.plugin.Plugin plugin, BuildCheckProvider provider, int priority) {
        core.buildChecks().register(plugin, provider, priority);
    }

    @Override
    public void unregisterBuildChecks(org.bukkit.plugin.Plugin plugin) {
        core.buildChecks().unregister(plugin);
    }

    private PlayerShelterMigrationRegion regionOf(Shelter shelter, String source) {
        int side = shelter.sideChunks();
        int minChunkX = Math.floorDiv(shelter.layout().originX(), 16);
        int minChunkZ = Math.floorDiv(shelter.layout().originZ(), 16);
        return new PlayerShelterMigrationRegion(
                shelter.owner().uuid(),
                shelter.worldName(),
                minChunkX,
                minChunkZ,
                minChunkX + side - 1,
                minChunkZ + side - 1,
                side,
                shelter.level(),
                source);
    }
}
