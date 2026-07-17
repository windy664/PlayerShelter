package org.windy.playershelter.adapter.world;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.WorldControl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * {@link WorldControl} 的 Paper 实现（走 org.bukkit API，普通版载体）。
 *
 * <p>生成类型分流（决策 #1）：
 * <ul>
 * <li>{@link GenerationType#NATURAL}：Iris 在场用 Iris 异步管线（见 {@link #ensureWorldAsync}），缺席回退原版 normal。</li>
 * <li>{@link GenerationType#FLAT}：超平坦，generatorSettings 走 JSON（MC1.16+ 要求，见 [[mc-flat-generatorsettings-json]]）。</li>
 * <li>{@link GenerationType#VOID}：原版 FLAT + 空层超平坦（{@code VOID_SETTINGS_JSON}）+ 出生点摆起始小岛（决策 #45）。</li>
 * </ul>
 *
 * <p><b>混合端友好</b>：FLAT/VOID 均用<b>原版超平坦生成器</b>（非 Bukkit 自定义 {@code ChunkGenerator}），
 * 故在 Youer 混合端也能正常生成——绕开了「混合端不 honor 自定义 ChunkGenerator」的坑，无需 NeoForge 原生维度。
 * NATURAL 经 Iris（在场）或原版 normal。
 */
public final class PaperWorldControl implements WorldControl {

    private static final String FLAT_SETTINGS_JSON =
            "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1}," +
                    "{\"block\":\"minecraft:deepslate\",\"height\":59}," +
                    "{\"block\":\"minecraft:stone\",\"height\":64}," +
                    "{\"block\":\"minecraft:dirt\",\"height\":3}," +
                    "{\"block\":\"minecraft:grass_block\",\"height\":1}]," +
                    "\"biome\":\"minecraft:plains\"}";

    /**
     * 虚空 = 空层超平坦（决策 #1 VOID）。<b>关键</b>：用<b>原版 FLAT 生成器</b>（空 layers = 纯虚空），
     * 而非 Bukkit 自定义 {@code ChunkGenerator}——这样在 Youer 混合端也能正常生成（混合端不 honor
     * 自定义 ChunkGenerator，但认原版超平坦），免去 NeoForge 原生维度的深水活。起始小岛事后摆。
     */
    private static final String VOID_SETTINGS_JSON =
            "{\"layers\":[],\"biome\":\"minecraft:plains\"}";

    private final Plugin plugin;
    private final Logger log;
    private final Material platformMaterial;
    private final SafeLanding safeLanding;
    private final boolean irisEnabled;
    private final String irisDimension;
    private final Map<String, String> gamerules;
    private final int viewDistance;
    private final int simulationDistance;

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             boolean irisEnabled, String irisDimension, boolean keepInventory) {
        this(plugin, log, platformMaterial, irisEnabled, irisDimension,
                keepInventory ? Map.of("keep_inventory", "true") : Map.of(), 6, 4);
    }

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             boolean irisEnabled, String irisDimension, Map<String, String> gamerules) {
        this(plugin, log, platformMaterial, irisEnabled, irisDimension, gamerules, 6, 4);
    }

    public PaperWorldControl(Plugin plugin, Logger log, Material platformMaterial,
                             boolean irisEnabled, String irisDimension, Map<String, String> gamerules,
                             int viewDistance, int simulationDistance) {
        this.plugin = plugin;
        this.log = log;
        this.platformMaterial = platformMaterial == null ? Material.GLASS : platformMaterial;
        this.safeLanding = new SafeLanding(this.platformMaterial);
        this.irisEnabled = irisEnabled;
        this.irisDimension = (irisDimension == null || irisDimension.isBlank()) ? "overworld" : irisDimension;
        this.gamerules = gamerules == null ? Map.of() : Map.copyOf(gamerules);
        this.viewDistance = clampPaperDistance(viewDistance, 6);
        this.simulationDistance = clampPaperDistance(simulationDistance, 4);
    }

    /** Iris 是否在场且启用（轻量判断，不构建生成器）。 */
    private boolean irisActive() {
        if (!irisEnabled) {
            return false;
        }
        Plugin p = Bukkit.getPluginManager().getPlugin("Iris");
        return p != null && p.isEnabled();
    }

    @Override
    public String worldName(Shelter shelter) {
        return shelter.worldName();
    }

    @SuppressWarnings("unchecked") // 压制 Registry 获取时的泛型强转警告
    @Override
    public Shelter ensureWorld(Shelter shelter) {
        String name = shelter.worldName();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            applyPaperWorldTuning(existing);
            applyBorder(shelter);
            return shelter;
        }

        boolean freshWorld = !worldFolderExists(name);
        WorldCreator wc = new WorldCreator(name).seed(shelter.seed());
        switch (shelter.genType()) {
            case NATURAL -> wc.environment(World.Environment.NORMAL).type(WorldType.NORMAL);
            case FLAT -> {
                wc.environment(World.Environment.NORMAL).type(WorldType.FLAT);
                wc.generatorSettings(FLAT_SETTINGS_JSON);
            }
            case VOID -> {
                wc.environment(World.Environment.NORMAL).type(WorldType.FLAT);
                wc.generatorSettings(VOID_SETTINGS_JSON);
            }
        }
        World world = wc.createWorld();
        if (world == null) {
            throw new IllegalStateException("createWorld 返回 null: " + name);
        }

        // 1.21 注册表：取消常加载
        GameRule<Integer> spawnRadius = (GameRule<Integer>) Registry.GAME_RULE.get(NamespacedKey.minecraft("spawn_chunk_radius"));
        if (spawnRadius != null) {
            world.setGameRule(spawnRadius, 0);
        }
        applyPaperWorldTuning(world);

        world.setSpawnFlags(true, true);

        if (freshWorld) {
            applyConfiguredGameRules(world);
        }

        if (shelter.genType() == GenerationType.VOID) {
            buildStarterIsland(world);
        }

        // 出生点落在锚定角第一个 chunk 中心；地块范围固定为 chunk (0..max-1, 0..max-1)。
        Location spawn = safeLanding.resolve(world, 8, 8);
        world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
        applyBorder(shelter);
        log.info("[PlayerShelter] 已创建庇护所世界 " + name + " (" + shelter.genType() + ")");

        if (shelter.genType() == GenerationType.NATURAL) {
            probeNaturalTerrain(world, name);
        }
        return shelter;
    }

    /** 采样自然地形是否真生成（混合端诊断）。仅日志，不改变行为。 */
    private void probeNaturalTerrain(World world, String name) {
        int hCenter = world.getHighestBlockYAt(0, 0);
        int hFar = world.getHighestBlockYAt(40, 40);
        int minY = world.getMinHeight();
        boolean centerVoid = hCenter <= minY + 1;
        boolean farVoid = hFar <= minY + 1;
        log.info("[PlayerShelter] 地形自检 " + name + "：地表高度 (0,0)=" + hCenter + " (40,40)=" + hFar
                + " 世界底=" + minY);
        if (centerVoid || farVoid) {
            log.warning("[PlayerShelter] ⚠ 该 NATURAL 世界地形疑似未生成（多处为虚空高度）。"
                    + "混合端(Youer/NeoForge)常不为 Bukkit 建的普通世界挂模组地形生成器 → 会是空世界。");
            log.warning("[PlayerShelter]   解决：① 安装 Iris 插件（本插件会自动用 Iris 异步真实生成 NATURAL）；"
                    + "② 或用 /ps create flat|void（原版超平坦，混合端可靠生成）。");
        }
    }

    /**
     * 覆盖异步建世界（决策 #1 Iris 优先 / #26 进度条）：NATURAL 且 Iris 在场且世界未建 → 走 Iris 异步管线
     * （避免 {@code Bukkit.createWorld} 主线程冻服）；否则走默认同步路径（FLAT/VOID/无 Iris/已存在）。
     *
     * <p>Iris 建世界须在异步线程调（Iris 内部 submit 回主线程做 addLevel），建好回主线程设出生点+边界+onReady。
     */
    @SuppressWarnings("unchecked") // 压制 Registry 获取时的泛型强转警告
    @Override
    public void ensureWorldAsync(Shelter shelter, UUID progressAudience,
                                 Consumer<Shelter> onReady, Runnable onError) {
        String name = shelter.worldName();
        boolean irisNeedsAsync = shelter.genType() == GenerationType.NATURAL
                && irisActive()
                && Bukkit.getWorld(name) == null;
        if (!irisNeedsAsync) {
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }
        if (plugin == null) {
            log.severe("[PlayerShelter] 建 Iris 世界需异步调度但未注入 plugin，回退同步。");
            WorldControl.super.ensureWorldAsync(shelter, progressAudience, onReady, onError);
            return;
        }
        boolean freshBuild = !worldFolderExists(name);
        Player audience = progressAudience != null ? Bukkit.getPlayer(progressAudience) : null;
        log.info("[PlayerShelter] " + (freshBuild ? "创建" : "重载") + " Iris 世界(维度 '" + irisDimension + "'): " + name
                + (audience != null ? "（进度→" + audience.getName() + "）" : ""));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final World w;
            try {
                w = IrisSupport.createWorld(name, irisDimension, shelter.seed(), audience);
            } catch (Throwable t) {
                log.severe("[PlayerShelter] Iris 建/载世界失败: " + t.getMessage());
                Bukkit.getScheduler().runTask(plugin, onError);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {

                // 1.21 注册表：取消常加载
                GameRule<Integer> spawnRadius = (GameRule<Integer>) Registry.GAME_RULE.get(NamespacedKey.minecraft("spawn_chunk_radius"));
                if (spawnRadius != null) {
                    w.setGameRule(spawnRadius, 0);
                }
                applyPaperWorldTuning(w);

                if (freshBuild) {
                    Location spawn = safeLanding.resolve(w, 8, 8);
                    w.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
                    applyConfiguredGameRules(w);
                }

                applyBorder(shelter);
                onReady.accept(shelter);
            });
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyConfiguredGameRules(World world) {
        for (Map.Entry<String, String> e : gamerules.entrySet()) {
            String key = e.getKey().toLowerCase();
            GameRule rule = Registry.GAME_RULE.get(NamespacedKey.minecraft(key));
            if (rule == null) {
                log.warning("[PlayerShelter] 未知 gamerule: " + key);
                continue;
            }
            Class<?> type = rule.getType();
            try {
                if (type == Boolean.class) {
                    world.setGameRule(rule, Boolean.parseBoolean(e.getValue()));
                } else if (type == Integer.class) {
                    world.setGameRule(rule, Integer.parseInt(e.getValue()));
                } else {
                    log.warning("[PlayerShelter] 不支持的 gamerule 类型: " + key + " -> " + type.getName());
                }
            } catch (Exception ex) {
                log.warning("[PlayerShelter] gamerule 值无效: " + key + "=" + e.getValue());
            }
        }
    }

    /** 虚空起始小岛（决策 #45）：锚定角出生点附近一小块草地。 */
    private void buildStarterIsland(World world) {
        int cy = 64;
        for (int dx = 6; dx <= 10; dx++) {
            for (int dz = 6; dz <= 10; dz++) {
                world.getBlockAt(dx, cy, dz).setType(Material.GRASS_BLOCK);
                world.getBlockAt(dx, cy - 1, dz).setType(Material.DIRT);
                world.getBlockAt(dx, cy - 2, dz).setType(Material.DIRT);
            }
        }
        world.getBlockAt(10, cy + 1, 10).setType(Material.OAK_SAPLING);
        world.getBlockAt(6, cy + 1, 6).setType(Material.CHEST);
    }

    private int clampPaperDistance(int value, int fallback) {
        int v = value <= 0 ? fallback : value;
        return Math.max(2, Math.min(32, v));
    }

    private void applyPaperWorldTuning(World world) {
        try {
            world.setViewDistance(viewDistance);
            world.setSimulationDistance(simulationDistance);
        } catch (RuntimeException e) {
            log.warning("[PlayerShelter] Failed to apply Paper world distances for "
                    + world.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void applyBorder(Shelter shelter) {
        World world = Bukkit.getWorld(shelter.worldName());
        if (world == null) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        // WorldBorder 就是当前可用正方形，从角落锚点向 +X/+Z 扩张。
        double size = shelter.borderSize();
        border.setCenter(shelter.layout().borderCenterXAtLevel(shelter.level()),
                shelter.layout().borderCenterZAtLevel(shelter.level()));
        border.setSize(shelter.borderSize());
        border.setWarningDistance(4);
        border.setDamageBuffer(2);
    }

    @Override
    public boolean unload(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        if (!world.getPlayers().isEmpty()) {
            return false; // 决策 #27 有人不卸载
        }
        return Bukkit.unloadWorld(world, true); // save=true
    }

    @Override
    public boolean deleteWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        File loadedFolder = null;
        if (world != null) {
            loadedFolder = world.getWorldFolder();
            // 先把里面的人踢到主世界（决策 #65 回大厅），再卸载。
            World fallback = Bukkit.getWorlds().get(0);
            world.getPlayers().forEach(p -> p.teleport(fallback.getSpawnLocation()));
            Bukkit.unloadWorld(world, false); // 不保存，反正要删
        }
        // 逐个删除所有候选位置（混合端存 world/dimensions/minecraft/ 下，与经典根目录不同）。
        boolean any = false;
        for (File f : candidateFolders(worldName, loadedFolder)) {
            if (f != null && f.isDirectory()) {
                log.info("[PlayerShelter] 删除世界目录: " + f.getAbsolutePath());
                any = deleteRecursively(f.toPath()) || any;
            }
        }
        return any;
    }

    /**
     * 世界存档文件夹的候选位置。<b>混合端(Youer/NeoForge)把 Bukkit 建的世界存为维度</b>，
     * 目录在 {@code <主世界>/dimensions/minecraft/<name>}，而非经典 CraftBukkit 的根目录 {@code <container>/<name>}。
     * 都列出来，谁存在删谁。加载中的世界用 {@code getWorldFolder()} 权威路径。
     */
    private List<File> candidateFolders(String worldName, File loadedFolder) {
        List<File> out = new ArrayList<>();
        if (loadedFolder != null) {
            out.add(loadedFolder);
        }
        File container = Bukkit.getWorldContainer();
        out.add(new File(container, worldName)); // 经典 CraftBukkit 根目录平铺
        String mainWorld = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        out.add(new File(container, mainWorld + File.separator + "dimensions"
                + File.separator + "minecraft" + File.separator + worldName)); // 混合端维度目录
        return out;
    }

    private boolean deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.log(Level.WARNING, "[PlayerShelter] 删除文件失败: " + p, e);
                }
            });
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "[PlayerShelter] 删除世界目录失败: " + path, e);
            return false;
        }
    }

    @Override
    public boolean isLoaded(String worldName) {
        return Bukkit.getWorld(worldName) != null;
    }

    @Override
    public int playerCount(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? 0 : world.getPlayers().size();
    }

    @Override
    public List<String> loadedShelterWorlds() {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().startsWith("shelter_")) {
                out.add(w.getName());
            }
        }
        return out;
    }

    /** Iris 世界惰性生成（决策 #31：扩界不预生成，玩家走到哪 Iris 生成到哪）。 */
    @Override
    public boolean lazilyGenerated(String worldName) {
        if (!irisActive()) {
            return false;
        }
        World w = Bukkit.getWorld(worldName);
        return w != null && IrisSupport.isIrisWorld(w);
    }

    /** 世界存档目录是否已存在（判首建 vs 加载）。查所有候选位置（含混合端 dimensions 目录）。 */
    private boolean worldFolderExists(String worldName) {
        for (File f : candidateFolders(worldName, null)) {
            if (f != null && f.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /** 给 Iris 世界用的安全落点（出生点 0,0 处铺站脚台，惰性世界传送时触发单区块生成）。 */
    public Location safeSpawn(World world) {
        Location s = world.getSpawnLocation();
        return safeLanding.resolve(world, s.getBlockX(), s.getBlockZ());
    }
}
