# Fortress layered hull — 0.18.8

Fortress alone receives a new presentation profile. It reuses Mace's existing optimized exterior hull generator and renderer: native alpha silhouette, one full textured roof, nested contour sides, exposed underside/overhangs and merged straight boundary faces. No new texture objects, hidden internal caps, per-instance generation or new renderer pass.

## Proportions and attachments

Fortress retains its native body dimensions instead of scaling Mace's art. Depth is native hit size divided by tile size, multiplied by **0.40**, clamped to **0.36–0.75 tiles**. For native hit size13 this gives **0.65 tiles**, versus Mace's unchanged0.425. Lower/middle/top contour scales are **0.72 / 0.90 / 1.00**; depth shares28% /32% /40%. Mace retains0.62 /0.82 /1.00 and22% /30% /48%. Broader lower armor makes Fortress denser rather than simply uniformly enlarging Mace.

Installed160.7 body artwork is **78×64 pixels**, at native scale0.25: **2.4375×2.0 tiles**, compared with Mace's56×48 /1.75×1.5 tiles. The original `fortress-leg` is78×60 and `fortress-base` is54×44. No artwork is copied into the release.

The original underside base uses a **0.64** chassis scale and **0.10-tile** hull clearance, versus Mace's0.54 and0.08. Native two-foot movement and gait remain in the existing mesh/animation path.

Both original `artillery` weapons retain native mirrored X=±9, Y=1 placement, aiming and **4-world-unit recoil**. Their visual extrusion depth is0.18tiles; lower faces sit **0.035 tiles above the roof**. Neither their native firing parameters nor actual XYZ projectile origins change. The previous Fortress eye height and F7/zoom behavior are preserved; the profile has a zero camera-reference override, as Mace does.

Dagger/Mace profiles and meshes, other unit families, hitboxes, health, armor, movement/AI, combat, camera logic, construction, selection, power/logistics and multiplayer protocols are unchanged.

## Geometry measurements

Using installed native art and the same Fortress profile/sampling budget for both paths:

| Hull generator | Vertices | Triangles | Horizontal quads | Side quads | Retained CPU + GPU vertex bytes |
|---|---:|---:|---:|---:|---:|
| Naive full-band layered | 5268 | 1756 | 236 | 642 | 337152 |
| Existing optimized exterior | 3474 | 1158 | 235 | 344 | 222336 |

Approximately **34.1% fewer triangles/vertex bytes**, **217.125 KiB instead of329.25 KiB**. This comparison uses the same new layered profile, not the earlier flat Fortress model. Retained memory includes8-float vertices in Arc's CPU storage and GPU VBO; shared atlas, metadata and temporary generation arrays are excluded.

An isolated Fortress requires **3 body/weapon draws and1 native movement/chassis draw**. Its hull is one mesh using one existing atlas texture. Thirteen visible Fortress units share cached hull, weapon and movement meshes without additional builds after warm-up. The generator itself is unchanged from Mace. No FPS or draw-count improvement is claimed.

## Verification

Final build passed **all82 Java suites**, including **344 Fortress hull assertions** and the previous2234 optimized-generator assertions. Tests use installed Mindustry160.7 SDL/OpenGL, actual atlas art, scripted native input and disposable map/save profiles.

Exact final package `fortress-final-1607` passed **35 Fortress model checks** and **10 possession/first-person/front/rear/trigger checks**. Captures cover top, side, ground level, oblique, movement, actual native artillery firing, first-person, rear follow,13-unit cache reuse and an actual side-by-side Mace comparison. Mace framebuffer pixels before/after Fortress cache/render activity match exactly. Previous31 Mace checks/10 controls,32 Dagger checks and20 ground-family gallery passed; Dagger/Mace geometry counts remain unchanged. Three maps, copied user saves and native save/load roundtrip passed, with GL errors zero.

Independent final layer run passed209 construction,685 camera,293 turret,83 first-person GPU,39 ground-eye,40 command and existing schematic/placement/picking checks. No camera/gameplay/protocol source was changed. Manual hardware play, a fresh multiplayer session and a full-scene FPS improvement are not claimed.

Screenshots and raw reports are in `local/compat-smoke/fortress-final-1607/`:

- [Top](../local/compat-smoke/fortress-final-1607/fortress-new-top.png), [side](../local/compat-smoke/fortress-final-1607/fortress-new-side.png), [oblique](../local/compat-smoke/fortress-final-1607/fortress-new-oblique.png), [Fortress beside Mace](../local/compat-smoke/fortress-final-1607/fortress-next-to-mace.png).
- [Moving](../local/compat-smoke/fortress-final-1607/fortress-new-moving.png), [firing/recoil](../local/compat-smoke/fortress-final-1607/fortress-new-firing.png), [first-person](../local/compat-smoke/fortress-final-1607/fortress-controlled-first.png), [rear follow](../local/compat-smoke/fortress-final-1607/fortress-controlled-rear.png), [crowd](../local/compat-smoke/fortress-final-1607/fortress-new-crowd.png).
- `fortress-model-verification.txt`, `mace-model-verification.txt`, `dagger-model-verification.txt` and `results.json` record native geometry/metadata/assertions. `fortress-old-*` captures the former generic model; numeric naive-layered comparison uses identical new profile bands.
- `local/layer-smoke/fortress-final-1607/layers-result.json` records the independent regressions.

Final0.18.8 JAR: **3,850,890 bytes**, SHA-256 `5e48dd9dff4c747dafdc4596a3035cb9c6a90a2a2e4e47f27eca82b2977bae31`. Installed this exact tested package in the existing play profile. All **633 original non-mod profile files** remained byte-identical.

Visual limits remain the family style: hard pixel-stepped contours, simple edge-sampled side materials and existing lightweight native leg geometry. No individually sculpted turret barrels or smooth bevels are introduced.

Working0.18.7 backup: `local/backups/before-fortress-0.18.7/mindustry-fpv-0.18.7.jar`.
