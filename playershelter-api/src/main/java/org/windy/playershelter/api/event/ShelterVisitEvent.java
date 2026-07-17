package org.windy.playershelter.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 玩家串门进入他人庇护所前触发（决策 #37 / #79）。<b>可取消</b>——附属可据自定义规则拦截访问
 * （例如黑名单插件、活动限制）。取消则传送中止。
 */
public final class ShelterVisitEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID visitor;
    private final UUID owner;
    private final String worldName;
    private boolean cancelled;

    public ShelterVisitEvent(UUID visitor, UUID owner, String worldName) {
        this.visitor = visitor;
        this.owner = owner;
        this.worldName = worldName;
    }

    public UUID getVisitor() {
        return visitor;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorldName() {
        return worldName;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
