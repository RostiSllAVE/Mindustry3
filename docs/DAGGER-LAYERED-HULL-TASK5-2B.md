# Task 5.2b — Dagger layered hull (0.18.2)

Started on clean `feature/ui-polish` at `b650fbc`. The exact previous 0.18.1 package is retained at `local/backups/before-layered-hull-0.18.1/mindustry-fpv-0.18.1.jar`, SHA256 `9e37b30473016cddd255a22665b092f70cdb95535a5f3f0f4458d2cf1bea1715`.

## Hull and attachments

The tall .72-tile full-silhouette extrusion made Dagger look like a drum. Its profile now has three thin, hard-edged silhouette bands. Dimensions below are tiles on level terrain with the unchanged canonical body center at Z.12:

| Band | XY silhouette scale | Thickness | Actual Z interval |
|---|---:|---:|---:|
| Original upper hull |1.00|.12|.28–.40|
| Middle armor |.84|.10|.18–.28|
| Lower armor |.66|.08|.10–.18|

The top retains the native Dagger atlas artwork and team-cell overlay. Lower masks are centered scaled silhouettes, intersected with the original alpha mask so the original holes and transparent top pixels remain empty. Geometry follows the pixel grid; there are no radial meshes, cylinders, smoothing or rectangular enclosing boxes. Band scales/heights are immutable, adjustable model-profile data.

The existing alpha-extrusion generator supplies each band's top/bottom/side faces. They are combined into **one cached body mesh**, with the same lighting, atlas sampling, orientation and per-unit uniforms as before. The native team-cell overlay applies only to the highest cap. All bands share the existing vertex/byte/entry limits and one-build-per-frame budget. The separate profile entry prevents mixing the compact mesh with the ordinary extrusion used by other units or the old/new fixture. Cache eviction, atlas invalidation and disposal include the profile entry; no game-owned textures are disposed.

The underside hub reuses the original `dagger-base` sprite at **.60 XY scale** (approximately .488 × .450 tiles for the actual installed atlas). Its original bottom remains above the ground; its upper face meets the lower hull with .008 overlap. On tilted poses it may extend upward to keep that joint closed. Native two-leg gait, central attachment origin, support heights and ground contact remain unchanged; the hub surrounds those original roots rather than attaching them to the upper hull's outer edge. There is no new movement or leg solver.

The native mirrored gun mounts retain XY offsets, aim rotation, native base rotation and recoil. Their **visual center is .36 tiles above the canonical body frame**, Z.48 on flat ground. With weapon thickness .12, the bottom is Z.42: **.02 above the hull roof at Z.40**. They are mounted on the roof instead of buried in the old barrel. This is a presentation-only mount correction: the existing authoritative shot origin, hitboxes, aim/cone behavior and damage are unchanged. Exact visual-muzzle/projectile-origin alignment remains outside this correction.

## Preserved camera and scope

Camera clearance and rendered hull height now have separate profile envelopes. Dagger's eye calculation retains the proven .72-depth reference envelope from 0.18.1, even though its visible hull is compact. Other units use their existing envelope. Ordinary-ground sizing, per-type eye override, ceiling probe/skin, easing, first-person sight-pick convergence and input are unchanged. Actual culling and marker height use the new visible hull rather than the obsolete barrel envelope.

Only Dagger registers this layered profile. Flying units, Legsc/walker models, other ground models, combat, physics, layered construction, logistics, protocols and saves retain their current behavior. The compatible original profile constructor and native-limb overload remain available.

## Verification

Development SDL runs `layered-hull-dev-1607` passed on installed Mindustry160.7. The Dagger fixture passed32 checks, including original alpha/native art, bands/roof/weapon clearance, four camera angles, native move/turn/fire,13 simultaneous cached instances and immutable map/physical state/HP. Visually inspected the top, side, oblique and controlled first-person screenshots before final packaging. The actual model assembly is **2,084 triangles / 6,252 unindexed vertices**, including body3,372, two native guns576 each and native limbs1,728. The earlier single-extrusion assembly was1,428 triangles; the increase is bounded, generated once and does not add body draw calls. This is a cache/geometry check, not a full-scene FPS claim.

Final package: `dist/mindustry-fpv-0.18.2.jar`, **3,773,739 bytes**, SHA256 `cdc3d80eada944cb18c3e85e4bf52e1130a132f9ba78eb779f5454f88bafb428`. Both SDL receipts match this exact package; the installed `play/saves/mods/mindustry-fpv.jar` also matches. All **612 original profile files** outside the updated mod remained byte-identical.

- `local/build-layered-hull-final.log`: **all79 Java suites passed**, including10,042 new layered-hull geometry/profile assertions,104 existing procedural-profile assertions,11,713 existing silhouette assertions and ground-eye sizing/easing/roof tests.
- `local/compat-smoke/layered-hull-final-1607/results.json`: **passed**, three maps and disposable copies of user saves/roundtrip; FPV/Build controls, native and true-3D combat, terrain and20 native ground models unchanged; **GL0**. `dagger-model-verification.txt` records **32 Dagger checks**, native movement/turn/shots, four camera angles and13 cached simultaneous models.
- `local/layer-smoke/layered-hull-final-1607/layers-result.json`: **39 production-camera checks**, **209 construction checks**, **32 first-person GPU checks**,9 preview GPU checks and7 display GPU checks passed. Camera takeover/release, per-type override, aiming, an actual Z1 roof/ceiling recovery and settled flying/legged camera behavior remain working.

Final flat-ground measurements reproduce0.18.1: Dagger eyeZ **1.650**, Fortress **1.533**, Reign **2.945** tiles. Under the Z1 platform with undersideZ1.24, Reign eyeZ **1.145**, preserving the .08 probe radius and .015 skin. Physical body XYZ and hitboxes are unchanged.

Production-renderer screenshots captured and visually inspected:

- [Original silhouette from above](../local/compat-smoke/layered-hull-final-1607/dagger-new-top.png).
- [Layered side profile](../local/compat-smoke/layered-hull-final-1607/dagger-new-side.png), [oblique view](../local/compat-smoke/layered-hull-final-1607/dagger-new-oblique.png), [ground-level view](../local/compat-smoke/layered-hull-final-1607/dagger-new-low-fpv.png).
- [Native movement](../local/compat-smoke/layered-hull-final-1607/dagger-new-moved.png), [native firing](../local/compat-smoke/layered-hull-final-1607/dagger-new-firing.png), [cached group](../local/compat-smoke/layered-hull-final-1607/dagger-new-crowd.png).
- [Controlled Dagger first person](../local/layer-smoke/layered-hull-final-1607/ground-eye-dagger-fpv.png), [Fortress](../local/layer-smoke/layered-hull-final-1607/ground-eye-fortress-fpv.png), [Reign](../local/layer-smoke/layered-hull-final-1607/ground-eye-reign-fpv.png).

These are real SDL/GPU integration fixtures with scripted production API input, not a manually played mouse/controller session. No fresh multiplayer/160.5 stress test or full-scene FPS benchmark was performed for this cosmetic change. No protocol, save-format, hitbox or combat-balance changes; no push or merge.
