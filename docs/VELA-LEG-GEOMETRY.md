# Vela articulated native-art legs — 0.18.12

Vela is a native **Mechc**, with two mirrored whole-leg sprites. It is not a four-leg Corvus rig: Corvus is **Legsc**, with four legs and separate upper/lower, joint and foot regions. Installed 160.7 Vela metadata is hitSize24, speed0.44, health8200; native mechStride derives from the standard unit-size formula. The resolved artwork is `vela-leg`122×104 and `vela-base`98×74; the hull is142×116. Native gait is bilateral, with its original base rotation and stride. No unit definition is edited.

## Cause and correction

The previous Vela path stretched each complete leg sprite along one hip-to-foot affine transform. There was no knee. The artwork also contains a large transparent horizontal offset, because vanilla mirrors the whole region around the body. Extruding and stretching that atlas rectangle produced slender, displaced crutches.

`VelaLegGeometry` finds the real alpha bounds and subdivides the original artwork once into upper armour, lower/shin, knee socket and toe crops. Every crop retains UV coordinates in the live original atlas. The existing silhouette extrusion supplies textured face/side geometry. No replacement texture, cloned atlas or copied game art is packaged.

`NativeLimbRenderer` selects this path only for the Vela mech. It places two hips under the body's flanks, solves two articulated chains with the existing pole-vector IK, overlaps link ends inside the native-art knee socket and plants native toe meshes at the existing shared gait feet. Both knees have mirrored outward and consistent forward bending. At short stance the cosmetic link length retracts to prevent a folded thigh rising through the body. At tall stance the same chains telescope toward the physical endpoints. The folded creator-flight pose remains a visual transformation of this same rig.

Four parts per leg plus the original base plate share one affine palette and cached atlas batch. Near/coarse LOD geometry is cached by unit type under the existing48MiB cache limit. Per frame work consists of two small IK solutions and affine updates; no per-leg dynamics, allocations, texture generation or mesh rebuilds are introduced.

## Preserved systems

The body extrusion, weapons, combat, health, movement statistics, hitboxes, standing solver, Q/E input, extension speed penalty, native bilateral foot layout, terrain/layer support, camera and network protocols are unchanged. The generic `renderLegs` path and ordinary whole-sprite mech geometry remain unchanged. Corvus, purple walkers and flying types do not enter the new Vela path.

The prior working0.18.11 package is retained at `local/backups/before-vela-0.18.11/mindustry-fpv.jar`, SHA-256 `78ca4bdf0b1e5a8e4553b4811136dd550122a6648cb584d4187485049851cd5a`. Source is the preceding Git commit.

## Verification approach

`VelaLegGeometryTest` checks off-centre alpha cropping, original UV bounds, crop overlap, two mirrored stable knees, yaw, connected links at short/tall stances, and a two-foot gait over unequal terrain. It does not need copied game assets.

The native SDL fixture reads actual installed artwork, reaches minimum/maximum clearance through the existing stance-control boundary, checks grounded state and speed penalty, renders top/side/oblique views, plants alternating feet on sand/a real copper wall and exercises production FPV/rear control. The all-family gallery advances real native Vela movement and turning across separate engine frames, before capturing its ordinary views. Existing green-walker checks exercise real AI/player updates, solid-wall traversal, native charged Vela firing and intentional boosting. The other20-family, model/control, map/save and camera/build regressions remain available.

Test profiles and copied saves are disposable and live under `local`; keyboard/controller hardware is not automated. Original Corvus code/art/leg count remain intact and its native views are captured and inspected. No claim of pixel-identical whole frames is made: unit IDs affect standing-height variation, and scene actors/time can differ.

## Visual limits

This remains a lightweight pixel-silhouette mesh, with hard/stepped contours and simple socket/toe volumes. Maximum extension deliberately produces long telescoped links because the requested existing physical height range is preserved. Vela's body and first-person weapon presentation are outside this task. No full-game FPS improvement or fresh multiplayer/hardware test is claimed.
## Release geometry measurements

For the installed Vela artwork, the near limb/base cache contains **6552 vertices /2184 triangles**, in **one atlas batch** for both articulated legs and the base plate. With the existing nine-float vertex format, retained CPU+GPU vertex storage is **471744 bytes** for this near entry; the separately bounded coarse LOD is additional. Multiple instances share type/LOD geometry. Repeated warm renders produce zero new limb builds. This task adds articulation rather than claiming a triangle-count or FPS reduction.

The release build passed all **88 Java suites**, including **10414** Vela crop/UV/IK/gait assertions. Full native release verification results follow below.

## Actual final release results

Final package: **3920650 bytes**, SHA-256 `6d988bb6928a0eedf84c2f611736002ae8308a53f8519917b2e5d3f8ac80f8de`. This exact JAR passed the native runs and was installed into `play/saves/mods/mindustry-fpv.jar`; all **645** original non-FPV-JAR profile files retained their hashes.

| Check | Actual result |
|---|---|
| Build |88 Java suites passed;10414 new Vela alpha/UV/IK/gait assertions |
| Native Vela |27 artwork/two-foot/height/uneven-support/FPV/rear/cache checks; zero GL errors |
| Walking/turning | Separate real native frames; explicit translation/yaw assertion and start/turn captures |
| Green stance | Existing Vela/Corvus minimum/maximum/Q-E/speed/AI-player wall traversal/charged firing/intentional boost checks passed |
| Other models | All20 native ground families; previous Dagger/Mace/Fortress/Scepter/Reign models and control checks; Corvus original four-leg views captured |
| Combat regression | Existing176 mech pitch SDL checks passed; combat code unchanged |
| Compatibility | Three maps/copied saves and native save roundtrip passed |
| Construction/camera |209 construction,685 camera,293 turret,83 first-person GPU,39 ground-eye and40 command checks; existing picking/placement/schematic checks passed |

Receipts/screenshots: `local/compat-smoke/vela-release-1607`, `local/layer-smoke/vela-release-1607`, `local/vela-verification.json`, and build output `local/vela-release-build.log`. During development, external-view captures were corrected to show the controlled hull; the movement fixture now starts after stance settling rather than relying on one exact warm-frame number, and asserts real translation/turning. No production movement fix was needed. No fresh multiplayer/hardware/FPS test or push/merge/publication.
