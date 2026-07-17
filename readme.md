# PlayerShelter

一人一世界的个人庇护所插件。每个玩家拥有一个独立 MC 世界，等级越高，WorldBorder 边界越大。

## 模块

- `playershelter-api`: 第三方附属 compileOnly API，含只读服务、事件、BuildCheckProvider。
- `core`: 平台无关领域模型、服务、端口和测试。
- `paper-common`: Paper/Bukkit 适配、存储、命令、GUI、保护、PAPI、跨服交接。
- `paper`: 普通 Paper 插件 jar。
- `neoforge_26_2`: Youer/NeoForge 26.2 混合端增强 jar。

## 构建

```powershell
.\gradlew.bat :core:test :paper-common:test
.\gradlew.bat :paper:build
.\gradlew.bat :neoforge_26_2:build
```

产物：

- `paper/build/libs/playershelter-paper-*.jar`
- `neoforge_26_2/build/libs/playershelter-neoforge-26.2-*.jar`

## 当前能力

- 生命周期：SQLite/MySQL、版本化迁移、写穿缓存、懒加载、空闲卸载、LRU 淘汰、不活跃清理。
- 命令：`/ps create|home|setspawn|info|gui|upgrade|reset|visibility|trust|visit|list|like|flag|board|msg` 等。
- GUI：`/ps gui` 打开庇护所控制器，默认多页配置在 `gui/controller/*.yml`。
- 等级表：`levels.yml` 管理新建庇护所的最大等级、边界线性参数、升级费用、成员名额和实体/机器限额。
- 文案：`messages.yml` 管理前缀、提示颜色和命名消息，旧文本可通过 overrides 逐步覆盖。
- 缓存：`config.yml` 的 `cache.shelter-positive-ttl-seconds` / `cache.shelter-negative-ttl-seconds` 控制跨服缓存刷新窗口。
- 保护：身份分级、访客 flag、越界保护、PvP/刷怪/爆炸/火焰/容器/交互保护。
- 扩展：`PlayerShelterApi` + Bukkit 事件 + `BuildCheckProvider`，附属插件可参与保护判定。
- 集成：Vault、PlaceholderAPI、Iris、BungeeCord/Velocity 标准插件消息跨服交接。

跨服和混合端功能仍需要在对应实服环境验证：BungeeCord/Velocity + 共享 MySQL，以及 Youer/NeoForge 26.2。

## PlaceholderAPI

玩家相关：`%playershelter_has%`、`_level%`、`_maxlevel%`、`_border%`、`_visibility%`、`_likes%`、`_world%`、`_tags%`、`_messages%`、`_admins%`、`_trusted%`、`_admincap%`、`_trustcap%`、`_upgradecost%`

全局监控：`%playershelter_loaded_worlds%`、`%playershelter_total_shelters%`
