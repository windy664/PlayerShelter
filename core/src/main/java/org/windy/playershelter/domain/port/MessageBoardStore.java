package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;

import java.util.List;

/**
 * 访客留言板（决策 P3）：访客给公开庇护所留言，owner 可看/清。留言存实现侧独立表。
 */
public interface MessageBoardStore {

    /** 一条留言。 */
    record Message(long id, PlayerRef author, String authorName, String text, long createdAt) {}

    /** 留言（返回新留言 id；author 冗余存名字，免离线查不到）。 */
    long post(PlayerRef owner, PlayerRef author, String authorName, String text);

    /** 取某庇护所留言（最新在前），分页。 */
    List<Message> list(PlayerRef owner, int offset, int limit);

    /** 删一条留言（owner 清理用）；返回是否删掉。 */
    boolean delete(PlayerRef owner, long messageId);

    /** 清空该庇护所全部留言（owner 清屏 / 删庇护所时调）。 */
    void clear(PlayerRef owner);

    /** 该庇护所留言总数。 */
    int count(PlayerRef owner);
}
