package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.Shelter;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 庇护所世界的创建 / 加载 / 卸载 / 边界 / 删除——平台无关端口。这是「换实现」的接缝（决策 M7）。
 *
 * <p><b>为什么是端口</b>：Youer(Spigot 系混合端) <b>不 honor Bukkit 自定义 {@code ChunkGenerator}</b>，
 * 世界生成由 NeoForge/原版引擎驱动。所以：
 * <ul>
 *   <li>普通版（bukkit）：用 Bukkit/Iris 建世界（超平坦/虚空在<b>混合端</b>上可能生不出，仅普通服有效）。</li>
 *   <li>增强版（neoforge_26_2）：用 NeoForge 动态维度 + 原生 ChunkGenerator（M7 填）。</li>
 * </ul>
 */
public interface WorldControl {

    /** 庇护所世界名 {@code shelter_<uuid>}（决策 #52），两侧需一致。 */
    String worldName(Shelter shelter);

    /**
     * 创建（或加载已存在的）庇护所世界，同步出生点与边界。须在主线程调用。
     * 首次创建时按 {@link Shelter#genType()} 选生成器、随机种子已在 {@link Shelter#seed()}。
     * 世界已存在时原样加载返回。
     */
    Shelter ensureWorld(Shelter shelter);

    /**
     * 异步建世界（决策 #26 进度条 / #55 串行队列）：对禁止主线程 create 的引擎（Iris）在异步线程建、
     * 建好回主线程调 {@code onReady}；失败回主线程调 {@code onError}。{@code progressAudience} 为触发玩家
     * UUID（在线则其客户端显示生成进度条），可为 {@code null}。
     *
     * <p>默认实现同步：直接 {@code ensureWorld} 后回调（普通/已加载世界走这条，行为不变）。
     */
    default void ensureWorldAsync(Shelter shelter, UUID progressAudience,
                                  Consumer<Shelter> onReady, Runnable onError) {
        Shelter ready;
        try {
            ready = ensureWorld(shelter);
        } catch (RuntimeException e) {
            onError.run();
            throw e;
        }
        onReady.accept(ready);
    }

    /** 按当前等级同步方形 WorldBorder 直径（决策 #4/#30）。世界需已加载。 */
    void applyBorder(Shelter shelter);

    /**
     * 卸载世界（决策 #7 空闲卸载 / #53 LRU 淘汰）：save 后 unload。
     * 须在世界内<b>零玩家</b>时调（决策 #27 有人不卸载，由 {@code WorldLifecycleService} 保证）。
     *
     * @return 是否成功卸载
     */
    boolean unload(String worldName);

    /** 删除世界存档文件（决策 #6 不活跃删除 / #20 reset 抹地形）。会先卸载。须在主线程。 */
    boolean deleteWorld(String worldName);

    /** 世界当前是否已加载（生命周期扫描用）。 */
    boolean isLoaded(String worldName);

    /** 世界内当前在线玩家数（决策 #27 判定能否卸载）。世界未加载返回 0。 */
    int playerCount(String worldName);

    /**
     * 当前【已加载】的庇护所世界名列表（{@code shelter_*}）。
     * 生命周期巡检（决策 #53 LRU）靠它，<b>避免每 tick 打 DB</b>——这是海量世界场景的关键。
     * 默认空（占位实现）。
     */
    default java.util.List<String> loadedShelterWorlds() {
        return java.util.List.of();
    }

    /**
     * 该世界地形是否<b>惰性生成</b>（Iris：异步、玩家走到哪生成到哪，决策 #31）。
     * 为 {@code true} 时扩界后<b>不应</b>立刻预生成新环（会抵消惰性、卡服）。
     */
    default boolean lazilyGenerated(String worldName) {
        return false;
    }
}
