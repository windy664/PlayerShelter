package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;

import java.util.List;

/**
 * 公开庇护所目录端口（决策 #14 完整目录 + 评分点赞 + 精选 / #38 排序 / #39 点赞去重 / #40 纯算法精选）。
 *
 * <p>点赞去重（每人对一个世界只能赞一次，#39）的「谁赞过谁」关系存在实现侧（独立表）；
 * {@link Shelter#likes()} 只是累计计数的快照。
 */
public interface DirectoryPort {

    /** 排序视图（决策 #38 点赞 + 热度 + 随机）。 */
    enum Sort {
        LIKES,    // 总点赞高→低
        HOT,      // 近期热度（点赞/访问增量）——纯算法精选也取这个 #40
        RANDOM,   // 随机推荐，防头部垄断
        NEWEST    // 最新创建
    }

    /** 取公开目录一页（仅 PUBLIC 且过等级门槛 #71 的世界，过滤在实现侧）。 */
    List<Shelter> list(Sort sort, int offset, int limit);

    /**
     * 点赞（决策 #39 每人一次）。已赞过返回 false（幂等，不重复计数）。
     *
     * @param target 被赞庇护所的 owner
     * @param byPlayer 点赞者
     */
    boolean like(PlayerRef target, PlayerRef byPlayer);

    /** 取消点赞；未赞过返回 false。 */
    boolean unlike(PlayerRef target, PlayerRef byPlayer);

    /** byPlayer 是否已赞过 target。 */
    boolean hasLiked(PlayerRef target, PlayerRef byPlayer);

    /** 纯算法精选位（决策 #40）：按 HOT 取前 n 个，无人工置顶。 */
    List<Shelter> featured(int n);

    /**
     * 全服等级榜（决策 #23）：按庇护所等级降序取前 n（等级同则点赞降序）。
     * 不限可见性——等级+玩家名无隐私顾虑，是「攻比」激励。
     */
    List<Shelter> topByLevel(int n);

    /** 全服点赞榜（决策 #23）：仅 PUBLIC（点赞是公开行为），按总赞降序取前 n。 */
    List<Shelter> topByLikes(int n);

    /**
     * 按标签搜索公开庇护所（决策 P3）。{@code tag} 不带 {@code #}，大小写不敏感。
     * 仅 PUBLIC 且过等级门槛。
     */
    List<Shelter> searchByTag(String tag, int offset, int limit);
}
