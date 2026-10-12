# 0.10 correction pass — validation notes

Incremental correction of the existing 0.9 mod, targeting installed Mindustry 160.5. Terrain, economy, Build Mode, existing spatial combat/balance and flyer artwork remain in the existing architecture. Final package identity and acceptance are recorded at the end; development iterations are explicitly separated below.

## Recoverable baseline

Before editing, `local/backups/before-0.10-20261008T111350Z` archived the exact working 0.9 JAR, source, evidence and entire play profile. All 906 archived files were checked against SHA-256 and ZIP CRC. Original saves stay in `play/saves`; tests use copied `imported-user.msav`, latest `34.msav`, and the large wall-base fixture. Gallery tests generate their own disposable 128×128 map. No test writes a user's original save.

## Native limbs and grounded stance

`NativeLimbRenderer` constructs cached alpha-silhouette extrusions from actual resolved native leg/base/joint/foot atlas fields. `NativeLegLayout`, `ArticulatedLegIK` and `GroundGait` supply unit-specific attachment spread, outward stable bends, staggered lifted steps and planted world-space feet. Up to 32 cached parts share each transform batch. Render animation does not move authoritative bodies or apply damage. Original art is read from the installed game at runtime, not distributed.

Coverage is capability-based: ten native `Legsc` types and nine `Mechc` types, plus the intentional Crawler wheel override. Native mechs have whole-leg artwork rather than separate knee/foot assets; those whole sprites retain physical thickness and animated placement. Atrax/Spiroct/Arkyid/Toxopid retain the existing 4/6/6/8 overrides; other leg counts and proportions come from native definitions. Existing body thickness and ordinary flyer extrusion stay unchanged. Negative native 2D leg overlap offsets are clamped at the actual 3D knee to avoid detached lower limbs; positive overlap remains.

Grounded stance uses `clamp(0.5 * maximumClearance + stableOffset[1..3 tiles], minimum, maximum)` above local support. It approaches smoothly. The per-unit offset is stable during possession/camera changes and is stored with current/desired clearance in the guarded `fpv-ground-stances` save chunk. Identity checks include native ID, type, team and position. Host settings and authoritative poses determine client display; native non-purple reach uses the already replicated step-height multiplier. The existing extension penalty is applied once to native movement, preserving its minimum-speed configuration.

Q/E changes desired grounded walker clearance while owned, including a zero-axis hold in Build/menu state. Release transitions smoothly to AI default/obstacle behavior. Physical terrain/buildings can support feet; projected barriers cannot. Native path planning remains; this does not invent a new route planner across arbitrary mountains. The visual gallery contains front/side/back/top captures for all twenty supported ground types, an unchanged Flare, and two extended Toxopid views with varied real foot support.

## Capability-based possession and creator flight

`UnitControlCapabilities` classifies native objects into aircraft, walker, mech, naval, hover, ordinary ground or stationary building proxy. Native `Call.unitControl` validates team/ownership/control permissions. Build Mode Ctrl+click selects a true-height body or native controllable turret through the 3D ray, with opaque-world occlusion and fog checks.

`GroundedPilot` applies native planar `movePref` and weapon controls on both offline host and server. It does not grant aircraft lift to ground types. Ships/hovercraft keep their native movement rules. Turret proxy XYZ stays at the building; aim flows into the existing spatial projectile adapter while native ammo, reload, power and other weapon requirements remain. Support units gain no invented gun. Actual native boosting capability and shooting restrictions remain native.

F8 requests creator flight for the current eligible mobile ground unit only. Host setting **Allow ground-unit flight** defaults OFF; client preference cannot authorize it. `CreatorFlightSystem` owns the per-instance XYZ override, collision and canonical combat body. It does not mutate `UnitType.flying`. Grounded planting suspends, limb transforms fold, and `CreatorFlightVisuals` submits inexpensive downward exhaust using native engine colors. No per-unit dynamic light or costly particle system is added.

Revocation, explicit exit, control transfer or missing controls enter server-owned landing. Native AI is temporarily held during unattended descent and the exact previous controller restored afterwards. Landing validates body footprint and walker foot spread. If no native passable footprint exists anywhere, it falls back to physically supported ground and retains native drowning/stranding consequences; if a wholly built artificial map has no non-building footprint, it uses a vulnerable grounded roof hold until a map edit permits recovery. That last fallback has no creator exhaust/flying classification, and is a stated impossible-map limitation rather than indefinite flight.

The `fpv-creator-bodies` save chunk stores guarded pose/momentum; load resumes landing, never unattended creator input. Death/removed-body cleanup settles already resolved ram damage before discarding the same-ID body; ID reuse cannot inherit it. Native map and save systems stay intact. Ordinary flyer altitude, active projectiles and projection HP remain session-only sidecars.

Lifecycle checks also exposed and corrected transient null-pilot access during map/control transitions, and duplicate native ground acceleration with network-owned movement in F5's 2D presentation. Native 2D cursor/build/aim/payload UI remains available; movement has one owner. Offline F5 during creator landing uses the same ownership boundary.

## Nearest projected wall per pixel

`ProjectedWallRenderer` retains the 0.9 exposed-face chunk cache, native wall atlas, geometry merging, frustum selection, and independent gameplay sidecars. A new `ProjectionCompositeTarget` uses a private RGBA/depth framebuffer. It copies the already rendered opaque scene depth entirely on the GPU, disables blending while drawing projections, and writes private depth so each pixel selects its nearest valid projection fragment. One straight-alpha composition then overlays that result on the unchanged scene color. The main scene depth is never modified.

This removes bright stacking between projected layers while preserving ordinary world visibility through their alpha. Farther projection side/top regions remain visible where the nearer projection does not cover that pixel. Texture cutouts discard before private depth writes; collapsed pools contribute no coverage. Boundaries render from both sides for cameras inside a projection. Wall collision, HP, regeneration, team filtering, native map tiles and query indexes are unchanged.

The normal path blits between matching depth formats. The fallback copies opaque depth to a GPU depth texture and rejects fragments behind it in the wall shader. The fallback was forced on the available desktop GL context and compared pixel-for-pixel with the blit path. This verifies fallback behavior on that context; it is not evidence of a separate OpenGL 2 hardware run. There is no routine synchronous pixel readback. Readbacks occur only in explicit isolated pixel tests.

## Paired wall measurements

Both runs use the same verified **0.9.0** release as the base, so unfinished 0.10 locomotion/control work cannot contaminate the wall comparison. Exact base SHA-256:

`90000f773f8f224fd8be6e75410881adc698c6feca5f728716b44ae6519e8cec`

- Before: `local/wall-profile-staging/corrections-before/mindustry-fpv-0.9.0-gpu-baseline.jar`, SHA-256 `d554e9172283f4b00149b4453aaba9d317147aa0c1f6d9fa289b181b613117e6`. The seven overlaid class files are `WallProfile`, `WallGpuTimer`, `ProjectedWallRenderer`, and its `ChunkCache`, `Data`, `Stream`, `Surface` classes. Geometry and composition behavior remain 0.9; the outer renderer only adds opt-in GPU timing.
- After: `local/wall-profile-staging/corrections-after1/mindustry-fpv-0.9.0-compositor-after.jar`, SHA-256 `5d50b4a803c3cb8970d7b8de997d10e8dadd9b93139dede5034d78d3079c6ef1`. Its eleven overlaid classes are the same seven, plus `ProjectionCompositeTarget`, its `DepthTarget`, `ProjectionCompositeChecks`, and `WallBenchmarkHarness`. The harness adds post-measurement pixel tests. The profile/timer class hashes are identical between runs. Every other class, including wall gameplay, remains the original release.

Each staging directory contains a `manifest.json` with exact source and class hashes. Raw results and screenshots are in `local/wall-profile/corrections-before` and `local/wall-profile/corrections-after1`.

The copied real save is `local/fixtures/wall-base.msav`, SHA-256 `cd788eb9e5d43c11b565eff11675f4c3e062843edb1d0bf44de842377fb1443e`: a 430 × 430 map containing **5,292 eligible native wall anchors** and **12,553 buildings**. Each near/mid/overview phase uses 120 warmup frames, 300 measured frames, and 256 identical deterministic spatial queries per frame. The world is paused for reproducibility; a load revision counter is advanced to ensure both versions initialize their map cache. No map tile data is changed.

| View | Wall CPU-side submission elapsed, ms before → after | Actual wall GPU elapsed, ms before → after | Query elapsed, µs/query before → after | Measured frame work, ms before → after |
| --- | ---: | ---: | ---: | ---: |
| Near | 0.6190 → 0.8972 | 0.0651 → 0.2109 | 15.0540 → 22.0040 | 13.9569 → 17.9805 |
| Mid | 1.5962 → 1.6706 | 1.1935 → 1.1545 | 14.2712 → 13.2809 | 30.0709 → 29.1887 |
| Overview | 1.8829 → 1.8842 | 1.2441 → 1.2962 | 12.9031 → 12.1821 | 36.4244 → 35.3386 |

GPU values come from asynchronous `GL_TIME_ELAPSED` queries, polling availability before collecting results. Each phase obtained 299 completed samples; no unavailable result is synchronously waited on. Arc exposes a 32-bit result reader, so the implementation explicitly supports pass durations below 4.29 seconds. These measured passes are well below that bound. CPU-side elapsed submission time is measured separately; it is not presented as GPU duration.

| View | Projection vertices before = after | Triangles before = after | Geometry draws before = after | Extra after-pass composition/depth copy | Render allocation bytes/call before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Near | 19,404 | 6,468 | 1 | 1 / 1 | 4,769.2 → 4,769.2 |
| Mid | 55,656 | 18,552 | 2 | 1 / 1 | 13,903.87 → 13,904 |
| Overview | 67,356 | 22,452 | 2 | 1 / 1 | 16,784 → 16,784 |

All views retain 179.5508 average query candidates and approximately 96 allocated bytes/query (the before near run measured 96.0302). Both runs recorded zero map scans, chunk rebuilds, and newly materialized volume records during the measured steady state. Framebuffer storage is extra GPU memory, separate from these Java allocation counters; it is reported by `ProjectedWallRenderer.compositorBytes()` and scales with resolution/depth format.

This is a visual-correctness fix with a small additional pass and depth-copy cost, **not a blanket performance speedup**. Near-view wall GPU time rose by about 0.146 ms; mid/overview differences are small and mixed. The unchanged query implementation also shows timing variation between runs. These measurements do not establish uncapped interactive FPS, combat-load FPS, a general hardware guarantee, or the performance of all combined 0.10 changes. `fpsFromMeasuredFrameWork` in raw output is only the reciprocal of this deterministic benchmark workload and is intentionally not advertised as game FPS.

Both runs pass 2,048 comparisons against exhaustive wall sweeps; both retain tile fingerprint `14047868000748587717` before/after and report GL error 0. The after run passes 11 native GPU pixel assertions: nearest-only overlap, exact front-only alpha, exposed rear regions, opaque foreground depth, visible underlying background, reversed draw order, collapsed-front reveal, inside-facing boundary, depth-copy fallback equivalence, and unchanged opaque depth. Before/after near screenshots were visually inspected; the repeated white layering is removed while the same walls, height and underlying structures remain recognizable.

`ProjectedWallViewChecks` additionally integrates actual native copper/thorium/surge front, collapsed-front, side, overhead and inside views into the full SDL smoke pass. It checks hidden rear wall projectile damage, friendly pass, independent HP and unchanged native base HP. Acceptance of that combined full-pass fixture is bound to the final integrated run separately.

## Actual multiplayer correction coverage

Development run `local/net-lab/v010-corrections3` passed using the official native 160.5 dedicated server and two separate native SDL clients over localhost. All three used the identical test-overlay JAR SHA-256 `151b52d89e61f6b6ed4ff6c62009cb34a40381b142f5f1dcf786a0932966115b`.

This overlay is based on integrated candidate SHA-256 `b583a81552207371339c76dc994ec7c78ff2fb3a772285d6c4ddd99bffb6bdbc` and replaces **only** `fpv/net/NetworkSmokeHarness.class` and `fpv/net/NetworkCreatorSmokeChecks.class`. `local/net-lab-staging/corrections3/manifest.json` binds all hashes. Production class bytes are unchanged from that candidate. This iteration proves those production paths; a fresh run against the final coherent release is still required.

The server passed 151 existing/extended harness assertions and 299 creator lifecycle assertions (the latter includes repeated immobility assertions while controls are held). Both client reports passed and independently submitted creator exhaust geometry through the actual 3D renderer. The assertions cover:

- Existing authenticated handshake, forged epoch/state rejection, native-position forgery rejection, XYZ miss/hit, independent pilots, ground movement, normal-speed multiplayer Build mode, native building, mod mismatch/reconnect, world reload and input timeout.
- Existing bounded once-only ram damage, wall collapse/regeneration, server-owned wall settings and rejected client writes, native ForceProjector hit/break/recovery, both-client healing beam GPU submission, and raised grounded-family snapshots/rendering.
- Fresh server creator permission OFF despite both clients setting their local preference ON and sending explicit flight requests.
- Host-authorized Dagger and Atrax climb from actual authenticated client axes, canonical flying flags and altitude reaching both clients, unchanged shared native `UnitType.flying`, and exhaust submission on both native renderers.
- Permission revocation landing both units alive on passable native terrain, replicated grounded state, a second authorized takeoff, control transfer with the old bodies retained until safe landing, and restoration of the exact released AI controllers.
- Controlled native Duo remains fixed under WASD/QE/creator requests, fires actual native-ammo shots with upward XYZ aim, and returns to native AI when released.

Client captures are `client-a-creator-flight.png` and `client-b-creator-flight.png` in the run directory; both were inspected. GPU-submission assertions prove submitted exhaust primitives; they are not a per-pixel legibility measurement. Input is generated through the explicit guarded test supplier, transmitted by the production input protocol, and applied by production server authority. Physical keyboard/controller interaction and internet latency are not tested by this automated localhost fixture.

Two preceding failed iterations are retained honestly. `v010-corrections1` exposed a fixture-only native PvP auto-pause when the later landing scenario changed both pilots to allies; its setup now disables PvP for that later scenario. `v010-corrections2` exposed a legacy test race that sampled stable projection HP before the once-per-frame settings cache applied regeneration=0; a short freeze-acknowledgment stage now samples after the authoritative rate reaches zero, with <0.0001 HP float tolerance. Neither correction changes production gameplay. Successful run 3 processes exited normally; their recorded PIDs were checked absent after result/capture completion.

## Explicit native control exceptions

The generated native control catalog includes BaseTurret, RepairTurret and RepairTower families. Segment and Parallax do not implement a player `ControlBlock`; they retain their automatic defensive behavior. `repair-point`, `repair-turret` and `unit-repair-tower` also lack that interface and retain automatic native healing with 3D presentation. They are not presented as manually possessable gun turrets. Ten internal native unit types with `playerControllable=false` likewise retain their native ownership restrictions: anthicus-missile, quell-missile, disrupt-missile, manifold, assembly-drone, dummy, scathe-missile, scathe-missile-phase, scathe-missile-surge and scathe-missile-surge-split. The catalog finds 60 native controllable types; this is capability/permission coverage, not an individual live combat playtest of every mount.

Q/E adjustable extension uses the ten native articulated `Legsc` profiles. The nine whole-leg `Mechc` models gain native textured volume and movement, retaining their native stance/boost behavior; they do not have an invented telescoping-height setting. Unknown modded leg counts outside the supported even 2–8 range and arbitrary custom movement/controllers have not been accepted by these vanilla checks.

## Final matching-JAR multiplayer acceptance

`local/net-lab/v010-final` passes with the **unmodified final packaged 0.10.0 JAR**, SHA-256 `ca22cbc4ee11b5f6ce05ea270276fc7f1f49c8e0bce22dc252200185cc6a67df` (2,882,955 bytes), on the actual native dedicated server and both native SDL clients. There is no test-class overlay in this acceptance run. Server result: 150 harness assertions and 299 creator lifecycle assertions, all passed. Both client results are passed, including both-client creator exhaust GPU submission. The count of repeated frame-dependent immobility/contact assertions can vary between runs; the same named scenarios passed.

All preceding network scenarios and the new creator/landing/turret scenarios listed above were rerun on that final JAR. The final client flight captures were separately inspected. The three recorded lab processes exited normally after flushing results and captures; no user profile or personal save was used. Raw `server-results.json`, `client-a-results.json`, `client-b-results.json`, logs, screenshots and matching hashes are retained in the final run directory.

`local/wall-profile-staging/final-0.10-binding.json` binds this release to the paired wall evidence: all **11 measured compositor/profile/harness class files match the measured after artifact byte-for-byte**. The paired result remains a scoped wall-pass measurement over the original 0.9 scene, not an invented whole-release FPS result.

## Final integrated SDL and package acceptance

The same final JAR passed the full native SDL/OpenGL run started **2026-10-08T12:12:43.056211700Z**. Thirty pure suites passed **184,952 assertions**. Native integration passed three original maps, the older imported user save, latest copied `34.msav`, native SaveIO roundtrip, unchanged tile fingerprints, FPV/Build/time/navigation/input, spatial weapons/shields/ram/support effects, the shared-control checks, persistent stance and creator pose/landing, and the deferred same-tick death/ram regression. Eleven GPU pixel-composition assertions and actual front/side/above/inside wall scenes passed; hidden rear defense remained active. OpenGL error: **0**.

The final native gallery passed all **20** supported ground types and terrain-supported extension. It recorded **12,127,104 bytes** of cached native limb geometry in this run (cache contents depend on the LODs visited). There are **127 fresh native captures**, including 84 with the walker prefix; actual atlas fields are recorded in `local/smoke/native-walker-assets.md`, and native control exceptions in `local/smoke/control-coverage.json`. Visual inspection of staged and final rendered scenes is separate from successful geometry-submission counters.

`local/smoke/verification.json` binds the release hash, all source/class/design bytes, same solo/server/two-client JARs, backup fixture hashes, captures, logs and paired wall evidence. `local/verify-0.10.py v010-final build-0.10-final3.log` verified that binding. The recoverable release checkpoint is `local/checkpoints/0.10.0-verified`.

Physical keyboard/controller feel, internet latency, arbitrary mods and exhaustive special-weapon play remain outside scripted acceptance. Non-projectile abilities/environment/death effects are not all spatial. Liftoff runtime dependence and Melty publication are unchanged and remain pending. These limitations are not claimed as successful tests.

