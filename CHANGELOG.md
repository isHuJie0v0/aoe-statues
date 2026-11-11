# Changelog

## 1.0.1 — 2026-11-11

### Changed
* **Wider adjustment ranges.** Scale is now `0.10×`–`10.00×` (step `0.10`) and height is
  `−32`–`+32` blocks (step `0.50`), so very large models — including ones that end up
  far above the statue block — can be lined up. Previously the range was `0.20×`–`4.00×`
  and `−1`–`+2` blocks with `0.05` steps.

### Fixed
* Statues no longer keep **transient visual states**. Capture now drops vanilla fire /
  frost state (`HasVisualFire`, `RemainingFireTicks`, `TicksFrozen`, snow flags), so a mob
  that died while burning no longer produces a burning statue.
* Statues no longer keep **L2 Hostility** levels and traits (`l2hostility:*` capabilities
  and forge data are discarded — its `fiery` trait was one source of the burning look).
* Statues no longer keep **Champions / "stronger rematch"-style** affixes
  (`champions:*` / `champ:*`).
* **Freeze state.** `mowziesmobs:frozen` is no longer captured, and in
  `iceandfire:entity_data` only the frozen-related keys are removed — species, gender,
  stage and variant data are preserved so Ice and Fire models still look right.

## 1.0.0 — 2026-11-07

First public release on CurseForge.

### Features
* Boss statues and creature statues (capture keeps the entity's appearance).
* Custom pedestal: hold any block and right-click to cycle upper half / lower half / default.
* Scale adjustment with iron ingots (right-click enlarge, sneak + right-click shrink).
* Height adjustment with gold ingots (right-click raise, sneak + right-click lower).
* Data-driven statue effect API (attribute bonuses while placed).
* Slim, bounded NBT storage so long-lived statues never bloat a save.
* Full tooltips on the statue item: binding info plus ingot / pedestal instructions.
* **Original 32×32 artwork** for both statue items, drawn as **scarecrow-style statues**
  (outstretched arms on a wooden post, burlap head, tattered hem): a dark gold-trimmed
  boss scarecrow and a mossy hemp creature scarecrow on stone plinths.

### Changed
* Config files moved to the mod's own folder: `config/age_of_element_statue/`
  (`statue_bosses.json`, `statue_effects.json`). Existing files under
  `config/age_of_element/` are copied over automatically on first run, so nothing breaks.

### Fixes
* `Shift` + right-click now reliably adjusts scale/height — the interaction is handled in
  a dedicated `RightClickBlock` hook instead of `Block#use`, so it can no longer be
  swallowed by pedestal-material cycling or by another mod.
* Statue NBT pruning no longer strips appearance data (`ArmorItems` / `HandItems` and
  whitelisted mod capabilities are preserved).

### Known limitations
* Old statues created before 1.0.0 keep their previously stored (stripped) NBT; re-capture
  them to benefit from the new format.
