package org.windy.playershelter.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 庇护所升级成功后触发（决策 #79 / #30 升级即扩界）。附属可据 newLevel 做奖励/广播。
 */
public final class ShelterUpgradeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final String worldName;
    private final int newLevel;
    private final int newBorderSize;

    public ShelterUpgradeEvent(UUID owner, String worldName, int newLevel, int newBorderSize) {
        this.owner = owner;
        this.worldName = worldName;
        this.newLevel = newLevel;
        this.newBorderSize = newBorderSize;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public int getNewBorderSize() {
        return newBorderSize;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
