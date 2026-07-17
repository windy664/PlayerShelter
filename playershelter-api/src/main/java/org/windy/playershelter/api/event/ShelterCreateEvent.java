package org.windy.playershelter.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 庇护所创建成功后触发（决策 #79 扩展 API）。第三方附属可监听做迎新/发奖等（非取消型，世界已建好）。
 */
public final class ShelterCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final String worldName;
    private final String generationType;

    public ShelterCreateEvent(UUID owner, String worldName, String generationType) {
        this.owner = owner;
        this.worldName = worldName;
        this.generationType = generationType;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorldName() {
        return worldName;
    }

    /** 生成类型：NATURAL / FLAT / VOID。 */
    public String getGenerationType() {
        return generationType;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
