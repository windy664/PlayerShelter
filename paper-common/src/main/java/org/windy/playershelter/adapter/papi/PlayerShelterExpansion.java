package org.windy.playershelter.adapter.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.runtime.PsCore;

/**
 * PlaceholderAPI 扩展（决策 #18 / #62 服主用 PAPI 变量看资源）。
 *
 * <p>玩家相关：%playershelter_level% %playershelter_border% %playershelter_visibility% %playershelter_likes%
 * 全局相关（决策 #62 监控）：%playershelter_loaded_worlds% %playershelter_total_shelters%
 */
public final class PlayerShelterExpansion extends PlaceholderExpansion {

    private final PsCore core;

    public PlayerShelterExpansion(PsCore core) {
        this.core = core;
    }

    @Override
    public String getIdentifier() {
        return "playershelter";
    }

    @Override
    public String getAuthor() {
        return "windy";
    }

    @Override
    public String getVersion() {
        return core.plugin().getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        // 全局监控变量（决策 #62）。
        switch (params.toLowerCase()) {
            case "loaded_worlds":
                return String.valueOf(core.world().loadedShelterWorlds().size());
            case "total_shelters":
                return String.valueOf(core.repo().all().size());
            default:
                break;
        }
        if (player == null) {
            return "";
        }
        Shelter s = core.repo().find(PlayerRef.of(player.getUniqueId())).orElse(null);
        if (s == null) {
            return switch (params.toLowerCase()) {
                case "has" -> "false";
                case "level" -> "0";
                default -> "";
            };
        }
        return switch (params.toLowerCase()) {
            case "has" -> "true";
            case "level" -> String.valueOf(s.level());
            case "maxlevel" -> String.valueOf(s.layout().maxLevel());
            case "border" -> String.valueOf(s.borderSize());
            case "side_chunks", "unlock_quota" -> String.valueOf(s.sideChunks());
            case "area_chunks", "unlocked_chunks" -> String.valueOf(s.areaChunks());
            case "max_chunks" -> String.valueOf(s.layout().maxChunks());
            case "visibility" -> s.visibility().name().toLowerCase();
            case "likes" -> String.valueOf(s.likes());
            case "world" -> s.worldName();
            case "tags" -> String.join(" ", core.tags().tagsOf(PlayerRef.of(player.getUniqueId())));
            case "messages" -> String.valueOf(core.board().count(PlayerRef.of(player.getUniqueId())));
            case "admins" -> String.valueOf(s.admins().size());
            case "trusted" -> String.valueOf(s.trusted().size());
            case "admincap" -> String.valueOf(core.config().adminCapAt(s.level()));
            case "trustcap" -> String.valueOf(core.config().trustCapAt(s.level()));
            case "upgradecost" -> {
                double c = core.config().upgradeCost(s.level());
                yield (c <= 0 || !core.economy().enabled()) ? "免费" : String.valueOf((long) c);
            }
            default -> "";
        };
    }
}
