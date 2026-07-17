package org.windy.playershelter.runtime.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat input for visitor messages. */
public final class MessageInputGui implements Listener {

    private static final Map<UUID, Prompt> PENDING = new ConcurrentHashMap<>();

    private final PsCore core;

    public MessageInputGui(PsCore core) {
        this.core = core;
    }

    public static void open(Player player, UUID targetOwner, String targetName) {
        String name = targetName == null || targetName.isBlank()
                ? Messages.get("word.some-player", "某位玩家")
                : targetName;
        PENDING.put(player.getUniqueId(), new Prompt(targetOwner, name));
        Messages.infoKey(player, "msg.input-hint", "在聊天里直接输入留言，输入 &f取消&e 放弃发送。");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = PENDING.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(core.plugin(), () -> submit(player, prompt, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PENDING.remove(event.getPlayer().getUniqueId());
    }

    private void submit(Player player, Prompt prompt, String text) {
        if (text.isBlank() || isCancel(text)) {
            Messages.infoKey(player, "msg.input-cancel", "已取消留言。");
            return;
        }
        if (prompt.targetOwner().equals(player.getUniqueId())) {
            Messages.warnKey(player, "msg.self", "不用给自己留言，/ps board 直接看。");
            return;
        }
        Shelter shelter = core.repo().find(PlayerRef.of(prompt.targetOwner())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "target.no-shelter", "{player} 还没有庇护所。", "player", prompt.targetName());
            return;
        }
        if (!shelter.visibility().isPublic()
                && !shelter.resolveRole(PlayerRef.of(player.getUniqueId())).canEnter()) {
            Messages.errorKey(player, "msg.not-open", "对方庇护所不公开，无法留言。");
            return;
        }
        String message = text.length() > 256 ? text.substring(0, 256) : text;
        core.board().post(shelter.owner(), PlayerRef.of(player.getUniqueId()), player.getName(), message);
        Messages.okKey(player, "msg.success", "已给 {player} 的留言板留言。", "player", prompt.targetName());
    }

    private static boolean isCancel(String text) {
        String low = text.toLowerCase();
        return "cancel".equals(low) || "取消".equals(low);
    }

    private record Prompt(UUID targetOwner, String targetName) {}
}