package org.windy.playershelter.runtime.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.api.BuildAction;
import org.windy.playershelter.api.BuildDecision;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;
import org.windy.playershelter.runtime.flag.Flag;
import org.windy.playershelter.runtime.flag.Flags;

/**
 * 领地守卫（决策 #16 身份分级 / #33 flag / #69 访客只能看不能动 / #78 越界保护）。
 * 仅在庇护所世界（{@code shelter_*}）生效；非庇护所世界一律放行。所有判定走写穿缓存仓库，热路径不打 DB。
 */
public final class ClaimGuardListener implements Listener {

    private final PsCore core;

    public ClaimGuardListener(PsCore core) {
        this.core = core;
    }

    private Shelter shelterOf(World world) {
        if (!world.getName().startsWith("shelter_")) {
            return null;
        }
        return core.repo().findByWorldName(world.getName()).orElse(null);
    }

    // —— 建造/破坏（决策 #16/#78）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        Player p = e.getPlayer();
        if (outsideBorder(e.getBlock().getLocation()) && !p.hasPermission("playershelter.admin.bypass.border")) {
            e.setCancelled(true);
            Messages.errorKey(p, "protection.outside-border-build", "这里在世界边界之外，无法建造。");
            return;
        }
        if (!canBuild(s, p, e.getBlock().getLocation(), BuildAction.PLACE,
                e.getBlockPlaced().getType().getKey().toString())) {
            e.setCancelled(true);
            Messages.errorKey(p, "protection.no-build", "你没有在这个庇护所建造的权限。");
            return;
        }
        // 实体/机器限额（决策 P1）：放带方块实体的方块（箱子/机器…）时查配额，超限拦下。对所有人生效（防卡）。
        org.bukkit.block.BlockState st = e.getBlockPlaced().getState();
        if (st instanceof org.bukkit.block.TileState || st instanceof org.bukkit.inventory.InventoryHolder) {
            String id = e.getBlockPlaced().getType().getKey().toString();
            String denial = core.limits().checkPlaceTile(s, e.getBlock().getWorld(), id);
            if (denial != null) {
                e.setCancelled(true);
                Messages.error(p, denial);
                return;
            }
            core.limits().census().invalidate(e.getBlock().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        Player p = e.getPlayer();
        if (!canBuild(s, p, e.getBlock().getLocation(), BuildAction.BREAK,
                e.getBlock().getType().getKey().toString())) {
            e.setCancelled(true);
            Messages.errorKey(p, "protection.no-break", "你没有在这个庇护所破坏方块的权限。");
        }
    }

    // —— 交互（决策 #69 访客只能看不能动）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) {
            return;
        }
        Shelter s = shelterOf(e.getClickedBlock().getWorld());
        if (s == null) {
            return;
        }
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        boolean container = b.getState() instanceof org.bukkit.inventory.InventoryHolder;
        BuildDecision external = core.buildChecks().query(p.getUniqueId(), b.getLocation(),
                container ? BuildAction.CONTAINER : BuildAction.INTERACT, b.getType().getKey().toString());
        if (external == BuildDecision.DENY) {
            e.setCancelled(true);
            Messages.errorKey(p, "protection.no-interact", "你不能在这里交互。");
            return;
        }
        if (external == BuildDecision.ALLOW || canBuild(s, p)) {
            return; // 共建及以上 或 管理员（admin.build.other）随意——与 ShelterProtection.canInteract 一致
        }
        if (container) {
            if (!Flags.isOn(s, Flag.VISITOR_CONTAINER)) {
                e.setCancelled(true);
                Messages.errorKey(p, "protection.visitor-container", "访客不能打开这里的容器。");
            }
            return;
        }
        // 门/按钮/拉杆等可交互方块：按 VISITOR_INTERACT。
        if (isInteractable(b) && !Flags.isOn(s, Flag.VISITOR_INTERACT)) {
            e.setCancelled(true);
            Messages.errorKey(p, "protection.visitor-interact", "访客不能在这里交互。");
        }
    }

    private boolean isInteractable(Block b) {
        // 粗粒度：能被右键操作的方块（门/活板门/按钮/拉杆/告示牌等）。用类型名近似，避免逐一枚举。
        String n = b.getType().name();
        return n.contains("DOOR") || n.contains("BUTTON") || n.contains("LEVER")
                || n.contains("TRAPDOOR") || n.contains("GATE") || n.contains("SIGN")
                || n.contains("PRESSURE_PLATE") || n.contains("REPEATER") || n.contains("COMPARATOR");
    }

    // —— 桶（防访客倒水/岩浆改地形或舀水）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        guardBuild(e.getBlockClicked().getWorld(), e.getPlayer(), e,
                "protection.no-liquid-place", "你不能在这里放置液体。");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        guardBuild(e.getBlockClicked().getWorld(), e.getPlayer(), e,
                "protection.no-liquid-take", "你不能在这里取走液体。");
    }

    // —— 挂画/物品展示框（防访客破坏/放置）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!(e.getRemover() instanceof Player p)) {
            return;
        }
        guardBuild(e.getEntity().getWorld(), p, e,
                "protection.no-hanging-break", "你不能破坏这里的展示框/挂画。");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null) {
            return;
        }
        guardBuild(e.getEntity().getWorld(), e.getPlayer(), e,
                "protection.no-hanging-place", "你不能在这里放置展示框/挂画。");
    }

    // —— 盔甲架（防访客取装备）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        guardBuild(e.getRightClicked().getWorld(), e.getPlayer(), e,
                "protection.no-armor-stand", "你不能操作这里的盔甲架。");
    }

    // —— 载具（防访客拆船/矿车）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent e) {
        if (e.getAttacker() instanceof Player p) {
            guardBuild(e.getVehicle().getWorld(), p, e, null, null);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        if (e.getAttacker() instanceof Player p) {
            guardBuild(e.getVehicle().getWorld(), p, e,
                    "protection.no-vehicle-break", "你不能拆这里的载具。");
        }
    }

    // —— 实体交互（物品展示框旋转/取物等，按访客交互 flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        String type = e.getRightClicked().getType().name();
        boolean protectedEntity = type.contains("ITEM_FRAME") || type.equals("ARMOR_STAND")
                || type.contains("PAINTING");
        if (!protectedEntity) {
            return;
        }
        Shelter s = shelterOf(e.getRightClicked().getWorld());
        if (s == null) {
            return;
        }
        Player p = e.getPlayer();
        ShelterRole role = s.resolveRole(PlayerRef.of(p.getUniqueId()));
        if (role.canBuild() || p.hasPermission("playershelter.admin.build.other")) {
            return;
        }
        if (!Flags.isOn(s, Flag.VISITOR_INTERACT)) {
            e.setCancelled(true);
        }
    }

    // —— PvP / 动物伤害（决策 #9 / flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        Shelter s = shelterOf(e.getEntity().getWorld());
        if (s == null) {
            return;
        }
        Entity victim = e.getEntity();
        Entity damager = e.getDamager();
        if (victim instanceof Player && damager instanceof Player) {
            if (!Flags.isOn(s, Flag.PVP)) {
                e.setCancelled(true);
            }
            return;
        }
        if (victim instanceof Animals && damager instanceof Player) {
            if (!Flags.isOn(s, Flag.DAMAGE_ANIMALS)) {
                e.setCancelled(true);
            }
        }
    }

    // —— 刷怪（决策 #10 默认开，flag 可调）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        Shelter s = shelterOf(e.getLocation().getWorld());
        if (s == null) {
            return;
        }
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                && !Flags.isOn(s, Flag.MOB_SPAWNING)) {
            e.setCancelled(true);
            return;
        }
        // 生物总额限制（决策 P1）：不拦刷怪蛋/繁殖之外，超额则不再自然/刷怪笼生成。
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && core.limits().checkSpawn(s, e.getLocation().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    // —— 载具限额（决策 P1）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVehicleCreate(org.bukkit.event.vehicle.VehicleCreateEvent e) {
        Shelter s = shelterOf(e.getVehicle().getWorld());
        if (s == null) {
            return;
        }
        if (core.limits().checkVehicle(s, e.getVehicle().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    // —— 爆炸（决策 #78 越界保护 + EXPLOSIONS flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Shelter s = shelterOf(e.getEntity().getWorld());
        if (s == null) {
            return;
        }
        if (!Flags.isOn(s, Flag.EXPLOSIONS)) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(b -> outsideBorder(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        if (!Flags.isOn(s, Flag.EXPLOSIONS)) {
            e.blockList().clear();
        } else {
            e.blockList().removeIf(b -> outsideBorder(b.getLocation()));
        }
    }

    // —— 火（决策 #78 / FIRE_SPREAD flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                && !Flags.isOn(s, Flag.FIRE_SPREAD)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        if (e.getSource().getType().name().contains("FIRE") && !Flags.isOn(s, Flag.FIRE_SPREAD)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s != null && !Flags.isOn(s, Flag.FIRE_SPREAD)) {
            e.setCancelled(true);
        }
    }

    // —— 生物破坏方块（决策：MOB_GRIEFING flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s == null) {
            return;
        }
        if (!(e.getEntity() instanceof Player) && !Flags.isOn(s, Flag.MOB_GRIEFING)) {
            e.setCancelled(true);
        }
    }

    // —— 树叶衰减（LEAF_DECAY flag）——

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent e) {
        Shelter s = shelterOf(e.getBlock().getWorld());
        if (s != null && !Flags.isOn(s, Flag.LEAF_DECAY)) {
            e.setCancelled(true);
        }
    }

    // —— 工具 ——

    private boolean canBuild(Shelter s, Player p) {
        ShelterRole role = s.resolveRole(PlayerRef.of(p.getUniqueId()));
        if (role.canBuild()) {
            return true;
        }
        return p.hasPermission("playershelter.admin.build.other");
    }

    private boolean canBuild(Shelter s, Player p, Location loc, BuildAction action, String blockId) {
        BuildDecision external = core.buildChecks().query(p.getUniqueId(), loc, action, blockId);
        if (external == BuildDecision.DENY) {
            return false;
        }
        if (external == BuildDecision.ALLOW) {
            return true;
        }
        return canBuild(s, p);
    }

    /**
     * 通用「建造权」守卫：在庇护所世界里、玩家无建造权 → 取消事件（可选提示）。非庇护所世界放行。
     * 给桶/挂画/盔甲架/载具等"等价于改动世界"的操作统一收口。
     */
    private void guardBuild(World world, Player p, org.bukkit.event.Cancellable event,
                            String denyKey, String denyFallback) {
        Shelter s = shelterOf(world);
        if (s == null) {
            return;
        }
        if (!canBuild(s, p, p.getLocation(), BuildAction.ENTITY, "")) {
            event.setCancelled(true);
            if (denyKey != null) {
                Messages.errorKey(p, denyKey, denyFallback);
            }
        }
    }

    private boolean outsideBorder(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        }
        return !w.getWorldBorder().isInside(loc);
    }

}
