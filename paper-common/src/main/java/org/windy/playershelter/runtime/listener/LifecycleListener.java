package org.windy.playershelter.runtime.listener;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;
import org.windy.playershelter.service.WorldLifecycleService;

import java.time.Instant;

/**
 * 生命周期挂钩（决策 #6/#7/#27/#28）：进入庇护所世界标记活跃、离开起空闲计时、登录重置不活跃计时+存续期提示。
 */
public final class LifecycleListener implements Listener {

    private final PsCore core;

    public LifecycleListener(PsCore core) {
        this.core = core;
    }

    /** 登录：先判不活跃存续期（决策 #28 任意登录重置），再刷新自己庇护所的 lastActive。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Shelter own = core.repo().find(PlayerRef.of(p.getUniqueId())).orElse(null);
        if (own != null) {
            WorldLifecycleService.InactiveVerdict verdict =
                    core.lifecycle().evaluateInactive(own, Instant.now());
            if (verdict == WorldLifecycleService.InactiveVerdict.IN_GRACE) {
                Messages.warnKey(p, "lifecycle.inactive-grace",
                        "你的庇护所已进入&c待删存续期&e，本次登录已为你重置计时。常来看看它就不会被清理。");
            }
            // 决策 #28：任意一次登录都刷新 lastActive，重置删除计时。
            core.lifecycle().markActive(own);
        }
        // 若登录点就在某庇护所世界（上次在此退出）→ 标记该世界活跃，避免被判空卸载。
        markIfShelterWorld(p.getWorld().getName());
        // 跨服交接：本服承载目标庇护所时，刚换服到站的玩家在此被送进对应世界（决策 #57/#59）。
        consumePending(p);
    }

    /** 消费跨服待办：到站后进目标庇护所（决策 #57/#59）。延迟一拍，等玩家完全加入再传送。 */
    private void consumePending(Player p) {
        if (!core.crossServer()) {
            return;
        }
        PlayerRef pr = PlayerRef.of(p.getUniqueId());
        core.pending().findTargetOwner(pr).ifPresent(targetOwner -> {
            core.pending().delete(pr);
            Shelter target = core.repo().find(targetOwner).orElse(null);
            if (target == null || !core.isLocal(target.serverName())) {
                return; // 目标庇护所已不在本服（迁移/删除）→ 放弃
            }
            // 延迟 20 tick 等客户端就绪再传送，避免刚换服就 teleport 被吞。
            core.plugin().getServer().getScheduler().runTaskLater(core.plugin(),
                    () -> new org.windy.playershelter.runtime.ShelterRouter(core)
                            .sendLocal(p, target, Messages.get("lifecycle.pending-load-failed", "目标庇护所加载失败。")), 20L);
        });
    }

    /** 切换世界：进入庇护所世界标记活跃 + 迎送词，离开的世界起空闲计时。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        String entered = p.getWorld().getName();
        markIfShelterWorld(entered);
        greet(p, entered);
        // 离开的世界可能变空。
        core.lifecycle().touchMaybeEmpty(e.getFrom().getName());
    }

    /** 进入庇护所世界的迎宾词（决策 #75 审美；自己回家 vs 串门两套话术 + 公告）。 */
    private void greet(Player p, String worldName) {
        if (!worldName.startsWith("shelter_")) {
            return;
        }
        Shelter s = core.repo().findByWorldName(worldName).orElse(null);
        if (s == null) {
            return;
        }
        if (s.owner().uuid().equals(p.getUniqueId())) {
            Messages.infoKey(p, "greet.own", "欢迎回到你的庇护所。");
        } else {
            String ownerName = core.plugin().getServer().getOfflinePlayer(s.owner().uuid()).getName();
            Messages.infoKey(p, "greet.visit", "欢迎来到 {owner} 的庇护所。",
                    "owner", ownerName == null ? Messages.get("word.some-player", "某玩家") : ownerName);
        }
        if (!s.bulletin().isEmpty()) {
            Messages.plainKey(p, "greet.bulletin", "&7公告：&f{bulletin}", "bulletin", s.bulletin());
        }
    }

    /** 退出：所在世界可能变空。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // quit 后该玩家仍短暂在世界里，延迟一 tick 再判空更准。
        String worldName = e.getPlayer().getWorld().getName();
        core.plugin().getServer().getScheduler().runTask(core.plugin(),
                () -> core.lifecycle().touchMaybeEmpty(worldName));
    }

    private void markIfShelterWorld(String worldName) {
        if (!worldName.startsWith("shelter_")) {
            return;
        }
        core.repo().findByWorldName(worldName).ifPresent(core.lifecycle()::markActive);
    }
}
