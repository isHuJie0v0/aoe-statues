# Release artifacts

Every released version is kept here — do not delete older jars.

| File | Version | Size | SHA-256 (first 16) |
|---|---|---|---|
| `aoe-statues-1.0.0.jar` | 1.0.0 | 92,649 | — |
| `aoe-statues-1.0.1.jar` | 1.0.1 | 93,079 | `B2F6A13F48CB8F61` |
| `aoe-statues-1.0.2.jar` | 1.0.2 | 108,316 | `444C19CD6E0DC4CB` |

## Version notes

- **1.0.0** — first public release.
- **1.0.1** — wider scale/height ranges; dropped burning and frozen visual states.
- **1.0.2** — GPU-baked statue rendering (vertices uploaded once, redrawn per statue), frustum culling restored,
  96-block distance cull, geometry-based placement (fixes oversized models floating above the pedestal),
  animation-frame sampling for the bake, MIT license, display name *Boss Statues*.

## How to cut a release

1. Bump `mod_version` in `gradle.properties`.
2. `gradle jar` (see the build notes in the main README).
3. Copy `build/libs/age_of_element_statue-<version>.jar` to `dist/aoe-statues-<version>.jar`.
4. Add a row to the table above and a short note in *Version notes*.
5. Commit and tag (`v<version>`).
