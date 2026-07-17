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

public final class PsgsBrigadier {

    private PsgsBrigadier() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(PsgsCommand command) {
        return Commands.literal("psgs")
                .executes(ctx -> exec(command, ctx.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, sb) -> suggest(command, ctx.getSource(), sb))
                        .executes(ctx -> exec(command, ctx.getSource(),
                                StringArgumentType.getString(ctx, "args"))))
                .build();
    }

    private static int exec(PsgsCommand command, CommandSourceStack source, String raw) {
        command.onCommand(source.getSender(), null, "psgs", split(raw));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(PsgsCommand command, CommandSourceStack source,
                                                          SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        int lastSpace = input.lastIndexOf(' ');
        String last = lastSpace < 0 ? input : input.substring(lastSpace + 1);
        String[] base = split(input);
        String[] args;
        if (input.isEmpty() || input.endsWith(" ")) {
            args = new String[base.length + 1];
            System.arraycopy(base, 0, args, 0, base.length);
            args[base.length] = "";
        } else {
            args = base;
        }

        List<String> out = command.onTabComplete(source.getSender(), null, "psgs", args);
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
        if (out != null) {
            String lower = last.toLowerCase(Locale.ROOT);
            for (String suggestion : out) {
                if (suggestion != null && suggestion.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    offset.suggest(suggestion);
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
