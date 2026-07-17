package org.windy.playershelter.adapter.proxy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 跨服传送（决策 #57/#59）：经 BungeeCord/Velocity 标准插件消息通道 {@code BungeeCord} 把玩家 Connect 到
 * 目标后端服。代理（Bungee/Velocity）默认支持此通道，无需伴生插件——决策 #58 的"代理端壳"暂留空即可。
 *
 * <p>调用方应先在共享库写好跨服待办（{@code ps_pending}），玩家到站后由目标后端 join 时消费，进对应庇护所。
 */
public final class ServerTransfer {

    /** 标准代理插件消息通道（Bungee/Velocity 均识别）。 */
    public static final String CHANNEL = "BungeeCord";

    private final Plugin plugin;
    private boolean registered;

    public ServerTransfer(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 注册 outgoing 通道（onEnable 调）。重复注册无害但加守卫。 */
    public void register() {
        if (registered) {
            return;
        }
        Messenger m = plugin.getServer().getMessenger();
        if (!m.isOutgoingChannelRegistered(plugin, CHANNEL)) {
            m.registerOutgoingPluginChannel(plugin, CHANNEL);
        }
        registered = true;
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        registered = false;
    }

    /**
     * 把玩家送到目标后端服（决策 #59 世界绑服）。需玩家在线、代理在场（单机直连无代理时本调用无效果）。
     *
     * @param player     要传送的在线玩家（其连接用于发插件消息）
     * @param serverName 目标后端服名（与代理配置里的 server 名一致）
     */
    public void connect(Player player, String serverName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeUTF("Connect");
            data.writeUTF(serverName);
        } catch (IOException e) {
            plugin.getLogger().warning("[PlayerShelter] 构造跨服传送消息失败：" + e.getMessage());
            return;
        }
        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }
}
