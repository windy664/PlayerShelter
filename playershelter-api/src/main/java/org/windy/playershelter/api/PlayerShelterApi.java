package org.windy.playershelter.api;

import java.util.Optional;
import java.util.UUID;

/**
 * PlayerShelter 对外扩展 API（决策 #79 预留 API 模块）。第三方附属插件【只 compileOnly 依赖本模块】，
 * 绝不 shade（运行期由主插件 jar 提供这些类）。本模块零内部依赖，自带轻量 DTO 保持隔离。
 *
 * <p>起步可不填满；先把契约占住，dogfooding 的官方附属（若做）与第三方走同一入口。
 * 主插件在 onEnable 把实现注册到 Bukkit ServicesManager，附属用
 * {@code getServer().getServicesManager().load(PlayerShelterApi.class)} 取。
 */
public interface PlayerShelterApi {

    /** API 版本号（破坏性变更时 +1，附属可据此判容）。 */
    int apiVersion();

    /** 该玩家是否拥有庇护所。 */
    boolean hasShelter(UUID owner);

    /** 该玩家庇护所的世界名（{@code shelter_<uuid>}）；无则 null。 */
    String worldNameOf(UUID owner);

    /** 该玩家庇护所当前等级；无则 0。 */
    int levelOf(UUID owner);

    /**
     * 某玩家在某庇护所世界里能否建造（只读决策查询，决策 #16 身份四级）。
     * 附属插件（如自定义保护/小游戏）据此与主插件权限对齐。
     *
     * @param worldName 庇护所世界名
     * @param actor     行为者
     */
    boolean canBuild(String worldName, UUID actor);

    /** 某玩家在某庇护所世界能否进入（决策 #16 身份四级 + 可见性）。 */
    boolean canEnter(String worldName, UUID actor);

    /** 该玩家庇护所的可见性（PRIVATE/FRIENDS/PUBLIC，小写）；无则 null。 */
    String visibilityOf(UUID owner);

    /** 该玩家庇护所的标签（决策 P3）；无则空列表。 */
    java.util.List<String> tagsOf(UUID owner);

    /**
     * 导出该玩家当前庇护所的可迁移区域。
     *
     * <p>本方法只返回坐标和元数据，不复制方块、不修改数据。区域为该玩家当前等级可用的正方形 chunk 范围。
     */
    Optional<PlayerShelterMigrationRegion> exportRegion(UUID owner);

    /**
     * 将该玩家已有的庇护所作为迁入目标进行预检。
     *
     * <p>玩家没有庇护所，或当前庇护所可用边长小于 {@code requiredSideChunks} 时返回 empty。
     * 本方法可能会确保目标世界加载。
     */
    Optional<PlayerShelterMigrationRegion> prepareImport(UUID owner, int requiredSideChunks);

    /**
     * 复制一块迁移区域。
     *
     * <p>本方法只复制方块/实体/方块实体数据，不清空源区域，不修改 PlayerShelter 或 GuildShelter 的数据库。
     * 普通 Paper 载体走 WorldEdit；NeoForge 增强版走主插件内置的原生复制实现。
     */
    boolean copyMigrationRegion(String fromWorld, int fromMinChunkX, int fromMinChunkZ, int sizeChunks,
                                String toWorld, int toMinChunkX, int toMinChunkZ);

    /**
     * Register a protection decision provider. Lower priority runs first.
     * Any DENY denies; otherwise any ALLOW allows when the built-in decision would deny.
     */
    void registerBuildCheck(org.bukkit.plugin.Plugin plugin, BuildCheckProvider provider, int priority);

    /** Unregister all protection decision providers owned by one plugin. */
    void unregisterBuildChecks(org.bukkit.plugin.Plugin plugin);
}
