package org.windy.playershelter.runtime.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 把既有的 {@link PsCommand}（Bukkit {@code TabExecutor}）桥接到 Paper 原生 Brigadier 命令树。
 *
 * <p>paper-plugin.yml <b>不支持 {@code commands:} 块</b>，命令必须经 {@code LifecycleEvents.COMMANDS} 注册。
 * 这里用一个<b>贪婪字符串参数</b>吃掉 {@code /ps} 后面的全部内容，切成 {@code String[]} 原样交回
 * {@link PsCommand} 分发——子命令与权限门控逻辑<b>零改动</b>，只换了「命令怎么被服务器认领」这一层。
 *
 * <p>{@code PsCommand.onCommand/onTabComplete} 都不使用 {@code command}/{@code label} 参数（只认 sender+args），
 * 故桥接处传 {@code null} 安全。
 */
public final class PsBrigadier {

    private PsBrigadier() {
    }

    /** 构建 {@code /ps} 命令节点（执行与补全全部委派给 {@link PsCommand}）。 */
    public static LiteralCommandNode<CommandSourceStack> node(PsCommand ps) {
        return Commands.literal("ps")
                .executes(ctx -> exec(ps, ctx.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, sb) -> suggest(ps, ctx.getSource(), sb))
                        .executes(ctx -> exec(ps, ctx.getSource(),
                                StringArgumentType.getString(ctx, "args"))))
                .build();
    }

    private static int exec(PsCommand ps, CommandSourceStack src, String raw) {
        ps.onCommand(src.getSender(), null, "ps", split(raw));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /**
     * 补全：Brigadier 的贪婪参数把光标前整串当成一个 token，需还原成 {@link PsCommand} 期望的
     * 「已完成词 + 当前正在输入词」形态，再把结果贴回<b>当前词</b>的起点偏移（否则会重复整串）。
     */
    private static CompletableFuture<Suggestions> suggest(PsCommand ps, CommandSourceStack src,
                                                          SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        int lastSpace = input.lastIndexOf(' ');
        String last = lastSpace < 0 ? input : input.substring(lastSpace + 1);

        // 组装 args：末尾是空串或整串以空格结尾 → 追加一个空「正在输入词」，让 PsCommand 按下一位补全。
        String[] base = split(input);
        String[] args;
        if (input.isEmpty() || input.endsWith(" ")) {
            args = new String[base.length + 1];
            System.arraycopy(base, 0, args, 0, base.length);
            args[base.length] = "";
        } else {
            args = base;
        }

        List<String> out = ps.onTabComplete(src.getSender(), null, "ps", args);
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
        if (out != null) {
            String lower = last.toLowerCase(Locale.ROOT);
            for (String s : out) {
                if (s != null && s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    offset.suggest(s);
                }
            }
        }
        return offset.buildFuture();
    }

    private static String[] split(String raw) {
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }
}
