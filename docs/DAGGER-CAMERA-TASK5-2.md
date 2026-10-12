# Task 5.2 — Dagger depth and ordinary ground FPV eye (0.18.1)

## Model correction

Started on clean `feature/ui-polish` at `b6ab18e`. Backup: `local/backups/before-dagger-camera-0.18.0/mindustry-fpv-0.18.0.jar`, SHA256 `1c485ce86b726ef6d55e7b330a42f8bb1fd021b625c20e59798554a5fbf53eaa`.

The old profile's thickness was capped by the unchanged physical half-height. Replaced that cap with lower-face anchoring: `bodyCenterLift = bodyThickness/2 - supportHalfDepth`, applied along the actual local-up basis. **Dagger only** uses .72 tiles instead of .24, a true3x increase, with the original top texture/team-cell overlay, aligned bottom, silhouette side faces and existing cache. On level terrain the lower cap remains at Z0 and the top reaches Z.72. Body center shifts visually; native unit XYZ, hitbox, ground movement and weapon firing Z do not. The shared visual top envelope also updates Dagger's culling bound and overhead marker, preventing the old marker from disappearing inside the taller armor; other models/markers retain their previous bounds.

Native whole-leg and weapon attachments retain their previous support frame, XY offsets, native base rotation and recoil animation. Thick side armor samples original texture pixels two pixels inward where opaque, falling back to the native edge color at transparent interior samples. Other units have zero side inset and retain the previous renderer. Geometry stays1428 triangles/4284 unindexed vertices including both weapons and the original limbs; thickness does not increase topology or per-frame mesh generation.

## Camera correction

The old .12-radius upward trace began at a .12-high grounded body. Its first probe touched the terrain, producing fraction0 and blocking the intended eye lift. The new ordinary-ground path uses an .08-radius camera probe and .015 skin, through the existing `World3DAdapter.traceSegment` including layered slabs.

AUTO eye lift from canonical body center is `visualTop + .35 + .40*hitSizeTiles + .12*nativeBodySpanTiles`, bounded above its own body by probe radius/skin. The optional Dagger profile contributes its actual raised top; tilted-body dimensions use the existing orientation. Current canonical support/ground clearance is already present in the anchor XYZ. No fixed one-size offset replaces all units.

The offset eases in raw real time with a .16-second time constant, independently of simulation/Build speed. Minimum hull clearance applies immediately on takeover; obstacles override easing immediately. On leaving a roof the eye rises smoothly. On releasing ordinary ground control it eases back to the next unit's existing eye offset, then that flying/legged behavior is exact. XY possession relocation remains native; this pass does not create a cinematic flight between units. Build/chase/rear camera behavior remains unchanged.

`Tab → Flight / camera → Ground eye lift` stores an override for the current ordinary unit type. Slider0 means AUTO; positive values are eye lift above the canonical body center in hundredths of a tile, up to12 tiles. The actual model and ceilings still bound the result. Preferences are local (`fpv-ground-eye-<native-unit-name>`), not authoritative physics or network settings. Flying, actual Legsc units, extensible Vela and block-unit turret proxies are excluded from this sizing policy.

Raising the eye introduces muzzle/eye parallax. Ordinary ground first-person aiming now reuses the existing shared3D sight-pick path already used by chase view, converging from the unchanged firing/body origin toward the visible terrain/unit target. First-person healers may sight allied units; existing chase target rules are retained. Camera direction, fixed-mount native cones, projectile dynamics, damage and permissions are unchanged.

## Verification and practical limits

Final package: `dist/mindustry-fpv-0.18.1.jar`, **3,767,677 bytes**, SHA256 `9e37b30473016cddd255a22665b092f70cdb95535a5f3f0f4458d2cf1bea1715`. Both nativeSDL160.7 receipts confirm this exact SHA.

- `local/build-ground-eye-final5.log`: **78 Java suites passed**, including104 profile/attachment/envelope assertions, ground-eye sizing/override/easing/release/clamp tests and11,713 existing silhouette assertions.
- `local/layer-smoke/ground-eye-final5-1607/layers-result.json`: passed **39 actual-camera checks**,209 construction checks,32 first-person GPU checks,9 construction-preview GPU checks and7 display GPU checks. Actual possession of Dagger/Fortress/Reign, own-hull clearance from the first frame, smooth takeover/release, flat-ground contact, per-unit override, ground-reticle/muzzle convergence, real upper roof, immediate ceiling clamp/recovery, unchanged physical state and exact settled Flare/Toxopid/Vela camera were checked.
- `local/compat-smoke/dagger-depth-final5-1607/results.json`: passed3 maps, copied saves/native roundtrip, FPV/Build input, native/3D combat, terrain, all20 native ground models, markers/vegetation and GL0. Dagger fixture passed **28 checks**: true3x depth, anchored lower cap, top/side/low-FPV views, native movement/rotation/firing,13 simultaneously visible Daggers sharing caches and immutable physical pose/HP/map. Geometry remains1428 triangles.

Measured level-ground eye heights in tiles (`ground-eye-measurements.txt`):

| Unit | Canonical body Z | Eye Z | Eye lift from body center |
|---|---:|---:|---:|
| Dagger |0.120|**1.650**|1.530|
| Fortress |0.120|**1.533**|1.413|
| Reign |0.263|**2.945**|2.682|

Dagger's thicker model places its eye above Fortress despite its smaller physical hit size. Under the actual Z1 platform, underside Z1.24, Reign's eye clamps to **Z1.145**, retaining .08 probe radius plus .015 skin.

Screenshots, captured through the production renderer and visually inspected:

- [Dagger above](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-top.png), [Dagger side](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-side.png), [low perspective](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-low-fpv.png), [moving](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-moved.png), [firing](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-firing.png), [13 units](../local/compat-smoke/dagger-depth-final5-1607/dagger-new-crowd.png).
- [Previous actual0.18.0 Dagger side](../local/compat-smoke/dagger-final-1607/dagger-new-side.png) for comparison. The new gallery's disabled-profile views show the original fallback, not the0.18.0 thickness.
- [Dagger FPV](../local/layer-smoke/ground-eye-final5-1607/ground-eye-dagger-fpv.png), [Fortress FPV](../local/layer-smoke/ground-eye-final5-1607/ground-eye-fortress-fpv.png), [Reign FPV](../local/layer-smoke/ground-eye-final5-1607/ground-eye-reign-fpv.png), [Reign under roof](../local/layer-smoke/ground-eye-final5-1607/ground-eye-reign-roof.png).

Actual SDL fixtures use disposable D: profiles and scripted production methods; no original user save is edited. Camera fixture uses a separate valid Alpha builder for its roof and temporarily enables fixture infinite resources, restoring every changed flag/settings/controller afterward. No production permission bypass was added. A sight-test attempt initially mixed the picker’s native-pixel XY with tile coordinates; the final test and new first-person sight distance explicitly use canonical tiles and account for the ray's near-plane origin. No physical mouse/controller gameplay, fresh multiplayer stress run,160.5 test or fullFPS benchmark was performed in this pass.

Installed the exact tested JAR at `play/saves/mods/mindustry-fpv.jar` after the owned test processes exited. All **609** pre-existing non-FPV-JAR files under `play/saves` remained byte-identical; evidence is `local/ground-eye-profile-before.json` and `local/ground-eye-verification.json`. The installer also archived the prior JAR at `local/backups/mod-imports-20261010T131940556Z-93a236da`. Launch normally using `Play-FPV.cmd`.

The .72-tile armor is visual; the original damage/movement hull remains unchanged intentionally. A very low ceiling can force an eye below its normal hull-clearance preference; solid-world clearance wins, and the controlled torso is already omitted from first-person rendering. This is a cached sprite extrusion, not a newly articulated handcrafted vehicle. Only eye offset handoffs are eased; existing possession XY relocation is retained. No multiplayer protocol/save changes or new group/Shift controls.
