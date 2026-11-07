# Changelog

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
