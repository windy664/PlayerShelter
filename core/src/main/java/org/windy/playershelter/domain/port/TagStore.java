package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;

import java.util.List;

/**
 * 庇护所标签（决策 P3：owner 打 {@code #现代}/{@code #中式} 标签，访客按标签搜索发现好建筑）。
 * 标签存实现侧独立表；一个庇护所可多标签。标签统一小写、不带 {@code #}。
 */
public interface TagStore {

    /** 该 owner 的全部标签（小写，不带 #）。 */
    List<String> tagsOf(PlayerRef owner);

    /** 加标签；已有则幂等。返回是否真的新增。 */
    boolean add(PlayerRef owner, String tag);

    /** 删标签；不存在返回 false。 */
    boolean remove(PlayerRef owner, String tag);

    /** 清空该 owner 的全部标签（删庇护所时调）。 */
    void clear(PlayerRef owner);
}
