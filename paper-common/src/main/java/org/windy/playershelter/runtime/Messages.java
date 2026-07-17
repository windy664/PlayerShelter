package org.windy.playershelter.runtime;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX_COLOR = Pattern.compile("&?#([A-Fa-f0-9]{6})");
    private static FileConfiguration config;
    private static String prefix = ChatColor.AQUA + "[" + ChatColor.WHITE + "庇护所"
            + ChatColor.AQUA + "] " + ChatColor.RESET;

    private Messages() {
    }

    public static void reload(JavaPlugin plugin) {
        String language = plugin.getConfig().getString("language", "zh_CN");
        File file = new File(plugin.getDataFolder(), "lang/" + safeLanguage(language) + ".yml");
        config = YamlConfiguration.loadConfiguration(file);
        prefix = color(config.getString("prefix", "&b[&f庇护所&b] &r"));
    }

    private static String safeLanguage(String language) {
        String lang = language == null || language.isBlank() ? "zh_CN" : language;
        return lang.replace('\\', '/').replace("/", "").replace("..", "");
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', expandHexColors(s == null ? "" : s));
    }

    private static String expandHexColors(String input) {
        Matcher matcher = HEX_COLOR.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (int i = 0; i < hex.length(); i++) {
                replacement.append('§').append(hex.charAt(i));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String get(String key, String fallback) {
        if (config == null) {
            return fallback;
        }
        return color(config.getString("messages." + key, fallback));
    }

    public static String getKey(String key) {
        return get(key, "");
    }

    public static String format(String key, String fallback, Map<String, String> values) {
        String out = get(key, fallback);
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    public static String format(String key, String fallback, Object... values) {
        return format(key, fallback, map(values));
    }

    public static String formatKey(String key, Object... values) {
        return format(key, "", values);
    }

    public static void infoKeyOnly(CommandSender to, String key, Object... values) {
        info(to, formatKey(key, values));
    }

    public static void okKeyOnly(CommandSender to, String key, Object... values) {
        ok(to, formatKey(key, values));
    }

    public static void warnKeyOnly(CommandSender to, String key, Object... values) {
        warn(to, formatKey(key, values));
    }

    public static void errorKeyOnly(CommandSender to, String key, Object... values) {
        error(to, formatKey(key, values));
    }

    public static void plainKeyOnly(CommandSender to, String key, Object... values) {
        plain(to, formatKey(key, values));
    }

    public static void infoKey(CommandSender to, String key, String fallback, Object... values) {
        info(to, format(key, fallback, values));
    }

    public static void okKey(CommandSender to, String key, String fallback, Object... values) {
        ok(to, format(key, fallback, values));
    }

    public static void warnKey(CommandSender to, String key, String fallback, Object... values) {
        warn(to, format(key, fallback, values));
    }

    public static void errorKey(CommandSender to, String key, String fallback, Object... values) {
        error(to, format(key, fallback, values));
    }

    public static void plainKey(CommandSender to, String key, String fallback, Object... values) {
        plain(to, format(key, fallback, values));
    }

    public static void send(CommandSender to, String msg) {
        to.sendMessage(component(prefix + localized(msg)));
    }

    public static void info(CommandSender to, String msg) {
        send(to, tone("info", ChatColor.GRAY) + msg);
    }

    public static void ok(CommandSender to, String msg) {
        send(to, tone("ok", ChatColor.GREEN) + msg);
    }

    public static void warn(CommandSender to, String msg) {
        send(to, tone("warn", ChatColor.YELLOW) + msg);
    }

    public static void error(CommandSender to, String msg) {
        send(to, tone("error", ChatColor.RED) + msg);
    }

    public static void plain(CommandSender to, String msg) {
        to.sendMessage(component(localized(msg)));
    }

    public static Component component(String msg) {
        return LEGACY.deserialize(color(msg));
    }

    private static String localized(String msg) {
        if (config == null || msg == null) {
            return msg;
        }
        String override = config.getString("overrides." + Integer.toHexString(msg.hashCode()));
        return override == null ? msg : color(override);
    }

    private static ChatColor tone(String key, ChatColor fallback) {
        if (config == null) {
            return fallback;
        }
        String name = config.getString("colors." + key);
        if (name == null) {
            return fallback;
        }
        try {
            return ChatColor.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static Map<String, String> map(Object... values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (int i = 0; i + 1 < values.length; i += 2) {
            out.put(String.valueOf(values[i]), String.valueOf(values[i + 1]));
        }
        return out;
    }
}
