# AoE Statues

**Statue addon for Age of Element** — capture bosses and mobs as collectible statues,
customise the pedestal with any block, and scale or raise the statue with ingots.

*Minecraft 1.20.1 · Forge 47+*

![logo](src/main/resources/logo.png)

## About

这个模组为我的世界添加了 BOSS 雕像和生物雕像。

- 生物雕像可以合成获得，Shift + 右键生物录入模型，放置下来作为装饰品
- BOSS 雕像通过标签和自定义名单，兼容绝大部分的 BOSS
- BOSS 雕像将在 BOSS 被玩家击杀时掉落
- BOSS 雕像可以作为击败 BOSS 的证明，用于装饰，也可以作为触发某些功能的开关（需要自定义）

This mod adds **BOSS statues** and **creature statues** to Minecraft.

- Creature statues can be crafted — hold one and **Shift + right-click** a mob to record its model, then place it as decoration.
- BOSS statues support most BOSSes via tags and a custom list.
- BOSS statues drop when a BOSS is killed by a player.
- BOSS statues serve as proof of your BOSS kills, as decoration, and can act as switches that trigger other features (configurable).

## Features

| Feature | How |
|---|---|
| **Boss statue** | Capture a boss (data-driven capture rules); the statue keeps its look |
| **Creature statue** | Capture any mob the same way |
| **Custom pedestal** | Hold a block and right-click the statue: upper half → lower half → default |
| **Scale** | Iron ingot: right-click to enlarge, **sneak + right-click** to shrink (`×0.10`–`×10.00`, step `0.10`) |
| **Height** | Gold ingot: right-click to raise, **sneak + right-click** to lower (`−32.00`–`+32.00` blocks, step `0.50`) |
| **Data-driven effects** | Optional API: statues can grant attribute bonuses / behaviour while placed |
| **Slim NBT** | Stored statue data is pruned and capped, so old statues never bloat a save |
| **Original art** | All statue textures are drawn for this mod (32×32 pixel art, no borrowed sheets) |

All interaction feedback is shown on the action bar, so you always know the current
scale or height.

## Installation

1. Install **Minecraft Forge 47.x** for **1.20.1**.
2. Drop `aoe-statues-1.0.0.jar` into `.minecraft/mods/`.
3. (Optional) Install **Age of Element** for the full experience — the statue mod also
   works on its own.

## Configuration

Statue capture rules and effect tables are **data-driven**
(`data/<namespace>/statues/...`), so a datapack can add or change statues without
recompiling the mod.

## Building from source

```bash
# JDK 17 required
./gradlew build
# output: build/libs/age_of_element_statue-1.0.0.jar
```

## Compatibility

* Minecraft **1.20.1**
* Forge **47.x**
* Client and server (BOTH)
* No mixins, no coremods, no native libraries

## License

All Rights Reserved. You may include this mod in modpacks as long as it is downloaded
from the official CurseForge project page and not re-hosted.

## Links

* Source: this repository
* Issues: <https://github.com/isHuJie0v0/aoe-statues/issues>

---

# 中文说明

**元素征途：雕像** —— 把 Boss 或任意生物收进雕像，可自定义底座材质，还可以用锭调整大小与高度。

| 功能 | 操作 |
|---|---|
| Boss 雕像 / 生物雕像 | 按数据驱动的捕捉规则收服，外形保留 |
| 自定义底座 | 手持方块右键：上半段 → 下半段 → 还原 |
| 大小 | 铁锭右键变大，**潜行右键**变小（0.10×–10.00×，步长 0.10） |
| 高度 | 金锭右键升高，**潜行右键**降低（−32.00–+32.00 格，步长 0.50） |
| 数据驱动效果 | 可选 API：雕像摆出后可提供属性/行为加成 |
| 精简 NBT | 雕像数据会裁剪并限长，旧雕像不会撑大存档 |

安装：把 `aoe-statues-1.0.0.jar` 丢进 `mods/` 即可（Forge 47.x / 1.20.1）。
