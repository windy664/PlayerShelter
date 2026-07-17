package org.windy.playershelter.domain.model;

/**
 * 庇护所对外可见性（决策 #13 默认私密）。决定<b>未列入任何身份集合</b>的玩家的默认待遇。
 *
 * <ul>
 *   <li>{@link #PRIVATE} —— 私密：只有 owner / admin / trusted / 显式 access 名单能进，其余拒。</li>
 *   <li>{@link #FRIENDS} —— 好友：在 access/trusted/admin 名单里的能进，陌生人拒（无公开目录展示）。</li>
 *   <li>{@link #PUBLIC} —— 公开：任何人都能作为 VISITOR 进入参观；上公开目录（需满足等级门槛 #71）。</li>
 * </ul>
 */
public enum ShelterVisibility {
    PRIVATE,
    FRIENDS,
    PUBLIC;

    /** 是否对外公开（可进公开目录、陌生人可 visit）。 */
    public boolean isPublic() {
        return this == PUBLIC;
    }

    public static ShelterVisibility parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase()) {
            case "private", "私密" -> PRIVATE;
            case "friends", "friend", "好友" -> FRIENDS;
            case "public", "公开" -> PUBLIC;
            default -> null;
        };
    }
}
