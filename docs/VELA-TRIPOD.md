# Vela visual tripod — 0.18.14

Vela keeps its accepted0.18.13 body, two existing leg attachment points, native Mechc classification and two-foot authoritative support solver. The extra rear-center leg is an intentional **render-only customization**. No native UnitType, movement, stance, camera, combat, AI, damage, networking or collision rule is changed.

## Layout and geometry

`VelaTripodLayout` preserves the first two native lateral foot offsets exactly. Rear hip is centered0.50 hit radius behind the body; rear nominal foot is1.35 native spread behind it. The three hips form a triangular underside layout. All legs reuse the same native-art upper/shin/socket/toe silhouette pieces and original live-atlas UVs. Rear segments use a perpendicular armour-face frame, and the existing two-link IK with a centered rear pole produces its knee. No new art or body generator is introduced.

The immutable near/coarse limb buffers now contain three copies of the existing cached chain plus the original base,13 affine parts total. One atlas batch still draws the whole rig. Original hull mesh/profile remains unchanged. A Vela-only render-culling envelope includes tall rear-knee bending; this does not enlarge the gameplay hitbox.

## Three stepping phases

`GroundGait.velaTripod()` is an explicit visual opt-in. Ordinary paired constructors/layouts remain unchanged and still reject odd native leg counts. The tripod schedules one swinging foot at a time with three staggered phases; at least two feet remain planted during ordinary updates. Planted XY stays anchored while the selected foot interpolates toward its own next target with the existing eased lift/surface clamp. An overdue phase can be serviced when the next nominal phase is already settled, preventing a stranded rear foot after a stop, reversal or turn.

`GroundFamilyRenderer` tracks three visual feet only for Vela; `GroundUnitProfiles` and `NativeMechLayout` still supply two native physical support samples. The rig consumes authoritative body XYZ and cannot grant extra standing height, traversal, stability physics or damage. Body motion is not animated or modified by this gait.

The existing render surface callback omitted elevated construction surfaces. Vela alone now uses the existing `LayerPhysicalQueries.supportBelow` below its current hip height, alongside the existing native terrain/block surface. Overhead platforms are excluded. This fixes visual foot planting on reachable upper structures without changing the authoritative two-foot solver or other unit renderers.

The existing folded/creator-owned presentation path resets the visual gait and uses tucked local endpoints instead of sampling terrain. The rear leg also fits below the body in that path. This is an integration seam for future transformation work, not a new boost toggle, flight state, landing system or physics mode.

## Verification and scope

`VelaTripodTest` checks the unchanged bilateral offsets, triangular hips, centered rear knee, pure forward/reverse/turn phases, independently swinging feet, world-anchored support, unequal terrain, pause/reset and bounds. Prior paired-rig, Vela hull and native physics tests remain.

The native gallery scripts360 separate engine frames of forward/backward/left/right motion. It asserts actual movement/turning, observes every leg swing and verifies at least two planted feet on every frame. Dedicated native checks verify the unchanged hull/native two-foot metadata, stable XYZ/height, Q/E, a real elevated rear support, FPV/rear/down views, scripted production player movement and warm mesh reuse. Other20-family/model/control/map/build/camera regressions remain. Hardware input is not automated.

The working0.18.13 JAR is preserved at `local/backups/before-vela-tripod-0.18.13/mindustry-fpv.jar`. User gameplay was active at the start: no process was stopped and live saves were not replaced or used by the isolated tests. Final package identity/results follow below.

## Limits

The extra foot provides no physical support advantage. At high manual extension all three chains remain long; pixel-stepped contours and inexpensive overlapping sockets are retained. Depending on angle the body legitimately occludes some legs; rear/oblique views show the tripod clearly. No full-game FPS or fresh network/hardware claim.

## Actual release results

Final0.18.14: **3926921 bytes**, SHA-256 `62df2b4edf6b0e15c9874466d0ce2c62cb12a20d51bc0ec78557c39e3535d5e5`, installed into the play profile. The user closed the previous game while work proceeded; no process was stopped. All **651** non-mod profile files hashed immediately before installation stayed unchanged through installation. Earlier active-game autosaves/log writes are user gameplay, not a mod migration.

| Check | Actual result |
|---|---|
| Full build |90 Java suites passed;6082 new tripod assertions,10414 existing Vela leg checks and330 unchanged hull checks |
| Final native gallery |47 Vela art/tripod/native-two-foot/height/terrain/platform/FPV/player movement/cache checks; all20 native ground families; GL0 |
| Real-frame phases |360 forward/backward/left/right native engine frames; every leg swung and at least two were planted on every observed frame |
| Core compatibility |Three maps/copied saves/native roundtrip and176 original mech-pitch checks passed |
| Layer/camera regression |209 construction,685 camera,293 turret,83 first-person GPU,37 generic eye and40 command checks plus prior picking/placement/schematics |
| Original gameplay |Green native AI/player wall/beam/boost regressions passed; native physical supports remain2 |

Final near limb/base geometry is **9456 vertices/3152 triangles/680832 retained CPU+GPU vertex bytes**, versus6552/2184/471744 before the extra chain. The addition is2904 vertices/968 triangles/209088 bytes per cached near type entry. The unchanged hull stays4608 vertices. All13 limb/base transforms still use **one atlas batch**, and warm repeated views produce zero hull/limb builds. No FPS improvement is claimed.

The initial elevated-foot test correctly failed because the old render callback ignored upper layers; fixed for Vela using the existing bounded support query. Rear-view capture orientation/position was corrected for an upright, unobstructed view of all three legs. Full compatibility/layer runs used SHA `c1c861b4c80ef1b471283fa0c28e8d8d0fce6fadff126ba8b00435d72e856d3d`; the final package differs byte-for-byte only in `fpv/VelaLegSmokeChecks.class` for that screenshot camera position. All gameplay/rendering classes are identical. The final package was rebuilt, focused geometry suites rerun and its native gallery passed47 checks. This avoids claiming a fresh three-map run for the camera-only fixture adjustment.

Receipts: `local/vela-tripod-release-build.log`, `local/compat-smoke/vela-tripod-release-1607`, `local/layer-smoke/vela-tripod-release-1607`, `local/compat-smoke/vela-tripod-final-gallery-1607`, `local/vela-tripod-fixture-comparison.json`, `local/vela-tripod-verification.json`. Screenshots cover normal rear/oblique/top, four movement phases, unequal/elevated supports and controlled rear/down FPV. No fresh hardware/network test, push, merge, reset or publication.
