# Vela heavy mech presentation — 0.18.13

Vela remains its native **Mechc**, with two legs and original hitSize24, speed0.44, health8200, weapons and boost capabilities. The rendering entry point is the existing `GroundFamilyRenderer.renderMech`, alongside Dagger/Mace/Fortress/Scepter/Reign. No Legsc conversion, spider rig, new mode switch, landing physics or boost system is added.

## Body and legs

The new Vela-only `ProceduralUnitModelProfile` reuses the established exterior-only `OptimizedHullGeometry`. Native142×116 roof art stays at full scale. Actual size24/8 ×0.32 gives **0.96 tiles** of hull depth, clamped0.70–1.12. Three underside-to-roof contours use scales0.82/0.96/1 and depth shares28/36/36%; the original98×74 chassis is scaled0.72 and seated under this hull. The native green silhouette, pixel edges and sampled side colours remain recognizable. Lower layers do not repeat full top artwork or produce hidden internal caps. No geometry generator or prior mech profile is redesigned.

Both legs still use the cached cropped native122×104 whole-leg artwork from0.18.12. Armour faces are wider (up to0.90 hit radius instead of0.67), with0.40–0.64-tile depth instead of0.16–0.32. Lower segments and socket proportions are broadened; toes are0.28 tiles deep, with10% extra width and15% length. Hips attach at0.50 hit radius to the original underside/chassis. Cached affine updates/IK preserve the existing two support endpoints and live gait; toe bottoms remain on their sampled surface. There are no extra legs or heavyweight limb dynamics.

The height-capability profile still supplies Q/E and terrain support. Its internal `LEGGED` capability flag is not a native unit conversion or choice of visual pipeline; native Mechc is routed into the mech renderer first. Corvus and purple native Legsc units retain the existing walker renderer.

## Resting height and camera

The user explicitly selected a lower ordinary stance with Q/E and maximum extension retained. `GroundStanceState.defaultHeight` therefore selects **1.10 tiles of clearance** for Vela alone, bounded by its existing limits. Manual desired height, saved intent/offset format, minimum/maximum clearance, extension penalty formula and terrain/layer solver remain unchanged. Other unit defaults retain the previous half-maximum-plus-stable-offset formula. An uncontrolled saved Vela at an old tall clearance descends through the existing smooth solver; explicit manual stance remains available. Reduced resting extension naturally reduces the existing extension-related slowdown; native speed statistics are not retuned.

Grounded Vela now participates in the existing size/profile-derived `GroundEyeHeight` path, rather than the0.72-tile walker-eye fallback. The eye clears the new roof, retains per-unit overrides/smoothing/ceiling traces and uses the normal ground-mech sight picking. Flying native states retain their existing exclusion. First-person viewmodels, camera controls and the weapon system are not redesigned; existing native weapon/recoil animation paths are reused.

## Caching and future work

The hull shares the existing native-atlas/model cache. Two leg chains and chassis retain one cached atlas batch per LOD; thicker affine dimensions do not add triangles. No per-frame geometry/texture generation is introduced. Body, limb, grounded stance and existing creator/runtime ownership remain separate so a later boost presentation can be added deliberately. This pass does not add that transformation.

## Verification

`VelaMechHullTest` checks compact adaptive exterior bands, original roof scale, preserved other hull profiles, Vela-only resting height, unchanged Corvus default formula and the existing manual extrema. The prior10414 leg/UV/gait checks remain. Native SDL checks use actual installed art and production code: normal/resting top/oblique, moving/turning frames, min/max Q/E, unequal native supports, controlled FPV/rear, camera above the new roof and zero warm hull/limb builds. The prior Dagger profile whitelist was extended only to admit Vela, and the ordinary-eye exclusion fixture now leaves Vela to its dedicated mech-camera checks.

Tests run in disposable D: profiles; physical keyboard/controller input is not automated. Full regression results and package identity are recorded below after verification. Original working0.18.12 is backed up in `local/backups/before-vela-mech-0.18.12/mindustry-fpv.jar`.

## Limits

At deliberately high Q/E extension the legs still telescope over the preserved height range; the new compact ordinary stance is the intended normal mech presentation. Geometry remains pixel-stepped and the original first-person integrated-weapon presentation remains. Hitboxes are not enlarged to the cosmetic hull. No fresh multiplayer/hardware/FPS improvement is claimed.

## Actual final release results

Final0.18.13: **3922054 bytes**, SHA-256 `3cdfa6754e1b2890d2a4a0d05532ff3239cf093940faec4fd997824e2a30de3c`. This exact JAR passed both final native runs and was installed in `play/saves/mods/mindustry-fpv.jar`. All **648** original non-FPV-JAR profile files retained their hashes.

| Verification | Actual result |
|---|---|
| Full build |89 Java suites passed;330 new mech-hull/resting-height checks and10414 existing Vela leg/UV/gait checks |
| Actual Vela |33 native-art/mech-hull/resting stance/height/uneven support/FPV/rear/cache checks; real separate-frame walking/turning assertion |
| Native geometry |Hull4608 vertices/1536 triangles; leg/base6552 vertices/2184 triangles in one atlas batch; repeated warm hull/limb renders generated zero new meshes |
| Preserved models |All20 native ground families and previous Dagger/Mace/Fortress/Scepter/Reign model/control checks passed |
| Existing Vela mechanics |Green stance/manual extremes/speed penalty/native AI-player wall traversal/charged beam/intentional native boosting tests passed |
| Maps/saves |Three native maps/copied saves and native roundtrip passed; OpenGL error0 |
| Camera/build |209 construction,685 camera,293 turret,83 FPV GPU,37 generic ground-eye and40 command checks; prior picking/placement/schematic checks passed |

Hull retained CPU+GPU vertex storage is294912 bytes; the unchanged near leg/base topology uses471744 bytes. These are per-type cache measurements, with coarse LOD and shared resources additional; no FPS improvement is inferred. Captured and inspected original roof, normal side/front/oblique, moving/turning, FPV/rear, min/max and independent support views. Initial gallery stopped at an obsolete five-profile whitelist; it was extended to Vela. A subsequent diagnostic used the wrong geometry-stat accessor and failed compilation; corrected before the final successful build. No gameplay workaround or unrelated model change was needed.

Receipts/captures: `local/compat-smoke/vela-mech-release-1607`, `local/layer-smoke/vela-mech-release-1607`, `local/vela-mech-verification.json`, `local/vela-mech-release-build.log`. No new flight transformation, fresh multiplayer/hardware/FPS claim, push, merge or publication.
