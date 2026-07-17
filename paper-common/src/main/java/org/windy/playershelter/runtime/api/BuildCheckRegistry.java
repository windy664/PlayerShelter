package org.windy.playershelter.runtime.api;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.windy.playershelter.api.BuildAction;
import org.windy.playershelter.api.BuildCheckProvider;
import org.windy.playershelter.api.BuildDecision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class BuildCheckRegistry {

    private record Entry(Plugin plugin, BuildCheckProvider provider, int priority) {}

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void register(Plugin plugin, BuildCheckProvider provider, int priority) {
        if (plugin == null || provider == null) {
            throw new IllegalArgumentException("plugin/provider required");
        }
        entries.add(new Entry(plugin, provider, priority));
        entries.sort(Comparator.comparingInt(Entry::priority));
    }

    public synchronized void unregister(Plugin plugin) {
        entries.removeIf(e -> e.plugin().equals(plugin));
    }

    public synchronized BuildDecision query(UUID actor, Location location, BuildAction action, String blockId) {
        boolean allowed = false;
        for (Entry entry : List.copyOf(entries)) {
            if (!entry.plugin().isEnabled()) {
                continue;
            }
            BuildDecision decision;
            try {
                decision = entry.provider().check(actor, location, action, blockId);
            } catch (Throwable ignored) {
                continue;
            }
            if (decision == BuildDecision.DENY) {
                return BuildDecision.DENY;
            }
            if (decision == BuildDecision.ALLOW) {
                allowed = true;
            }
        }
        return allowed ? BuildDecision.ALLOW : BuildDecision.PASS;
    }
}
