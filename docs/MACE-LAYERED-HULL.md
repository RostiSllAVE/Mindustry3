# Mace: optimized layered hull — 0.18.7

This pass adds a presentation-only model profile for Mace. Dagger retains its existing legacy geometry and parameters. Other unit families, physics, hitboxes, combat, camera controls, construction, logistics and multiplayer messages are unchanged.

## Hull generation

The existing Dagger generator joins three independent silhouette extrusions into one cached mesh. Each band includes full textured upper and lower caps, including hidden overlapping horizontal faces. It already shares the installed Mindustry atlas and uses one body draw: it does **not** duplicate texture objects or issue a draw per band.

Mace opts into `OptimizedHullGeometry` through the existing profile and mesh cache. Its native alpha mask produces aligned nested contours. Only the roof maps the full original sprite. Connected boundary faces form the three hard-edged armor bands; contiguous straight boundaries merge into single quads. The generator omits internal caps and retains only the small underside and exposed downward overhangs. Sides and underside sample opaque source-art colors from the shared atlas. There are no new texture assets or radial/cylindrical shapes.

The grid/vertex budget and smaller-grid fallback remain bounded. Generation occurs on cache warm-up, not every frame or instance. The native team-cell overlay, movement components, weapon art, aiming, gait and recoil remain in the existing renderer.

## Mace proportions

Native 160.7 Mace has a 56×48 body sprite, 10-world-unit hit size, `mace-leg` and `mace-base` artwork, and two mirrored flamethrower mounts at X=±5, Y=0. These native dimensions and mounts are retained.

Hull depth is `(hitSize / tilesize) × MACE_THICKNESS_FACTOR`, with factor **0.34** and clamps **0.24–0.60 tiles**. Mace therefore receives **0.425 tiles** of body depth, compared with Dagger's unchanged **0.30**. Bottom-to-top contour scales are **0.62 / 0.82 / 1.00**, with 22% / 30% / 48% of the depth. Dagger retains 0.66 / 0.84 / 1.00. Mace uses a 0.54-scale native underside chassis and 0.08-tile clearance; Dagger retains 0.60 and 0.10.

Mace's weapon depth is 0.14 tiles. The mount elevation derives from support height and hull roof: each gun's lower surface sits **0.025 tiles above the roof** on flat ground. Native horizontal positions, rotation and recoil are unchanged. The profile explicitly retains Mace's previous camera eye calculation. No collision envelope changes accompany the cosmetic depth.

## Geometry comparison

Measured using the installed native atlas at the same sampling budget and **identical layered profiles** for both generator paths. This compares against a naive layered hull, not against Mace's earlier flat single extrusion. Body geometry only:

| Native art/profile | Generator | Vertices | Triangles | Horizontal quads | Side quads | Retained CPU + GPU vertex bytes |
|---|---|---:|---:|---:|---:|---:|
| Dagger | Existing, still used | 3372 | 1124 | 166 | 396 | 215808 |
| Dagger | Optimized, diagnostic only | 2304 | 768 | 152 | 232 | 147456 |
| Mace | Naive layered comparison | 4416 | 1472 | 226 | 510 | 282624 |
| Mace | Optimized, now used | 2916 | 972 | 190 | 296 | 186624 |

Mace has approximately **34% fewer triangles and retained vertex bytes** than the same-profile naive layered version: 182.25 KiB instead of 276 KiB. Memory counts the retained Arc CPU vertex buffer and its GPU VBO, at 8 floats per vertex; shared atlas, metadata and temporary generation buffers are excluded.

An isolated Mace uses one hull mesh and one shared body atlas texture, with **3 body/weapon draws plus 1 native movement/chassis draw**. Draw count is not reduced by this change. Thirteen visible Maces reuse the same cached geometry, without mesh regeneration after warm-up. No FPS improvement is claimed.

## Verification

Final build passed **all 81 Java suites**, including **2234 assertions** for exterior caps, roof UVs, nested contours, merged faces, adaptive clamps, cache budgets and the preserved Dagger path.

The exact final package passed the actual Mindustry **160.7 SDL/OpenGL** regression run `mace-final-1607`: **31 Mace model checks**, **10 possession/camera/firing checks**, all **32 previous Dagger checks**, and the **20 native ground-family** gallery. Top, side, ground-level and oblique captures, native movement/turning, actual flamethrower shots, first-person and both third-person modes, and 13-Mace cache reuse were verified. The Dagger framebuffer pixels before/after Mace cache/render activity were identical, and its body retained exactly 3372 vertices. Three maps, copied user saves and a native save/load roundtrip passed; GL errors were zero.

Independent `local/layer-smoke/mace-final-1607/layers-result.json` passed **209 construction**, **685 camera**, **293 elevated-turret**, **83 first-person weapon GPU**, **39 ground-eye**, **40 unit-command**, and the existing schematic/placement/picking checks. The fixtures use scripted registered input and disposable maps/save copies; they are not manual hardware-input tests.

Screenshots and raw geometry measurements are in `local/compat-smoke/mace-final-1607/`:

- [Top](../local/compat-smoke/mace-final-1607/mace-new-top.png), [side](../local/compat-smoke/mace-final-1607/mace-new-side.png), [oblique](../local/compat-smoke/mace-final-1607/mace-new-oblique.png), [crowd](../local/compat-smoke/mace-final-1607/mace-new-crowd.png).
- [Moving](../local/compat-smoke/mace-final-1607/mace-new-moving.png), [firing](../local/compat-smoke/mace-final-1607/mace-new-firing.png), [first-person](../local/compat-smoke/mace-final-1607/mace-controlled-first.png), [third-person](../local/compat-smoke/mace-final-1607/mace-controlled-rear.png).
- `mace-model-verification.txt`, `dagger-model-verification.txt` and `results.json` contain native metadata, geometry and assertions. Comparison screenshots use the same native renderer; `mace-old-*` shows the pre-profile generic model, while the numeric naive-layered comparison uses identical new hull bands.

Final JAR: **3,838,404 bytes**, SHA-256 `3b393db6f534ff7cee0cec3e95442bfe9a3c44aef59a8fc26ad8df2fee17041c`. Installed this exact tested package in the existing play profile. All **632 original non-mod profile files** remained byte-identical.

Known visual limits: contour edges are deliberately pixel-stepped and sides use sampled colors rather than detailed new artwork. Native movement sprites remain the existing lightweight movement geometry. The legacy Dagger path is deliberately not migrated. No fresh multiplayer session or FPS benchmark is claimed for this visual-only pass.

Working 0.18.6 backup: `local/backups/before-mace-0.18.6/mindustry-fpv-0.18.6.jar`.
