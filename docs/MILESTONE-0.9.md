# Projected wall optimization and purple ground family — 0.9

This is an incremental extension of the verified 0.8 implementation. The existing FPV controls, Build Mode, map/economy, flying art, spatial weapons and matching-mod server authority remain in place. Only Crawler, Atrax, Spiroct, Arkyid and Toxopid use the new ground-family models.

## Recovery point

`local/backups/before-0.9-20261008T095154Z` contains the exact working 0.8 JAR, source/design/tools, play profile and previous verification evidence. All 860 archived entries were reread and checked by SHA-256 and ZIP CRC. Original 0.8 package SHA-256: `d372a05051b7397618bde923210bfe397e9a65791a23ec3b280f2b96209c4add`. Restore source and the JAR separately from saves; restoring an old profile deliberately rolls back later gameplay.

All tests use isolated profiles on D: and copies from this backup. `31.msav` is the latest user save and has no natural mountains. `26.msav` supplies the real 430×430 base with 12,553 buildings and 5,292 defensive wall anchors used for performance measurement. Neither original is written by the tests.

## Measurement method

`tools/launch-wall-profile.ps1 -Label <unique-name> -Save local/fixtures/wall-base.msav` runs fixed near/mid/overview cameras on the same paused base. Each view has 120 warmup and 300 measured frames, with the same 256 seeded XYZ projection queries per frame (including long rays). Ground-family additions are explicitly disabled in this benchmark to isolate wall changes. The native grid fingerprint must remain identical and OpenGL errors are recorded.

Frame timing covers game-thread/render submission work including that declared query workload. Its reciprocal is a work-rate estimate, not an interactive FPS or GPU-time promise. Results, exact tested JAR, source save hash, process identity and screenshots are retained per run under `local/wall-profile/`.

Baseline is `before`; the query-only intermediate is `queries`. The old cache used the same `-1` value as native WorldLoad, so the harness advances only the tile-change revision after loading in both builds to initialize the old system fairly. Production initialization now uses an explicit flag.

Final wall implementation is measured in `after`. Its wall-system, renderer, instrumentation and benchmark class bytes match the final release. All three runs retained 5,292 logical wall pools and 238 spatial buckets; visible logical layer counts stayed identical. One bounded GPU mesh is retained per atlas, with no per-wall GPU objects.

| View | Wall render before → after | Triangles before → after | Draws before → after | Measured full frame work before → after |
|---|---:|---:|---:|---:|
| Near | 3.107 → 0.898 ms | 27,968 → 6,468 | 2 → 1 | 54.29 → 14.92 ms |
| Mid | 8.509 → 1.600 ms | 96,730 → 18,552 | 6 → 2 | 76.30 → 31.06 ms |
| Overview | 10.748 → 1.895 ms | 120,582 → 22,452 | 8 → 2 | 88.58 → 39.27 ms |

Overview render allocation fell from 3,175,976 to 16,784 bytes per frame. Average query cost fell from 153.15 to 13.86 microseconds, candidates from 1,276.95 to 179.55 and allocation from 41,566 to 96 bytes per query. Steady measured frames created no chunk geometry or new Volume records. Update cost increased from 0.024 to 0.604 ms because explicit lifecycle polling now catches door/ownership transitions without a tile event; the combined cost is substantially lower. These results were measured on the local RTX4050 system, not extrapolated to another computer.

Adjacent compatible surfaces merge inside spatial chunks; exposed height intervals remove even partially covered internal faces. The shader repeats the native atlas without stacking one surface per layer. Mid/far materials simplify while collision keeps full resolution. Geometry invalidates only for changed pools and neighboring chunk boundaries; fog and frustum filtering remain. Queries visit ray-crossed spatial buckets and reuse candidate storage. Exact underlying wall tokens keep independent HP/regen and team rules. The real-base query regression agrees with exhaustive per-wall slabs for 2,048 deterministic rays; a separate 2,560-query native fixture verifies a forty-wall line, independent HP, cached frames, three-chunk rebuild after one hit and door/team transitions.

## Ground family

The native sprite silhouette and team cell remain the upper body. A configurable depth multiplier adds volume: 1.6× for Crawler and 2× for the four spiders. Crawler has four rotating wheels and no extension. Spiders share rounded underside, joint, foot and two-segment leg templates with 4/6/6/8 legs. Two detail levels share at most eight cached rigs; animated transforms do not rebuild meshes. A compact 99-vector affine palette fits the OpenGL minimum vertex-uniform budget. Per-unit gait states are bounded and discarded after removal/world change.

Canonical body XYZ comes from `GroundFamilyLocomotion` through `UnitAltitudeSystem`, shared by rendering, combat and network poses. Feet sample physical terrain/structures; holograms are obstructions only. World-planted alternating feet adapt to movement and turning. Grounded sweeps wait for actual vertical clearance, reject obstacles above reach, and reduce displacement once as extension rises. Selected legged units can recover commanded movement rejected solely by native 2D solidity, subject to the same 3D sweep. High-level native AI route selection remains; this does not introduce a new mountain path planner.

Settings → **3D ground units** exposes Atrax/Spiroct/Arkyid/Toxopid maximum extra leg extension, initially **3.3 / 6.6 / 13.2 / 26.4 tiles** (2/4/8/16 physical large-wall heights), and minimum speed at full extension, initially **25%**. Normal stance keeps full speed; the penalty rises smoothly with squared normalized extension. Crawler remains low. The existing optional projected-unit barrier remains optional: when enabled, the new family must have enough reach to clear it; legs never stand on its transparent volume.

The server owns movement, height and family settings. A validated 40-byte channel distributes four reach values and the minimum speed fraction. Existing unit snapshots carry physical pose; clients animate that pose and cannot replace host settings. New clients/epochs/world resets discard stale settings. Altitude/extension remains session state; the native save format is unchanged.

## Validation

Package-bound acceptance is recorded in `local/smoke/verification.json`. Tests cover real wall contact attribution and caches, all five family members, wheel/leg count, actual climb/slowdown/limits, physical support, native-art GPU submission, host settings, synchronized raised poses, FPV/Build/combat regressions, three original maps and copied saves. Fifteen additional family captures show top/side views and real raised supports. Scripted tests do not claim physical mouse/controller or WAN testing.

Final package SHA-256: `90000f773f8f224fd8be6e75410881adc698c6feca5f728716b44ae6519e8cec`. All27 pure suites passed181410 assertions; the final native SDL run passed with46 captures andOpenGLerror0. `local/net-lab/v09-final` passed with the identical JAR in the dedicated server and both clients; server147 assertions, raised family poses visible on both clients, conflicting local reach/speed preferences overridden and forged settings rejected. The coherent final build also includes the updated generated design descriptor; wall benchmark class bytes were verified identical to the measured build. Source, packaged classes/resources/design, copied-save hashes and all evidence are bound in the receipt.
