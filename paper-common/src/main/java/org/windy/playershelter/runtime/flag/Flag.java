package org.windy.playershelter.runtime.flag;

import java.util.Arrays;
import java.util.Optional;

/**
 * 精简常用 flag 集（决策 #33：只挑单人世界用得上的，不搬公会专用的）。
 * 每个 flag 是布尔，{@code id} 是存储/命令用的稳定键，{@code def} 是默认值（未显式设置时取它）。
 *
 * <p>默认值对齐决策：PvP 默认关(#9)、刷怪默认开(#10)、访客只能看不能动(#69 → 交互/容器默认关)、
 * 越界与建筑防护倾向保守（火/爆炸默认关）。
 */
public enum Flag {

    PVP("pvp", false, "玩家间伤害"),
    MOB_SPAWNING("mob-spawning", true, "怪物/生物自然生成"),
    MOB_GRIEFING("mob-griefing", false, "生物破坏方块（苦力怕/末影人等）"),
    EXPLOSIONS("explosions", false, "爆炸破坏方块"),
    FIRE_SPREAD("fire-spread", false, "火焰蔓延/点燃"),
    DAMAGE_ANIMALS("damage-animals", true, "可伤害动物"),
    LEAF_DECAY("leaf-decay", true, "树叶自然衰减"),
    VISITOR_INTERACT("visitor-interact", false, "访客可交互（门/按钮/拉杆）"),
    VISITOR_CONTAINER("visitor-container", false, "访客可开容器（箱子/熔炉）"),
    VISITOR_PICKUP("visitor-pickup", false, "访客可捡拾掉落物");

    private final String id;
    private final boolean def;
    private final String desc;

    Flag(String id, boolean def, String desc) {
        this.id = id;
        this.def = def;
        this.desc = desc;
    }

    public String id() {
        return id;
    }

    public boolean defaultValue() {
        return def;
    }

    public String description() {
        return desc;
    }

    public static Optional<Flag> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase();
        return Arrays.stream(values()).filter(f -> f.id.equals(key)).findFirst();
    }
}
