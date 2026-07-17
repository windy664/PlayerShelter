package org.windy.playershelter.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 庇护所被删除时触发（决策 #79）。在世界文件删除前后由实现决定；附属可据此清理关联数据。
 */
public final class ShelterDeleteEvent extends Event {

    /** 删除原因。 */
    public enum Reason {
        /** 管理员命令删除（决策 #61）。 */
        ADMIN,
        /** 长期不活跃自动清理（决策 #6）。 */
        INACTIVE
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final String worldName;
    private final Reason reason;

    public ShelterDeleteEvent(UUID owner, String worldName, Reason reason) {
        this.owner = owner;
        this.worldName = worldName;
        this.reason = reason;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorldName() {
        return worldName;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
