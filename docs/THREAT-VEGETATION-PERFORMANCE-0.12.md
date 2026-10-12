# Unit markers and vegetation: native visual/performance evidence (0.12)

This pass measures the production world renderer on an isolated generated map. It does not measure interactive FPS, combat simulation, a user's save, or the wall-query benchmark. Runtime marker visibility uses GPU depth and never reads framebuffer pixels back to the CPU. Synchronous pixel reads below exist only in the explicitly enabled native smoke test.

The native SoLoud audio backend was unavailable in both releases before mod loading; these measurements and visual checks do not establish working sound. The same error persists in a separate sequential0.12 run. No audio code or settings were changed by this pass.

## Native visual acceptance

`UnitThreatVisualChecks.run` uses native Flare/Horizon/Zenith/Antumbra/Eclipse units, their real team colors and canonical XYZ poses. It checks actual changed pixels and native-color fill pixels, not only CPU submission counters. The controlled unit's white edge is also checked as pixels. The renderer's private test instance is fully cache-warmed before each ON/OFF pair: this prevents a newly generated extrusion mesh from being mistaken for marker output. Because the smoke hook executes synchronously inside one app frame, only that private fixture's cache-budget bookkeeping is reset between warm-up passes. Final rendering, geometry, shaders and captured frames remain the production paths.

The final fixture makes 64 native assertions: T1–T5 symbols/colors, pilot outline, vertical anchoring, camera-facing bounded size, 80/90/101-tile horizontal distance and >100-tile vertical distance, behind-camera culling, controlled-unit priority in a crowded group, one batched draw and one depth copy, a body-only opaque blocker suppressing the whole glyph, ordinary depth partially clipping the glyph, and real native thorium-wall and natural-mountain occlusion. It also checks OpenGL error zero and restores/fingerprints native map data after cleanup.

The cache-warmed lineup was visually inspected: all five symbols sit above the extruded aircraft, including the T4 square and recognizable T5 skull. The earlier un-warmed `v012-markers` screenshots are superseded: their first-frame fallback meshes made the old pixel-difference test weaker than intended.

Accepted quick-run artifacts: `local/maintenance-smoke/v012-markers-warm/results.json` and `threat-*.png`. The root acceptance report binds these checks again to the final full native release run.

## Paired timing method

`VisualBenchmarkHarness` generates a paused 256×192 native map with 1,024 natural props (six native tree/bush types), 256 native combat aircraft from T1–T5 across three teams, and one pilot. No user save is loaded. Each of five views receives 60 warm-up renders and 180 measured renders at 1280×800. The forest has a low and an overview view; the crowd runs markers OFF, ON, then OFF again. No other project lab renderer runs concurrently during measurement.

The exact same three compiled lab classes (`SmokeHarness`, `VisualBenchmarkHarness`, `VisualGpuTimer`) are overlaid onto copies of the immutable 0.11 release and the candidate 0.12 release. The original production renderer, physics and assets in each release are unchanged. SHA-256 manifests record both originals and overlays plus each overlay class. The normal automatic FPV view is disabled so it does not add an unmeasured duplicate 3D pass. Benchmark dispatch runs independently of the game's disabled-FPV update branch.

CPU values are elapsed wall-clock time around the complete production world render. Allocation is measured by the JVM's per-thread allocation counter. GPU values are asynchronous `GL_TIME_ELAPSED` query results: only already-available samples are consumed, no wait/readback is introduced. Typically 179 of 180 results are available at the end; the last pending query is not silently counted. Coarse Windows thread-CPU counters are retained in raw evidence but are not used for comparative conclusions. Both releases must leave the native floor/block/overlay fingerprint unchanged and finish with OpenGL error zero.

Baseline: `local/visual-profile/v011-before-final`, original 0.11 SHA-256 `06d399a94d632bb44c0d58118d8446685e4de082e22083919d98c9c8acf526ec`. `v011-before` and `v011-before-fixed` are superseded lab-entry debugging runs, not paired performance evidence.

## Measured results

Final candidate: `local/visual-profile/v012-after-final`, original release SHA-256 `7ec70e7b66ffdb58e093adf68c2024450f250cb85933de8c676367ac1728f155`. Both runs passed with the same unchanged tile fingerprint `9229357668879911086`, OpenGL error zero, and identical three profiler/entry class hashes. Their isolated processes exited normally before the final full native/network acceptance began.

| View | 0.11 CPU ms | 0.12 CPU ms | 0.11 GPU ms | 0.12 GPU ms | 0.11 allocation B/render | 0.12 allocation B/render |
|---|---:|---:|---:|---:|---:|---:|

| forest-low-off | 1.058 | 1.062 | 0.183 | 0.209 | 33745 | 33744 |
| forest-overview-off | 1.001 | 1.144 | 0.184 | 0.198 | 35668 | 36179 |
| units-off | 1.369 | 1.405 | 1.028 | 0.952 | 35316 | 36169 |
| units-on | 1.169 | 1.710 | 1.030 | 1.662 | 35288 | 68920 |
| units-off-repeat | 1.222 | 1.685 | 1.147 | 1.088 | 35288 | 36152 |

0.11 has no marker module; its “units-on” row toggles the same unused setting as a control. Each GPU row has 179 available samples. The ON/OFF repeats show ordinary timing variation, so a small CPU difference must not be presented as a precise speedup.

The enlarged forest's measured cost is small in these views: CPU +0.003 ms low / +0.143 ms overview; GPU +0.026 / +0.014 ms. Geometry cache size is unchanged in the low view (10,739,304 bytes), while the larger visual bounds correctly admit more detailed chunks in the overview (41 to 50; cache 12,343,536 to 12,564,720 bytes). Tree/bush scale changes preserve the template vertex count. Warm-up builds cached geometry; the existing distant coarse fallback still streams a small number of prop instances each frame (1,980 low / 1,440 overview submissions across 180 renders, identical before/after). This is not a claim that every distant prop is permanently cached.

The dense crowd adds measurable marker cost. With 256 candidate aircraft, the screen-grid suppresses 140 overlapping candidates and submits 116 glyphs in one draw (696 vertices), with one depth copy per render. The marker module's own CPU scope averages 0.213 ms. Allocation rises by 32,768 bytes/render, chiefly canonical pose snapshots already used by the shared adapter. Total render CPU is 1.710 ms ON versus 1.405 and 1.685 ms OFF. GPU is 1.662 ms ON versus 0.952 and 1.088 ms OFF: approximately +0.64 ms compared with the mean of the two OFF views. This is a bounded additional cost, **not a performance improvement**. The setting removes the marker pass when disabled.

Before/after forest captures and the dense crowd capture were inspected. The forest visibly changes to the requested 5× dimensions; the dense crowd retains recognizable glyphs without stacking all 256 symbols over one another. Native visual checks separately verify hiding behind opaque terrain/buildings and cached sprite correctness. These measurements cover the stated synthetic heavy scene on this machine, not every map, GPU, resolution, or interactive combat workload.

Raw evidence and exact overlays:

- `local/visual-profile/v011-before-final/results.json`, screenshots, `profiled-mod.jar`.
- `local/visual-profile/v012-after-final/results.json`, screenshots, `profiled-mod.jar`.
- `local/visual-profile-staging/v011-before-final/manifest.json`.
- `local/visual-profile-staging/v012-after-final/manifest.json`.

To reproduce, compile the three lab entry/timer classes into an isolated class directory, stage with `tools/stage-visual-profile.py`, and launch with `tools/launch-visual-profile.ps1 -Label <unique-label> -ModJar <staged-jar>`. The launcher refuses to overwrite previous evidence and creates an independent profile on D:. Never aim these fixture tools at the play profile.
