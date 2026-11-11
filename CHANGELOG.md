# Changelog / 更新日志

> Every entry is provided in **English** first and **中文** below.
> 每条记录**先英文、后中文**。

---

## 1.0.1 — 2026-11-11

### Changed / 变更

* **Wider adjustment ranges.** Scale is now `0.10×`–`10.00×` (step `0.10`) and height is
  `−32`–`+32` blocks (step `0.50`), so very large models — including ones that end up far
  above the statue block — can be lined up. Previously the range was `0.20×`–`4.00×` and
  `−1`–`+2` blocks with `0.05` steps.
* **可调范围放大。** 大小改为 `0.10×`–`10.00×`（步长 `0.10`），高度改为
  `−32`–`+32` 格（步长 `0.50`），因此很大的模型——包括落点高出雕像方块十几个方块的
  模型——也能对齐。此前范围是 `0.20×`–`4.00×` 与 `−1`–`+2` 格，步长 `0.05`。

### Fixed / 修复

* Statues no longer keep **transient visual states**. Capture now drops vanilla fire and
  frost state (`HasVisualFire`, `RemainingFireTicks`, `TicksFrozen`, snow flags), so a mob
  that died while burning no longer produces a burning statue.
* **不再保留临时视觉状态。** 抓取时丢弃原版燃烧与结霜状态
  （`HasVisualFire`、`RemainingFireTicks`、`TicksFrozen`、雪相关标记），被火烧死的生物
  不会再生成带火焰的雕像。
* Statues no longer keep **L2 Hostility** levels and traits (`l2hostility:*` capabilities
  and forge data are discarded — its `fiery` trait was one source of the burning look).
* **不再保留 L2 Hostility 的等级与词条**（`l2hostility:*` 的 capability 与 forge data
  一律丢弃——它的 `fiery` 特性正是“火雕像”的来源之一）。
* Statues no longer keep **Champions / "stronger rematch"-style** affixes
  (`champions:*` / `champ:*`).
* **不再保留 Champions（含“强敌再续”）的词缀**（`champions:*` / `champ:*`）。
* **Freeze state.** `mowziesmobs:frozen` is no longer captured, and in
  `iceandfire:entity_data` only the frozen-related keys are removed — species, gender,
  stage and variant data are preserved so Ice and Fire models still look right.
* **冻结状态。** `mowziesmobs:frozen` 不再抓取；`iceandfire:entity_data` 里**只**剔除与
  冻结有关的字段，物种 / 性别 / 阶段 / 变体等数据保留，冰火模组的模型外观不受影响。

---

## 1.0.0 — 2026-11-07

First public release on CurseForge.
CurseForge 上的首个公开版本。

### Features / 功能

* Boss statues and creature statues (capture keeps the entity's appearance).
* BOSS 雕像与生物雕像（抓取时保留生物外观）。
* Custom pedestal: hold any block and right-click to cycle upper half / lower half / default.
* 自定义底座：手持任意方块右键，在「上半砖 / 下半砖 / 默认」之间循环。
* Scale adjustment with iron ingots (right-click enlarge, sneak + right-click shrink).
* 铁锭调整大小（右键变大，潜行 + 右键变小）。
* Height adjustment with gold ingots (right-click raise, sneak + right-click lower).
* 金锭调整高度（右键升高，潜行 + 右键降低）。
* Data-driven statue effect API (attribute bonuses while placed).
* 数据驱动的雕像效果 API（摆放期间提供属性加成）。
* Slim, bounded NBT storage so long-lived statues never bloat a save.
* 精简且有上限的 NBT 存储，长期存在的雕像不会撑大存档。
* Full tooltips on the statue item: binding info plus ingot / pedestal instructions.
* 雕像物品带完整 tooltip：绑定信息 + 铁锭 / 金锭 / 底座用法。
* **Original 32×32 artwork** for both statue items, drawn as **scarecrow-style statues**
  (outstretched arms on a wooden post, burlap head, tattered hem): a dark gold-trimmed
  boss scarecrow and a mossy hemp creature scarecrow on stone plinths.
* 两件雕像物品均为**原创 32×32 贴图**，画成**稻草人风格雕像**（木桩上张开双臂、麻布头、
  破布下摆）：深金描边的 BOSS 稻草人，与苔藓麻绳的生物稻草人，都立在石质底座上。

### Changed / 变更

* Config files moved to the mod's own folder: `config/age_of_element_statue/`
  (`statue_bosses.json`, `statue_effects.json`). Existing files under
  `config/age_of_element/` are copied over automatically on first run, so nothing breaks.
* 配置文件移到模组自己的目录：`config/age_of_element_statue/`
  （`statue_bosses.json`、`statue_effects.json`）。首次启动会自动从
  `config/age_of_element/` 复制旧文件，不会失效。

### Fixes / 修复

* `Shift` + right-click now reliably adjusts scale/height — the interaction is handled in
  a dedicated `RightClickBlock` hook instead of `Block#use`, so it can no longer be
  swallowed by pedestal-material cycling or by another mod.
* `Shift` + 右键现在能可靠地调整大小 / 高度——交互改由独立的 `RightClickBlock` 钩子处理，
  不再走 `Block#use`，因此不会被底座换材质或其它模组吞掉。
* Statue NBT pruning no longer strips appearance data (`ArmorItems` / `HandItems` and
  whitelisted mod capabilities are preserved).
* 雕像 NBT 瘦身不再误删外观数据（`ArmorItems` / `HandItems` 与白名单里的模组 capability
  都会保留）。

### Known limitations / 已知限制

* Old statues created before 1.0.0 keep their previously stored (stripped) NBT; re-capture
  them to benefit from the new format.
* 1.0.0 之前创建的旧雕像会保留当时存下的（已瘦身的）NBT；想用新格式请重新抓取。
