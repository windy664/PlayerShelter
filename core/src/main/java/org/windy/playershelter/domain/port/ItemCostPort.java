package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.ItemCost;
import org.windy.playershelter.domain.model.PlayerRef;

import java.util.List;

/**
 * Platform-side inventory charging for upgrade item costs.
 */
public interface ItemCostPort {

    boolean has(PlayerRef player, List<ItemCost> costs);

    boolean withdraw(PlayerRef player, List<ItemCost> costs);

    ItemCostPort DISABLED = new ItemCostPort() {
        @Override
        public boolean has(PlayerRef player, List<ItemCost> costs) {
            return true;
        }

        @Override
        public boolean withdraw(PlayerRef player, List<ItemCost> costs) {
            return true;
        }
    };
}
