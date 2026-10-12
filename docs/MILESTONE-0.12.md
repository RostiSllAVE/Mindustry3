# Mindustry FPV 0.12 — unit tier markers and larger vegetation

Verified release: `dist/mindustry-fpv-0.12.0.jar`, SHA-256 `7ec70e7b66ffdb58e093adf68c2024450f250cb85933de8c676367ac1728f155`. Clean build, full native SDL acceptance, matching-package server/two-client acceptance and paired visual profiling passed. Evidence is bound to this package by `local/smoke/verification.json` and preserved in `local/checkpoints/0.12.0-verified`.

This pass extends the working 0.11 renderer against Mindustry 160.5. Unit markers and vegetation enlargement are presentation changes. Existing native content, simulation, unit models, combat, terrain, collision, Build Mode and server authority remain the underlying systems.

## Unit tier discovery

`UnitThreatTiers` builds an immutable lookup from loaded native content after initialization. It uses factory outputs, reconstructor upgrade edges, assembler payload-family/module progression and core research ancestry. It does not estimate tiers from health, weapon damage, size or unit names, and does not change production or research metadata.

The cached production graph resolves T1–T5 where native progression provides a consistent answer. Missing, conflicting or unanchored progression remains unknown. Auxiliary cargo/construction drones and summoned missiles are not automatically promoted to a combat tier. Render-time lookups reuse cached enum values.

## Marker appearance and rendering

One procedural atlas contains six 64×64 cells: unknown hollow diamond, T1 dot, T2 two dots, T3 triangle, T4 square and T5 skull. Fill, dark edge and controlled-unit white edge occupy separate atlas channels. The generated silhouettes do not depend on fonts or copied UI artwork.

Markers use the unit's native team color. The locally controlled unit receives a white outer outline in external camera views; it is excluded when the controlled body is hidden in first person. Marker anchors follow the shared canonical XYZ body pose above its visual upper envelope, including pitch/roll and original sprite dimensions. This visual envelope does not enlarge collision bounds.

The local **Show Unit Threat Markers** preference (`fpv-threat-markers`) defaults on. It affects only this renderer and sends no gameplay settings to the server.

Selection applies native fog visibility, camera-facing/screen bounds and camera-to-body XYZ distance. Opacity remains full through 80 tiles, fades linearly to zero at 100 tiles, and geometry beyond 100 tiles is excluded. Glyphs stay within a bounded screen size rather than growing indefinitely at close range.

The renderer batches camera-facing quads into one mesh draw. Its two occlusion checks use the rendered scene depth:

- A copied depth texture gates the marker using a projected point on the visible-facing body envelope. A hidden body does not receive an otherwise unobstructed floating label.
- The marker quad itself uses the ordinary GPU depth test, so opaque geometry can also cover glyph pixels above the body.

The depth copy is GPU-to-GPU and happens only for a nonempty submitted batch. Depth writes stay off; blending, depth settings, texture bindings and the previous shader program are restored after drawing. “Submitted” diagnostics count scheduled glyphs, not a guarantee that pixels survive the shader's depth tests.

## Bounded crowd handling

The current renderer admits at most 8,192 candidates and submits at most 2,048 markers. It prioritizes the controlled unit, then processes distance buckets from near to far. A reusable 32-pixel occupancy grid suppresses intersecting marker regions. Candidate storage, vertex arrays, atlas and mesh are retained between frames.

This thinning is deliberately conservative. A marker reserves its screen cells before GPU occlusion; a subsequently hidden marker can therefore suppress another candidate in the same cells. The pass does not perform per-unit CPU visibility readback or promise one visible symbol for every member of a dense group. Final density/performance measurements remain pending.

## Vegetation at five times visual size

`VegetationVisualScale` applies exactly 5× to all three local dimensions of the existing pine, leafy-tree, dead-tree and bush families. `OrganicPropRenderer` applies this factor while baking the same cached templates into the existing atlas/chunk batches. Deterministic shape/color variation and texture sources are unchanged. The instance's native X/Y tile anchor and zero-height base stay fixed; enlargement occurs around that base, not around the map origin.

Decorative rocks remain 1×. Buildings, drills, ores, units and natural mountain geometry do not use this multiplier. `HeadlessGeometry`, tile solidity, physical surface heights and collision queries retain their original values. The larger canopy is visual decoration and introduces no larger physical barrier.

Chunk-frustum bounds include the expanded geometry. `FpvWorldRenderer` caches the maximum vegetation height and horizontal overhang from loaded content. Each chunk's horizontal half-extent grows by the conservative overhang; its vertical bound includes the maximum scaled vegetation height. A native one-tile plant needs two extra tiles of horizontal padding, and a tree with original height 1.4 tiles reaches a visual height of 7 tiles. Rotation and existing instance variation remain inside these bounds. No per-frame vegetation scan or new per-object draw call is added.

## Backup and preliminary evidence

- Before editing, `local/backups/before-0.12-20261008T164159Z` was verified with 1,065 files. Its exact 0.11 release SHA-256 is `06d399a94d632bb44c0d58118d8446685e4de082e22083919d98c9c8acf526ec`.
- The focused vegetation compilation passed. `VegetationVisualScaleTest` checks 11,760 vertices from all cached prop kinds/variants: vegetation 5× XYZ, rocks unchanged, zero-height base preserved and source templates/cache identity unchanged.
- The actual SDL quick run `local/maintenance-smoke/v012-first/results.json` passed. Its isolated candidate SHA-256 was `b5ec69c82ee0d8bf7b1a3767d0e08ec595aab5cb9554da71d42036726dc1638d`; this is a preliminary candidate identity, not the final release.
- That run reports 136 native tier checks covering all 50 combat progression tiers, six core stages, unranked auxiliaries and immutable native metadata. It also verifies original vegetation heights/solidity, low/high collision traces, unchanged non-vegetation dimensions, cached chunks and tile immutability.
- Quick-run captures include `vegetation-5x-overview.png` and `vegetation-5x-low-angle.png`. The existing 20-walker gallery and extended Toxopid terrain fixture also passed; OpenGL error was 0. The quick scope reports zero campaign maps verified and is not the full regression suite.

## Final verification

- Clean Java17 build against installed Mindustry160.5: 39 test suites, 368,264 checks. This includes native tier metadata, procedural symbol topology/outline masks/fade boundaries and every cached vegetation vertex.
- Full native SDL scenario: three maps, imported/latest save copies and save roundtrip; FPV, Build Mode, XYZ combat, shields, native controls and existing walker/creator systems passed. Original user saves were not used as writable test profiles.
- The final artifact repeats all 64 cache-warmed marker GPU checks, including actual native-color pixels, white outline, body and glyph occlusion, native wall/mountain blockers, true XYZ distance and 80/90/101-tile boundaries. Vegetation visual/physical separation and native tile immutability also passed. OpenGL error: 0.
- Real dedicated server and two graphical clients: `local/net-lab/v012-final`; identical release SHA on all three. Both clients and server passed their authority, compatibility and creator-flight scenarios. These are scripted native/protocol tests, not physical keyboard/controller or WAN tests.
- Audio is not verified in this environment: the native SoLoud backend fails initialization before mod loading in both the immutable0.11 baseline and0.12. A separate sequential solo retry produces the same startup error while all gameplay/visual checks pass. The verification receipt preserves this specific host limitation; any other logged error remains a failure.
- Paired forest/crowd profiling: 1,024 props, 256 combat units, 60 warmup and 180 measured frames per view. 116 crowd glyphs use one draw, 696 vertices and one GPU depth copy. The marker pass averages 0.213ms CPU; the measured additional GPU cost is approximately 0.64ms. Enlarged vegetation adds 0.003–0.143ms CPU and 0.014–0.026ms GPU in the tested views. No overall FPS improvement is claimed. Full method and raw identities: [performance report](THREAT-VEGETATION-PERFORMANCE-0.12.md).

No Liftoff runtime integration or Melty publication result is established by this presentation pass.
