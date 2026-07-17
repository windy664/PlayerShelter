package org.windy.playershelter.domain.model;

/**
 * 玩家在某个庇护所里的身份（决策 #16，单人世界四级 + OWNER）。
 *
 * <p>权力从低到高：
 * <ul>
 *   <li>{@link #DENIED} —— 禁止进入：黑名单，哪怕世界公开也进不来（owner/admin 除外，决策 #36 硬拦）。</li>
 *   <li>{@link #VISITOR} —— 可访问：能进，默认只参观不能动（决策 #69），靠 flag 放宽。</li>
 *   <li>{@link #TRUSTED} —— 可建造：始终可建造/交互（不要求 owner 在线，决策 #35）。</li>
 *   <li>{@link #ADMIN} —— 管理员/副主人：除「删世界/转让/解散」这类不可逆+所有权操作外，与 owner 同权。</li>
 *   <li>{@link #OWNER} —— 庄主，最高权。</li>
 * </ul>
 */
public enum ShelterRole {
    DENIED,
    VISITOR,
    TRUSTED,
    ADMIN,
    OWNER;

    /** 是否可进入该世界（DENIED 之外都行；VISITOR 能否进还要看可见性，在 {@link Shelter#resolveRole} 已合成）。 */
    public boolean canEnter() {
        return this != DENIED;
    }

    /** 是否可建造/破坏（TRUSTED 及以上）。 */
    public boolean canBuild() {
        return this == TRUSTED || this == ADMIN || this == OWNER;
    }

    /** 是否可管理（改 flag/增删信任/设可见性…）：ADMIN 副主人及 OWNER。 */
    public boolean canManage() {
        return this == ADMIN || this == OWNER;
    }

    /** 是否拥有所有权级操作（删世界/转让/解散）：仅 OWNER。 */
    public boolean isOwner() {
        return this == OWNER;
    }
}
