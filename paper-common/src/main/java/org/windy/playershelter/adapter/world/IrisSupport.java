package org.windy.playershelter.adapter.world;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.util.plugin.VolmitSender;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Iris 接入（决策 #1 自然地形优先 Iris / #31 惰性生成）。<b>直接引用</b> {@code com.volmit.iris.*}
 * （compileOnly，运行期由 Iris 插件提供），不走反射 [[avoid-reflection-preference]]。
 *
 * <p><b>惰性隔离</b>：本类仅在 Iris 在场时由 {@link PaperWorldControl} 实例化/调用——JVM 惰性解析：
 * Iris 不在场则本类永不加载，其 {@code com.volmit.iris.*} 引用永不解析，绝无 {@code NoClassDefFoundError}。
 * 调用方<b>务必</b>先用 {@code Bukkit.getPluginManager().getPlugin("Iris")} 判存在再触达本类。
 *
 * <p>与 GuildShelter 不同：PlayerShelter <b>不整地</b>（决策 #47），故无需 Iris 预生成器，只需异步建世界。
 */
final class IrisSupport {

    private IrisSupport() {
    }

    /**
     * 经 Iris 异步管线创建世界（与 {@code /iris create} 同款，不走 {@code Bukkit.createWorld} 的主线程冻服）。
     * <b>须在异步线程调用</b>（Iris 内部自行 submit 回主线程做 addLevel）。
     *
     * @param worldName  世界名 {@code shelter_<uuid>}
     * @param dimension  Iris 维度包名（config，默认 {@code overworld}）
     * @param seed       世界种子
     * @param progressTo 进度受众（在线玩家 → 其客户端显示生成进度条）；{@code null} 则进度仅入控制台
     * @return 创建好的 Bukkit {@link World}
     * @throws RuntimeException Iris 建世界失败时
     */
    static World createWorld(String worldName, String dimension, long seed, CommandSender progressTo) {
        try {
            var creator = IrisToolbelt.createWorld()
                    .name(worldName)
                    .dimension(dimension)
                    .seed(seed);
            if (progressTo != null) {
                creator = creator.sender(new VolmitSender(progressTo));
            }
            return creator.create();
        } catch (Throwable t) {
            throw new RuntimeException("Iris 建世界失败(world=" + worldName + ", dimension=" + dimension + "): " + t, t);
        }
    }

    /** 该世界是否由 Iris 生成（→ 惰性世界，扩界不预生成，决策 #31）。 */
    static boolean isIrisWorld(World world) {
        return IrisToolbelt.isIrisWorld(world);
    }
}
