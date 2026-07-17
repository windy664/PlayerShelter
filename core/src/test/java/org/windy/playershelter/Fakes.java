package org.windy.playershelter;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;
import org.windy.playershelter.domain.port.EconomyPort;
import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.port.ItemCostPort;
import org.windy.playershelter.domain.port.ShelterRepository;
import org.windy.playershelter.domain.port.WorldControl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 测试替身（内存仓库 / 假世界控制 / 可控经济），让 service 逻辑可在无服务器下验证。 */
final class Fakes {

    private Fakes() {
    }

    static final class MemRepo implements ShelterRepository {
        final Map<PlayerRef, Shelter> map = new HashMap<>();

        @Override public Optional<Shelter> find(PlayerRef owner) { return Optional.ofNullable(map.get(owner)); }

        @Override public Optional<Shelter> findByWorldName(String worldName) {
            return map.values().stream().filter(s -> s.worldName().equals(worldName)).findFirst();
        }

        @Override public void save(Shelter shelter) { map.put(shelter.owner(), shelter); }

        @Override public void delete(PlayerRef owner) { map.remove(owner); }

        @Override public List<Shelter> all() { return new ArrayList<>(map.values()); }

        @Override public List<Shelter> ownedByServer(String serverName) { return all(); }
    }

    /** 假世界控制：记录加载/删除调用，ensureWorld 直接回传（同步路径）。 */
    static class FakeWorld implements WorldControl {
        final Set<String> loaded = new HashSet<>();
        final List<String> deleted = new ArrayList<>();
        int borderApplied = 0;

        @Override public String worldName(Shelter shelter) { return shelter.worldName(); }

        @Override public Shelter ensureWorld(Shelter shelter) {
            loaded.add(shelter.worldName());
            return shelter;
        }

        @Override public void applyBorder(Shelter shelter) { borderApplied++; }

        @Override public boolean unload(String worldName) { return loaded.remove(worldName); }

        @Override public boolean deleteWorld(String worldName) {
            loaded.remove(worldName);
            deleted.add(worldName);
            return true;
        }

        @Override public boolean isLoaded(String worldName) { return loaded.contains(worldName); }

        @Override public int playerCount(String worldName) { return 0; }

        @Override public List<String> loadedShelterWorlds() { return new ArrayList<>(loaded); }
    }

    /** 可控经济：余额固定，记录扣款。 */
    static final class FakeEconomy implements EconomyPort {
        final boolean enabled;
        double balance;
        double withdrawn = 0;

        FakeEconomy(boolean enabled, double balance) {
            this.enabled = enabled;
            this.balance = balance;
        }

        @Override public boolean enabled() { return enabled; }

        @Override public boolean has(PlayerRef player, double amount) { return balance >= amount; }

        @Override public boolean withdraw(PlayerRef player, double amount) {
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            withdrawn += amount;
            return true;
        }
    }

    static final class FakeItemCosts implements ItemCostPort {
        final Map<String, Integer> inventory = new HashMap<>();

        FakeItemCosts with(String itemId, int amount) {
            inventory.put(itemId, amount);
            return this;
        }

        @Override
        public boolean has(PlayerRef player, List<ItemCost> costs) {
            return costs.stream().allMatch(c -> inventory.getOrDefault(c.itemId(), 0) >= c.amount());
        }

        @Override
        public boolean withdraw(PlayerRef player, List<ItemCost> costs) {
            if (!has(player, costs)) {
                return false;
            }
            for (ItemCost cost : costs) {
                inventory.merge(cost.itemId(), -cost.amount(), Integer::sum);
            }
            return true;
        }
    }
}
