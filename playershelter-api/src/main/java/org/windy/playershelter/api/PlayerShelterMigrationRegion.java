package org.windy.playershelter.api;

import java.util.UUID;

/**
 * A PlayerShelter-owned square region that can be used by migration addons.
 *
 * @param owner         shelter owner
 * @param worldName     Bukkit world name
 * @param minChunkX     minimum chunk X, inclusive
 * @param minChunkZ     minimum chunk Z, inclusive
 * @param maxChunkX     maximum chunk X, inclusive
 * @param maxChunkZ     maximum chunk Z, inclusive
 * @param sideChunks    square side length in chunks
 * @param level         shelter level
 * @param source        human-readable source label for diagnostics
 */
public record PlayerShelterMigrationRegion(UUID owner, String worldName,
                                           int minChunkX, int minChunkZ,
                                           int maxChunkX, int maxChunkZ,
                                           int sideChunks, int level,
                                           String source) {
}
