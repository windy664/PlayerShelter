package org.windy.playershelter.runtime;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.HandlerList;
import org.windy.playershelter.adapter.economy.VaultEconomy;
import org.windy.playershelter.adapter.papi.PlayerShelterExpansion;
import org.windy.playershelter.adapter.proxy.ServerTransfer;
import org.windy.playershelter.adapter.sched.BukkitScheduler;
import org.windy.playershelter.adapter.storage.CachedShelterRepository;
import org.windy.playershelter.adapter.storage.Database;
import org.windy.playershelter.adapter.storage.Migrations;
import org.windy.playershelter.adapter.storage.SqlDirectory;
import org.windy.playershelter.adapter.storage.SqlResetLogStore;
import org.windy.playershelter.adapter.storage.SqlShelterRepository;
import org.windy.playershelter.api.PlayerShelterApi;
import org.windy.playershelter.domain.port.DirectoryPort;
import org.windy.playershelter.domain.port.EconomyPort;
import org.windy.playershelter.domain.port.SchedulerPort;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.domain.port.WorldControl;
import org.windy.playershelter.platform.PlatformBindings;
import org.windy.playershelter.runtime.api.ApiImpl;
import org.windy.playershelter.runtime.command.PsCommand;
import org.windy.playershelter.runtime.listener.ClaimGuardListener;
import org.windy.playershelter.runtime.listener.LifecycleListener;
import org.windy.playershelter.service.ShelterConfig;
import org.windy.playershelter.service.ShelterService;
import org.windy.playershelter.service.WorldLifecycleService;

import java.io.File;
import java.time.Clock;
import java.util.List;

public abstract class AbstractPlayerShelterPlugin extends JavaPlugin {

    private Database database;
    private WorldLifecycleService lifecycle;
    private InactiveSweeper sweeper;
    private DropSweeper dropSweeper;
    private ServerTransfer transfer;
    private PlayerShelterExpansion papiExpansion;
    private PsCore core;

    private static volatile ShelterProtection PROTECTION;
    private static volatile PsCore CORE0;

    public static ShelterProtection protection() {
        return PROTECTION;
    }

    public static PsCore core0() {
        return CORE0;
    }

    protected abstract PlatformBindings createBindings(PluginConfig config);

    protected final PsCore core() {
        return core;
    }

    protected boolean registerPaperWorldMonitor() {
        return true;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveLanguageResourceIfMissing();
        saveResourceIfMissing("levels.yml");
        saveResourceIfMissing("storage.yml");
        saveResourceIfMissing("gui/controller/shelter_controller.yml");
        saveResourceIfMissing("gui/controller/info.yml");
        saveResourceIfMissing("gui/controller/upgrade.yml");
        saveResourceIfMissing("gui/controller/members.yml");
        saveResourceIfMissing("gui/controller/flags.yml");
        saveResourceIfMissing("gui/controller/limits.yml");
        saveResourceIfMissing("gui/social/social.yml");
        saveResourceIfMissing("gui/social/directory.yml");
        saveResourceIfMissing("gui/social/board.yml");
        saveResourceIfMissing("gui/social/tags.yml");
        saveResourceIfMissing("gui/social/tags_search.yml");
        saveResourceIfMissing("gui/visitor/visitor.yml");

        PluginConfig pc = new PluginConfig(getConfig(),
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "levels.yml")),
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "storage.yml")),
                loadServerConfig());
        Messages.reload(this);
        ShelterConfig config = pc.shelterConfig();

        try {
            this.database = openDatabase(pc);
            new Migrations(database, getLogger()).migrate();
        } catch (Exception e) {
            getLogger().severe("[PlayerShelter] Database initialization failed; disabling plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ShelterRepository repo = new CachedShelterRepository(new SqlShelterRepository(database, getLogger()),
                pc.cachePositiveTtlMillis(), pc.cacheNegativeTtlMillis());
        DirectoryPort directory = new SqlDirectory(database, repo, config, getLogger());
        PlatformBindings bindings = createBindings(pc);
        WorldControl world = bindings.worldControl();
        SchedulerPort scheduler = new BukkitScheduler(this);
        EconomyPort economy = resolveEconomy(pc);

        Clock clock = Clock.systemUTC();
        SqlResetLogStore resetLog = new SqlResetLogStore(database, getLogger());
        ShelterService shelterService = new ShelterService(repo, world, economy,
                new org.windy.playershelter.adapter.inventory.BukkitItemCostPort(getLogger()),
                config, clock, resetLog);
        this.lifecycle = new WorldLifecycleService(repo, world, scheduler, config, clock);

        org.windy.playershelter.adapter.world.SafeLanding safeLanding =
                new org.windy.playershelter.adapter.world.SafeLanding(pc.platformMaterial());

        boolean crossServer = pc.crossServerEnabled();
        org.windy.playershelter.domain.port.PendingActionStore pending =
                new org.windy.playershelter.adapter.storage.SqlPendingActionStore(database, getLogger());
        this.transfer = new ServerTransfer(this);
        if (crossServer) {
            transfer.register();
            getLogger().info("[PlayerShelter] Cross-server mode enabled for '" + config.defaultServerName() + "'.");
        }

        org.windy.playershelter.adapter.limit.LimitEnforcer limits =
                new org.windy.playershelter.adapter.limit.LimitEnforcer(
                        pc.entityLimits(),
                        new org.windy.playershelter.adapter.limit.ShelterCensus(),
                        pc.limitsEnabled());

        org.windy.playershelter.domain.port.TagStore tags =
                new org.windy.playershelter.adapter.storage.SqlTagStore(database, getLogger());
        org.windy.playershelter.domain.port.MessageBoardStore board =
                new org.windy.playershelter.adapter.storage.SqlMessageBoardStore(database, getLogger());

        this.core = new PsCore(this, repo, world, directory, economy, shelterService, lifecycle, config,
                safeLanding, pending, transfer, crossServer, limits, tags, board,
                new org.windy.playershelter.runtime.api.BuildCheckRegistry(), bindings.regionMover());

        PROTECTION = new ShelterProtection(core);
        CORE0 = core;

        org.windy.playershelter.runtime.command.PsgsCommand psgs =
                new org.windy.playershelter.runtime.command.PsgsCommand(core);
        PsCommand command = new PsCommand(core, psgs);
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event ->
                        event.registrar().register(
                                org.windy.playershelter.runtime.command.PsBrigadier.node(command),
                                "PlayerShelter command", List.of()));
        getServer().getPluginManager().registerEvents(new LifecycleListener(core), this);
        if (registerPaperWorldMonitor()) {
            getServer().getPluginManager().registerEvents(
                    new org.windy.playershelter.runtime.listener.PaperWorldMonitor(core), this);
        }
        getServer().getPluginManager().registerEvents(new ClaimGuardListener(core), this);
        getServer().getPluginManager().registerEvents(
                new org.windy.playershelter.runtime.gui.ControllerGui(core), this);
        getServer().getPluginManager().registerEvents(
                new org.windy.playershelter.runtime.gui.MessageInputGui(core), this);
        this.sweeper = new InactiveSweeper(core,
                getConfig().getInt("lifecycle.inactive-sweep-minutes", 60),
                getConfig().getInt("lifecycle.inactive-sweep-max-per-run", 5));
        sweeper.start();

        this.dropSweeper = new DropSweeper(core, pc.dropCleanupIntervalSeconds());
        dropSweeper.start();

        PlayerShelterApi api = new ApiImpl(core);
        getServer().getServicesManager().register(PlayerShelterApi.class, api, this, ServicePriority.Normal);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.papiExpansion = new PlayerShelterExpansion(core);
            papiExpansion.register();
            getLogger().info("[PlayerShelter] Registered PlaceholderAPI expansion.");
        }

        banner(config, economy);
    }

    @Override
    public void onDisable() {
        if (dropSweeper != null) {
            dropSweeper.stop();
            dropSweeper = null;
        }
        if (sweeper != null) {
            sweeper.stop();
            sweeper = null;
        }
        if (lifecycle != null) {
            lifecycle.stop();
            lifecycle = null;
        }
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        if (papiExpansion != null) {
            papiExpansion.unregister();
            papiExpansion = null;
        }
        if (transfer != null) {
            transfer.unregister();
            transfer = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        PROTECTION = null;
        CORE0 = null;
        core = null;
        getLogger().info("PlayerShelter disabled.");
    }

    private void saveResourceIfMissing(String path) {
        File target = new File(getDataFolder(), path);
        if (!target.exists()) {
            saveResource(path, false);
        }
    }

    private void saveLanguageResourceIfMissing() {
        String language = getConfig().getString("language", "zh_CN");
        String safeLanguage = safeLanguage(language);
        File target = new File(getDataFolder(), "lang/" + safeLanguage + ".yml");
        if (target.exists()) {
            return;
        }
        String resourcePath = "lang/" + safeLanguage + ".yml";
        try (java.io.InputStream ignored = getResource(resourcePath)) {
            if (ignored != null) {
                saveResource(resourcePath, false);
            } else {
                getLogger().warning("[PlayerShelter] Missing bundled language file " + resourcePath
                        + "; falling back to built-in text.");
            }
        } catch (java.io.IOException e) {
            getLogger().warning("[PlayerShelter] Failed to check language file " + resourcePath + ": " + e.getMessage());
        }
    }

    private String safeLanguage(String language) {
        String lang = language == null || language.isBlank() ? "zh_CN" : language;
        return lang.replace('\\', '/').replace("/", "").replace("..", "");
    }

    private YamlConfiguration loadServerConfig() {
        File file = new File(getDataFolder(), "server.yml");
        if (!file.exists()) {
            String defaultName = defaultServerName();
            List<String> lines = List.of(
                    "# PlayerShelter server identity",
                    "# Defaults to the current working directory name.",
                    "# Used only when config.yml cross_server is true.",
                    "server-name: \"" + defaultName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
                    "");
            try {
                java.nio.file.Files.write(file.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                getLogger().warning("[PlayerShelter] Failed to generate server.yml: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private String defaultServerName() {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath().normalize();
        java.nio.file.Path fileName = cwd.getFileName();
        String name = fileName != null ? fileName.toString() : "";
        if (name == null || name.isBlank()) {
            File worldContainer = getServer().getWorldContainer();
            name = worldContainer != null ? worldContainer.getName() : "";
        }
        return (name == null || name.isBlank()) ? "server" : name;
    }

    private Database openDatabase(PluginConfig pc) {
        if ("mysql".equals(pc.storageType())) {
            return Database.mysql(pc.mysqlHost(), pc.mysqlPort(), pc.mysqlDatabase(),
                    pc.mysqlUser(), pc.mysqlPassword());
        }
        return Database.sqlite(new File(getDataFolder(), "data.db"));
    }

    private EconomyPort resolveEconomy(PluginConfig pc) {
        if (!pc.economyEnabled()) {
            return EconomyPort.DISABLED;
        }
        EconomyPort hooked = VaultEconomy.tryHook();
        if (hooked == null) {
            getLogger().warning("[PlayerShelter] Economy is enabled but Vault provider was not found; using free mode.");
            return EconomyPort.DISABLED;
        }
        getLogger().info("[PlayerShelter] Hooked Vault economy.");
        return hooked;
    }

    protected final Material platformMaterialOf(PluginConfig pc) {
        return pc.platformMaterial();
    }

    private void banner(ShelterConfig config, EconomyPort economy) {
        getLogger().info("==================================================");
        getLogger().info("  PlayerShelter " + getDescription().getVersion());
        getLogger().info("  personal shelter worlds");
        getLogger().info("  border " + config.layout().startBorder()
                + " / growth +" + config.layout().growthPerLevel()
                + " / max level " + config.layout().maxLevel());
        getLogger().info("  world/chunk auto-unload off / economy " + (economy.enabled() ? "on" : "off"));
        getLogger().info("==================================================");
    }
}
