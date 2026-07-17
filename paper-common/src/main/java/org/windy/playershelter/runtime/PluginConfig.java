package org.windy.playershelter.runtime;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.ShelterLayout;
import org.windy.playershelter.service.EntityLimits;
import org.windy.playershelter.service.ShelterConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code config.yml} 读出 {@link ShelterConfig}（决策：可调数值一律 config 键 + 默认值，不挑魔数）。
 * 也解析存储后端、落点材料等平台侧设置。缺键时用 {@link ShelterConfig#defaults()} 同源默认。
 */
public final class PluginConfig {

    private final FileConfiguration cfg;
    private final FileConfiguration levels;
    private final FileConfiguration storage;
    private final FileConfiguration server;
    private final ShelterConfig shelterConfig;

    public PluginConfig(FileConfiguration cfg) {
        this(cfg, null, null, null);
    }

    public PluginConfig(FileConfiguration cfg, FileConfiguration levels) {
        this(cfg, levels, null, null);
    }

    public PluginConfig(FileConfiguration cfg, FileConfiguration levels, FileConfiguration storage) {
        this(cfg, levels, storage, null);
    }

    public PluginConfig(FileConfiguration cfg, FileConfiguration levels, FileConfiguration storage,
                        FileConfiguration server) {
        this.cfg = cfg;
        this.levels = levels;
        this.storage = storage;
        this.server = server;
        ShelterConfig d = ShelterConfig.defaults();
        int maxLevel = maxLevel(d.layout().maxLevel());
        int initialChunks = initialChunks(d.layout().initialChunks());
        int maxChunks = maxChunks(d.layout().maxChunks(), initialChunks);
        ShelterLayout layout = new ShelterLayout(initialChunks, maxChunks, maxLevel,
                sideChunkLevelMap(initialChunks, maxChunks, maxLevel));
        this.shelterConfig = new ShelterConfig(
                layout,
                performanceOptimizeInactiveMinutes(d.idleUnloadMinutes()),
                performanceOptimizeMaxLoadedWorlds(d.maxLoadedWorlds()),
                cfg.getInt("lifecycle.inactive-delete-days", d.inactiveDeleteDays()),
                cfg.getInt("lifecycle.inactive-grace-days", d.inactiveGraceDays()),
                cfg.getInt("reset.cooldown-hours", d.resetCooldownHours()),
                cfg.getInt("reset.max-per-day", d.resetMaxPerDay()),
                d.adminCap(),
                d.adminPerLevel(),
                d.trustCapBase(),
                d.trustPerLevel(),
                cfg.getInt("directory.public-min-level", d.publicMinLevel()),
                d.upgradeBaseCost(),
                d.upgradeCostFactor(),
                serverName(d.defaultServerName()),
                upgradeMoneyLevelMap(),
                upgradeItemLevelMap(),
                capLevelMap("admin"),
                capLevelMap("trust"));
    }

    public ShelterConfig shelterConfig() {
        return shelterConfig;
    }

    /** 存储后端：sqlite（默认）/ mysql。 */
    public String storageType() {
        if (crossServerConfigured()) {
            return "mysql";
        }
        return storageString("type", "sqlite").toLowerCase();
    }

    public String mysqlHost() {
        return storageString("mysql.host", "localhost");
    }

    public int mysqlPort() {
        return storageInt("mysql.port", 3306);
    }

    public String mysqlDatabase() {
        return storageString("mysql.database", "playershelter");
    }

    public String mysqlUser() {
        return storageString("mysql.user", "root");
    }

    public String mysqlPassword() {
        return storageString("mysql.password", "");
    }

    public boolean crossServerEnabled() {
        return crossServerConfigured() && !shelterConfig.defaultServerName().isBlank();
    }

    private boolean crossServerConfigured() {
        return cfg.getBoolean("cross_server", false);
    }

    /** 安全落点站脚台材料（决策 #48，config 可改）。无法解析回退玻璃。 */
    public Material platformMaterial() {
        String name = cfg.getString("landing.platform-material", "GLASS");
        Material m = Material.matchMaterial(name);
        return m == null ? Material.GLASS : m;
    }

    /** 经济默认启用；仅兼容旧配置里的 economy.enabled=false。 */
    public boolean economyEnabled() {
        return cfg.getBoolean("economy.enabled", true);
    }

    /** NATURAL 世界是否优先用 Iris 生成（决策 #1，需服务器装 Iris；缺席自动回退原版 normal）。 */
    public boolean irisEnabled() {
        return cfg.getBoolean("generation.iris.enabled", true);
    }

    /** Iris 维度包名（默认 overworld）。 */
    public String irisDimension() {
        return cfg.getString("generation.iris.dimension", "overworld");
    }

    public Map<String, String> gamerules() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("world.gamerules");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                out.put(key.toLowerCase(), String.valueOf(sec.get(key)));
            }
        }
        if (out.isEmpty()) {
            out.put("keep_inventory", "true");
        }
        return out;
    }

    /** 实体/机器限额（决策 P1）。等级线性 + 机器按方块 id 配额。 */
    public EntityLimits entityLimits() {
        int maxLevel = shelterConfig.layout().maxLevel();
        Map<String, Integer> machines = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("limits.machines");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                machines.put(key.toLowerCase(), sec.getInt(key));
            }
        }
        return new EntityLimits(
                cfg.getInt("limits.mobs.base", 60), cfg.getInt("limits.mobs.per-level", 4),
                cfg.getInt("limits.tiles.base", 200), cfg.getInt("limits.tiles.per-level", 20),
                cfg.getInt("limits.drops.base", 100), cfg.getInt("limits.drops.per-level", 8),
                cfg.getInt("limits.vehicles.base", 20), cfg.getInt("limits.vehicles.per-level", 2),
                maxLevel,
                machines, cfg.getInt("limits.machines-per-level", 1),
                explicitLimitCaps(),
                explicitMachineCaps());
    }

    /** 限额总开关（关则不拦，决策 P1 可关）。 */
    public boolean limitsEnabled() {
        return cfg.getBoolean("limits.enabled", true);
    }

    public int dropCleanupIntervalSeconds() {
        return cfg.getInt("performance.drop-cleanup.check-interval-seconds", 60);
    }

    public int shelterWorldViewDistance() {
        return cfg.getInt("performance.paper-world.view-distance", 6);
    }

    public int shelterWorldSimulationDistance() {
        return cfg.getInt("performance.paper-world.simulation-distance", 4);
    }

    public long cachePositiveTtlMillis() {
        return Math.max(1L, cfg.getLong("performance.cache.shelter-positive-ttl-seconds", 15L)) * 1000L;
    }

    public long cacheNegativeTtlMillis() {
        return Math.max(1L, cfg.getLong("performance.cache.shelter-negative-ttl-seconds", 5L)) * 1000L;
    }

    private int maxLevel(int def) {
        int modernMax = highestLevelKey(modernLevelEntries());
        if (modernMax > 0) {
            return modernMax;
        }
        return def;
    }

    private int initialChunks(int def) {
        ConfigurationSection modern = modernLevelEntries();
        if (modern != null) {
            Integer levelOne = sideChunks(modern, "1");
            return levelOne == null ? def : Math.max(1, levelOne);
        }
        return def;
    }

    private int maxChunks(int def, int initialChunks) {
        ConfigurationSection modern = modernLevelEntries();
        if (modern != null) {
            int max = -1;
            for (String key : modern.getKeys(false)) {
                Integer side = sideChunks(modern, key);
                if (side != null) {
                    max = Math.max(max, side);
                }
            }
            return Math.max(initialChunks, max < 1 ? def : max);
        }
        return def;
    }

    private Integer sideChunks(ConfigurationSection levels, String key) {
        ConfigurationSection lv = levels == null ? null : levels.getConfigurationSection(key);
        if (lv == null || !lv.contains("side-chunks")) {
            return null;
        }
        return lv.getInt("side-chunks");
    }

    private int highestLevelKey(ConfigurationSection sec) {
        int max = -1;
        if (sec == null) {
            return max;
        }
        for (String key : sec.getKeys(false)) {
            try {
                max = Math.max(max, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // ignore malformed level keys
            }
        }
        return max;
    }

    private ConfigurationSection levelEntries() {
        return modernLevelEntries();
    }

    private ConfigurationSection modernLevelEntries() {
        if (levels == null) {
            return null;
        }
        return levels.getConfigurationSection("levels");
    }

    private String storageString(String storagePath, String def) {
        if (storage != null && storage.contains(storagePath)) {
            return storage.getString(storagePath, def);
        }
        return def;
    }

    private int storageInt(String storagePath, int def) {
        if (storage != null && storage.contains(storagePath)) {
            return storage.getInt(storagePath, def);
        }
        return def;
    }

    private String serverName(String def) {
        if (server != null && server.contains("server-name")) {
            return server.getString("server-name", def);
        }
        return def;
    }

    private int performanceOptimizeInactiveMinutes(int def) {
        return cfg.getInt("performance.optimize.inactive-minutes", def);
    }

    private int performanceOptimizeMaxLoadedWorlds(int def) {
        return cfg.getInt("performance.optimize.max-loaded-worlds", def);
    }

    private Map<Integer, Integer> sideChunkLevelMap(int initialChunks, int maxChunks, int maxLevel) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        out.put(1, initialChunks);
        out.put(maxLevel, maxChunks);
        ConfigurationSection sec = levelEntries();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection lv = sec.getConfigurationSection(key);
            if (lv == null || !lv.contains("side-chunks")) {
                continue;
            }
            try {
                out.put(Integer.parseInt(key), lv.getInt("side-chunks"));
            } catch (NumberFormatException ignored) {
                // ignore malformed level keys
            }
        }
        return out;
    }

    private Map<Integer, Double> upgradeMoneyLevelMap() {
        Map<Integer, Double> modern = new LinkedHashMap<>();
        ConfigurationSection sec = levelEntries();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection lv = sec.getConfigurationSection(key);
                if (lv == null) {
                    continue;
                }
                try {
                    int level = Integer.parseInt(key);
                    if (lv.contains("upgrade.money")) {
                        modern.put(level, lv.getDouble("upgrade.money"));
                    }
                } catch (NumberFormatException ignored) {
                    // ignore malformed level keys
                }
            }
        }
        return modern;
    }

    private Map<Integer, List<ItemCost>> upgradeItemLevelMap() {
        Map<Integer, List<ItemCost>> modern = new LinkedHashMap<>();
        ConfigurationSection sec = levelEntries();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection lv = sec.getConfigurationSection(key);
                if (lv == null) {
                    continue;
                }
                List<String> raw = lv.getStringList("upgrade.items");
                List<ItemCost> costs = raw.stream()
                        .map(this::parseItemCost)
                        .filter(ItemCost::valid)
                        .toList();
                try {
                    if (!costs.isEmpty()) {
                        modern.put(Integer.parseInt(key), costs);
                    }
                } catch (NumberFormatException ignored) {
                    // ignore malformed level keys
                }
            }
        }
        return modern;
    }

    private Map<Integer, Integer> capLevelMap(String id) {
        Map<Integer, Integer> modern = new LinkedHashMap<>();
        ConfigurationSection sec = levelEntries();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection lv = sec.getConfigurationSection(key);
                if (lv == null) {
                    continue;
                }
                String path = "caps." + id;
                if (!lv.contains(path)) {
                    continue;
                }
                try {
                    modern.put(Integer.parseInt(key), lv.getInt(path));
                } catch (NumberFormatException ignored) {
                    // ignore malformed level keys
                }
            }
        }
        return modern;
    }

    private ItemCost parseItemCost(String raw) {
        if (raw == null) {
            return new ItemCost("", 0);
        }
        int split = raw.lastIndexOf(':');
        if (split <= 0 || split >= raw.length() - 1) {
            return new ItemCost(raw, 1);
        }
        try {
            return new ItemCost(raw.substring(0, split), Integer.parseInt(raw.substring(split + 1)));
        } catch (NumberFormatException ignored) {
            return new ItemCost(raw, 1);
        }
    }

    private Map<Integer, EntityLimits.Caps> explicitLimitCaps() {
        Map<Integer, EntityLimits.Caps> out = new LinkedHashMap<>();
        ConfigurationSection sec = modernLevelEntries();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection lv = sec.getConfigurationSection(key + ".limits");
            if (lv == null) {
                continue;
            }
            try {
                out.put(Integer.parseInt(key), new EntityLimits.Caps(
                        lv.contains("mobs") ? lv.getInt("mobs") : null,
                        lv.contains("tiles") ? lv.getInt("tiles") : null,
                        lv.contains("drops") ? lv.getInt("drops") : null,
                        lv.contains("vehicles") ? lv.getInt("vehicles") : null));
            } catch (NumberFormatException ignored) {
                // ignore malformed level keys
            }
        }
        return out;
    }

    private Map<Integer, Map<String, Integer>> explicitMachineCaps() {
        Map<Integer, Map<String, Integer>> out = new LinkedHashMap<>();
        ConfigurationSection sec = modernLevelEntries();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection machines = sec.getConfigurationSection(key + ".limits.machines");
            if (machines == null) {
                continue;
            }
            Map<String, Integer> caps = new LinkedHashMap<>();
            for (String id : machines.getKeys(false)) {
                caps.put(id.toLowerCase(), machines.getInt(id));
            }
            try {
                out.put(Integer.parseInt(key), caps);
            } catch (NumberFormatException ignored) {
                // ignore malformed level keys
            }
        }
        return out;
    }
}
