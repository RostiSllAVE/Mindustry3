# Scepter layered hull — 0.18.9

Scepter alone receives an opt-in cosmetic profile through the existing optimized hull and native mech renderer. Dagger, Mace and Fortress profiles/generation paths are preserved, as are flying/walker models, camera/selection/UI logic, construction, power/logistics, movement/collision, combat and multiplayer messages.

## Higher-tier proportions

The roof uses Scepter's own full-size native sprite and silhouette, not enlarged Fortress art. Three broad contours give an armored skirt and compact upper deck: underside/middle/top scales **0.78 / 0.94 / 1.00**, depth shares **32% /34% /34%**. Fortress retains0.72 /0.90 /1.00 and28% /32% /40%.

Depth derives from native hit size divided by tile size, multiplied by **0.30**, clamped **0.54–0.88 tiles**. Native size22 gives **0.825 tiles** versus Fortress0.65. The factor is deliberately smaller for the larger unit so it gains presence without becoming a tall drum. Scepter uses a0.70-scale original underside chassis and0.12-tile visual clearance; Fortress retains0.64 and0.10. Native feet, gait, body rotation and animations remain in their existing paths.

Installed160.7 body art is **128×96 pixels**, native scale0.25: **4×3 tiles**, compared with Fortress78×64 /2.4375×2 tiles. Native `scepter-leg` is122×104 and `scepter-base`98×74. The distinct silhouette, upper detail and six-weapon layout supply its higher-tier identity.

## Six native weapons and player control

Scepter's original layout contains a mirrored main `scepter-weapon` pair at X=±16/Y=1, plus two mirrored rotating `scepter-mount` pairs at X=±8.5/Y=6 andY=−7. Original art, mount rotations, reload/burst timing and recoil remain native. All six visual gun meshes have0.22-tile depth and lower faces0.045 tiles above the roof; projectile origins and combat stats are unchanged.

The model opts out of cosmetic camera-height changes, retaining Scepter's existing size-derived eye calculation and F7/zoom behavior. First-person weapons continue using the existing perspective/private-depth viewmodel pass; the controller and global camera are not rewritten.

## Efficiency and measurements

The existing Mace/Fortress generator is reused unchanged. Only the roof maps the full original texture; nested lower contours use merged boundary sides and exposed underside/overhangs with sampled edge colors. No hidden internal caps, repeated full roof textures, copied game assets or per-instance/per-frame mesh builds.

Measured installed-art body geometry, with identical new Scepter bands/grid budgets for both paths:

| Generator | Vertices | Triangles | Horizontal quads | Side quads | Retained CPU + GPU vertex bytes |
|---|---:|---:|---:|---:|---:|
| Naive full-band layered | 5664 | 1888 | 270 | 674 | 362496 |
| Existing optimized exterior | 3804 | 1268 | 242 | 392 | 243456 |

Approximately **32.8% fewer triangles and retained vertex bytes**:237.75 KiB versus354 KiB. This is a same-profile layered comparison, not a reduction versus the former flat model. Vertex memory counts Arc's retained CPU buffer and GPU VBO,8 floats per vertex, excluding shared atlas/metadata/temporary arrays.

One isolated model submits **7 body/weapon draws +1 native movement/chassis draw**, reflecting its six native weapons. The hull remains one cached mesh/atlas texture;13 instances share hull/weapon/limb caches with zero additional builds after warm-up. Previous Fortress body geometry stays3474 vertices /1158 triangles.

The timed scene compares13 Fortress against13 Scepter at the same XY ground positions and camera, with24 warm renders and64 measured submissions per phase, in F/S/F/S order. CPU elapsed measures rendering/submission; GPU elapsed queries collect available asynchronous samples. It excludes simulation/AI, swap/vsync and input, so it is **not a gameplay FPS benchmark**.

| Phase | CPU elapsed mean, ms | CPU elapsed maximum, ms | Geometry builds during measurement |
|---|---:|---:|---:|
| Fortress0 | 0.332041 | 0.9056 | 0 |
| Scepter1 | 0.378825 | 0.8419 | 0 |
| Fortress2 | 0.275622 | 0.8214 | 0 |
| Scepter3 | 0.360494 | 1.1278 | 0 |

Across these equal-length samples the means are **0.304 ms Fortress /0.370 ms Scepter**, approximately0.066ms additional CPU submission cost. This is a small warm static-scene measurement, not evidence for large battles or a claimed FPS gain. Only12 asynchronous GPU results were available for the first Fortress phase (mean0.623ms); none were available for the Scepter phases in the bounded submission loop. A GPU comparison is therefore **unavailable**, not zero-cost. Raw values/statuses: `scepter-render-profile.txt`.

## Validation and limits

Final build passed **all83 Java suites**, including321 Scepter hull and previous2234 optimized-generator assertions. Native fixtures use Mindustry160.7 SDL/OpenGL, original installed art, scripted registered input and disposable maps/save copies.

Exact final run `scepter-final2-1607` passed **41 Scepter model checks** and **10 possession/first-person/front/rear/firing checks**. Verified top/side/ground/oblique, native movement/turning/firing, mirrored native mount positions, six submitted first-person weapons, multiple instances/cache reuse and Fortress side-by-side. Fortress framebuffer pixels before/after Scepter rendering/cache activity match exactly. Existing35 Fortress /31 Mace /32 Dagger model checks and20 ground families passed, along with previous Fortress/Mace controls. Three maps, copied user saves and native save/load roundtrip passed; GL errors were zero.

Independent final layer run passed209 construction,685 camera,293 elevated-turret,83 first-person GPU,39 ground-eye,40 command and previous schematic/placement/picking checks. The initial full-frame preservation check failed because the larger moved/firing Scepter entered the edge of the Fortress reference frame; moving the reference farther away corrected the fixture without changing the model or previous units.

Screenshots and raw reports are in `local/compat-smoke/scepter-final2-1607/`:

- [Top](../local/compat-smoke/scepter-final2-1607/scepter-new-top.png), [side](../local/compat-smoke/scepter-final2-1607/scepter-new-side.png), [oblique](../local/compat-smoke/scepter-final2-1607/scepter-new-oblique.png), [beside Fortress](../local/compat-smoke/scepter-final2-1607/scepter-next-to-fortress.png).
- [Movement](../local/compat-smoke/scepter-final2-1607/scepter-new-moving.png), [firing/recoil](../local/compat-smoke/scepter-final2-1607/scepter-new-firing.png), [first-person](../local/compat-smoke/scepter-final2-1607/scepter-controlled-first.png), [rear follow](../local/compat-smoke/scepter-final2-1607/scepter-controlled-rear.png), [crowd](../local/compat-smoke/scepter-final2-1607/scepter-new-crowd.png).
- `scepter-model-verification.txt`, `scepter-render-profile.txt` and `results.json` record metadata, geometry, timings and assertions. `local/layer-smoke/scepter-final2-1607/layers-result.json` records independent regressions.

Final0.18.9 JAR: **3,866,734 bytes**, SHA-256 `e6291484187a8fa77a2b37dfc14acccf3834fe32ac02de475dcdfe1146544816`. Installed this exact tested package. All **636 original non-mod profile files** remained byte-identical.

Visual limits are the existing family style: pixel-stepped contours, simple sampled sides and lightweight original movement geometry. The two secondary pairs partially overlap in the preserved first-person layout; all six meshes submit, while world/follow views retain their distinct native front/rear positions. No global camera/viewmodel redesign is included. Native six-gun layout necessarily costs more weapon submissions than Fortress's two guns. No fresh manual-input/multiplayer/full-game FPS claim.

Working0.18.8 backup: `local/backups/before-scepter-0.18.8/mindustry-fpv-0.18.8.jar`.
