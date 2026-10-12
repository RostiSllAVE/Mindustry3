# Projected-wall distance rendering — 0.11 evidence

This pass adds local camera-based projection visibility. The default maximum is **100 tiles**, with smooth fading from **80 to 100 tiles**. Distance is measured in three dimensions from the actual camera; the pilot's ground position does not control it. The controls are `fpv-wall-view-distance` (20–2000) and `fpv-wall-fade-start` (0 through maximum−1).

## What changes

The existing nearest-projection-per-pixel compositor, native wall textures and persistent chunk cache remain. A chunk is first tested against the camera distance sphere and frustum. Within a boundary chunk, individual wall bounds are tested too. Completely excluded walls submit no geometry. If no projections remain, the private depth copy and full-screen composition are skipped as well. The fragment shader computes continuous camera distance for partially visible faces and discards fragments at the maximum distance.

Complete visible chunks retain merged geometry. Boundary chunks use a second cached set of per-wall ranges built only when the existing topology/HP/fog revision changes. Moving the camera or changing the distance controls does not rebuild those meshes. This uses additional persistent CPU geometry memory, and boundary selection can allocate more temporary iteration objects than the previous fully merged fast path. There is no synchronous GPU readback in routine rendering.

Only rendering consults these local controls. The 5,292 logical projection anchors and their spatial collision index remain active; hidden walls retain HP, regeneration, team rules and authority. The separate 0.11 laser rule uses native `Building.absorbLasers()` to let beams cross ordinary projections while retaining Plastanium interception. Native nonabsorbing beam families, including Rail and continuous flame, also bypass Plastanium projections; ForceProjector protection remains independent. Single-target Sap and endpoint-only point weapons retain their separate target behavior. Those combat changes are deliberately excluded from the distance-only performance comparison below.

## Paired experiment

Both runs used the same copied 430×430 real save containing 12,553 buildings and 5,292 eligible walls. The deterministic world was paused. Each of three fixed cameras received 120 warm-up frames and 300 measured frames, with 256 identical wall queries per frame. The camera positions, harness, simulation and query implementation were identical.

- Save: `local/fixtures/wall-base.msav`, SHA-256 `cd788eb9e5d43c11b565eff11675f4c3e062843edb1d0bf44de842377fb1443e`.
- Before: unchanged verified 0.10 JAR, SHA-256 `ca22cbc4ee11b5f6ce05ea270276fc7f1f49c8e0bce22dc252200185cc6a67df`.
- After: distance renderer classes overlaid on that exact 0.10 JAR, SHA-256 `6603e665d21849064ea40cef04a3f7ee7c0ab5cf97e8f416dadeedf76a43b149`. This intentionally keeps the old scene and physics for isolation; the JAR still reports version 0.10.0 and is an experiment artifact, not the distributable release.
- Exact seven overlaid class hashes: `local/wall-profile-staging/distance-011-after/manifest.json`.
- Raw results, staged JARs, process/save hashes and PNGs: `local/wall-profile/distance-011-before/` and `local/wall-profile/distance-011-after/`.

GPU durations use asynchronous `GL_TIME_ELAPSED`, reading only available queries. Each view returned 299 completed GPU samples. CPU elapsed is recorded separately. Geometry draw counts exclude the separate full-screen composite; its one draw and one opaque-depth copy become zero when no geometry survives.

| Measured per rendered frame | Near before → after | Mid before → after | Overview before → after |
|---|---:|---:|---:|
| Wall GPU mean, ms | 0.304 → 0.386 | 1.893 → 0.341 | 2.017 → 0.0016 |
| Wall rendering CPU elapsed, ms | 0.808 → 0.874 | 1.868 → 0.401 | 2.299 → 0.076 |
| Submitted logical walls | 1,317 → 1,005 | 4,207 → 251 | 5,221 → 0 |
| Submitted vertices | 19,404 → 14,346 | 55,656 → 4,956 | 67,356 → 0 |
| Geometry draws | 1 → 1 | 2 → 1 | 2 → 0 |
| Allocated bytes per wall render | 4,769 → 17,081 | 13,904 → 9,224 | 16,784 → 80 |
| Cache rebuilds during measurement | 0 → 0 | 0 → 0 | 0 → 0 |
| Mean wall query, µs | 17.219 → 20.447 | 14.734 → 18.067 | 16.016 → 17.776 |
| Whole harness frame elapsed, ms | 15.259 → 17.030 | 32.037 → 34.331 | 42.771 → 43.660 |
| Whole harness thread CPU, ms | 14.531 → 15.521 | 30.573 → 33.958 | 41.094 → 42.865 |

Every phase retained 179.55078125 query candidates per call and 96 allocated bytes per query. Both runs passed 2,048 comparisons against exhaustive wall queries, 11 native GPU nearest-composition assertions, and GL error=0. The native tile fingerprint remained `14047868000748587717` before and after both runs. Before/after mid images and the after overview were visually inspected: the physical base remains visible and distant projection columns disappear.

## Interpretation and limits

The requested geometry exclusion works and substantially reduces the mid/overview wall pass. Near-camera GPU time and temporary render allocations rose because selective boundary chunks have extra work. The whole harness frame time **did not improve in this pair**. Unchanged query code also measured slower; this run cannot identify a single cause. It would be incorrect to claim a measured FPS increase or subtract wall GPU savings directly from whole-frame CPU time.

The result therefore establishes reduced distant projection work, not a general interactive FPS guarantee. Additional geometry storage is a real memory tradeoff; no retained-heap size measurement was taken. Other 0.11 changes, native AI, active battles, live input and unrelated scene rendering are outside this isolated performance comparison. Final release class identity and integrated native/network acceptance are recorded by the release verification separately.
