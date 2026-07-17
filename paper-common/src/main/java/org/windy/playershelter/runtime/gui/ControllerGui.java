package org.windy.playershelter.runtime.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.windy.playershelter.adapter.limit.ShelterCensus;
import org.windy.playershelter.api.event.ShelterVisitEvent;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.model.ShelterRole;
import org.windy.playershelter.domain.port.DirectoryPort;
import org.windy.playershelter.domain.port.MessageBoardStore;
import org.windy.playershelter.runtime.flag.Flag;
import org.windy.playershelter.runtime.flag.Flags;
import org.windy.playershelter.runtime.Messages;
import org.windy.playershelter.runtime.PsCore;
import org.windy.playershelter.runtime.ShelterRouter;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** YAML-driven shelter controller GUI. */
public final class ControllerGui implements Listener {

    private final PsCore core;
    private final ShelterRouter router;
    private static final Map<UUID, Holder> OPEN = new HashMap<>();
    private static final Map<UUID, Prompt> PENDING_PROMPTS = new ConcurrentHashMap<>();
    private static final int DIRECTORY_PAGE_SIZE = 21;
    private static final int[] DIRECTORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int BOARD_PAGE_SIZE = 21;
    private static final int[] BOARD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int TAG_PAGE_SIZE = 21;
    private static final int[] TAG_SLOTS = {
            10, 11, 12, 13, 14, 15, 16, 19
    };
    private static final DateTimeFormatter BOARD_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public ControllerGui(PsCore core) {
        this.core = core;
        this.router = new ShelterRouter(core);
    }

    public void open(Player player) {
        Shelter current = core.repo().findByWorldName(player.getWorld().getName()).orElse(null);
        if (current != null && !current.owner().uuid().equals(player.getUniqueId())) {
            open(player, "visitor", current);
            return;
        }
        Shelter own = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (own == null) {
            if (current != null) {
                open(player, "visitor", current);
                return;
            }
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        open(player, "shelter_controller", own);
    }

    private void openOwn(Player player) {
        Shelter own = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (own == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        open(player, "shelter_controller", own);
    }

    private void open(Player player, String menuId, Shelter shelter) {
        String safeMenu = menuId.replace('\\', '/').replace("..", "");
        File file = menuFile(safeMenu);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = Math.max(9, Math.min(54, ((yaml.getInt("size", 54) + 8) / 9) * 9));
        String title = apply(yaml.getString("title", "&0庇护所控制器"), player, shelter);

        Holder holder = new Holder(shelter);
        Inventory inv = Bukkit.createInventory(holder, size, Messages.component(title));
        holder.inventory = inv;

        Material pane = material(yaml.getString("pane-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        if (pane != Material.AIR) {
            ItemStack filler = named(pane, " ");
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int slot = sec.getInt("index", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                Material mat = material(sec.getString("material", "STONE"), Material.STONE);
                String name = apply(sec.getString("name", key), player, shelter);
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(apply(line, player, shelter));
                }
                inv.setItem(slot, item(mat, name, lore));
                holder.actions.put(slot, apply(sec.getString("action", ""), player, shelter));
            }
        }

        OPEN.put(player.getUniqueId(), holder);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Holder holder = OPEN.get(player.getUniqueId());
        if (holder == null || event.getInventory().getHolder() != holder) {
            return;
        }
        event.setCancelled(true);
        String action = holder.actions.getOrDefault(event.getRawSlot(), "");
        if (action == null || action.isBlank()) {
            return;
        }
        player.closeInventory();
        if ("close".equalsIgnoreCase(action)) {
            return;
        }
        if (action.startsWith("menu:")) {
            String menu = action.substring("menu:".length()).trim();
            if (!menu.isBlank()) {
                if ("own_controller".equalsIgnoreCase(menu)) {
                    openOwn(player);
                } else if ("tags".equalsIgnoreCase(menu)) {
                    openTags(player);
                } else {
                    open(player, menu, holder.context);
                }
            }
            return;
        }
        if (action.startsWith("message:")) {
            openMessageInput(player, holder.context);
            return;
        }
        if (action.startsWith("directory:")) {
            openDirectory(player, holder.context, action.substring("directory:".length()));
            return;
        }
        if (action.startsWith("prompt:")) {
            String prompt = action.substring("prompt:".length()).trim();
            if (!prompt.isBlank()) {
                beginPrompt(player, prompt);
            }
            return;
        }
        if (action.startsWith("board:")) {
            openBoard(player, parsePage(action.substring("board:".length())));
            return;
        }
        if (action.startsWith("board-delete:")) {
            deleteBoardMessage(player, action.substring("board-delete:".length()));
            return;
        }
        if (action.startsWith("board-clear:")) {
            clearBoard(player);
            return;
        }
        if (action.startsWith("tag-add")) {
            beginPrompt(player, Prompt.TAG_ADD.id);
            return;
        }
        if (action.startsWith("tag-search")) {
            beginPrompt(player, Prompt.TAG_SEARCH.id);
            return;
        }
        if (action.startsWith("tag-remove:")) {
            removeTag(player, action.substring("tag-remove:".length()));
            return;
        }
        if (action.startsWith("tagresult:")) {
            openTagSearch(player, holder.context, holder.searchTag, parsePage(action.substring("tagresult:".length())));
            return;
        }
        if (action.startsWith("visit:")) {
            visitFromDirectory(player, action.substring("visit:".length()));
            return;
        }
        if (action.startsWith("command:")) {
            String command = action.substring("command:".length()).trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isBlank()) {
                player.performCommand(command);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        OPEN.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PENDING_PROMPTS.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = PENDING_PROMPTS.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(core.plugin(), () -> handlePrompt(player, prompt, text));
    }

    private String apply(String s, Player player, Shelter shelter) {
        double nextCost = shelter.isMaxLevel() ? 0 : core.config().upgradeCost(shelter.level());
        List<ItemCost> nextItems = shelter.isMaxLevel() ? List.of() : core.config().upgradeItems(shelter.level());
        int nextLevel = Math.min(shelter.level() + 1, shelter.layout().maxLevel());
        Shelter nextShelter = shelter.withLevel(nextLevel);
        String bulletin = shelter.bulletin().isBlank() ? "未设置" : shelter.bulletin();
        if (bulletin.length() > 24) {
            bulletin = bulletin.substring(0, 24) + "…";
        }
        String ownerName = ownerName(shelter);
        LimitView limits = limitView(shelter);
        String out = s
                .replace("{player}", player.getName())
                .replace("{owner}", ownerName)
                .replace("{level}", String.valueOf(shelter.level()))
                .replace("{max_level}", String.valueOf(shelter.layout().maxLevel()))
                .replace("{border}", String.valueOf(shelter.borderSize()))
                .replace("{next_border}", String.valueOf(shelter.layout().borderSizeAtLevel(shelter.level() + 1)))
                .replace("{side_chunks}", String.valueOf(shelter.sideChunks()))
                .replace("{area_chunks}", String.valueOf(shelter.areaChunks()))
                .replace("{next_side_chunks}", String.valueOf(shelter.nextSideChunks()))
                .replace("{max_chunks}", String.valueOf(shelter.layout().maxChunks()))
                .replace("{plot_size}", shelter.sideChunks() + "x" + shelter.sideChunks())
                .replace("{next_plot_size}", shelter.nextSideChunks() + "x" + shelter.nextSideChunks())
                .replace("{visibility}", shelter.visibility().name().toLowerCase())
                .replace("{visibility_label}", visibilityLabel(shelter))
                .replace("{likes}", String.valueOf(shelter.likes()))
                .replace("{admins}", String.valueOf(shelter.admins().size()))
                .replace("{admin_cap}", String.valueOf(core.config().adminCapAt(shelter.level())))
                .replace("{next_admin_cap}", String.valueOf(core.config().adminCapAt(nextLevel)))
                .replace("{trusted}", String.valueOf(shelter.trusted().size()))
                .replace("{trust_cap}", String.valueOf(core.config().trustCapAt(shelter.level())))
                .replace("{next_trust_cap}", String.valueOf(core.config().trustCapAt(nextLevel)))
                .replace("{access}", String.valueOf(shelter.access().size()))
                .replace("{denied}", String.valueOf(shelter.denied().size()))
                .replace("{upgrade_cost}", nextCost <= 0 ? "免费" : String.format("%.2f", nextCost))
                .replace("{upgrade_items}", formatItems(nextItems))
                .replace("{upgrade_state}", shelter.isMaxLevel() ? "已满级" : "可升级")
                .replace("{upgrade_hint}", shelter.isMaxLevel() ? "当前已到等级上限，点击只会收到提示。"
                        : "点击按钮执行升级。")
                .replace("{next_mob_cap}", String.valueOf(core.limits().mobCap(nextShelter)))
                .replace("{next_tile_cap}", String.valueOf(core.limits().tileCap(nextShelter)))
                .replace("{next_drop_cap}", String.valueOf(core.limits().dropCap(nextShelter)))
                .replace("{next_vehicle_cap}", String.valueOf(core.limits().vehicleCap(nextShelter)))
                .replace("{bulletin}", bulletin)
                .replace("{limits_state}", limits.state())
                .replace("{mobs}", limits.mobs())
                .replace("{mob_cap}", limits.mobCap())
                .replace("{tiles}", limits.tiles())
                .replace("{tile_cap}", limits.tileCap())
                .replace("{drops}", limits.drops())
                .replace("{drop_cap}", limits.dropCap())
                .replace("{vehicles}", limits.vehicles())
                .replace("{vehicle_cap}", limits.vehicleCap())
                .replace("{world}", shelter.worldName())
                .replace("{flags_on}", String.valueOf(enabledFlagCount(shelter)))
                .replace("{flags_total}", String.valueOf(Flag.values().length));
        out = replaceFlagPlaceholders(out, shelter);
        if (out.contains("{tags_count}")) {
            out = out.replace("{tags_count}", String.valueOf(core.tags().tagsOf(shelter.owner()).size()));
        }
        if (out.contains("{board_messages}")) {
            out = out.replace("{board_messages}", String.valueOf(core.board().count(shelter.owner())));
        }
        return out;
    }

    private String replaceFlagPlaceholders(String s, Shelter shelter) {
        String out = s;
        for (Flag flag : Flag.values()) {
            boolean on = Flags.isOn(shelter, flag);
            String id = flag.id().replace('-', '_');
            out = out
                    .replace("{" + id + "_state}", flagState(on))
                    .replace("{" + id + "_toggle}", flagToggle(on))
                    .replace("{" + id + "_next}", flagNext(on))
                    .replace("{" + id + "_label}", flagDescription(flag));
        }
        return out;
    }

    private static String flagState(boolean on) {
        return on ? "&a开" : "&c关";
    }

    private static String flagToggle(boolean on) {
        return on ? "off" : "on";
    }

    private static String flagNext(boolean on) {
        return on ? "关" : "开";
    }

    private static String visibilityLabel(Shelter shelter) {
        return switch (shelter.visibility()) {
            case PRIVATE -> "私密";
            case FRIENDS -> "好友";
            case PUBLIC -> "公开";
        };
    }

    private static String flagDescription(Flag flag) {
        return Messages.get("flag-description." + flag.id(), flag.description());
    }

    private static int enabledFlagCount(Shelter shelter) {
        int count = 0;
        for (Flag flag : Flag.values()) {
            if (Flags.isOn(shelter, flag)) {
                count++;
            }
        }
        return count;
    }

    private static String ownerName(Shelter shelter) {
        String name = Bukkit.getOfflinePlayer(shelter.owner().uuid()).getName();
        return name == null || name.isBlank() ? Messages.get("word.some-player", "某位玩家") : name;
    }

    private void openMessageInput(Player player, Shelter shelter) {
        if (shelter == null) {
            Messages.errorKey(player, "target.no-shelter", "对方还没有庇护所。");
            return;
        }
        org.windy.playershelter.runtime.gui.MessageInputGui.open(player, shelter.owner().uuid(), ownerName(shelter));
    }

    private void openTags(Player player) {
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }

        File file = menuFile("tags");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = Math.max(9, Math.min(54, ((yaml.getInt("size", 54) + 8) / 9) * 9));
        List<String> tags = core.tags().tagsOf(shelter.owner());

        String title = Messages.color(yaml.getString("title", "&0标签管理"))
                .replace("{count}", String.valueOf(tags.size()))
                .replace("{max_count}", "8");

        Holder holder = new Holder(shelter);
        Inventory inv = Bukkit.createInventory(holder, size, Messages.component(title));
        holder.inventory = inv;

        Material pane = material(yaml.getString("pane-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        if (pane != Material.AIR) {
            ItemStack filler = named(pane, " ");
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int slot = sec.getInt("index", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                Material mat = material(sec.getString("material", "STONE"), Material.STONE);
                String name = apply(sec.getString("name", key), player, shelter)
                        .replace("{count}", String.valueOf(tags.size()))
                        .replace("{max_count}", "8");
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(apply(line, player, shelter)
                            .replace("{count}", String.valueOf(tags.size()))
                            .replace("{max_count}", "8"));
                }
                inv.setItem(slot, item(mat, name, lore));
                holder.actions.put(slot, sec.getString("action", ""));
            }
        }

        for (int i = 0; i < tags.size() && i < TAG_SLOTS.length; i++) {
            String tag = tags.get(i);
            int slot = TAG_SLOTS[i];
            inv.setItem(slot, tagItem(tag));
            holder.actions.put(slot, "tag-remove:" + tag);
        }

        if (tags.isEmpty()) {
            inv.setItem(22, item(Material.LIGHT_GRAY_DYE, "&7暂无标签",
                    List.of("&8点击“新增标签”来添加第一个标签。")));
        }

        OPEN.put(player.getUniqueId(), holder);
        player.openInventory(inv);
    }

    private ItemStack tagItem(String tag) {
        return item(Material.NAME_TAG, "&f#" + tag, List.of("&8点击移除这条标签。"));
    }

    private void removeTag(Player player, String rawTag) {
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        String tag = rawTag == null ? "" : rawTag.trim();
        if (tag.isBlank()) {
            return;
        }
        tag = normalizeTag(tag);
        if (core.tags().remove(shelter.owner(), tag)) {
            Messages.okKey(player, "tag.remove-success", "已成功移除标签：&#8ee5ee#{tag}", "tag", tag);
        } else {
            Messages.okKey(player, "tag.remove-missing", "&#ff6b6b找不到该标签。");
        }
        openTags(player);
    }

    private void openTagSearch(Player player, Shelter context, String query, int requestedPage) {
        query = normalizeTag(query);
        if (query.isBlank()) {
            Messages.warnKey(player, "search.usage", "用法：&#ffda75/ps search <标签>&f（例如：/ps search 现代建筑）");
            return;
        }
        File file = menuFile("tags_search");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = Math.max(9, Math.min(54, ((yaml.getInt("size", 54) + 8) / 9) * 9));

        List<Shelter> all = core.directory().searchByTag(query, 0, Integer.MAX_VALUE);
        int total = all.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) TAG_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        List<Shelter> rows = core.directory().searchByTag(query, (page - 1) * TAG_PAGE_SIZE, TAG_PAGE_SIZE);

        String title = Messages.color(yaml.getString("title", "&0标签搜索"))
                .replace("{tag}", query)
                .replace("{page}", String.valueOf(page))
                .replace("{max_page}", String.valueOf(maxPage))
                .replace("{total}", String.valueOf(total));

        Holder holder = new Holder(context);
        holder.searchTag = query;
        Inventory inv = Bukkit.createInventory(holder, size, Messages.component(title));
        holder.inventory = inv;

        Material pane = material(yaml.getString("pane-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        if (pane != Material.AIR) {
            ItemStack filler = named(pane, " ");
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int slot = sec.getInt("index", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                Material mat = material(sec.getString("material", "STONE"), Material.STONE);
                String name = replaceTagPlaceholders(sec.getString("name", key), query, page, maxPage, total);
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(replaceTagPlaceholders(line, query, page, maxPage, total));
                }
                inv.setItem(slot, item(mat, name, lore));
                holder.actions.put(slot, sec.getString("action", ""));
            }
        }

        for (int i = 0; i < rows.size() && i < DIRECTORY_SLOTS.length; i++) {
            Shelter target = rows.get(i);
            int slot = DIRECTORY_SLOTS[i];
            inv.setItem(slot, directoryItem(target));
            holder.actions.put(slot, "visit:" + target.owner().uuid());
        }

        if (page > 1) {
            inv.setItem(45, item(Material.ARROW, "&f上一页", List.of("&7第 &f" + (page - 1) + " &7页")));
            holder.actions.put(45, "tagresult:" + (page - 1));
        }
        if (page < maxPage) {
            inv.setItem(53, item(Material.ARROW, "&f下一页", List.of("&7第 &f" + (page + 1) + " &7页")));
            holder.actions.put(53, "tagresult:" + (page + 1));
        }
        inv.setItem(49, item(Material.ARROW, "&f返回", List.of("&7回到标签页。")));
        holder.actions.put(49, "menu:tags");

        OPEN.put(player.getUniqueId(), holder);
        player.openInventory(inv);
    }

    private void openDirectory(Player player, Shelter context, String rawAction) {
        DirectoryRequest req = parseDirectoryRequest(rawAction);
        File file = menuFile("directory");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = Math.max(9, Math.min(54, ((yaml.getInt("size", 54) + 8) / 9) * 9));

        List<Shelter> all = core.directory().list(req.sort(), 0, Integer.MAX_VALUE);
        int total = all.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) DIRECTORY_PAGE_SIZE));
        int page = Math.max(1, Math.min(req.page(), maxPage));
        List<Shelter> rows = core.directory().list(req.sort(), (page - 1) * DIRECTORY_PAGE_SIZE, DIRECTORY_PAGE_SIZE);

        String title = Messages.color(yaml.getString("title", "&0公开目录"))
                .replace("{sort}", sortLabel(req.sort()))
                .replace("{page}", String.valueOf(page))
                .replace("{max_page}", String.valueOf(maxPage))
                .replace("{total}", String.valueOf(total));

        Holder holder = new Holder(context);
        Inventory inv = Bukkit.createInventory(holder, size, Messages.component(title));
        holder.inventory = inv;

        Material pane = material(yaml.getString("pane-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        if (pane != Material.AIR) {
            ItemStack filler = named(pane, " ");
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int slot = sec.getInt("index", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                Material mat = material(sec.getString("material", "STONE"), Material.STONE);
                String name = replaceDirectoryPlaceholders(sec.getString("name", key), req.sort(), page, maxPage, total);
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(replaceDirectoryPlaceholders(line, req.sort(), page, maxPage, total));
                }
                inv.setItem(slot, item(mat, name, lore));
                holder.actions.put(slot, sec.getString("action", ""));
            }
        }

        for (int i = 0; i < rows.size() && i < DIRECTORY_SLOTS.length; i++) {
            Shelter target = rows.get(i);
            int slot = DIRECTORY_SLOTS[i];
            inv.setItem(slot, directoryItem(target));
            holder.actions.put(slot, "visit:" + target.owner().uuid());
        }

        if (page > 1) {
            inv.setItem(45, item(Material.ARROW, "&f上一页", List.of("&7第 &f" + (page - 1) + " &7页")));
            holder.actions.put(45, "directory:" + req.sort().name().toLowerCase(Locale.ROOT) + ":" + (page - 1));
        }
        if (page < maxPage) {
            inv.setItem(53, item(Material.ARROW, "&f下一页", List.of("&7第 &f" + (page + 1) + " &7页")));
            holder.actions.put(53, "directory:" + req.sort().name().toLowerCase(Locale.ROOT) + ":" + (page + 1));
        }
        inv.setItem(49, item(Material.ARROW, "&f返回", List.of("&7回到社交页。")));
        holder.actions.put(49, "menu:social");

        OPEN.put(player.getUniqueId(), holder);
        player.openInventory(inv);
    }

    private ItemStack directoryItem(Shelter target) {
        String name = ownerName(target);
        List<String> lore = new ArrayList<>();
        lore.add("&7等级: &f" + target.level());
        lore.add("&7点赞: &f" + target.likes());
        lore.add("&7可见性: &f" + target.visibility().name().toLowerCase(Locale.ROOT));
        if (!target.bulletin().isBlank()) {
            lore.add("&7公告: &f" + trim(target.bulletin(), 24));
        }
        lore.add("");
        lore.add("&8左键拜访。");
        return item(Material.PLAYER_HEAD, "&f" + name, lore);
    }

    private void visitFromDirectory(Player player, String raw) {
        UUID ownerId;
        try {
            ownerId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        Shelter target = core.repo().find(PlayerRef.of(ownerId)).orElse(null);
        if (target == null) {
            Messages.errorKey(player, "target.no-shelter", "对方还没有庇护所。");
            return;
        }
        visitTarget(player, target);
    }

    private void visitTarget(Player player, Shelter target) {
        ShelterRole role = target.resolveRole(PlayerRef.of(player.getUniqueId()));
        boolean bypass = player.hasPermission("playershelter.admin.visit.any");
        if (!role.canEnter() && !bypass) {
            Messages.errorKey(player, "visit.not-open", "对方的庇护所不对你开放。");
            return;
        }
        ShelterVisitEvent ve = new ShelterVisitEvent(player.getUniqueId(), target.owner().uuid(), target.worldName());
        Bukkit.getPluginManager().callEvent(ve);
        if (ve.isCancelled()) {
            Messages.errorKey(player, "visit.cancelled", "访问被拦截。");
            return;
        }
        Messages.infoKey(player, "visit.start", "正在前往 {player} 的庇护所…", "player", ownerName(target));
        router.send(player, target, Messages.get("visit.target-load-failed", "对方世界加载失败。"));
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String replaceDirectoryPlaceholders(String text, DirectoryPort.Sort sort, int page, int maxPage, int total) {
        return text
                .replace("{sort}", sortLabel(sort))
                .replace("{page}", String.valueOf(page))
                .replace("{max_page}", String.valueOf(maxPage))
                .replace("{total}", String.valueOf(total));
    }

    private static String replaceTagPlaceholders(String text, String tag, int page, int maxPage, int total) {
        return text
                .replace("{tag}", tag)
                .replace("{page}", String.valueOf(page))
                .replace("{max_page}", String.valueOf(maxPage))
                .replace("{total}", String.valueOf(total));
    }

    private static String sortLabel(DirectoryPort.Sort sort) {
        return switch (sort) {
            case LIKES -> "点赞";
            case HOT -> "热门";
            case RANDOM -> "随机";
            case NEWEST -> "最新";
        };
    }

    private static DirectoryRequest parseDirectoryRequest(String rawAction) {
        String[] parts = rawAction == null ? new String[0] : rawAction.split(":");
        DirectoryPort.Sort sort = DirectoryPort.Sort.HOT;
        int page = 1;
        if (parts.length >= 1 && !parts[0].isBlank()) {
            sort = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "likes" -> DirectoryPort.Sort.LIKES;
                case "random" -> DirectoryPort.Sort.RANDOM;
                case "new", "newest" -> DirectoryPort.Sort.NEWEST;
                default -> DirectoryPort.Sort.HOT;
            };
        }
        if (parts.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        return new DirectoryRequest(sort, page);
    }

    private File menuFile(String menuId) {
        String id = menuId == null ? "" : menuId.trim().replace('\\', '/').replace("..", "");
        String folder = switch (id) {
            case "social", "directory", "board", "tags", "tags_search" -> "social";
            case "visitor" -> "visitor";
            default -> "controller";
        };
        return new File(core.plugin().getDataFolder(), "gui/" + folder + "/" + id + ".yml");
    }

    private LimitView limitView(Shelter shelter) {
        if (!core.limits().enabled()) {
            return new LimitView("已关闭", "-", "-", "-", "-", "-", "-", "-", "-");
        }
        String mobCap = String.valueOf(core.limits().mobCap(shelter));
        String tileCap = String.valueOf(core.limits().tileCap(shelter));
        String dropCap = String.valueOf(core.limits().dropCap(shelter));
        String vehicleCap = String.valueOf(core.limits().vehicleCap(shelter));
        World world = Bukkit.getWorld(shelter.worldName());
        if (world == null) {
            return new LimitView("世界未加载", "未加载", mobCap, "未加载", tileCap,
                    "未加载", dropCap, "未加载", vehicleCap);
        }
        ShelterCensus.Census census = core.limits().census().countCached(world);
        return new LimitView("实时统计", String.valueOf(census.mobs()), mobCap,
                String.valueOf(census.tiles()), tileCap,
                String.valueOf(census.drops()), dropCap,
                String.valueOf(census.vehicles()), vehicleCap);
    }

    private void openBoard(Player player, int requestedPage) {
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }

        int total = core.board().count(shelter.owner());
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) BOARD_PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        List<MessageBoardStore.Message> messages =
                core.board().list(shelter.owner(), (page - 1) * BOARD_PAGE_SIZE, BOARD_PAGE_SIZE);

        File file = menuFile("board");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = Math.max(9, Math.min(54, ((yaml.getInt("size", 54) + 8) / 9) * 9));
        String title = replaceBoardPlaceholders(yaml.getString("title", "&0留言板 {page}/{max_page}"),
                page, maxPage, total);

        Holder holder = new Holder(shelter);
        Inventory inv = Bukkit.createInventory(holder, size, Messages.component(title));
        holder.inventory = inv;

        Material pane = material(yaml.getString("pane-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        if (pane != Material.AIR) {
            ItemStack filler = named(pane, " ");
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }

        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                if (("empty".equalsIgnoreCase(key) && !messages.isEmpty())
                        || ("clear".equalsIgnoreCase(key) && total <= 0)
                        || ("previous".equalsIgnoreCase(key) && page <= 1)
                        || ("next".equalsIgnoreCase(key) && page >= maxPage)) {
                    continue;
                }
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int slot = sec.getInt("index", -1);
                if (slot < 0 || slot >= size) {
                    continue;
                }
                Material mat = material(sec.getString("material", "STONE"), Material.STONE);
                String name = replaceBoardPlaceholders(sec.getString("name", key), page, maxPage, total);
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(replaceBoardPlaceholders(line, page, maxPage, total));
                }
                inv.setItem(slot, item(mat, name, lore));
                String action = replaceBoardPlaceholders(sec.getString("action", ""), page, maxPage, total);
                holder.actions.put(slot, action);
            }
        }

        if (!messages.isEmpty()) {
            for (int i = 0; i < messages.size() && i < BOARD_SLOTS.length; i++) {
                MessageBoardStore.Message message = messages.get(i);
                int slot = BOARD_SLOTS[i];
                if (slot >= size) {
                    continue;
                }
                inv.setItem(slot, boardMessageItem(message));
                holder.actions.put(slot, "board-delete:" + message.id() + ":" + page);
            }
        }

        OPEN.put(player.getUniqueId(), holder);
        player.openInventory(inv);
    }

    private ItemStack boardMessageItem(MessageBoardStore.Message message) {
        List<String> lore = new ArrayList<>();
        lore.add("&7作者: &f" + safe(message.authorName(), "?"));
        lore.add("&7时间: &f" + BOARD_TIME.format(Instant.ofEpochMilli(message.createdAt())));
        lore.add("&7编号: &f" + message.id());
        lore.add("");
        lore.addAll(wrap("&f" + safe(message.text(), ""), 24, 4));
        lore.add("");
        lore.add("&8左键删除这条留言。");
        return item(Material.PAPER, "&f留言 #" + message.id(), lore);
    }

    private void deleteBoardMessage(Player player, String data) {
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        String[] parts = data.split(":", 2);
        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return;
        }
        int page = parts.length > 1 ? parsePage(parts[1]) : 1;
        if (core.board().delete(shelter.owner(), id)) {
            Messages.okKey(player, "board.delete-success", "已删除该条留言。");
        } else {
            Messages.warnKey(player, "board.delete-missing", "找不到这条留言。");
        }
        openBoard(player, page);
    }

    private void clearBoard(Player player) {
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        core.board().clear(shelter.owner());
        Messages.okKey(player, "board.clear", "已清空留言板。");
        openBoard(player, 1);
    }

    private static int parsePage(String value) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String replaceBoardPlaceholders(String text, int page, int maxPage, int total) {
        return text
                .replace("{page}", String.valueOf(page))
                .replace("{max_page}", String.valueOf(maxPage))
                .replace("{total}", String.valueOf(total))
                .replace("{previous_page}", String.valueOf(Math.max(1, page - 1)))
                .replace("{next_page}", String.valueOf(page + 1));
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> wrap(String value, int width, int maxLines) {
        String text = value == null ? "" : value;
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length() && out.size() < maxLines; start += width) {
            int end = Math.min(text.length(), start + width);
            out.add(text.substring(start, end));
        }
        if (out.isEmpty()) {
            out.add("&7(空)");
        } else if (text.length() > width * maxLines) {
            int last = out.size() - 1;
            out.set(last, out.get(last) + "...");
        }
        return out;
    }

    private void beginPrompt(Player player, String promptId) {
        Prompt prompt = Prompt.byId(promptId);
        if (prompt == null) {
            return;
        }
        PENDING_PROMPTS.put(player.getUniqueId(), prompt);
        switch (prompt) {
            case BULLETIN -> Messages.infoKey(player, "bulletin.prompt-start",
                    "在聊天里输入公告内容，输入 &f取消&e 放弃编辑。");
            case TAG_ADD -> Messages.infoKey(player, "tag.prompt-start",
                    "在聊天里输入要添加的标签，输入 &f取消&e 放弃编辑。");
            case TAG_SEARCH -> Messages.infoKey(player, "search.prompt-start",
                    "在聊天里输入要搜索的标签，输入 &f取消&e 放弃搜索。");
        }
    }

    private void handlePrompt(Player player, Prompt prompt, String text) {
        switch (prompt) {
            case BULLETIN -> handleBulletinPrompt(player, text);
            case TAG_ADD -> handleTagAddPrompt(player, text);
            case TAG_SEARCH -> handleTagSearchPrompt(player, text);
        }
    }

    private void handleTagAddPrompt(Player player, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || isCancel(trimmed)) {
            Messages.infoKey(player, "tag.prompt-cancel", "已取消标签编辑。");
            return;
        }
        String normalized = normalizeTag(trimmed);
        if (normalized.isBlank()) {
            Messages.warnKey(player, "tag.invalid", "标签不能为空。");
            return;
        }
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        if (core.tags().tagsOf(shelter.owner()).size() >= 8) {
            Messages.errorKey(player, "tag.cap", "每个庇护所最多只能添加 {max} 个标签。", "max", 8);
            return;
        }
        if (core.tags().add(shelter.owner(), normalized)) {
            Messages.okKey(player, "tag.add-success", "已成功添加标签：&#8ee5ee#{tag}", "tag", normalized);
        } else {
            Messages.okKey(player, "tag.add-exists", "&#ff6b6b你已经添加过这个标签了。");
        }
        openTags(player);
    }

    private void handleTagSearchPrompt(Player player, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || isCancel(trimmed)) {
            Messages.infoKey(player, "search.prompt-cancel", "已取消标签搜索。");
            return;
        }
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        openTagSearch(player, shelter, normalizeTag(trimmed), 1);
    }

    private void handleBulletinPrompt(Player player, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || isCancel(trimmed)) {
            Messages.infoKey(player, "bulletin.prompt-cancel", "已取消公告编辑。");
            return;
        }
        Shelter shelter = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (shelter == null) {
            Messages.errorKey(player, "no-shelter", "你还没有庇护所。");
            return;
        }
        String bulletin = trimmed;
        if (isClear(trimmed)) {
            bulletin = "";
        } else if (bulletin.length() > 64) {
            bulletin = bulletin.substring(0, 64);
        }
        core.repo().save(shelter.withBulletin(bulletin));
        if (bulletin.isEmpty()) {
            Messages.okKey(player, "bulletin.prompt-empty", "公告已清空。");
        } else {
            Messages.okKey(player, "bulletin.prompt-updated", "公告已更新：{text}", "text", bulletin);
        }
    }

    private static boolean isCancel(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        return "cancel".equals(low) || "取消".equals(low);
    }

    private static boolean isClear(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        return "clear".equals(low) || "清空".equals(low);
    }

    private static String normalizeTag(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() > 32) {
            t = t.substring(0, 32);
        }
        return t;
    }

    private static String formatItems(List<ItemCost> items) {
        if (items.isEmpty()) {
            return "无";
        }
        return items.stream()
                .filter(ItemCost::valid)
                .map(i -> i.itemId() + " x" + i.amount())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static ItemStack named(Material material, String name) {
        return item(material, name, List.of());
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.component(name));
            meta.lore(lore.stream().map(Messages::component).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Material material(String name, Material fallback) {
        Material material = name == null ? null : Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    private static final class Holder implements InventoryHolder {
        private final Map<Integer, String> actions = new HashMap<>();
        private final Shelter context;
        private String searchTag;
        private Inventory inventory;

        private Holder(Shelter context) {
            this.context = context;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record LimitView(String state, String mobs, String mobCap, String tiles, String tileCap,
                             String drops, String dropCap, String vehicles, String vehicleCap) {}

    private record DirectoryRequest(DirectoryPort.Sort sort, int page) {}

    private enum Prompt {
        BULLETIN("bulletin"),
        TAG_ADD("tag_add"),
        TAG_SEARCH("tag_search");

        private final String id;

        Prompt(String id) {
            this.id = id;
        }

        static Prompt byId(String id) {
            if (id == null) {
                return null;
            }
            String low = id.trim().toLowerCase(Locale.ROOT);
            for (Prompt prompt : values()) {
                if (prompt.id.equals(low)) {
                    return prompt;
                }
            }
            return null;
        }
    }
}
