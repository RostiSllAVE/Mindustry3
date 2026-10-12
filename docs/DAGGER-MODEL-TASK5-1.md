# Task 5.1 — Dagger-only procedural model (0.18.0)

## Implementation and scope

Continued clean `feature/ui-polish` at `0cf9d2e`. Read the complete task before editing. Backup: `local/backups/before-dagger-0.17.0/mindustry-fpv-0.17.0.jar`, SHA256 `32133b3fc294a0929754d49d7d3785b02d36e49fe2a96604c63dffc50c54da74`.

The existing `SpriteExtrusionGeometry` already produces alpha-grid silhouettes, greedy rectangular caps and only exposed side edges, including holes. Dagger's native body fits its64-cell limit, so its silhouette is retained at source-pixel resolution. Reused this geometry, the existing extrusion shader/cache and ground-family unit pass. No new rendering pipeline, copied game assets, Blender files or dependencies.

`ProceduralUnitModelProfile` is a presentation-only registry: **only `UnitTypes.dagger` is registered**. It exposes body thickness/scale, side shading, model origin/clearance, weapon depth/mount offsets and movement offset. Body thickness is .24 tiles, bounded by the unchanged canonical .12-tile collision half-height. Original sprite dimensions, pose XYZ/yaw/pitch/roll, team-cell atlas art and health-dependent cell color remain native. Top/bottom UVs align with the silhouette; sides sample opaque edge pixels. Profile-only pixel-center sampling keeps pixel art sharp without modifying native atlas filtering or other units.

Dagger is a `Mechc`, with two mirrored **whole-leg** sprites (`dagger-leg`,40x36) and a base (`dagger-base`,26x24), not an articulated `Legsc`. Existing native gait/base rotation and ground-contact adapter are reused. Limbs attach below the thicker body and stay supported by the existing surface. No invented wheels, tracks, joints or new gait system.

The two native `large-weapon` mounts now have cached alpha-extruded depth and keep native mount positions, mirrored textures, aiming rotation and recoil displacement. Their centers use the existing body/muzzle Z. Native shooting code, bullet origins, weapon properties and first-person viewmodel are unchanged. Unsupported/deferred body generation uses the old rendering route; missing/deferred component art does not prevent the body/limbs from rendering.

No changes to unit movement, collision, combat balance, flying/naval/other ground models, terrain, structural construction, logistics, power or multiplayer packet/save formats. The existing protocol still requires matching mod JARs.

## Geometry and cache

Actual installed160.7 atlas measurements:

| Component | Triangles | Unindexed vertices |
|---|---:|---:|
| Body, aligned top/bottom and alpha sides |468|1404|
| One native weapon |192|576|
| Two original whole legs plus base |576|1728|
| Full Dagger, including both weapons |**1428**|**4284**|

Body has37 top quads,37 bottom quads and160 alpha-boundary side quads. Each weapon has11/11/74 respectively. Thus neither body nor weapon is a single bounding box. One body mesh, one shared mirrored-weapon mesh and the existing limb assembly are reused by every Dagger. No complete mesh regeneration during ordinary frames; existing cache budgets and one new extrusion build per frame still apply. Near Dagger uses four draw calls (body, two mounts, batched original limbs). No full-frame performance claim is made.

## Verification

Development run `local/compat-smoke/dagger-dev2-1607` passed the full installedSDL160.7 regression: three maps, copied user saves and native save/load, FPV/Build navigation and input APIs, actual3D combat, terrain/vegetation/threat markers and all20 native ground-model fixtures. Dagger-specific test passed27 checks with above/side/low-FPV A/B views, real native move/turn/fire,13 Daggers simultaneously visible, stable body/weapon/limb build counters, immutable render-time collision/map/HP and GL error0. The earlier firing-test failure was an incorrect fixture aim outside the native shoot cone after rotation; corrected the fixture, not combat behavior.

Final build: `local/build-dagger-final.log`, all **77 Java test suites passed**, including102 profile/attachment assertions and11,713 existing silhouette assertions. Package: `dist/mindustry-fpv-0.18.0.jar`,3,756,466 bytes, SHA256 `1c485ce86b726ef6d55e7b330a42f8bb1fd021b625c20e59798554a5fbf53eaa`. Package audit found no bundled Mindustry/Arc classes or Liftoff assets.

Both final nativeSDL160.7 launches used that **exact SHA**, recorded in their `process.json`:

- `local/compat-smoke/dagger-final-1607/results.json`: passed,3 maps,20 ground models, FPV/Build input and navigation, copied saves/native roundtrip, native/3D combat, terrain/vegetation/markers, GL0. `dagger-model-verification.txt` contains the27 Dagger checks and measured geometry.
- `local/layer-smoke/dagger-final-1607/layers-result.json`: passed209 construction checks,32 first-person GPU checks (Dagger/Reign/Toxopid weapons/legs and Flare),9 construction-preview/beam/depth GPU checks and7 native display checks. The existing1,000-platform120-frame construction-only pass measured meanCPU .092622ms; this is **not** a Dagger/full-scene FPS benchmark.

Tests use production methods and actual SDL/GPU frames in disposable D: profiles; physical mouse/controller input is not automated. No fresh network stress run or160.5 test was performed for this visual-only task. Original play-profile saves are not opened by these tests.

Installed the exact tested JAR at `play/saves/mods/mindustry-fpv.jar` after both owned test processes exited. All606 pre-existing non-FPV-JAR profile files remained byte-identical (`local/dagger-profile-before.json`, `local/dagger-verification.json`). The old installed JAR was also archived by the installer at `local/backups/mod-imports-20261010T122136473Z-4448e6a7`. Start normally with `Play-FPV.cmd`.

Final screenshots, taken in the actual production renderer:

- [Above, new](../local/compat-smoke/dagger-final-1607/dagger-new-top.png) / [above, old](../local/compat-smoke/dagger-final-1607/dagger-old-top.png).
- [Side, new](../local/compat-smoke/dagger-final-1607/dagger-new-side.png) / [side, old](../local/compat-smoke/dagger-final-1607/dagger-old-side.png).
- [Low FPV angle](../local/compat-smoke/dagger-final-1607/dagger-new-low-fpv.png), [native movement/rotation](../local/compat-smoke/dagger-final-1607/dagger-new-moved.png), [native firing](../local/compat-smoke/dagger-final-1607/dagger-new-firing.png), [13-unit group](../local/compat-smoke/dagger-final-1607/dagger-new-crowd.png).

## Limitations

This is intentionally a silhouette extrusion prototype awaiting visual approval, not a handcrafted articulated robot. The body side inherits the dark outline colors of the original sprite; no detailed mechanical side texture or expensive bevel is invented. Existing whole-leg movement and first-person weapon animation are retained. The old/new gallery isolates the body/weapon profile in the same scene, rather than launching two complete game versions. Source textures remain owned by the player's installed game.
