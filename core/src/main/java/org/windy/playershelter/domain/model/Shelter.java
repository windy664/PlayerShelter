package org.windy.playershelter.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个玩家的个人庇护所 = 一个独立世界（一人一世界，决策 #5 严格 1 个/人）。
 * 这是持久化的核心记录；可用地块边长由 {@link ShelterLayout}（创建时冻结）控制。
 *
 * <p><b>身份四级集合</b>（决策 #16）：判定优先级 owner &gt; denied &gt; admin &gt; trusted &gt; access &gt; 可见性默认。
 * <ul>
 *   <li>{@code admins} —— 副主人（决策 #34 上限 config N，与 owner 同权除不可逆操作）</li>
 *   <li>{@code trusted} —— 可建造（决策 #35 始终生效，不要求 owner 在线）</li>
 *   <li>{@code access} —— 可访问（私密世界里显式放进来参观、不能建的人）</li>
 *   <li>{@code denied} —— 禁止进入（决策 #36 硬拦，覆盖公开）</li>
 * </ul>
 *
 * @param owner       庄主（即主键，一人一世界）
 * @param worldName   独立世界名 {@code shelter_<uuid>}（决策 #52）
 * @param seed        世界种子（每人随机，决策 #8）
 * @param genType     生成类型（决策 #1，创建时冻结）
 * @param level       庇护所等级（决定可用地块边长）
 * @param layout      创建时冻结的地块参数
 * @param visibility  对外可见性（决策 #13 默认私密）
 * @param admins      副主人集合
 * @param trusted     可建造集合
 * @param access      可访问集合（仅参观）
 * @param denied      黑名单集合
 * @param flags       庇护所 flag（id → 值字符串）；未含的 flag 用其默认值（精简常用集，决策 #33）
 * @param bulletin    公告/介绍语（公开目录展示用）
 * @param serverName  世界绑属的后端服名（决策 #59 世界绑服，跨服路由用；单服留空/本服名）
 * @param createdAt   创建时间
 * @param lastActive  最后活跃时间（★生命周期核心：空闲卸载 #7 / 不活跃删除 #6 都看它）
 * @param likes       公开目录累计点赞数（决策 #39 每人一次，去重在 {@code DirectoryPort}）
 */
public record Shelter(PlayerRef owner, String worldName, long seed, GenerationType genType,
                      int level, ShelterLayout layout, ShelterVisibility visibility,
                      Set<PlayerRef> admins, Set<PlayerRef> trusted, Set<PlayerRef> access,
                      Set<PlayerRef> denied, Map<String, String> flags,
                      String bulletin, String serverName,
                      Instant createdAt, Instant lastActive, int likes) {

    public Shelter {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(genType, "genType");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastActive, "lastActive");
        if (level < 1) {
            throw new IllegalArgumentException("level 必须 ≥1");
        }
        admins = Set.copyOf(admins == null ? Set.of() : admins);
        trusted = Set.copyOf(trusted == null ? Set.of() : trusted);
        access = Set.copyOf(access == null ? Set.of() : access);
        denied = Set.copyOf(denied == null ? Set.of() : denied);
        flags = Map.copyOf(flags == null ? Map.of() : flags);
        bulletin = bulletin == null ? "" : bulletin;
        serverName = serverName == null ? "" : serverName;
        if (likes < 0) {
            throw new IllegalArgumentException("likes 必须 ≥0");
        }
    }

    /** 新建一个庇护所（等级 1，私密，空名单），世界名/种子由调用方（适配层）算好传入。 */
    public static Shelter create(PlayerRef owner, String worldName, long seed,
                                 GenerationType genType, ShelterLayout layout, Instant now) {
        return new Shelter(owner, worldName, seed, genType, 1, layout, ShelterVisibility.PRIVATE,
                Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), "", "", now, now, 0);
    }

    /** 当前庇护所物理地块 WorldBorder 直径（格）。 */
    public int borderSize() {
        return layout.borderSizeAtLevel(level);
    }

    public int sideChunks() {
        return layout.sideChunksAtLevel(level);
    }

    public int areaChunks() {
        return layout.areaChunksAtLevel(level);
    }

    public int nextSideChunks() {
        return layout.sideChunksAtLevel(level + 1);
    }

    /** 是否已达等级上限（不能再升/再扩）。 */
    public boolean isMaxLevel() {
        return level >= layout.maxLevel();
    }

    /**
     * 该玩家在本庇护所的<b>合成身份</b>（已并入可见性默认，决策 #13/#16/#36）。
     * 判定：owner &gt; denied(硬拦) &gt; admin &gt; trusted &gt; access &gt; 可见性默认。
     *
     * <p>「可见性默认」：PUBLIC → VISITOR（任何人可参观）；PRIVATE/FRIENDS → DENIED（陌生人进不来）。
     */
    public ShelterRole resolveRole(PlayerRef player) {
        if (owner.equals(player)) {
            return ShelterRole.OWNER;
        }
        if (denied.contains(player)) {
            return ShelterRole.DENIED;   // 硬拦，覆盖一切
        }
        if (admins.contains(player)) {
            return ShelterRole.ADMIN;
        }
        if (trusted.contains(player)) {
            return ShelterRole.TRUSTED;
        }
        if (access.contains(player)) {
            return ShelterRole.VISITOR;
        }
        return visibility.isPublic() ? ShelterRole.VISITOR : ShelterRole.DENIED;
    }

    // —— 不可变 with* 拷贝（service 层据此演进状态）——

    public Shelter withLevel(int newLevel) {
        return new Shelter(owner, worldName, seed, genType, newLevel, layout, visibility,
                admins, trusted, access, denied, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withVisibility(ShelterVisibility v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, v,
                admins, trusted, access, denied, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withAdmins(Set<PlayerRef> v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                v, trusted, access, denied, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withTrusted(Set<PlayerRef> v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, v, access, denied, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withAccess(Set<PlayerRef> v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, v, denied, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withDenied(Set<PlayerRef> v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, v, flags, bulletin, serverName, createdAt, lastActive, likes);
    }

    public Shelter withFlags(Map<String, String> v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, denied, v, bulletin, serverName, createdAt, lastActive, likes);
    }

    /** 兼容旧调用：边界由 level + layout 直接计算，不需要额外状态。 */
    public Shelter withAutoUnlockedChunksForLevel() {
        return this;
    }

    public Shelter withBulletin(String v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, denied, flags, v, serverName, createdAt, lastActive, likes);
    }

    public Shelter withServerName(String v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, denied, flags, bulletin, v, createdAt, lastActive, likes);
    }

    /** 标记活跃（玩家进入/操作时刷新；空闲卸载与不活跃删除都以此为基准）。 */
    public Shelter withLastActive(Instant when) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, denied, flags, bulletin, serverName, createdAt, when, likes);
    }

    public Shelter withLikes(int v) {
        return new Shelter(owner, worldName, seed, genType, level, layout, visibility,
                admins, trusted, access, denied, flags, bulletin, serverName, createdAt, lastActive, v);
    }
}
