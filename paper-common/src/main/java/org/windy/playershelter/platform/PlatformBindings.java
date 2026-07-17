package org.windy.playershelter.platform;

import org.windy.playershelter.domain.port.RegionMover;
import org.windy.playershelter.domain.port.WorldControl;

/**
 * 平台接缝（决策：双载体分流）。普通版（bukkit）与增强版（neoforge_26_2）各自提供一份实现，
 * 把「会因载体不同而换实现」的端口暴露给共享引导。
 *
 * <p>当前最关键的分流点是 {@link WorldControl}：
 * <ul>
 *   <li>普通版 → Bukkit/Iris 建世界（混合端上超平坦/虚空可能生不出，仅普通服有效）。</li>
 *   <li>增强版 → NeoForge 动态维度 + 原生 ChunkGenerator（M7；绕开 Youer 不 honor Bukkit ChunkGenerator）。</li>
 * </ul>
 *
 * <p>铁律：本模块 classpath 里没有 net.neoforged，增强版的实现放在 neoforge_26_2 模块里再注入。
 */
public interface PlatformBindings {

    /** 世界创建/加载/卸载/边界/删除端口（按载体分流的核心）。 */
    WorldControl worldControl();

    /** 迁移区域复制端口：普通版走 WorldEdit，增强版走 NeoForge 原生复制。 */
    default RegionMover regionMover() {
        return RegionMover.UNSUPPORTED;
    }
}
