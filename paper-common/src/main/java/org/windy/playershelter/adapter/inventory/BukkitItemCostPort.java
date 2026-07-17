package org.windy.playershelter.adapter.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.port.ItemCostPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BukkitItemCostPort implements ItemCostPort {

    private final Logger log;

    public BukkitItemCostPort(Logger log) {
        this.log = log;
    }

    @Override
    public boolean has(PlayerRef player, List<ItemCost> costs) {
        Player p = Bukkit.getPlayer(player.uuid());
        if (p == null) {
            return false;
        }
        Map<Material, Integer> amounts = materialAmounts(costs);
        if (amounts == null) {
            return false;
        }
        for (Map.Entry<Material, Integer> e : amounts.entrySet()) {
            if (count(p.getInventory(), e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean withdraw(PlayerRef player, List<ItemCost> costs) {
        Player p = Bukkit.getPlayer(player.uuid());
        if (p == null) {
            return false;
        }
        Map<Material, Integer> amounts = materialAmounts(costs);
        if (amounts == null) {
            return false;
        }
        for (Map.Entry<Material, Integer> e : amounts.entrySet()) {
            if (count(p.getInventory(), e.getKey()) < e.getValue()) {
                return false;
            }
        }
        for (Map.Entry<Material, Integer> e : amounts.entrySet()) {
            remove(p.getInventory(), e.getKey(), e.getValue());
        }
        return true;
    }

    private Map<Material, Integer> materialAmounts(List<ItemCost> costs) {
        Map<Material, Integer> out = new LinkedHashMap<>();
        for (ItemCost cost : costs) {
            if (cost == null || !cost.valid()) {
                continue;
            }
            Material material = material(cost.itemId());
            if (material == null || material == Material.AIR) {
                if (log != null) {
                    log.warning("[PlayerShelter] Unknown upgrade item material: " + cost.itemId());
                }
                return null;
            }
            out.merge(material, cost.amount(), Integer::sum);
        }
        return out;
    }

    private static Material material(String itemId) {
        Material material = Material.matchMaterial(itemId);
        if (material != null) {
            return material;
        }
        String normalized = itemId;
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        normalized = normalized.toUpperCase(Locale.ROOT).replace('-', '_');
        return Material.matchMaterial(normalized);
    }

    private static int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static void remove(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        inventory.setStorageContents(contents);
    }
}
