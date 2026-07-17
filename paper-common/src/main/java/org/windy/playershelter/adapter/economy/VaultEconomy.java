package org.windy.playershelter.adapter.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.EconomyPort;

/**
 * {@link EconomyPort} 的 Vault 实现（决策 #17 软依赖可选）。Vault 缺席/未启用时由装配层用
 * {@link EconomyPort#DISABLED} 顶替——本类只在 Vault 在场时构造。
 */
public final class VaultEconomy implements EconomyPort {

    private final Economy economy;

    private VaultEconomy(Economy economy) {
        this.economy = economy;
    }

    /**
     * 尝试接管 Vault 经济；不可用（无 Vault 插件/无经济提供者）返回 {@code null}，调用方退回 DISABLED。
     */
    public static EconomyPort tryHook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }
        return new VaultEconomy(rsp.getProvider());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean has(PlayerRef player, double amount) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(player.uuid());
        return economy.has(op, amount);
    }

    @Override
    public boolean withdraw(PlayerRef player, double amount) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(player.uuid());
        return economy.withdrawPlayer(op, amount).transactionSuccess();
    }
}
