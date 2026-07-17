package org.windy.playershelter.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.api.BuildAction;
import org.windy.playershelter.api.BuildDecision;
import org.windy.playershelter.runtime.flag.Flag;
import org.windy.playershelter.runtime.flag.Flags;

import java.util.UUID;

/**
 * 领地保护的<b>共享判定</b>（决策 #16/#33/#69/#78）——Bukkit 监听器与 NeoForge 原生监听器<b>共用同一处</b>，
 * 保证两条路结论一致（同 GuildShelter 的 ClaimGuard 思想）。
 *
 * <p>只接收平台无关的原语（worldName / 坐标 / 玩家 UUID），不碰 Bukkit 事件或 NeoForge 类，
 * 所以既能被 Bukkit 监听调用、也能被 neoforge 模块的原生监听调用。
 *
 * <p><b>为什么要原生监听</b>：混合端上模组机器（部署器/采石场/机械臂）和模组方块/容器<b>不触发 Bukkit 事件</b>，
 * 只靠 Bukkit 监听会被绕过。neoforge 模块用原生事件覆盖这些，再回调本类做同样判定。
 */
public final class ShelterProtection {

    /** 交互类别。 */
    public enum InteractKind { USE, CONTAINER, ENTITY }

    private final PsCore core;

    public ShelterProtection(PsCore core) {
        this.core = core;
    }

    /** 该世界对应的庇护所；非庇护所世界返回 null（放行一切）。 */
    public Shelter shelterOf(String worldName) {
        if (worldName == null || !worldName.startsWith("shelter_")) {
            return null;
        }
        return core.repo().findByWorldName(worldName).orElse(null);
    }

    public boolean isShelterWorld(String worldName) {
        return shelterOf(worldName) != null;
    }

    /** 真实在线玩家能否在该世界建造/破坏（庄主/副主人/共建 或 admin 绕过）。 */
    public boolean canBuild(String worldName, UUID actor) {
        return canBuild(worldName, actor, null, BuildAction.PLACE, "");
    }

    public boolean canBuild(String worldName, UUID actor, Location loc, BuildAction action, String blockId) {
        Shelter s = shelterOf(worldName);
        if (s == null) {
            return true;
        }
        Player p = Bukkit.getPlayer(actor);
        if (loc != null && !insideBorder(worldName, loc.getBlockX(), loc.getBlockZ())) {
            return false;
        }
        BuildDecision external = core.buildChecks().query(actor, loc, action, blockId == null ? "" : blockId);
        if (external == BuildDecision.DENY) {
            return false;
        }
        if (external == BuildDecision.ALLOW) {
            return true;
        }
        if (s.resolveRole(PlayerRef.of(actor)).canBuild()) {
            return true;
        }
        return p != null && p.hasPermission("playershelter.admin.build.other");
    }

    /**
     * 模组<b>假玩家</b>（部署器/机械臂/采石场假人等，映射不到真实在线玩家）能否在该世界改动方块。
     * 庇护所里默认<b>禁止</b>——防玩家用自动化设备跨墙破坏/占用别人地盘。非庇护所世界不管。
     */
    public boolean fakePlayerAllowed(String worldName) {
        return shelterOf(worldName) == null; // 庇护所内一律禁；世界外放行
    }

    /** 坐标是否在世界边界内（决策 #78 越界保护）。世界未加载则不拦。 */
    public boolean insideBorder(String worldName, int x, int z) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return true;
        }
        return w.getWorldBorder().isInside(new Location(w, x + 0.5, 64, z + 0.5));
    }

    /** 真实玩家的交互（容器/一般/实体）是否放行（决策 #69 访客只能看不能动 + flag）。 */
    public boolean canInteract(String worldName, UUID actor, InteractKind kind) {
        return canInteract(worldName, actor, kind, null, "");
    }

    public boolean canInteract(String worldName, UUID actor, InteractKind kind, Location loc, String blockId) {
        Shelter s = shelterOf(worldName);
        if (s == null) {
            return true;
        }
        Player p = Bukkit.getPlayer(actor);
        if (loc != null && !insideBorder(worldName, loc.getBlockX(), loc.getBlockZ())) {
            return false;
        }
        BuildAction action = switch (kind) {
            case CONTAINER -> BuildAction.CONTAINER;
            case ENTITY -> BuildAction.ENTITY;
            case USE -> BuildAction.INTERACT;
        };
        BuildDecision external = core.buildChecks().query(actor, loc, action, blockId == null ? "" : blockId);
        if (external == BuildDecision.DENY) {
            return false;
        }
        if (external == BuildDecision.ALLOW) {
            return true;
        }
        ShelterRole role = s.resolveRole(PlayerRef.of(actor));
        if (role.canBuild()) {
            return true; // 共建及以上随意
        }
        if (p != null && p.hasPermission("playershelter.admin.build.other")) {
            return true;
        }
        Flag flag = kind == InteractKind.CONTAINER ? Flag.VISITOR_CONTAINER : Flag.VISITOR_INTERACT;
        return Flags.isOn(s, flag);
    }

    /** 给被拒玩家的统一提示（可为 null 静默）。 */
    public void notifyDenied(UUID actor, String msg) {
        Player p = Bukkit.getPlayer(actor);
        if (p != null && msg != null) {
            Messages.error(p, msg);
        }
    }

    /**
     * 生物生成是否放行（决策 #10 mob-spawning flag + P1 生物额）——原生侧模组生物走这里，覆盖 Bukkit 抓不到的模组刷怪。
     *
     * @param worldName 世界名
     * @param natural   是否自然生成（受 MOB_SPAWNING flag 约束；刷怪蛋/命令生成传 false 只查数量额）
     */
    public boolean mobSpawnAllowed(String worldName, boolean natural) {
        Shelter s = shelterOf(worldName);
        if (s == null) {
            return true;
        }
        if (natural && !Flags.isOn(s, Flag.MOB_SPAWNING)) {
            return false;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return true;
        }
        return core.limits().checkSpawn(s, w) == null; // 生物额（决策 P1）
    }

    /** 爆炸是否允许破坏方块（决策 #78 EXPLOSIONS flag）——原生侧模组爆炸走这里。 */
    public boolean explosionsAllowed(String worldName) {
        Shelter s = shelterOf(worldName);
        return s == null || Flags.isOn(s, Flag.EXPLOSIONS);
    }

    /** 生物是否允许破坏方块（决策：MOB_GRIEFING flag）——原生侧模组生物破坏走这里。 */
    public boolean mobGriefingAllowed(String worldName) {
        Shelter s = shelterOf(worldName);
        return s == null || Flags.isOn(s, Flag.MOB_GRIEFING);
    }

    /** 玩家间伤害是否允许（决策 #9 PvP flag）——原生侧模组武器/远程攻击走这里。 */
    public boolean pvpAllowed(String worldName) {
        Shelter s = shelterOf(worldName);
        return s == null || Flags.isOn(s, Flag.PVP);
    }

    /** 玩家伤害动物是否允许（DAMAGE_ANIMALS flag）——原生侧走这里。 */
    public boolean damageAnimalsAllowed(String worldName) {
        Shelter s = shelterOf(worldName);
        return s == null || Flags.isOn(s, Flag.DAMAGE_ANIMALS);
    }

}
