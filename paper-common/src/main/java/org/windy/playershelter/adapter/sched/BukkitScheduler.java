package org.windy.playershelter.adapter.sched;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.windy.playershelter.domain.port.SchedulerPort;

/**
 * {@link SchedulerPort} 的 Bukkit 实现（决策 #55）。把 core 的调度需求落到 Bukkit 调度器，
 * 让建世界/生命周期巡检调度到干净 tick，避免命令上下文里嵌套建世界死锁（GuildShelter 踩过的坑）。
 */
public final class BukkitScheduler implements SchedulerPort {

    private final Plugin plugin;

    public BukkitScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runMainLater(Runnable task, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, ticks));
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public Cancellable repeatMain(Runnable task, long periodTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, task, periodTicks, periodTicks);
        return t::cancel;
    }
}
