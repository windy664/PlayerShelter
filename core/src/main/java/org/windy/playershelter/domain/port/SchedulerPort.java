package org.windy.playershelter.domain.port;

/**
 * 调度端口（决策 #55 串行队列 + 干净 tick / #7 空闲计时）。core 不认识 Bukkit Scheduler，
 * 生命周期与建世界队列经此抽象调度，避免命令上下文里嵌套建世界导致死锁（GuildShelter 踩过的坑）。
 */
public interface SchedulerPort {

    /** 立刻在主线程跑（已在主线程则可直接执行）。 */
    void runMain(Runnable task);

    /** 延迟 ticks 个游戏刻后在主线程跑。 */
    void runMainLater(Runnable task, long ticks);

    /** 异步线程跑（建世界等耗时操作）。 */
    void runAsync(Runnable task);

    /**
     * 注册一个主线程周期任务（生命周期巡检：空闲卸载/LRU/不活跃删除扫描）。
     *
     * @param periodTicks 周期（游戏刻）
     * @return 取消句柄
     */
    Cancellable repeatMain(Runnable task, long periodTicks);

    /** 可取消的周期任务句柄。 */
    interface Cancellable {
        void cancel();
    }
}
