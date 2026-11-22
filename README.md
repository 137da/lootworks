# LootWorks (宝藏工坊)

> Inspect unopened structure loot chests in real time, reveal Treasure Cores, and boost target item drop odds with fate-rewriting tools.  
> **Minecraft `1.20.1`** · **Forge `>= 47.4.23`** · **Mod ID: `lootworks`** · **Current Version: `0.1.1-beta+1.20.1`**

- **Modrinth**: <https://modrinth.com/mod/lootworks>
- **Source Code**: <https://github.com/137da/lootworks>
- **Issue Tracker**: <https://github.com/137da/lootworks/issues>

---

## Features

- **Chest Detector (`宝箱检测器`)**: Sneak + Right-Click an unopened structure loot chest (chests, trapped chests, barrels, shulker boxes, and optional `Lootr` containers) to inspect its loot table and estimated per-item drop chances in a dedicated UI.
- **Hybrid Static + Dynamic Probability Engine**: Combines static loot-table analysis with live container sampling and consistency checks so vanilla, datapack, KubeJS, and Global Loot Modifier (GLM) drops are reflected accurately without triggering side-effect loot functions (such as explorer maps) prematurely.
- **Fate Rewriting (`命运改写`)**: Select a target item inside the Chest Detector and feed **Fate Dust (`命运粉尘`)**, **Fate Crystal (`命运水晶`)**, or **Destined Crystal (`命定水晶`)** to reroll and lock a higher-odds loot seed (with **Destined Crystal** guaranteeing the selected drop).
- **Skeleton Key? & Core Reveal (`万能钥匙？` 与 铜锭淬炼)**: Crack an unopened structure loot chest to reveal a **Treasure Core (`宝藏核心`)**, and temper nearby dropped Copper Ingots on the ground into **Relic Metal Ingots (`遗物金属锭`)**.
- **Built-in Advancement Chain**: Includes an in-game advancement tree guiding you through detection, fate rewriting, and Treasure Core crafting.

## Requirements & Compatibility

- **Minecraft**: `1.20.1`
- **Forge**: `47.4.23` or higher (`[47.4.23, 48)`)
- **Java**: JDK `17`
- **Optional Mods**: `Lootr` (`0.7+`), `JEI`, `Jade`

## Building from Source

```bash
# Standard Gradle build (output in build/libs/)
./gradlew build

# Launch development client
./gradlew runClient
```

## License

- Original code and assets in this repository are licensed under the **[MIT License](LICENSE)**.

---

## 简体中文简介

**宝藏工坊（LootWorks）** 是一款面向 Minecraft `1.20.1`（Forge `>= 47.4.23`）的探索与战利品机制模组：

- **宝箱检测器（Chest Detector）**：潜行右键未开启的结构战利品箱（兼容箱子、陷阱箱、木桶、潜影盒及可选模组 `Lootr` 容器），实时预览箱内潜在战利品与估计掉率。
- **静动结合概率引擎**：结合静态战利品表理论估算与动态采样，兼顾原版、数据包、KubeJS 与全局战利品修改器（GLM），同时避免提前触发探险地图等带副作用的战利品函数。
- **命运改写**：在检测界面中锁定目标物品，消耗 **命运粉尘**、**命运水晶** 或 **命定水晶** 重塑容器命运种子；其中 **命定水晶** 可提供 100% 保底命中。
- **万能钥匙？与宝藏核心**：强行撬开未开启的结构宝箱以提取 **宝藏核心**，并利用爆发余能将周围地面上的铜锭淬炼为 **遗物金属锭**。
- **内置进度树**：提供完整的游戏内探索与改命引导进度链。

### 开源协议

- 本项目原创代码与资源采用 **[MIT License](LICENSE)** 开源。
