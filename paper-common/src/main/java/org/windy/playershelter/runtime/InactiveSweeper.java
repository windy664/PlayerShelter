package org.windy.playershelter.runtime;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.windy.playershelter.api.event.ShelterDeleteEvent;
import org.windy.playershelter.domain.model.Shelter;

import java.util.List;

/**
 * Periodic inactive shelter cleanup.
 *
 * <p>The database scan runs asynchronously. World deletion is scheduled back to
 * the main thread because it touches Bukkit world state.
 */
public final class InactiveSweeper {

    private final PsCore core;
    private final long periodTicks;
    private final int maxPerRun;
    private BukkitTask task;
    private volatile boolean running;

    public InactiveSweeper(PsCore core, int sweepMinutes, int maxPerRun) {
        this.core = core;
        this.periodTicks = Math.max(1, sweepMinutes) * 60L * 20L;
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    public void start() {
        running = true;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(core.plugin(), this::sweep, 6000L, periodTicks);
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        if (!running || !core.plugin().isEnabled()) {
            return;
        }

        List<Shelter> deletable;
        try {
            deletable = core.lifecycle().findDeletable();
        } catch (Exception e) {
            core.plugin().getLogger().warning("[PlayerShelter] Inactive sweep failed: " + e.getMessage());
            return;
        }
        if (deletable.isEmpty()) {
            return;
        }

        int limit = Math.min(maxPerRun, deletable.size());
        for (int i = 0; i < limit; i++) {
            Shelter s = deletable.get(i);
            Bukkit.getScheduler().runTask(core.plugin(), () -> {
                if (!running || !core.plugin().isEnabled()) {
                    return;
                }
                Bukkit.getPluginManager().callEvent(new ShelterDeleteEvent(
                        s.owner().uuid(), s.worldName(), ShelterDeleteEvent.Reason.INACTIVE));
                if (core.lifecycle().deleteInactive(s)) {
                    core.tags().clear(s.owner());
                    core.board().clear(s.owner());
                    core.plugin().getLogger().info("[PlayerShelter] Deleted inactive shelter "
                            + s.worldName() + " (owner=" + s.owner().uuid() + ")");
                }
            });
        }
    }
}
