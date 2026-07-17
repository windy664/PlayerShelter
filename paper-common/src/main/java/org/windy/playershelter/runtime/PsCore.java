package org.windy.playershelter.runtime;

import org.bukkit.plugin.java.JavaPlugin;
import org.windy.playershelter.adapter.limit.LimitEnforcer;
import org.windy.playershelter.adapter.proxy.ServerTransfer;
import org.windy.playershelter.adapter.world.SafeLanding;
import org.windy.playershelter.domain.port.DirectoryPort;
import org.windy.playershelter.domain.port.EconomyPort;
import org.windy.playershelter.domain.port.MessageBoardStore;
import org.windy.playershelter.domain.port.PendingActionStore;
import org.windy.playershelter.domain.port.RegionMover;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.domain.port.TagStore;
import org.windy.playershelter.domain.port.WorldControl;
import org.windy.playershelter.runtime.api.BuildCheckRegistry;
import org.windy.playershelter.service.ShelterConfig;
import org.windy.playershelter.service.ShelterService;
import org.windy.playershelter.service.WorldLifecycleService;

/**
 * 运行期服务容器：装配后把所有 service/port/config 攥成一束，传给命令与监听器，避免到处穿参。
 */
public final class PsCore {

    private final JavaPlugin plugin;
    private final ShelterRepository repo;
    private final WorldControl world;
    private final DirectoryPort directory;
    private final EconomyPort economy;
    private final ShelterService shelterService;
    private final WorldLifecycleService lifecycle;
    private final ShelterConfig config;
    private final SafeLanding safeLanding;
    private final PendingActionStore pending;
    private final ServerTransfer transfer;
    private final boolean crossServer;
    private final LimitEnforcer limits;
    private final TagStore tags;
    private final MessageBoardStore board;
    private final BuildCheckRegistry buildChecks;
    private final RegionMover regionMover;

    public PsCore(JavaPlugin plugin, ShelterRepository repo, WorldControl world, DirectoryPort directory,
                  EconomyPort economy, ShelterService shelterService, WorldLifecycleService lifecycle,
                  ShelterConfig config, SafeLanding safeLanding, PendingActionStore pending,
                  ServerTransfer transfer, boolean crossServer, LimitEnforcer limits,
                  TagStore tags, MessageBoardStore board, BuildCheckRegistry buildChecks,
                  RegionMover regionMover) {
        this.plugin = plugin;
        this.repo = repo;
        this.world = world;
        this.directory = directory;
        this.economy = economy;
        this.shelterService = shelterService;
        this.lifecycle = lifecycle;
        this.config = config;
        this.safeLanding = safeLanding;
        this.pending = pending;
        this.transfer = transfer;
        this.crossServer = crossServer;
        this.limits = limits;
        this.tags = tags;
        this.board = board;
        this.buildChecks = buildChecks;
        this.regionMover = regionMover;
    }

    public JavaPlugin plugin() { return plugin; }
    public ShelterRepository repo() { return repo; }
    public WorldControl world() { return world; }
    public DirectoryPort directory() { return directory; }
    public EconomyPort economy() { return economy; }
    public ShelterService shelters() { return shelterService; }
    public WorldLifecycleService lifecycle() { return lifecycle; }
    public ShelterConfig config() { return config; }
    public SafeLanding safeLanding() { return safeLanding; }
    public PendingActionStore pending() { return pending; }
    public ServerTransfer transfer() { return transfer; }
    public LimitEnforcer limits() { return limits; }
    public TagStore tags() { return tags; }
    public MessageBoardStore board() { return board; }
    public BuildCheckRegistry buildChecks() { return buildChecks; }
    public RegionMover regionMover() { return regionMover; }

    /** 是否启用跨服模式（config.yml cross_server=true 且 server.yml 本服名非空）。 */
    public boolean crossServer() { return crossServer; }

    /** 某庇护所是否属于本服（决策 #59 世界绑服）。跨服关闭时恒 true。 */
    public boolean isLocal(String shelterServerName) {
        if (!crossServer) {
            return true;
        }
        return config.defaultServerName().equalsIgnoreCase(shelterServerName == null ? "" : shelterServerName);
    }
}
