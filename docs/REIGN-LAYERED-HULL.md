# Reign: final mech-family hull — 0.18.10

Reign alone receives a cosmetic profile using the existing optimized contour generator and native mech renderer. Previous Dagger/Mace/Fortress/Scepter profile values and geometry paths remain unchanged. No flying/walker models, camera/UI/selection logic, movement/collision, combat balance, construction, power/logistics or multiplayer messages are changed.

## Apex proportions

Reign uses its own original native top silhouette at full scale. Three contours form a broad armored skirt and substantial upper deck: **0.84 /0.96 /1.00** scales, **30% /34% /36%** depth shares, compared with Scepter0.78 /0.94 /1.00 and32% /34% /34%. The native lower chassis scale is **0.76**, versus Scepter0.70; visual hull clearance is **0.14 tiles**, versus0.12.

Depth derives from native hit size divided by tile size, multiplied by **0.30**, clamped **0.72–1.20 tiles**. Native Reign size30 produces **1.125 tiles** of depth, versus Scepter0.825. Native art proportions and broad lower contours give it a distinct larger/heavier form without uniformly enlarging Scepter or adding more layers.

Installed160.7 body artwork is **152×124 pixels**, native scale0.25: **4.75×3.875 tiles**, compared with Scepter128×96 /4×3 tiles. Native `reign-leg` is152×110 and `reign-base`144×84. Native gun art and the broader body pattern retain Reign's original identity.

The existing band validator's maximum height is extended from0.30 to **0.45 tiles** to accommodate three T5 plates (actual0.3375 /0.3825 /0.405). All previous band heights remain identical; generator/shaders are unchanged. The max-depth clamp keeps even the thickest Reign plate below0.45. No additional hidden caps or repeated full roof textures accompany the larger volume.

## Guns, movement and camera

Two original mirrored `reign-weapon` mounts retain native X=±21.5 /Y=1, aiming, reload and recoil. Cosmetic gun depth is **0.28 tiles**; lower faces sit **0.055 tiles above the roof**. Native projectile origins, damage, hitboxes and attack rules are unchanged.

Original foot/base artwork, gait and recoil remain in the existing cached rendering path. The profile retains Reign's prior size-derived camera eye calculation and existing first-person private-depth gun pass. F7/front/rear distance, camera collision and smoothing are tested through the current production camera, without rewriting it.

## Verification and performance

Measured installed-art body geometry, using identical new Reign bands and sampling budgets for both generator paths:

| Generator | Vertices | Triangles | Horizontal quads | Side quads | Retained CPU + GPU vertex bytes |
|---|---:|---:|---:|---:|---:|
| Naive full-band layered | 6900 | 2300 | 318 | 832 | 441600 |
| Existing optimized exterior | 4716 | 1572 | 284 | 502 | 301824 |

Approximately **31.7% fewer triangles and retained vertex bytes**:294.75KiB versus431.25KiB. This compares the same new layered profile, not the former flat Reign model. Memory counts retained Arc CPU vertices plus their GPU VBO,8 floats per vertex; shared atlas, metadata and temporary arrays are excluded. The native roof texture occurs once, lower surfaces use sampled materials, and hidden caps are omitted.

An isolated Reign uses **3 body/weapon draws +1 native movement/chassis draw**, one hull mesh and one existing atlas texture. Thirteen instances reuse cached hull/weapon/limb meshes with no additional builds. Scepter retains3804 body vertices /1268 triangles and its original six-gun layout.

Fixtures use Mindustry160.7 SDL/OpenGL, installed original atlas art, scripted native input and disposable map/save profiles. A warm13-unit Scepter/Reign render comparison measures submission CPU time and available asynchronous GPU queries; it excludes AI, swap/vsync and physical input and is not a full gameplay FPS benchmark. Same camera/XY positions, native ground Z,24 warm renders then64 measured submissions per S/R/S/R phase:

| Phase | CPU elapsed mean, ms | CPU maximum, ms | GPU mean, ms | Available GPU samples | Geometry builds |
|---|---:|---:|---:|---:|---:|
| Scepter0 | 0.400277 | 0.9009 | 0.181150 | 63 | 0 |
| Reign1 | 0.380630 | 1.6245 | 0.203516 | 63 | 0 |
| Scepter2 | 0.387819 | 0.9485 | 0.192382 | 63 | 0 |
| Reign3 | 0.256172 | 0.7359 | 0.195308 | 63 | 0 |

Equal-phase means: **CPU0.318ms Reign /0.394ms Scepter; GPU0.199ms Reign /0.187ms Scepter**. The larger model has fewer native gun draws and slightly greater GPU work in this small static scene. These timings do not establish full-game FPS gains or performance in large moving armies. Raw durations/statuses: `reign-render-profile.txt`.

## Actual validation

Final build passed **all84 Java suites**, including327 Reign hull, previous321 Scepter and2234 exterior-generator assertions. Exact final `reign-final2-1607` passed **42 Reign model checks** and **18 possession/first-person/fire/F7 checks**, including front/rear cameras with smoothing OFF and ON and visible owner meshes. Top/side/ground/oblique, native movement/firing/recoil,13-instance reuse, direct Scepter comparison and all-five-unit lineup were captured and inspected. Default eye height remains the pre-profile calculation; no doubled first-person/world guns.

Previous32 Dagger /31 Mace /35 Fortress /41 Scepter native model checks, prior control checks and20 ground-family gallery passed with unchanged geometry/profile values. Scepter's centered reference hull/gun/foot pixel region matched before/after Reign cache/render activity. The initial full-frame assertion caught an unrelated actor changing only the top9-pixel strip; an explicit target-region comparison corrected the fixture, with full screenshots retained. No model change was needed.

Three maps, copied user saves and native save/load roundtrip passed, with GL errors zero. Independent final layer run passed209 construction,685 camera,293 elevated-turret,83 first-person GPU,39 ground-eye,40 command and previous schematic/placement/picking checks. Scripted native input, not physical hardware input.

Screenshots and raw reports are in `local/compat-smoke/reign-final2-1607/`:

- [All five mechs](../local/compat-smoke/reign-final2-1607/reign-family-lineup.png), [beside Scepter](../local/compat-smoke/reign-final2-1607/reign-next-to-scepter.png), [top](../local/compat-smoke/reign-final2-1607/reign-new-top.png), [side](../local/compat-smoke/reign-final2-1607/reign-new-side.png), [oblique](../local/compat-smoke/reign-final2-1607/reign-new-oblique.png).
- [Moving](../local/compat-smoke/reign-final2-1607/reign-new-moving.png), [firing](../local/compat-smoke/reign-final2-1607/reign-new-firing.png), [first-person](../local/compat-smoke/reign-final2-1607/reign-controlled-first.png), [rear](../local/compat-smoke/reign-final2-1607/reign-controlled-rear.png), [smooth rear](../local/compat-smoke/reign-final2-1607/reign-controlled-rear-smooth.png), [crowd](../local/compat-smoke/reign-final2-1607/reign-new-crowd.png).
- `reign-model-verification.txt`, `reign-render-profile.txt` and `results.json` contain metadata, geometry, timings and assertions; `local/layer-smoke/reign-final2-1607/layers-result.json` contains independent regressions.

Final0.18.10 JAR: **3,883,205 bytes**, SHA-256 `868dd99b1ef15ab11fa52e4664d178d75647b8856d9a033d74d1f3182511e0ed`. Installed the exact tested package; all **639 original non-mod profile files** remained byte-identical.

Visual limits remain the family style: pixel-stepped contours, sampled side materials and existing lightweight original movement geometry. No handcrafted barrel articulation or smooth bevels are added; gameplay collision envelopes remain native, independently of cosmetic hull thickness. No major clipping was observed in the requested views. No fresh manual-input/network/full-game FPS improvement claim.

Working0.18.9 backup: `local/backups/before-reign-0.18.9/mindustry-fpv-0.18.9.jar`.
