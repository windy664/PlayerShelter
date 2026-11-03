<div align="center">

<img src="assets/banner.svg" alt="PlayerShelter" width="100%">

</div>

# PlayerShelter

![Platform](https://img.shields.io/badge/Platform-Paper%20%2F%20NeoForge-62bda8?style=flat-square&logo=minecraft&logoColor=white)
![Minecraft](https://img.shields.io/badge/MC-1.21%2B%20%2F%2026.2-8a9a5b?style=flat-square)
![Optional](https://img.shields.io/badge/Optional-Vault%20·%20PAPI%20·%20Iris-e2b90f?style=flat-square)

一人一世界的个人庇护所插件。每个玩家拥有一个独立 MC 世界，等级越高，边界越大。

## 模块

- `playershelter-api`: 第三方附属 API
- `core`: 核心逻辑与测试
- `paper-common`: Paper/Bukkit 适配层
- `paper`: 普通 Paper 插件 jar
- `neoforge_26_2`: NeoForge 26.2 增强 jar

## 构建

```powershell
.\gradlew.bat :core:test :paper-common:test
.\gradlew.bat :paper:build
.\gradlew.bat :neoforge_26_2:build
```

产物：

- `paper/build/libs/playershelter-paper-*.jar`
- `neoforge_26_2/build/libs/playershelter-neoforge-26.2-*.jar`

## 功能

- 每个玩家拥有独立世界，支持 SQLite / MySQL 存储
- 命令：`/ps create|home|setspawn|info|gui|upgrade|reset|visibility|trust|visit|list|like|flag|board|msg` 等
- GUI：`/ps gui` 打开庇护所控制器
- 等级系统：通过 `levels.yml` 管理边界大小、升级费用、成员名额和实体限额
- 保护：访客限制、越界保护、PvP/刷怪/爆炸/火焰/容器/交互保护
- 跨服：支持 BungeeCord / Velocity 跨服交接

## PlaceholderAPI

玩家：`%playershelter_has%`、`_level%`、`_maxlevel%`、`_border%`、`_visibility%`、`_likes%`、`_world%`、`_tags%`、`_messages%`、`_admins%`、`_trusted%`、`_admincap%`、`_trustcap%`、`_upgradecost%`

全局：`%playershelter_loaded_worlds%`、`%playershelter_total_shelters%`
