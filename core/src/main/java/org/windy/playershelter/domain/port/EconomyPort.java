package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;

/**
 * 经济端口（决策 #17 Vault 软依赖可选 / #42 建世界免费 / #41 升级递增收费）。
 *
 * <p>软依赖缺席（没装 Vault）时实现返回一个「永远免费且成功」的桩：{@link #enabled()} = false，
 * {@code withdraw} 恒 true——这样上层升级逻辑无需到处判空，没经济就是不收钱。
 */
public interface EconomyPort {

    /** 经济是否启用（装了 Vault 且 config 开启收费）。false 时一切免费。 */
    boolean enabled();

    /** 玩家余额是否 ≥ amount。enabled=false 恒 true。 */
    boolean has(PlayerRef player, double amount);

    /** 扣款；成功返回 true。enabled=false 恒 true（不扣）。 */
    boolean withdraw(PlayerRef player, double amount);

    /** 桩：经济关闭、永远免费成功。 */
    EconomyPort DISABLED = new EconomyPort() {
        @Override public boolean enabled() { return false; }
        @Override public boolean has(PlayerRef player, double amount) { return true; }
        @Override public boolean withdraw(PlayerRef player, double amount) { return true; }
    };
}
