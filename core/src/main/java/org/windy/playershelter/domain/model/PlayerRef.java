package org.windy.playershelter.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家引用（仅 UUID）。domain 层不认识 Bukkit 的 Player，只用它标识玩家。
 *
 * <p>在 PlayerShelter 里，<b>玩家即庇护所主键</b>（一人一世界，1:1）；{@link Shelter#owner()} 用它定位。
 */
public record PlayerRef(UUID uuid) {

    public PlayerRef {
        Objects.requireNonNull(uuid, "PlayerRef.uuid");
    }

    public static PlayerRef of(UUID uuid) {
        return new PlayerRef(uuid);
    }
}
