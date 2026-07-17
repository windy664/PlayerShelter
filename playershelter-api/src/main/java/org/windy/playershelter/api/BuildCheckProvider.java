package org.windy.playershelter.api;

import org.bukkit.Location;

import java.util.UUID;

/** Extension point for plugins that need to participate in shelter protection. */
@FunctionalInterface
public interface BuildCheckProvider {
    BuildDecision check(UUID actor, Location location, BuildAction action, String blockId);
}
