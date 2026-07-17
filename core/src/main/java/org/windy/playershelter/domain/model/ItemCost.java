package org.windy.playershelter.domain.model;

/**
 * A platform-neutral item cost entry.
 *
 * @param itemId Minecraft item id, for example {@code minecraft:oak_log}
 * @param amount required amount
 */
public record ItemCost(String itemId, int amount) {

    public ItemCost {
        itemId = itemId == null ? "" : itemId.trim().toLowerCase();
        amount = Math.max(0, amount);
    }

    public boolean valid() {
        return !itemId.isBlank() && amount > 0;
    }
}
