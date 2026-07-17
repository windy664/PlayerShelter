package org.windy.playershelter.runtime.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.windy.playershelter.api.PlayerShelterMigrationRegion;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.domain.port.RegionMover;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PlayerShelter/GuildShelter 互迁软集成命令。
 *
 * <p>本类不静态引用 GuildShelter API，避免 GuildShelter 缺席时 PlayerShelter 启动失败。
 */
public final class PsgsCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.AQUA + "[PSGS] " + ChatColor.RESET;
    private static final List<String> SUBS = List.of("gs-to-ps", "ps-to-gs", "confirm", "cancel", "reload", "help");
    private static final long PENDING_TTL_MILLIS = 30_000L;
    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final PsCore core;
    private final GuildShelterBridge guildShelter = new GuildShelterBridge();
    private final Map<String, PendingMigration> pending = new HashMap<>();
    private boolean migrationRunning;
    private final ThreadLocal<String> commandPath = ThreadLocal.withInitial(() -> "/psgs");

    public PsgsCommand(PsCore core) {
        this.core = core;
    }

    public boolean execute(CommandSender sender, String commandPath, String[] args) {
        this.commandPath.set(commandPath == null || commandPath.isBlank() ? "/psgs" : commandPath);
        try {
            return onCommand(sender, null, "psgs", args);
        } finally {
            this.commandPath.remove();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAdmin(sender)) {
            error(sender, "你没有权限。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                guildShelter.clear();
                ok(sender, "已重新获取 GuildShelter API。");
            }
            case "confirm" -> confirm(sender);
            case "cancel" -> {
                pending.remove(senderKey(sender));
                ok(sender, "已取消待确认迁移。");
            }
            case "gs-to-ps" -> gsToPs(sender, args);
            case "ps-to-gs" -> psToGs(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void gsToPs(CommandSender sender, String[] args) {
        if (!guildShelter.ready(sender)) {
            return;
        }
        if (args.length < 2) {
            warn(sender, "用法：" + commandPath.get() + " gs-to-ps <玩家> [--force]");
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
        UUID owner = player.getUniqueId();
        boolean force = hasForceOption(args, 2);

        Optional<GsRegion> src = guildShelter.exportManor(owner);
        if (src.isEmpty()) {
            error(sender, "该玩家没有可导出的 GuildShelter 庄园。");
            return;
        }
        if (force) {
            warn(sender, "强制模式已启用：目标 PS 若不存在或边长不足，将先重建为与源 GS 同尺寸。");
        }
        Optional<PlayerShelterMigrationRegion> dst = preparePsImport(owner, src.get().sideChunks(), force);
        if (dst.isEmpty()) {
            error(sender, "PlayerShelter 目标不存在或边长不足，需要至少 "
                    + src.get().sideChunks() + "x" + src.get().sideChunks() + " chunk。");
            return;
        }

        PendingMigration migration = PendingMigration.gsToPs(owner, src.get(), dst.get());
        pending.put(senderKey(sender), migration);
        printPlan(sender, player.getName(), src.get(), dst.get());
        warn(sender, "当前复制包含方块和 BlockEntity NBT；NeoForge 增强版会尝试迁移 World+BlockPos 类 data/*.dat。");
        warn(sender, "30 秒内输入 " + commandPath.get() + " confirm 执行复制。源 GS 庄园不会被清空。");
    }

    private void psToGs(CommandSender sender, String[] args) {
        if (!guildShelter.ready(sender)) {
            return;
        }
        if (args.length < 3) {
            warn(sender, "用法：" + commandPath.get() + " ps-to-gs <玩家> <目标公会> [--force]");
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
        UUID owner = player.getUniqueId();

        Optional<PlayerShelterMigrationRegion> src = exportPsRegion(owner);
        if (src.isEmpty()) {
            error(sender, "该玩家没有可导出的 PlayerShelter 庇护所。");
            return;
        }
        boolean force = hasForceOption(args, 3);
        if (force) {
            warn(sender, "--force enabled: releasing the player's existing GS manor slot before import. Blocks are not cleared.");
            if (!guildShelter.releaseExistingManor(owner)) {
                error(sender, "Force overwrite failed: could not call GuildShelter release API.");
                return;
            }
        }
        Optional<GsRegion> dst = guildShelter.prepareManorImport(owner, args[2], src.get().sideChunks());
        if (dst.isEmpty()) {
            if (force) {
                warn(sender, "--force was executed, but GuildShelter still rejected the import precheck.");
            }
            error(sender, "GuildShelter 目标公会不存在、已满员、玩家已有庄园，或目标边长不足。");
            return;
        }

        PendingMigration migration = PendingMigration.psToGs(owner, src.get(), dst.get());
        pending.put(senderKey(sender), migration);
        printPlan(sender, player.getName(), src.get(), dst.get());
        warn(sender, "当前复制包含方块和 BlockEntity NBT；NeoForge 增强版会尝试迁移 World+BlockPos 类 data/*.dat。");
        warn(sender, "30 秒内输入 " + commandPath.get() + " confirm 执行复制并创建 GS 庄园记录。成功后会先备份源 PS 世界，再删除源 PS 世界和记录。");
    }

    private void confirm(CommandSender sender) {
        PendingMigration migration = pending.remove(senderKey(sender));
        if (migration == null || System.currentTimeMillis() > migration.expiresAt()) {
            error(sender, "没有待确认迁移，或确认已过期。");
            return;
        }
        if (!guildShelter.ready(sender)) {
            return;
        }
        if (migrationRunning) {
            error(sender, msg("psgs.migration.already-running", "已有迁移正在执行，请等待当前迁移完成。"));
            return;
        }

        migrationRunning = true;
        int copySize = Math.min(migration.source().sideChunks(), migration.target().sideChunks());
        String migrationId = migration.kind().name().toLowerCase() + "-" + migration.owner().toString().substring(0, 8)
                + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
        MigrationUi ui = new MigrationUi(core, sender, migration, migrationId);
        RegionMover.ProgressListener progress = step -> {
            logProgress(migrationId, step);
            ui.update(step);
        };

        core.plugin().getLogger().info("[PSGS] 迁移开始 id=" + migrationId
                + " kind=" + migration.kind()
                + " owner=" + migration.owner()
                + " source=" + migration.source().worldName()
                + " chunks=" + fmt(migration.source().minChunkX(), migration.source().minChunkZ(),
                migration.source().maxChunkX(), migration.source().maxChunkZ())
                + " target=" + migration.target().worldName()
                + " chunks=" + fmt(migration.target().minChunkX(), migration.target().minChunkZ(),
                migration.target().maxChunkX(), migration.target().maxChunkZ())
                + " copySize=" + copySize);
        warn(sender, msg("psgs.migration.background", "迁移已进入后台执行，进度会显示在 bossbar 和后台日志中。"));
        try {
            ui.start();
            core.regionMover().copyRegionAsync(
                    migration.source().worldName(),
                    migration.source().minChunkX(),
                    migration.source().minChunkZ(),
                    copySize,
                    migration.target().worldName(),
                    migration.target().minChunkX(),
                    migration.target().minChunkZ(),
                    progress,
                    copied -> finishConfirmedMigration(sender, migration, migrationId, ui, copied));
        } catch (Throwable t) {
            migrationRunning = false;
            ui.fail(msg("psgs.migration.start-failed-title", "迁移启动失败"));
            core.plugin().getLogger().warning("Migration copy failed: " + t.getMessage());
            error(sender, Messages.format("psgs.migration.copy-exception", "复制失败：{error}",
                    "error", t.getMessage()));
        }
    }

    private void finishConfirmedMigration(CommandSender sender, PendingMigration migration, String migrationId,
                                          MigrationUi ui, boolean copied) {
        try {
            if (!copied) {
                core.plugin().getLogger().warning("[PSGS] 迁移失败 id=" + migrationId + " copyResult=false");
                ui.fail(msg("psgs.migration.copy-failed-title", "复制失败"));
                error(sender, msg("psgs.migration.copy-failed",
                        "复制失败：源/目标世界不存在、目标载体复制能力不可用，或高度范围不兼容。"));
                return;
            }
            ui.update(RegionMover.Progress.of("finalize",
                    msg("psgs.migration.finalize-detail", "提交迁移记录"), 1, 1, 0.98D));
            if (migration.kind() == MigrationKind.PS_TO_GS) {
                boolean completed = guildShelter.completeManorImport(
                        migration.owner(), migration.targetGuild(), migration.targetSlot(), migration.sourceLevel());
                if (!completed) {
                    core.plugin().getLogger().warning("[PSGS] 迁移记录提交失败 id=" + migrationId
                            + " owner=" + migration.owner()
                            + " guild=" + migration.targetGuild()
                            + " slot=" + migration.targetSlot());
                    ui.fail(msg("psgs.migration.gs-record-failed-title", "GS 记录提交失败"));
                    error(sender, msg("psgs.migration.gs-record-failed",
                            "方块已复制，但 GS 庄园记录提交失败。请先不要重复执行，手动检查目标区域。"));
                    return;
                }
            }
            if (migration.kind() == MigrationKind.PS_TO_GS) {
                backupAndDeletePsSource(sender, migration, migrationId, ui);
                return;
            }
            core.plugin().getLogger().info("[PSGS] 迁移完成 id=" + migrationId);
            ui.success();
            ok(sender, msg("psgs.migration.success", "迁移复制完成。源区域未清空。"));
        } catch (Throwable t) {
            core.plugin().getLogger().warning("[PSGS] 迁移收尾失败 id=" + migrationId + ": " + t.getMessage());
            ui.fail(msg("psgs.migration.finish-failed-title", "迁移收尾失败"));
            error(sender, Messages.format("psgs.migration.finish-failed", "迁移收尾失败：{error}",
                    "error", t.getMessage()));
        } finally {
            migrationRunning = false;
        }
    }

    private void backupAndDeletePsSource(CommandSender sender, PendingMigration migration, String migrationId,
                                         MigrationUi ui) {
        UUID owner = migration.owner();
        String worldName = migration.source().worldName();
        String playerName = safeName(Bukkit.getOfflinePlayer(owner).getName(), owner);
        World loadedWorld = Bukkit.getWorld(worldName);
        if (loadedWorld != null) {
            try {
                loadedWorld.save();
            } catch (RuntimeException e) {
                core.plugin().getLogger().warning("[PSGS] PS source save before backup failed id="
                        + migrationId + " world=" + worldName + ": " + e.getMessage());
            }
        }
        Optional<Path> sourceFolder = resolveWorldFolder(worldName);
        if (sourceFolder.isEmpty()) {
            core.plugin().getLogger().warning("[PSGS] PS backup skipped id=" + migrationId
                    + " world=" + worldName + " reason=world-folder-not-found");
            ui.success();
            warn(sender, "迁移完成，但没有找到源世界目录，已跳过备份和删除。");
            return;
        }

        Path backupDir = core.plugin().getDataFolder().toPath().resolve("backup");
        Path targetZip = uniqueBackupZip(backupDir, playerName, worldName);
        core.plugin().getLogger().info("[PSGS] PS backup start id=" + migrationId
                + " source=" + sourceFolder.get()
                + " backup=" + targetZip);

        Bukkit.getScheduler().runTaskAsynchronously(core.plugin(), () -> {
            boolean backedUp = zipDirectory(sourceFolder.get(), targetZip);
            Bukkit.getScheduler().runTask(core.plugin(), () -> {
                if (!backedUp) {
                    core.plugin().getLogger().warning("[PSGS] PS backup failed id=" + migrationId
                            + " world=" + worldName + " backup=" + targetZip);
                    ui.success();
                    warn(sender, "迁移完成，但源世界备份失败，已保留原世界未删除。");
                    return;
                }
                boolean deleted = core.world().deleteWorld(worldName);
                if (deleted) {
                    core.repo().delete(PlayerRef.of(owner));
                    core.plugin().getLogger().info("[PSGS] PS source backed up and deleted id=" + migrationId
                            + " backup=" + targetZip);
                    ui.success();
                    ok(sender, "迁移完成，源世界已备份并删除：" + targetZip.getFileName());
                } else {
                    core.plugin().getLogger().warning("[PSGS] PS world delete failed id=" + migrationId
                            + " world=" + worldName + " backup=" + targetZip);
                    ui.success();
                    warn(sender, "迁移完成，备份已保存，但源世界删除失败：" + worldName);
                }
            });
        });
    }

    private Optional<Path> resolveWorldFolder(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            File folder = world.getWorldFolder();
            if (folder != null && folder.isDirectory()) {
                return Optional.of(folder.toPath());
            }
        }
        for (File candidate : candidateWorldFolders(worldName)) {
            if (candidate != null && candidate.isDirectory()) {
                return Optional.of(candidate.toPath());
            }
        }
        return Optional.empty();
    }

    private List<File> candidateWorldFolders(String worldName) {
        List<File> out = new ArrayList<>();
        File container = Bukkit.getWorldContainer();
        out.add(new File(container, worldName));
        String mainWorld = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        out.add(new File(container, mainWorld + File.separator + "dimensions"
                + File.separator + "minecraft" + File.separator + worldName));
        return out;
    }

    private Path uniqueBackupZip(Path backupDir, String playerName, String worldName) {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建备份目录: " + backupDir, e);
        }
        String base = safeFilePart(playerName) + "+" + safeFilePart(worldName);
        Path first = backupDir.resolve(base + ".zip");
        if (Files.notExists(first)) {
            return first;
        }
        String stamp = BACKUP_TS.format(Instant.now());
        Path stamped = backupDir.resolve(base + "-" + stamp + ".zip");
        if (Files.notExists(stamped)) {
            return stamped;
        }
        int i = 1;
        while (true) {
            Path candidate = backupDir.resolve(base + "-" + stamp + "-" + i + ".zip");
            if (Files.notExists(candidate)) {
                return candidate;
            }
            i++;
        }
    }

    private boolean zipDirectory(Path sourceDir, Path targetZip) {
        try {
            Files.createDirectories(targetZip.getParent());
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip));
                 Stream<Path> walk = Files.walk(sourceDir)) {
                Path root = sourceDir.toAbsolutePath().normalize();
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String entryName = root.getFileName() + "/" + root.relativize(path.toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            return true;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            core.plugin().getLogger().warning("[PlayerShelter] PS backup zip failed: " + cause.getMessage());
            return false;
        } catch (IOException e) {
            core.plugin().getLogger().warning("[PlayerShelter] PS backup zip failed: " + e.getMessage());
            return false;
        }
    }

    private String safeFilePart(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value;
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private void logProgress(String migrationId, RegionMover.Progress step) {
        int percent = (int) Math.round(step.overall() * 100.0D);
        core.plugin().getLogger().info("[PSGS] 迁移进度 id=" + migrationId
                + " phase=" + step.phase()
                + " progress=" + step.current() + "/" + step.total()
                + " overall=" + percent + "%"
                + (step.detail().isBlank() ? "" : " detail=" + step.detail()));
    }

    private static final class MigrationUi {

        private final PsCore core;
        private final BossBar bossBar;

        MigrationUi(PsCore core, CommandSender sender, PendingMigration migration, String migrationId) {
            this.core = core;
            Set<Player> viewers = new LinkedHashSet<>();
            if (sender instanceof Player player) {
                viewers.add(player);
            }
            Player owner = Bukkit.getPlayer(migration.owner());
            if (owner != null) {
                viewers.add(owner);
            }
            if (viewers.isEmpty()) {
                this.bossBar = null;
                return;
            }
            this.bossBar = Bukkit.createBossBar(Messages.format("psgs.migration.bossbar.prepare",
                            "[PSGS] 迁移准备中 {id}", "id", migrationId),
                    BarColor.BLUE, BarStyle.SEGMENTED_20);
            this.bossBar.setProgress(0.0D);
            for (Player viewer : viewers) {
                this.bossBar.addPlayer(viewer);
            }
            this.bossBar.setVisible(true);
        }

        void start() {
            update(RegionMover.Progress.of("start",
                    Messages.get("psgs.migration.start-detail", "开始迁移"), 0, 1, 0.01D));
        }

        void update(RegionMover.Progress step) {
            if (bossBar == null) {
                return;
            }
            Runnable task = () -> {
                bossBar.setColor(BarColor.BLUE);
                bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, step.overall())));
                bossBar.setTitle(Messages.format("psgs.migration.bossbar.progress",
                        "[PSGS] {phase} {percent}{progress}",
                        "phase", phaseLabel(step.phase()),
                        "percent", percent(step.overall()),
                        "progress", progressSuffix(step)));
            };
            runMain(task);
        }

        void success() {
            finish(BarColor.GREEN, Messages.get("psgs.migration.bossbar.success", "[PSGS] 迁移完成"), 1.0D, 60L);
        }

        void fail(String reason) {
            finish(BarColor.RED, Messages.format("psgs.migration.bossbar.failed",
                    "[PSGS] {reason}", "reason", reason), 1.0D, 100L);
        }

        private void finish(BarColor color, String title, double progress, long removeAfterTicks) {
            if (bossBar == null) {
                return;
            }
            runMain(() -> {
                bossBar.setColor(color);
                bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
                bossBar.setTitle(title);
                Bukkit.getScheduler().runTaskLater(core.plugin(), bossBar::removeAll, removeAfterTicks);
            });
        }

        private void runMain(Runnable task) {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(core.plugin(), task);
            }
        }

        private static String percent(double value) {
            return Math.round(Math.max(0.0D, Math.min(1.0D, value)) * 100.0D) + "%";
        }

        private static String progressSuffix(RegionMover.Progress step) {
            if (step.total() <= 1) {
                return "";
            }
            return " " + step.current() + "/" + step.total();
        }

        private static String phaseLabel(String phase) {
            return switch (phase) {
                case "resolve" -> Messages.get("psgs.migration.phase.resolve", "检查世界");
                case "blocks" -> Messages.get("psgs.migration.phase.blocks", "复制区块");
                case "entities" -> Messages.get("psgs.migration.phase.entities", "迁移实体");
                case "saved_data" -> Messages.get("psgs.migration.phase.saved-data", "迁移数据");
                case "finalize" -> Messages.get("psgs.migration.phase.finalize", "提交记录");
                case "start" -> Messages.get("psgs.migration.phase.start", "准备迁移");
                default -> Messages.get("psgs.migration.phase.default", "迁移中");
            };
        }
    }

    private Optional<PlayerShelterMigrationRegion> exportPsRegion(UUID owner) {
        return core.repo().find(PlayerRef.of(owner)).map(shelter -> {
            Shelter ready = core.world().ensureWorld(shelter);
            core.world().applyBorder(ready);
            return regionOf(ready, "playershelter:export");
        });
    }

    private Optional<PlayerShelterMigrationRegion> preparePsImport(UUID owner, int requiredSideChunks, boolean force) {
        PlayerRef ref = PlayerRef.of(owner);
        Optional<Shelter> shelterOpt = core.repo().find(ref);
        if (shelterOpt.isEmpty()) {
            if (!force) {
                return Optional.empty();
            }
            Shelter rebuilt = buildForcedPsShelter(ref, requiredSideChunks);
            core.repo().save(rebuilt);
            Shelter ready = core.world().ensureWorld(rebuilt);
            core.world().applyBorder(ready);
            return Optional.of(regionOf(ready, "playershelter:import-force"));
        }

        Shelter shelter = shelterOpt.get();
        if (!force && shelter.sideChunks() < Math.max(1, requiredSideChunks)) {
            return Optional.empty();
        }
        if (force && shelter.sideChunks() < Math.max(1, requiredSideChunks)) {
            if (!core.world().deleteWorld(shelter.worldName())) {
                core.plugin().getLogger().warning("[PlayerShelter] Forced PS rebuild failed: could not delete world " + shelter.worldName());
                return Optional.empty();
            }
            Shelter rebuilt = rebuildForcedPsShelter(shelter, requiredSideChunks);
            core.repo().save(rebuilt);
            Shelter ready = core.world().ensureWorld(rebuilt);
            core.world().applyBorder(ready);
            return Optional.of(regionOf(ready, "playershelter:import-force"));
        }

        Shelter ready = core.world().ensureWorld(shelter);
        core.world().applyBorder(ready);
        return Optional.of(regionOf(ready, "playershelter:import"));
    }

    private Shelter rebuildForcedPsShelter(Shelter shelter, int requiredSideChunks) {
        int side = Math.max(1, requiredSideChunks);
        Map<Integer, Integer> sideLevels = new LinkedHashMap<>(shelter.layout().sortedSideLevels());
        sideLevels.put(Math.max(1, shelter.level()), Math.max(side, shelter.sideChunks()));
        int initialChunks = Math.max(side, shelter.layout().initialChunks());
        int maxChunks = Math.max(side, shelter.layout().maxChunks());
        int maxLevel = shelter.layout().maxLevel();
        org.windy.playershelter.domain.model.ShelterLayout layout =
                new org.windy.playershelter.domain.model.ShelterLayout(initialChunks, maxChunks, maxLevel,
                        sideLevels, shelter.layout().originX(), shelter.layout().originZ());
        return new Shelter(
                shelter.owner(),
                shelter.worldName(),
                new java.util.Random().nextLong(),
                shelter.genType(),
                shelter.level(),
                layout,
                shelter.visibility(),
                shelter.admins(),
                shelter.trusted(),
                shelter.access(),
                shelter.denied(),
                shelter.flags(),
                shelter.bulletin(),
                shelter.serverName(),
                shelter.createdAt(),
                Instant.now(),
                shelter.likes());
    }

    private Shelter buildForcedPsShelter(PlayerRef owner, int requiredSideChunks) {
        int side = Math.max(1, requiredSideChunks);
        org.windy.playershelter.domain.model.ShelterLayout layout =
                new org.windy.playershelter.domain.model.ShelterLayout(side, side, 1,
                        Map.of(1, side), 0, 0);
        return new Shelter(
                owner,
                core.shelters().worldNameFor(owner),
                new java.util.Random().nextLong(),
                GenerationType.FLAT,
                1,
                layout,
                ShelterVisibility.PRIVATE,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                "",
                "",
                Instant.now(),
                Instant.now(),
                0);
    }

    private PlayerShelterMigrationRegion regionOf(Shelter shelter, String source) {
        int side = shelter.sideChunks();
        int minChunkX = Math.floorDiv(shelter.layout().originX(), 16);
        int minChunkZ = Math.floorDiv(shelter.layout().originZ(), 16);
        return new PlayerShelterMigrationRegion(
                shelter.owner().uuid(),
                shelter.worldName(),
                minChunkX,
                minChunkZ,
                minChunkX + side - 1,
                minChunkZ + side - 1,
                side,
                shelter.level(),
                source);
    }

    private void printPlan(CommandSender sender, String playerName, GsRegion src, PlayerShelterMigrationRegion dst) {
        ok(sender, "迁移预检通过：GuildShelter -> PlayerShelter，玩家 " + safeName(playerName, src.owner()));
        plain(sender, "源 GS: " + src.worldName() + " guild=" + src.guildId() + " slot=" + src.slot()
                + " chunks=" + fmt(src.minChunkX(), src.minChunkZ(), src.maxChunkX(), src.maxChunkZ()));
        plain(sender, "目标 PS: " + dst.worldName()
                + " chunks=" + fmt(dst.minChunkX(), dst.minChunkZ(), dst.maxChunkX(), dst.maxChunkZ()));
        plain(sender, "边长: " + src.sideChunks() + " -> " + dst.sideChunks() + " chunk");
    }

    private void printPlan(CommandSender sender, String playerName, PlayerShelterMigrationRegion src, GsRegion dst) {
        ok(sender, "迁移预检通过：PlayerShelter -> GuildShelter，玩家 " + safeName(playerName, src.owner()));
        plain(sender, "源 PS: " + src.worldName()
                + " chunks=" + fmt(src.minChunkX(), src.minChunkZ(), src.maxChunkX(), src.maxChunkZ()));
        plain(sender, "目标 GS: " + dst.worldName() + " guild=" + dst.guildId() + " slot=" + dst.slot()
                + " chunks=" + fmt(dst.minChunkX(), dst.minChunkZ(), dst.maxChunkX(), dst.maxChunkZ()));
        plain(sender, "边长: " + src.sideChunks() + " -> " + dst.sideChunks() + " chunk");
    }

    private String fmt(int minX, int minZ, int maxX, int maxZ) {
        return "(" + minX + "," + minZ + ")-(" + maxX + "," + maxZ + ")";
    }

    private String safeName(String name, UUID uuid) {
        return name == null ? uuid.toString() : name;
    }

    private void help(CommandSender sender) {
        plain(sender, ChatColor.GOLD + "==== PlayerShelter/GuildShelter 互迁 ====");
        plain(sender, commandPath.get() + " gs-to-ps <玩家> [--force] " + ChatColor.GRAY + "预检：GS 庄园迁入 PS 世界");
        plain(sender, commandPath.get() + " ps-to-gs <玩家> <目标公会> [--force] " + ChatColor.GRAY + "预检：PS 世界迁入 GS 庄园，成功后备份并删除源 PS");
        plain(sender, commandPath.get() + " confirm " + ChatColor.GRAY + "确认执行最近一次预检");
        plain(sender, commandPath.get() + " cancel " + ChatColor.GRAY + "取消待确认迁移");
        plain(sender, commandPath.get() + " reload " + ChatColor.GRAY + "重新获取 GuildShelter API");
    }

    private void ok(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + message);
    }

    private void warn(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.YELLOW + message);
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.RED + message);
    }

    private void plain(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + message);
    }

    private String msg(String key, String fallback) {
        return Messages.get(key, fallback);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAdmin(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefix(SUBS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("gs-to-ps") || args[0].equalsIgnoreCase("ps-to-gs"))) {
            return prefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gs-to-ps")) {
            return prefix(List.of("--force"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("ps-to-gs")) {
            return prefix(List.of("--force"), args[3]);
        }
        return List.of();
    }

    private boolean hasForceOption(String[] args, int startIndex) {
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if ("--force".equalsIgnoreCase(arg) || "force".equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    private List<String> prefix(List<String> values, String rawPrefix) {
        String lower = rawPrefix == null ? "" : rawPrefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }

    private String senderKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console:" + sender.getName();
    }

    private boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("playershelter.admin") || sender.hasPermission("psgs.admin");
    }

    private enum MigrationKind {
        GS_TO_PS,
        PS_TO_GS
    }

    private record RegionRef(String worldName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                             int sideChunks) {
    }

    private record GsRegion(UUID owner, String worldName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                            int sideChunks, int manorLevel, String guildId, int slot) {
    }

    private record PendingMigration(MigrationKind kind, UUID owner, RegionRef source, RegionRef target,
                                    String targetGuild, int targetSlot, int sourceLevel, long expiresAt) {
        static PendingMigration gsToPs(UUID owner, GsRegion source, PlayerShelterMigrationRegion target) {
            return new PendingMigration(MigrationKind.GS_TO_PS, owner,
                    new RegionRef(source.worldName(), source.minChunkX(), source.minChunkZ(),
                            source.maxChunkX(), source.maxChunkZ(), source.sideChunks()),
                    new RegionRef(target.worldName(), target.minChunkX(), target.minChunkZ(),
                            target.maxChunkX(), target.maxChunkZ(), target.sideChunks()),
                    null, -1, source.manorLevel(), System.currentTimeMillis() + PENDING_TTL_MILLIS);
        }

        static PendingMigration psToGs(UUID owner, PlayerShelterMigrationRegion source, GsRegion target) {
            return new PendingMigration(MigrationKind.PS_TO_GS, owner,
                    new RegionRef(source.worldName(), source.minChunkX(), source.minChunkZ(),
                            source.maxChunkX(), source.maxChunkZ(), source.sideChunks()),
                    new RegionRef(target.worldName(), target.minChunkX(), target.minChunkZ(),
                            target.maxChunkX(), target.maxChunkZ(), target.sideChunks()),
                    target.guildId(), target.slot(), source.level(),
                    System.currentTimeMillis() + PENDING_TTL_MILLIS);
        }
    }

    private final class GuildShelterBridge {
        private Object api;
        private Method exportManor;
        private Method prepareManorImport;
        private Method completeManorImport;
        private Object guildService;
        private Method playerRefOf;
        private Method releaseManorAnywhere;

        void clear() {
            api = null;
            exportManor = null;
            prepareManorImport = null;
            completeManorImport = null;
            guildService = null;
            playerRefOf = null;
            releaseManorAnywhere = null;
        }

        boolean ready(CommandSender sender) {
            if (api != null) {
                return true;
            }
            try {
                Class<?> apiClass = Class.forName("org.windy.guildshelter.api.GuildShelterAPI");
                RegisteredServiceProvider<?> provider = core.plugin().getServer().getServicesManager()
                        .getRegistration(apiClass);
                if (provider == null || provider.getProvider() == null) {
                    error(sender, "GuildShelter API 不可用，请确认 GuildShelter 已启用。");
                    return false;
                }
                Object resolved = provider.getProvider();
                exportManor = apiClass.getMethod("exportManor", UUID.class);
                prepareManorImport = apiClass.getMethod("prepareManorImport", UUID.class, String.class, int.class);
                completeManorImport = apiClass.getMethod("completeManorImport", UUID.class, String.class, int.class, int.class);
                api = resolved;
                return true;
            } catch (ClassNotFoundException e) {
                error(sender, "未检测到 GuildShelter，互迁命令不可用。");
                return false;
            } catch (ReflectiveOperationException e) {
                error(sender, "GuildShelter API 版本不兼容，缺少迁移接口：" + e.getMessage());
                return false;
            }
        }

        Optional<GsRegion> exportManor(UUID owner) {
            return invokeRegion(exportManor, owner);
        }

        Optional<GsRegion> prepareManorImport(UUID owner, String targetGuild, int requiredSideChunks) {
            return invokeRegion(prepareManorImport, owner, targetGuild, requiredSideChunks);
        }

        boolean completeManorImport(UUID owner, String targetGuild, int slot, int manorLevel) {
            try {
                Object result = completeManorImport.invoke(api, owner, targetGuild, slot, manorLevel);
                return result instanceof Boolean ok && ok;
            } catch (ReflectiveOperationException e) {
                core.plugin().getLogger().warning("[PlayerShelter] GuildShelter completeManorImport failed: " + e.getMessage());
                return false;
            }
        }

        boolean releaseExistingManor(UUID owner) {
            try {
                ensureReleaseApi();
                Object playerRef = playerRefOf.invoke(null, owner);
                releaseManorAnywhere.invoke(guildService, playerRef);
                return true;
            } catch (ReflectiveOperationException e) {
                core.plugin().getLogger().warning("[PlayerShelter] GuildShelter force release failed: " + e.getMessage());
                return false;
            }
        }

        private void ensureReleaseApi() throws ReflectiveOperationException {
            if (guildService != null && playerRefOf != null && releaseManorAnywhere != null) {
                return;
            }
            Field serviceField = api.getClass().getDeclaredField("service");
            serviceField.setAccessible(true);
            guildService = serviceField.get(api);
            Class<?> playerRefClass = Class.forName("org.windy.guildshelter.domain.model.PlayerRef");
            playerRefOf = playerRefClass.getMethod("of", UUID.class);
            releaseManorAnywhere = guildService.getClass().getMethod("releaseManorAnywhere", playerRefClass);
        }

        private Optional<GsRegion> invokeRegion(Method method, Object... args) {
            try {
                Object result = method.invoke(api, args);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(readRegion(optional.get()));
            } catch (ReflectiveOperationException e) {
                core.plugin().getLogger().warning("[PlayerShelter] GuildShelter migration API failed: " + e.getMessage());
                return Optional.empty();
            }
        }

        private GsRegion readRegion(Object region) throws ReflectiveOperationException {
            Object guild = call(region, "guild");
            return new GsRegion(
                    (UUID) call(region, "owner"),
                    (String) call(region, "worldName"),
                    (Integer) call(region, "minChunkX"),
                    (Integer) call(region, "minChunkZ"),
                    (Integer) call(region, "maxChunkX"),
                    (Integer) call(region, "maxChunkZ"),
                    (Integer) call(region, "sideChunks"),
                    (Integer) call(region, "manorLevel"),
                    (String) call(guild, "id"),
                    (Integer) call(region, "slot"));
        }

        private Object call(Object target, String method) throws ReflectiveOperationException {
            return target.getClass().getMethod(method).invoke(target);
        }
    }
}
