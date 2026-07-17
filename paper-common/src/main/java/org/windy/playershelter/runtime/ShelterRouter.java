package org.windy.playershelter.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;

/**
 * 把玩家送进某个庇护所——单服直接传送，跨服先写交接待办再让代理换服（决策 #57/#59）。
 *
 * <p>本服承载（{@link PsCore#isLocal})：异步确保世界加载（Iris 走异步管线）→ 安全落点传送。
 * 他服承载：写 {@code ps_pending}（到站消费）→ {@link org.windy.playershelter.adapter.proxy.ServerTransfer}
 * 经 BungeeCord 通道把人 Connect 过去。目标后端在 join 时由 {@link org.windy.playershelter.runtime.listener.LifecycleListener}
 * 读出待办、把人传进对应庇护所。
 */
public final class ShelterRouter {

    private final PsCore core;

    public ShelterRouter(PsCore core) {
        this.core = core;
    }

    /**
     * 送玩家进目标庇护所。
     *
     * @param player  在线玩家
     * @param target  目标庇护所
     * @param failMsg 失败时给玩家的提示（世界加载失败/跨服不可达）
     */
    public void send(Player player, Shelter target, String failMsg) {
        if (core.isLocal(target.serverName())) {
            sendLocal(player, target, failMsg);
            return;
        }
        // 跨服：写待办 + 让代理换服。到站后由目标后端消费待办进世界。
        core.pending().save(PlayerRef.of(player.getUniqueId()), target.owner());
        Messages.infoKey(player, "router.cross-server", "正在把你转送到目标服务器…");
        core.transfer().connect(player, target.serverName());
    }

    /** 本服承载：确保世界加载后安全落点传送。 */
    public void sendLocal(Player player, Shelter target, String failMsg) {
        core.world().ensureWorldAsync(target, player.getUniqueId(), ready -> {
            World w = Bukkit.getWorld(ready.worldName());
            if (w == null) {
                Messages.error(player, failMsg);
                return;
            }
            core.world().applyBorder(ready);
            Location spawn = w.getSpawnLocation();
            core.safeLanding().prepareAsync(core.plugin(), spawn).whenComplete((landing, error) -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null || landing == null) {
                    Messages.error(player, failMsg);
                    return;
                }
                player.teleportAsync(landing).thenAccept(ok -> {
                    if (!ok && player.isOnline()) {
                        Messages.error(player, failMsg);
                    }
                });
            });
        }, () -> Messages.error(player, failMsg));
    }
}
