package org.windy.playershelter.domain.port;

import org.windy.playershelter.domain.model.PlayerRef;
import org.windy.playershelter.domain.model.Shelter;

import java.util.List;
import java.util.Optional;

/**
 * 庇护所持久化端口（决策 #49 SQLite 默认可切 MySQL / #50 一开始就上版本化迁移）。
 * 实现藏在适配层，core 只认接口，不写不可移植 SQL、不写死后端（同 GuildShelter 存储可插拔铁律）。
 */
public interface ShelterRepository {

    /** 按 owner 取庇护所（一人一世界，1:1）。 */
    Optional<Shelter> find(PlayerRef owner);

    /** 按世界名反查（监听器从 world 反推 owner 时用）。 */
    Optional<Shelter> findByWorldName(String worldName);

    /** 落库（upsert）。 */
    void save(Shelter shelter);

    /** 彻底删除（决策 #6 不活跃删除 / #20 玩家自删时，世界文件另由 WorldControl 删）。 */
    void delete(PlayerRef owner);

    /** 全部庇护所（生命周期扫描/不活跃治理用，量大时实现可分页）。 */
    List<Shelter> all();

    /** 本服承载的庇护所（决策 #59 世界绑服；按 serverName 过滤，单服可返回全部）。 */
    List<Shelter> ownedByServer(String serverName);
}
