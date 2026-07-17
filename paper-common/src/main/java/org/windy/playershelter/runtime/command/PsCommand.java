package org.windy.playershelter.runtime.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.windy.playershelter.api.event.ShelterCreateEvent;
import org.windy.playershelter.api.event.ShelterUpgradeEvent;
import org.windy.playershelter.api.event.ShelterVisitEvent;
import org.windy.playershelter.domain.model.GenerationType;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.domain.model.ShelterVisibility;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;
import org.windy.playershelter.runtime.flag.Flag;
import org.windy.playershelter.runtime.flag.Flags;
import org.windy.playershelter.service.ShelterService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@code /ps} 命令总入口（决策 #21 只 /ps / #24 只命令）。子命令分发 + Tab 补全。
 */
public final class PsCommand implements TabExecutor {

    private final PsCore core;
    /** reset 二次确认（决策 #20）：uuid → 待确认请求（含是否保留等级 + 过期时刻）。 */
    private final ConcurrentHashMap<UUID, PendingReset> pendingReset = new ConcurrentHashMap<>();
    private final PsgsCommand migrationCommand;

    /**
     * 子命令 → 所需权限节点（决策 P4 权限门控）。分发前统一校验，让 plugin.yml 里声明的
     * {@code playershelter.command.*} 节点真正生效（此前只声明未检查 = 摆设，任何人都能用）。
     * 全部 default:true，不影响默认玩家；服主把某节点设 false 即可禁用该命令。别名（vis/message）各自映射。
     * <p>不在表内的子命令自行处理：{@code admin} 走 {@link AdminCommand} 内部查 {@code playershelter.admin}；
     * {@code confirm}（reset 二次确认）/ {@code flags}（查看）/ {@code help} 恒放行。
     */
    private static final Map<String, String> COMMAND_PERMS = Map.ofEntries(
            Map.entry("create", "playershelter.command.create"),
            Map.entry("home", "playershelter.command.home"),
            Map.entry("setspawn", "playershelter.command.setspawn"),
            Map.entry("info", "playershelter.command.info"),
            Map.entry("upgrade", "playershelter.command.upgrade"),
            Map.entry("reset", "playershelter.command.reset"),
            Map.entry("visibility", "playershelter.command.visibility"),
            Map.entry("vis", "playershelter.command.visibility"),
            Map.entry("trust", "playershelter.command.trust"),
            Map.entry("untrust", "playershelter.command.untrust"),
            Map.entry("access", "playershelter.command.access"),
            Map.entry("deny", "playershelter.command.deny"),
            Map.entry("undeny", "playershelter.command.undeny"),
            Map.entry("time", "playershelter.command.time"),
            Map.entry("weather", "playershelter.command.weather"),
            Map.entry("top", "playershelter.command.top"),
            Map.entry("tag", "playershelter.command.tag"),
            Map.entry("search", "playershelter.command.search"),
            Map.entry("gui", "playershelter.command.gui"),
            Map.entry("controller", "playershelter.command.gui"),
            Map.entry("board", "playershelter.command.board"),
            Map.entry("msg", "playershelter.command.msg"),
            Map.entry("message", "playershelter.command.msg"),
            Map.entry("visit", "playershelter.command.visit"),
            Map.entry("list", "playershelter.command.list"),
            Map.entry("like", "playershelter.command.like"),
            Map.entry("bulletin", "playershelter.command.bulletin"),
            Map.entry("flag", "playershelter.command.flag"));

    private record PendingReset(boolean keepLevel, long expireAt) {}

    private final org.windy.playershelter.runtime.ShelterRouter router;

    public PsCommand(PsCore core, PsgsCommand migrationCommand) {
        this.core = core;
        this.migrationCommand = migrationCommand;
        this.router = new org.windy.playershelter.runtime.ShelterRouter(core);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        // 决策 P4 权限门控：让 plugin.yml 声明的 command.* 节点真正生效（admin/flag/time/weather/visit 另在各自方法自查）。
        String perm = COMMAND_PERMS.get(sub);
        if (perm != null && !sender.hasPermission(perm)) {
            Messages.errorKey(sender, "command.no-permission-sub", "你没有使用 /ps {command} 的权限。", "command", sub);
            return true;
        }
        switch (sub) {
            case "create" -> create(sender, rest);
            case "home" -> home(sender);
            case "setspawn" -> setspawn(sender);
            case "info" -> info(sender);
            case "upgrade" -> upgrade(sender);
            case "reset" -> reset(sender, rest);
            case "confirm" -> confirm(sender);
            case "visibility", "vis" -> visibility(sender, rest);
            case "trust" -> changeRole(sender, rest, Role.TRUST);
            case "untrust" -> changeRole(sender, rest, Role.CLEAR);
            case "admin" -> adminOrAddAdmin(sender, rest);
            case "access" -> changeRole(sender, rest, Role.ACCESS);
            case "deny" -> changeRole(sender, rest, Role.DENY);
            case "undeny" -> changeRole(sender, rest, Role.CLEAR);
            case "time" -> time(sender, rest);
            case "weather" -> weather(sender, rest);
            case "top" -> top(sender, rest);
            case "tag" -> tag(sender, rest);
            case "search" -> search(sender, rest);
            case "gui", "controller" -> gui(sender);
            case "board" -> board(sender, rest);
            case "msg", "message" -> msg(sender, rest);
            case "migrate" -> migrate(sender, rest);
            case "visit" -> visit(sender, rest);
            case "list" -> list(sender, rest);
            case "like" -> like(sender);
            case "bulletin" -> bulletin(sender, rest);
            case "flag" -> flag(sender, rest);
            case "flags" -> flags(sender);
            case "help" -> help(sender);
            default -> Messages.errorKey(sender, "command.unknown", "未知子命令：{command}，输入 /ps help 查看。", "command", sub);
        }
        return true;
    }

    // —— create（决策 #25 命令带类型 / #5 严格 1 个 / #26 进度条异步 / #42 免费）——

    private void migrate(CommandSender sender, String[] args) {
        if (migrationCommand == null) {
            Messages.errorKey(sender, "psgs.error.gs-api-unavailable", "GuildShelter 迁移命令不可用。");
            return;
        }
        migrationCommand.execute(sender, "/ps migrate", args);
    }

    private void gui(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "player-only", "只有玩家能使用这个命令。");
            return;
        }
        new org.windy.playershelter.runtime.gui.ControllerGui(core).open(p);
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "create.player-only", "只有玩家能创建庇护所。");
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(p, "create.usage", "用法：/ps create <natural|flat|void>");
            return;
        }
        GenerationType type = GenerationType.parse(args[0]);
        if (type == null) {
            Messages.errorKey(p, "create.unknown-type", "未知类型：{type}（可选 natural / flat / void）", "type", args[0]);
            return;
        }
        Messages.infoKey(p, "create.generating", "正在生成你的庇护所世界，请稍候…");
        core.shelters().create(PlayerRef.of(p.getUniqueId()), type, p.getUniqueId(),
                shelter -> {
                    Messages.okKey(p, "create.success", "庇护所创建成功！正在送你回家。");
                    Bukkit.getPluginManager().callEvent(new ShelterCreateEvent(
                            p.getUniqueId(), shelter.worldName(), shelter.genType().name()));
                    goHome(p, shelter);
                },
                () -> Messages.errorKey(p, "create.already-exists", "你已经有一个庇护所了（一人一世界）。用 /ps home 回去，或 /ps reset 推倒重来。"));
    }

    // —— home（决策 #23 /ps home + 出生点）——

    private void home(CommandSender sender) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        Messages.infoKey(p, "home.teleporting", "正在送你回庇护所…");
        goHome(p, s);
    }

    private void goHome(Player p, Shelter s) {
        // 经路由：本服直接传送，他服承载则跨服换服（决策 #59）。
        router.send(p, s, Messages.get("world-load-failed-retry", "世界加载失败，请稍后再试。"));
    }

    // —— setspawn（决策 #23 owner 改落点）——

    private void setspawn(CommandSender sender) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        if (!p.getWorld().getName().equals(s.worldName())) {
            Messages.errorKey(p, "setspawn.must-be-home", "请站在自己的庇护所世界里再设置出生点。");
            return;
        }
        Location loc = p.getLocation();
        p.getWorld().setSpawnLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Messages.okKey(p, "setspawn.success", "已把庇护所出生点设到你脚下。");
    }

    // —— info ——

    private void info(CommandSender sender) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        Messages.plainKey(p, "info.header", "&b==== 你的庇护所 ====");
        // 权限封顶：玩家实际能升到的上限 = min(config level.max, 权限 maxlevel.<n>)；已到上限则显示满级。
        int permCap = permissionMaxLevel(p);
        boolean atCap = s.isMaxLevel() || (permCap >= 0 && s.level() >= permCap);
        Messages.plainKey(p, "info.type-level", "&7类型：&f{type}&7  等级：&f{level}{max}",
                "type", s.genType(), "level", s.level(), "max", atCap ? ChatColor.GOLD + "(满级)" : "");
        Messages.plainKey(p, "info.plot", "&7可用边长：&f{plot} chunk&7（{blocks} 格，面积 {area} chunk）",
                "plot", s.sideChunks() + "x" + s.sideChunks(), "blocks", s.borderSize(), "area", s.areaChunks());
        Messages.plainKey(p, "info.visibility-likes", "&7可见性：&f{visibility}&7  点赞：&f{likes}",
                "visibility", visName(s.visibility()), "likes", s.likes());
        Messages.plainKey(p, "info.members", "&7成员：管理员 {admins} / 共建 {trusted} / 访客 {access} / 黑名单 {denied}",
                "admins", s.admins().size(), "trusted", s.trusted().size(),
                "access", s.access().size(), "denied", s.denied().size());
        if (!atCap) {
            double cost = core.config().upgradeCost(s.level());
            List<ItemCost> items = core.config().upgradeItems(s.level());
            Messages.plainKey(p, "info.upgrade-cost", "&7升级到 {level} 级费用：&f{cost}",
                    "level", s.level() + 1,
                    "cost", cost <= 0 || !core.economy().enabled()
                            ? Messages.get("word.free", "免费") : Long.toString((long) cost));
            if (!items.isEmpty()) {
                Messages.plainKey(p, "info.upgrade-items", "&7升级材料：&f{items}", "items", formatItems(items));
            }
        } else if (permCap >= 0 && s.level() >= permCap && !s.isMaxLevel()) {
            Messages.plainKey(p, "info.permission-cap", "&7已达权限上限 &f{level}&7 级（升更高需更高 playershelter.maxlevel 权限）。",
                    "level", permCap);
        }
    }

    // —— upgrade（决策 #30 升级即扩 / #41 递增收费）——

    private void upgrade(CommandSender sender) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        // 按权限分级封顶（决策 P4）：玩家 playershelter.maxlevel.<n> 的最大 n 即其升级上限；
        // 无此类节点则不额外限制，交给 config level.max 兜底（service 里 layout.maxLevel() 是绝对硬顶）。
        int permCap = permissionMaxLevel(p);
        if (permCap >= 0) {
            Shelter cur = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
            if (cur.level() >= permCap) {
                Messages.warnKey(p, "upgrade.permission-cap",
                        "你已达到权限允许的最高等级 {level} 级（升更高需要更高的 playershelter.maxlevel 权限）。",
                        "level", permCap);
                return;
            }
        }
        ShelterService.UpgradeResult r = core.shelters().upgrade(PlayerRef.of(p.getUniqueId()));
        switch (r) {
            case NO_MATERIALS -> Messages.errorKey(p, "upgrade.no-materials", "升级材料不足，无法升级。");
            case OK -> {
                Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
                Bukkit.getPluginManager().callEvent(new ShelterUpgradeEvent(
                        p.getUniqueId(), s.worldName(), s.level(), s.borderSize()));
                Messages.okKey(p, "upgrade.success",
                        "升级成功！现在是 {level} 级，可用地块边长 {chunks} chunk（{blocks} 格）。",
                        "level", s.level(), "chunks", s.sideChunks(), "blocks", s.borderSize());
            }
            case MAX_LEVEL -> Messages.warnKey(p, "upgrade.max-level", "你的庇护所已经满级了。");
            case NO_FUNDS -> Messages.errorKey(p, "upgrade.no-funds", "余额不足，无法升级。");
            case NO_SHELTER -> Messages.errorKey(p, "no-shelter", "你还没有庇护所。");
        }
    }

    /**
     * 玩家的权限升级上限：取其所有 {@code playershelter.maxlevel.<n>} 节点里最大的 {@code n}；
     * 没有任何此类节点返回 {@code -1}（不额外限制，用 config {@code level.max} 兜底）。
     * 用 {@link Player#getEffectivePermissions()} 枚举，兼容 LuckPerms 等权限组授予（不依赖 plugin.yml 预声明）。
     */
    private static String formatItems(List<ItemCost> items) {
        return items.stream()
                .filter(ItemCost::valid)
                .map(i -> i.itemId() + " x" + i.amount())
                .collect(Collectors.joining(", "));
    }

    private int permissionMaxLevel(Player p) {
        final String prefix = "playershelter.maxlevel.";
        int best = -1;
        for (org.bukkit.permissions.PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
            if (!pai.getValue()) {
                continue;
            }
            String node = pai.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (node.startsWith(prefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 忽略 playershelter.maxlevel.* 等非数字尾巴
                }
            }
        }
        return best;
    }

    // —— reset（决策 #20 双确认 / #77 冷却限额）——

    private void reset(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        boolean keep = args.length > 0 && (args[0].equalsIgnoreCase("keep") || args[0].equalsIgnoreCase("keeplevel"));
        pendingReset.put(p.getUniqueId(), new PendingReset(keep, System.currentTimeMillis() + 30_000));
        Messages.warnKey(p, keep ? "reset.warning-keep" : "reset.warning",
                keep
                        ? ChatColor.RED + "警告：" + ChatColor.YELLOW + "这会" + ChatColor.RED + "彻底抹掉"
                        + ChatColor.YELLOW + "你庇护所的地形并重新生成，建筑无法找回！（保留等级与边界）"
                        : ChatColor.RED + "警告：" + ChatColor.YELLOW + "这会" + ChatColor.RED + "彻底抹掉"
                        + ChatColor.YELLOW + "你庇护所的地形并重新生成，建筑无法找回！（等级也将重置为 1）");
        Messages.warnKey(p, "reset.confirm-hint", "30 秒内输入 &f/ps confirm &e确认，或忽略本消息取消。");
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return;
        }
        PendingReset req = pendingReset.remove(p.getUniqueId());
        if (req == null || System.currentTimeMillis() > req.expireAt()) {
            Messages.infoKey(p, "reset.no-pending", "没有待确认的操作。");
            return;
        }
        Messages.infoKey(p, "reset.starting", "正在重置你的庇护所世界…");
        ShelterService.ResetResult r = core.shelters().reset(PlayerRef.of(p.getUniqueId()), req.keepLevel(),
                p.getUniqueId(),
                ready -> {
                    Messages.okKey(p, "reset.success", "庇护所已重置。正在送你回家。");
                    goHome(p, ready);
                },
                () -> Messages.errorKey(p, "reset.failed", "重置失败。"));
        switch (r) {
            case COOLDOWN -> Messages.errorKey(p, "reset.cooldown",
                    "重置太频繁了，请稍后再试（冷却 {hours} 小时）。",
                    "hours", core.config().resetCooldownHours());
            case DAILY_LIMIT -> Messages.errorKey(p, "reset.daily-limit",
                    "今天的重置次数已用完（每天 {max} 次）。",
                    "max", core.config().resetMaxPerDay());
            case NO_SHELTER -> Messages.errorKey(p, "no-shelter", "你还没有庇护所。");
            case STARTED -> { /* 异步回调里已提示 */ }
        }
    }

    // —— visibility（决策 #13 / #71 门槛）——

    private void visibility(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(p, "visibility.usage", "用法：/ps visibility <private|friends|public>");
            return;
        }
        ShelterVisibility v = ShelterVisibility.parse(args[0]);
        if (v == null) {
            Messages.errorKey(p, "visibility.unknown", "未知可见性：{value}", "value", args[0]);
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        if (v.isPublic() && !core.shelters().canGoPublic(s)) {
            Messages.errorKey(p, "visibility.public-level-required", "公开庇护所需要达到 {level} 级。",
                    "level", core.config().publicMinLevel());
            return;
        }
        core.shelters().setVisibility(PlayerRef.of(p.getUniqueId()), v);
        Messages.okKey(p, "visibility.success", "可见性已设为 {visibility}。", "visibility", visName(v));
    }

    // —— 四级身份管理（决策 #16/#34）——

    private enum Role { TRUST, ACCESS, DENY, CLEAR, ADMIN_ROLE }

    /** /ps admin 既是「加副主人」也是「管理面板」入口：带玩家名=加副主人，否则进 admin 子命令。 */
    private void adminOrAddAdmin(CommandSender sender, String[] args) {
        if (args.length >= 1 && AdminCommand.isAdminAction(args[0])) {
            new AdminCommand(core).handle(sender, args);
            return;
        }
        changeRole(sender, args, Role.ADMIN_ROLE);
    }

    private void changeRole(CommandSender sender, String[] args, Role role) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(p, "role.usage", "用法：/ps <trust|untrust|admin|access|deny|undeny> <玩家>");
            return;
        }
        OfflinePlayer target = resolveTarget(args[0]);
        if (target == null || target.getUniqueId().equals(p.getUniqueId())) {
            Messages.errorKey(p, "role.invalid-target", "找不到该玩家，或不能对自己操作。");
            return;
        }
        PlayerRef owner = PlayerRef.of(p.getUniqueId());
        PlayerRef tgt = PlayerRef.of(target.getUniqueId());
        switch (role) {
            case TRUST -> {
                if (core.shelters().addTrusted(owner, tgt)) {
                    Messages.okKey(p, "role.trust-success", "已把 {player} 设为共建人（可建造）。", "player", args[0]);
                } else {
                    Shelter sh = core.repo().find(owner).orElseThrow();
                    Messages.errorKey(p, "role.trust-cap", "共建名额已满（上限 {cap}，升级可增加）。",
                            "cap", core.config().trustCapAt(sh.level()));
                }
            }
            case ACCESS -> {
                core.shelters().addAccess(owner, tgt);
                Messages.okKey(p, "role.access-success", "已允许 {player} 进入参观。", "player", args[0]);
            }
            case DENY -> {
                core.shelters().deny(owner, tgt);
                Messages.okKey(p, "role.deny-success", "已把 {player} 加入黑名单（禁止进入）。", "player", args[0]);
            }
            case CLEAR -> {
                core.shelters().clearRole(owner, tgt);
                Messages.okKey(p, "role.clear-success", "已移除 {player} 的身份。", "player", args[0]);
            }
            case ADMIN_ROLE -> {
                boolean ok = core.shelters().addAdmin(owner, tgt);
                if (ok) {
                    Messages.okKey(p, "role.admin-success", "已把 {player} 设为管理员（副主人）。", "player", args[0]);
                } else {
                    Shelter sh = core.repo().find(owner).orElseThrow();
                    Messages.errorKey(p, "role.admin-cap", "管理员名额已满（上限 {cap}，升级可增加）。",
                            "cap", core.config().adminCapAt(sh.level()));
                }
            }
        }
    }

    // —— visit（决策 #37 / #15 离线可进按 flag / #36 denied 硬拦）——

    private void visit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "visit.player-only", "只有玩家能串门。");
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(p, "visit.usage", "用法：/ps visit <玩家>");
            return;
        }
        OfflinePlayer target = resolveTarget(args[0]);
        if (target == null) {
            Messages.errorKey(p, "target.not-found", "找不到该玩家。");
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(target.getUniqueId())).orElse(null);
        if (s == null) {
            Messages.errorKey(p, "target.no-shelter", "{player} 还没有庇护所。", "player", args[0]);
            return;
        }
        ShelterRole role = s.resolveRole(PlayerRef.of(p.getUniqueId()));
        boolean bypass = p.hasPermission("playershelter.admin.visit.any");
        if (!role.canEnter() && !bypass) {
            Messages.errorKey(p, "visit.not-open", "对方的庇护所不对你开放。");
            return;
        }
        // 可取消事件：附属可据自定义规则拦截访问（决策 #79）。
        ShelterVisitEvent ve = new ShelterVisitEvent(p.getUniqueId(), s.owner().uuid(), s.worldName());
        Bukkit.getPluginManager().callEvent(ve);
        if (ve.isCancelled()) {
            Messages.errorKey(p, "visit.cancelled", "访问被拦截。");
            return;
        }
        Messages.infoKey(p, "visit.start", "正在前往 {player} 的庇护所…", "player", args[0]);
        router.send(p, s, Messages.get("visit.target-load-failed", "对方世界加载失败。")); // 本服传送 / 跨服换服（决策 #59）
    }

    // —— list（决策 #14/#38 目录 + 排序）——

    private void list(CommandSender sender, String[] args) {
        org.windy.playershelter.domain.port.DirectoryPort.Sort sort =
                org.windy.playershelter.domain.port.DirectoryPort.Sort.HOT;
        int page = 1;
        if (args.length >= 1) {
            sort = switch (args[0].toLowerCase()) {
                case "likes", "赞" -> org.windy.playershelter.domain.port.DirectoryPort.Sort.LIKES;
                case "new", "newest", "最新" -> org.windy.playershelter.domain.port.DirectoryPort.Sort.NEWEST;
                case "random", "随机" -> org.windy.playershelter.domain.port.DirectoryPort.Sort.RANDOM;
                default -> org.windy.playershelter.domain.port.DirectoryPort.Sort.HOT;
            };
        }
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                // 默认第 1 页
            }
        }
        int pageSize = 8;
        List<Shelter> rows = core.directory().list(sort, (page - 1) * pageSize, pageSize);
        if (rows.isEmpty()) {
            Messages.infoKey(sender, "list.empty", "暂无公开庇护所。");
            return;
        }
        Messages.plainKey(sender, "list.header", "&b==== 公开庇护所目录（{sort} · 第 {page} 页）====",
                "sort", sortName(sort), "page", page);
        for (Shelter s : rows) {
            String ownerName = Bukkit.getOfflinePlayer(s.owner().uuid()).getName();
            Messages.plainKey(sender, "list.row", "&f{name}&7 · {level} 级 · &6♥{likes}{bulletin}",
                    "name", ownerName == null ? "?" : ownerName,
                    "level", s.level(),
                    "likes", s.likes(),
                    "bulletin", s.bulletin().isEmpty() ? "" : ChatColor.GRAY + " · " + s.bulletin());
        }
        Messages.infoKey(sender, "list.hint", "用 /ps visit <玩家> 串门，/ps like 在对方世界点赞。");
    }

    // —— like（决策 #39 站在对方世界里点赞，每人一次）——

    private void like(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return;
        }
        String world = p.getWorld().getName();
        Shelter s = core.repo().findByWorldName(world).orElse(null);
        if (s == null) {
            Messages.errorKey(p, "like.must-be-in-shelter", "请站在想点赞的庇护所世界里再点赞。");
            return;
        }
        if (s.owner().uuid().equals(p.getUniqueId())) {
            Messages.warnKey(p, "like.self", "不能给自己的庇护所点赞。");
            return;
        }
        boolean ok = core.directory().like(s.owner(), PlayerRef.of(p.getUniqueId()));
        if (ok) {
            Messages.okKey(p, "like.success", "点赞成功！❤");
        } else {
            Messages.infoKey(p, "like.already", "你已经赞过这个庇护所了。");
        }
    }

    // —— bulletin（公开目录展示语）——

    private void bulletin(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        String text = String.join(" ", args).trim();
        if (text.length() > 64) {
            text = text.substring(0, 64);
        }
        core.repo().save(s.withBulletin(text));
        if (text.isEmpty()) {
            Messages.okKey(p, "bulletin.clear", "已清空公告。");
        } else {
            Messages.okKey(p, "bulletin.update", "公告已更新：{text}", "text", text);
        }
    }

    // —— flag（决策 #33 设置 flag）——

    private void flag(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        if (args.length < 2) {
            Messages.warnKey(p, "flag.usage", "用法：/ps flag <flag> <on|off>，用 /ps flags 看全部。");
            return;
        }
        Flag flag = Flag.byId(args[0]).orElse(null);
        if (flag == null) {
            Messages.errorKey(p, "flag.unknown", "未知 flag：{flag}（用 /ps flags 查看可用项）", "flag", args[0]);
            return;
        }
        // per-flag 权限节点（决策 P4）：服主可用 playershelter.flag.<id> 限制谁能设某 flag。
        // 默认全放行（plugin.yml 里 default: true）；负号收紧即可。
        if (!p.hasPermission("playershelter.flag." + flag.id())) {
            Messages.errorKey(p, "flag.no-permission", "你没有设置 {flag} 的权限。", "flag", flag.id());
            return;
        }
        Boolean value = parseOnOff(args[1]);
        if (value == null) {
            Messages.errorKey(p, "flag.on-off", "请用 on 或 off。");
            return;
        }
        core.shelters().setFlag(PlayerRef.of(p.getUniqueId()), flag.id(), value);
        Messages.okKey(p, "flag.set", "已将 &f{flag}&a 设为 {value}（{description}）。",
                "flag", flag.id(),
                "value", value ? Messages.get("word.on", "开") : Messages.get("word.off", "关"),
                "description", flagDescription(flag));
    }

    private void flags(CommandSender sender) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(p.getUniqueId())).orElseThrow();
        Messages.plainKey(p, "flags.header", "&b==== 庇护所 flag ====");
        for (Flag f : Flag.values()) {
            boolean on = Flags.isOn(s, f);
            Messages.plainKey(p, "flags.row", "{state}&f {flag}&7 — {description}",
                    "state", on ? ChatColor.GREEN + "[" + Messages.get("word.on", "开") + "]"
                            : ChatColor.RED + "[" + Messages.get("word.off", "关") + "]",
                    "flag", f.id(), "description", flagDescription(f));
        }
        Messages.infoKey(p, "flags.hint", "用 /ps flag <flag> <on|off> 修改。");
    }

    private Boolean parseOnOff(String s) {
        return switch (s.toLowerCase()) {
            case "on", "true", "yes", "开", "1" -> Boolean.TRUE;
            case "off", "false", "no", "关", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    // —— time / weather（决策 P2 owner 掌控家园）——

    /** 只有 owner/admin(副主人) 能改；须站在自己庇护所世界里。返回该世界或 null（已提示）。 */
    private World requireManagedWorld(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "managed-world.player-only", "只有玩家能用这个命令。");
            return null;
        }
        Shelter s = core.repo().findByWorldName(p.getWorld().getName()).orElse(null);
        if (s == null) {
            Messages.errorKey(p, "managed-world.must-be-in-shelter", "请站在庇护所世界里再操作。");
            return null;
        }
        if (!s.resolveRole(PlayerRef.of(p.getUniqueId())).canManage()
                && !p.hasPermission("playershelter.admin")) {
            Messages.errorKey(p, "managed-world.no-permission", "只有庄主或管理员能改这里的时间/天气。");
            return null;
        }
        return p.getWorld();
    }

    private void time(CommandSender sender, String[] args) {
        World w = requireManagedWorld(sender);
        if (w == null) {
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(sender, "time.usage", "用法：/ps time <day|noon|night|midnight|数字>");
            return;
        }
        Long t = switch (args[0].toLowerCase()) {
            case "day", "白天" -> 1000L;
            case "noon", "正午" -> 6000L;
            case "night", "夜晚" -> 13000L;
            case "midnight", "午夜" -> 18000L;
            default -> {
                try {
                    yield Long.parseLong(args[0]);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
        if (t == null) {
            Messages.errorKey(sender, "time.unknown", "无法识别的时间：{value}", "value", args[0]);
            return;
        }
        w.setTime(t);
        Messages.okKey(sender, "time.success", "已把庇护所时间设为 {value}。", "value", args[0]);
    }

    private void weather(CommandSender sender, String[] args) {
        World w = requireManagedWorld(sender);
        if (w == null) {
            return;
        }
        if (args.length < 1) {
            Messages.warnKey(sender, "weather.usage", "用法：/ps weather <clear|rain|thunder>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "clear", "晴" -> {
                w.setStorm(false);
                w.setThundering(false);
                Messages.okKey(sender, "weather.clear", "已放晴。");
            }
            case "rain", "雨" -> {
                w.setStorm(true);
                w.setThundering(false);
                Messages.okKey(sender, "weather.rain", "已下雨。");
            }
            case "thunder", "雷" -> {
                w.setStorm(true);
                w.setThundering(true);
                Messages.okKey(sender, "weather.thunder", "已雷暴。");
            }
            default -> Messages.errorKey(sender, "weather.unknown", "无法识别的天气：{value}", "value", args[0]);
        }
    }

    // —— top 排行榜（决策 #23/P3）——

    private void top(CommandSender sender, String[] args) {
        String kind = args.length >= 1 ? args[0].toLowerCase() : "level";
        boolean byLikes = kind.equals("likes") || kind.equals("赞") || kind.equals("点赞");
        List<Shelter> rows = byLikes ? core.directory().topByLikes(10) : core.directory().topByLevel(10);
        Messages.plainKey(sender, "top.header", "&b==== 庇护所{kind} Top 10 ====",
                "kind", byLikes ? Messages.get("top.kind-likes", "点赞榜") : Messages.get("top.kind-level", "等级榜"));
        if (rows.isEmpty()) {
            Messages.infoKey(sender, "top.empty", "暂无数据。");
            return;
        }
        int rank = 1;
        for (Shelter s : rows) {
            String name = Bukkit.getOfflinePlayer(s.owner().uuid()).getName();
            String metric = byLikes ? (ChatColor.GOLD + "♥" + s.likes()) : (ChatColor.GREEN + "Lv." + s.level());
            Messages.plainKey(sender, "top.row", "&7#{rank} &f{name} {metric}&7 {extra}",
                    "rank", rank,
                    "name", name == null ? "?" : name,
                    "metric", metric,
                    "extra", byLikes ? "(Lv." + s.level() + ")" : "(♥" + s.likes() + ")");
            rank++;
        }
        Messages.infoKey(sender, "top.hint", "用 /ps top likes 看点赞榜，/ps top level 看等级榜。");
    }

    // —— tag 标签（决策 P3）——

    private void tag(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        PlayerRef owner = PlayerRef.of(p.getUniqueId());
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> tags = core.tags().tagsOf(owner);
            if (tags.isEmpty()) {
                Messages.infoKey(p, "tag.empty", "你还没打标签。用 /ps tag add <标签>。");
            } else {
                Messages.infoKey(p, "tag.list", "你的标签：&f#{tags}", "tags", String.join(" #", tags));
            }
            return;
        }
        String action = args[0].toLowerCase();
        if (args.length < 2) {
            Messages.warnKey(p, "tag.usage", "用法：/ps tag <add|remove> <标签>");
            return;
        }
        String t = args[1];
        switch (action) {
            case "add", "加" -> {
                if (core.tags().tagsOf(owner).size() >= 8) {
                    Messages.errorKey(p, "tag.cap", "标签最多 {max} 个。", "max", 8);
                    return;
                }
                if (core.tags().add(owner, t)) {
                    Messages.okKey(p, "tag.add-success", "已添加标签 #{tag}", "tag", t);
                } else {
                    Messages.okKey(p, "tag.add-exists", "已有该标签。");
                }
            }
            case "remove", "del", "删" -> {
                if (core.tags().remove(owner, t)) {
                    Messages.okKey(p, "tag.remove-success", "已移除标签 #{tag}", "tag", t);
                } else {
                    Messages.okKey(p, "tag.remove-missing", "没有该标签。");
                }
            }
            default -> Messages.warnKey(p, "tag.usage", "用法：/ps tag <add|remove> <标签>");
        }
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length < 1) {
            Messages.warnKey(sender, "search.usage", "用法：/ps search <标签>（如 /ps search 现代）");
            return;
        }
        List<Shelter> rows = core.directory().searchByTag(args[0], 0, 10);
        if (rows.isEmpty()) {
            Messages.infoKey(sender, "search.empty", "没有标签 #{tag} 的公开庇护所。", "tag", args[0]);
            return;
        }
        Messages.plainKey(sender, "search.header", "&b==== 标签 #{tag} 的庇护所 ====", "tag", args[0]);
        for (Shelter s : rows) {
            String name = Bukkit.getOfflinePlayer(s.owner().uuid()).getName();
            Messages.plainKey(sender, "search.row", "&f{name}&7 · Lv.{level} · &6♥{likes}",
                    "name", name == null ? "?" : name, "level", s.level(), "likes", s.likes());
        }
        Messages.infoKey(sender, "search.hint", "用 /ps visit <玩家> 串门。");
    }

    // —— board 留言板（决策 P3）——

    /** /ps board [页] 看自己庇护所的留言；/ps board clear 清空；/ps board del <id> 删一条。 */
    private void board(CommandSender sender, String[] args) {
        Player p = requirePlayerWithShelter(sender);
        if (p == null) {
            return;
        }
        PlayerRef owner = PlayerRef.of(p.getUniqueId());
        if (args.length >= 1 && args[0].equalsIgnoreCase("clear")) {
            core.board().clear(owner);
            Messages.okKey(p, "board.clear", "已清空留言板。");
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("del")) {
            try {
                boolean ok = core.board().delete(owner, Long.parseLong(args[1]));
                if (ok) {
                    Messages.okKey(p, "board.delete-success", "已删除该留言。");
                } else {
                    Messages.okKey(p, "board.delete-missing", "没有这条留言。");
                }
            } catch (NumberFormatException e) {
                Messages.errorKey(p, "board.id-number", "留言 id 必须是数字。");
            }
            return;
        }
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException ignored) {
                // 默认第 1 页
            }
        }
        int pageSize = 8;
        List<org.windy.playershelter.domain.port.MessageBoardStore.Message> msgs =
                core.board().list(owner, (page - 1) * pageSize, pageSize);
        Messages.plainKey(p, "board.header", "&b==== 留言板（第 {page} 页，共 {count} 条）====",
                "page", page, "count", core.board().count(owner));
        if (msgs.isEmpty()) {
            Messages.infoKey(p, "board.empty", "还没有留言。");
            return;
        }
        for (var m : msgs) {
            Messages.plainKey(p, "board.row", "&7[{id}] &f{author}&7：&r{text}",
                    "id", m.id(), "author", m.authorName(), "text", m.text());
        }
        Messages.infoKey(p, "board.hint", "用 /ps board del <id> 删留言，/ps board clear 清空。");
    }

    /** /ps msg <玩家> <文字> 给某玩家的公开庇护所留言。 */
    private void msg(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "msg.player-only", "只有玩家能留言。");
            return;
        }
        if (args.length == 0) {
            Shelter current = core.repo().findByWorldName(p.getWorld().getName()).orElse(null);
            if (current == null) {
                Messages.warnKey(p, "msg.usage", "用法：/ps msg <玩家> <留言内容>");
                return;
            }
            openMessageInput(p, current.owner().uuid(), ownerName(current));
            return;
        }
        if (args.length == 1) {
            OfflinePlayer target = resolveTarget(args[0]);
            if (target == null) {
                Messages.errorKey(p, "target.not-found", "找不到该玩家。");
                return;
            }
            openMessageInput(p, target.getUniqueId(), args[0]);
            return;
        }
        OfflinePlayer target = resolveTarget(args[0]);
        if (target == null) {
            Messages.errorKey(p, "target.not-found", "找不到该玩家。");
            return;
        }
        Shelter s = core.repo().find(PlayerRef.of(target.getUniqueId())).orElse(null);
        if (s == null) {
            Messages.errorKey(p, "target.no-shelter", "{player} 还没有庇护所。", "player", args[0]);
            return;
        }
        // 只能给公开或自己有权访问的庇护所留言（防骚扰私密世界）。
        ShelterRole role = s.resolveRole(PlayerRef.of(p.getUniqueId()));
        if (!s.visibility().isPublic() && !role.canEnter()) {
            Messages.errorKey(p, "msg.not-open", "对方庇护所不公开，无法留言。");
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            Messages.warnKey(p, "msg.self", "不用给自己留言，/ps board 直接看。");
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        if (text.length() > 256) {
            text = text.substring(0, 256);
        }
        core.board().post(PlayerRef.of(target.getUniqueId()), PlayerRef.of(p.getUniqueId()), p.getName(), text);
        Messages.okKey(p, "msg.success", "已给 {player} 的留言板留言。", "player", args[0]);
    }

    private void openMessageInput(Player player, UUID targetOwner, String targetName) {
        if (targetOwner.equals(player.getUniqueId())) {
            Messages.warnKey(player, "msg.self", "不用给自己留言，/ps board 直接看。");
            return;
        }
        Shelter shelter = core.repo().find(PlayerRef.of(targetOwner)).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "target.no-shelter", "{player} 还没有庇护所。", "player", targetName);
            return;
        }
        ShelterRole role = shelter.resolveRole(PlayerRef.of(player.getUniqueId()));
        if (!shelter.visibility().isPublic() && !role.canEnter()) {
            Messages.errorKey(player, "msg.not-open", "对方庇护所不公开，无法留言。");
            return;
        }
        org.windy.playershelter.runtime.gui.MessageInputGui.open(player, targetOwner, targetName);
    }

    private String ownerName(Shelter shelter) {
        String name = Bukkit.getOfflinePlayer(shelter.owner().uuid()).getName();
        return name == null || name.isBlank() ? Messages.get("word.some-player", "某位玩家") : name;
    }

    // —— help ——

    private void help(CommandSender sender) {
        Messages.plainKey(sender, "help.header", "&b==== PlayerShelter 一人一世界 ====");
        Messages.plainKey(sender, "help.create", "&f/ps create <natural|flat|void> &7创建你的庇护所");
        Messages.plainKey(sender, "help.home", "&f/ps home &7回家");
        Messages.plainKey(sender, "help.setspawn", "&f/ps setspawn &7把出生点设到脚下");
        Messages.plainKey(sender, "help.info", "&f/ps info &7查看庇护所信息");
        Messages.plainKey(sender, "help.gui", "&f/ps gui &7打开庇护所控制器");
        Messages.plainKey(sender, "help.upgrade", "&f/ps upgrade &7升级（扩大边界）");
        Messages.plainKey(sender, "help.reset", "&f/ps reset [keep] &7推倒重来（双重确认）");
        Messages.plainKey(sender, "help.visibility", "&f/ps visibility <private|friends|public> &7设置可见性");
        Messages.plainKey(sender, "help.role", "&f/ps trust|untrust|admin|access|deny|undeny <玩家> &7管理成员");
        Messages.plainKey(sender, "help.visit", "&f/ps visit <玩家> &7串门");
        Messages.plainKey(sender, "help.list", "&f/ps list [likes|hot|new|random] &7公开目录");
        Messages.plainKey(sender, "help.top", "&f/ps top [level|likes] &7排行榜");
        Messages.plainKey(sender, "help.tag", "&f/ps tag <add|remove> <标签> · /ps search <标签> &7标签与搜索");
        Messages.plainKey(sender, "help.board", "&f/ps board · /ps msg <玩家> <文字> &7留言板");
        Messages.plainKey(sender, "help.like", "&f/ps like &7给所在庇护所点赞");
        Messages.plainKey(sender, "help.bulletin", "&f/ps bulletin <文字> &7设置公告");
        Messages.plainKey(sender, "help.flag", "&f/ps flag <flag> <on|off> · /ps flags &7设置/查看保护开关");
        Messages.plainKey(sender, "help.time-weather", "&f/ps time <day|night|...> · /ps weather <clear|rain|thunder> &7掌控家园时间天气");
        if (sender.hasPermission("playershelter.admin") || sender.hasPermission("psgs.admin")) {
            Messages.plainKey(sender, "help.migrate", "&f/ps migrate <gs-to-ps|ps-to-gs|confirm|cancel> &7迁移 PS/GS 区域（ps-to-gs 成功后备份并删除源 PS）");
        }
        if (sender.hasPermission("playershelter.admin")) {
            Messages.plainKeyOnly(sender, "help.admin");
        }
    }

    // —— 工具 ——

    private Player requirePlayerWithShelter(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Messages.errorKey(sender, "player-only", "只有玩家能使用这个命令。");
            return null;
        }
        if (core.repo().find(PlayerRef.of(p.getUniqueId())).isEmpty()) {
            Messages.errorKey(p, "no-shelter-with-create", "你还没有庇护所，用 /ps create <类型> 创建一个。");
            return null;
        }
        return p;
    }

    private static String flagDescription(Flag flag) {
        return Messages.get("flag-description." + flag.id(), flag.description());
    }

    private OfflinePlayer resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return (off.hasPlayedBefore() || off.isOnline()) ? off : null;
    }

    private static String visName(ShelterVisibility v) {
        return switch (v) {
            case PRIVATE -> Messages.get("visibility.private", "私密");
            case FRIENDS -> Messages.get("visibility.friends", "好友");
            case PUBLIC -> Messages.get("visibility.public", "公开");
        };
    }

    private static String sortName(org.windy.playershelter.domain.port.DirectoryPort.Sort s) {
        return switch (s) {
            case LIKES -> Messages.get("sort.likes", "点赞");
            case HOT -> Messages.get("sort.hot", "热度");
            case RANDOM -> Messages.get("sort.random", "随机");
            case NEWEST -> Messages.get("sort.newest", "最新");
        };
    }

    // —— Tab 补全 ——

    private static final List<String> SUBS = Arrays.asList(
            "create", "home", "setspawn", "info", "upgrade", "reset", "confirm",
            "visibility", "trust", "untrust", "admin", "migrate", "access", "deny", "undeny",
            "time", "weather", "top", "tag", "search", "gui", "controller", "board", "msg",
            "visit", "list", "like", "bulletin", "flag", "flags", "help");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("migrate")) {
            return migrationCommand == null
                    ? List.of()
                    : migrationCommand.onTabComplete(sender, command, alias,
                    Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2) {
            switch (sub) {
                case "create":
                    return prefix(Arrays.asList("natural", "flat", "void"), args[1]);
                case "visibility": case "vis":
                    return prefix(Arrays.asList("private", "friends", "public"), args[1]);
                case "list":
                    return prefix(Arrays.asList("hot", "likes", "new", "random"), args[1]);
                case "flag":
                    return prefix(Arrays.stream(Flag.values()).map(Flag::id).collect(Collectors.toList()), args[1]);
                case "time":
                    return prefix(Arrays.asList("day", "noon", "night", "midnight"), args[1]);
                case "weather":
                    return prefix(Arrays.asList("clear", "rain", "thunder"), args[1]);
                case "top":
                    return prefix(Arrays.asList("level", "likes"), args[1]);
                case "tag":
                    return prefix(Arrays.asList("add", "remove", "list"), args[1]);
                case "board":
                    return prefix(Arrays.asList("clear", "del"), args[1]);
                case "msg", "message":
                    return onlineNames(args[1]);
                case "admin": {
                    // /ps admin 既能加副主人（玩家名），也能进管理子命令（动作）→ 两者都提示。
                    List<String> opts = new ArrayList<>(AdminCommand.actions());
                    opts.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                    return prefix(opts, args[1]);
                }
                case "trust": case "untrust": case "access": case "deny": case "undeny": case "visit":
                    return onlineNames(args[1]);
                default:
                    return List.of();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("flag")) {
            return prefix(Arrays.asList("on", "off"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && AdminCommand.isAdminAction(args[1])
                && !List.of("list", "disk", "perf", "reload").contains(args[1].toLowerCase())) {
            return onlineNames(args[2]); // /ps admin <action> <玩家>
        }
        return List.of();
    }

    private List<String> onlineNames(String pre) {
        return prefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), pre);
    }

    private List<String> prefix(List<String> opts, String pre) {
        String low = pre.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(low)) {
                out.add(o);
            }
        }
        return out;
    }
}
