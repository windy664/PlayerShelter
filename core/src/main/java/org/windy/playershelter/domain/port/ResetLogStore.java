package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;

import java.util.Optional;

/**
 * 重置冷却/限额记录的持久化（决策 #77）。把"上次 reset 时刻 + 当日次数"落库，
 * 这样<b>服务器重启不会清零</b>冷却与每日限额（否则按日重启的服等于绕过限制）。
 */
public interface ResetLogStore {

    /** 一条重置记录。{@code epochDay} 用于跨自然日归零 {@code dayCount}。 */
    record ResetRecord(long lastResetAt, int dayCount, long epochDay) {}

    Optional<ResetRecord> find(PlayerRef owner);

    void save(PlayerRef owner, ResetRecord record);

    /** 内存实现（测试/无持久化回退；语义同旧的 ConcurrentHashMap）。 */
    final class InMemory implements ResetLogStore {
        private final java.util.Map<PlayerRef, ResetRecord> map = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public Optional<ResetRecord> find(PlayerRef owner) {
            return Optional.ofNullable(map.get(owner));
        }

        @Override public void save(PlayerRef owner, ResetRecord record) {
            map.put(owner, record);
        }
    }
}
