package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;

import java.util.Optional;

/**
 * 跨服待办：玩家跨服传送时的「到站后该去谁的庇护所」交接（决策 #57/#59）。
 *
 * <p>跨服场景下各后端共享 MySQL（决策 #49/#59），故交接经本表落地——玩家被代理送到目标后端后，
 * 该后端在其加入时读出待办、把人传进对应庇护所、删除待办。Redis 信道（决策 #60）是更快的可选优化，非必需。
 */
public interface PendingActionStore {

    /** 该玩家待办的「目标庇护所 owner」（即要进谁的世界）；无则空。 */
    Optional<PlayerRef> findTargetOwner(PlayerRef player);

    /** 记一条待办：玩家到站后进 {@code targetOwner} 的庇护所。 */
    void save(PlayerRef player, PlayerRef targetOwner);

    /** 删除待办（消费后调；或过期清理）。 */
    void delete(PlayerRef player);

    /** 内存实现（单服/测试；跨服无意义但不报错）。 */
    final class InMemory implements PendingActionStore {
        private final java.util.Map<PlayerRef, PlayerRef> map = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public Optional<PlayerRef> findTargetOwner(PlayerRef player) {
            return Optional.ofNullable(map.get(player));
        }

        @Override public void save(PlayerRef player, PlayerRef targetOwner) {
            map.put(player, targetOwner);
        }

        @Override public void delete(PlayerRef player) {
            map.remove(player);
        }
    }
}
