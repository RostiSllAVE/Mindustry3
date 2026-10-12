# Duplicate placement fix — 0.14.4

Existing branch `feature/ui-polish`, clean baseline `be7df9aca109d2da807a342fc6ff49ab00b99dcd`. This is an input-lifecycle correction; wall geometry, the shared picker, renderer, simulation and network protocol are unchanged.

## Confirmed cause

The controller recomputed the automatic placement target during every held poll. Newly placed geometry could become the nearest surface, moving the next target outward along a platform edge. The per-gesture deduplication tracked X/Y, so those newly selected cells looked like legitimate drag cells even with an unmoved pointer. Floor changes also cancelled/cleared that deduplication, and a repeated `selectTap` sample restarted the gesture. A Z0 press could consequently change ownership when the world under it changed.

Actual installed Mindustry 160.7 reproduction: `local/layer-smoke/placement-lifecycle-before/layers-result.json`. The first press left two components (one existing slab plus one new slab at X41). Four further stationary held polls left six components and a target at X45. No mouse movement or extra intentional placement occurred. This failure was observed before changing the controller.

## Correction

- Latch the press edge rather than restarting on every sampled tap flag. Capture the initial X/Y/layer and construction context once.
- Keep the target stable while the pointer is stationary, including when geometry or the camera changes. A small 3-screen-pixel movement starts dragging; subsequent changed pointer positions use the existing construction-plane resolver on the captured floor.
- Retain the existing visited-cell deduplication for the entire stroke. The floor cannot automatically change during that stroke, so the cell key cannot be reused to stack buildings. Changing block/platform/mode, manual floor selection, cancellation or suspension aborts the stroke until a fresh press.
- Preserve native Z0 ownership through the release poll, allowing `DesktopInput` to flush its native build line once. Upper placement stays in the upper handler. Same-poll tap/release and consecutive rapid clicks work without an idle frame or time cooldown.
- After release, the next press again resolves the actual visible surface automatically. Release the button before starting a line on a different floor. Manual floor locking remains available.

Server execution did not require a new cooldown or packet format. Existing epoch/sequence replay rejection occurs before applying a command, and ordinary occupied-cell/resource/team/support checks still run on every distinct request. The client now emits at most one request per visited cell of its captured upper stroke; accepted/pending actions do not trigger a held-button retry.

## Actual verification

Package: `dist/mindustry-fpv-0.14.4.jar`, 3,656,353 bytes, SHA-256 `dcf5ca2b9c11749874b4e15f36a8bfd1718cb3792b209fe272893907fccc6c12`.

- All 73 Java suites passed (`local/build-placement-final.log`).
- Installed 160.7 SDL: 48 new placement lifecycle checks passed (`local/layer-smoke/placement-lifecycle-final2/layers-result.json`). These use a scripted polling input inside the real game and invoke actual `FpvDesktopInput.update`, `DesktopInput` planning/flushing and native unit build execution. Covered Z0/Z1/Z2 clicks, same-poll and consecutive rapid clicks, a combined old-release/new-down poll followed by continued holding, stationary/repeated-tap holds over 120 polls, automatic and locked conveyor/wall drags, revisits, a newly appearing wall during a Z0 press, and interrupted Build/FPV input state.
- The same run passed the prior 37 construction checks and 13 wall/conveyor GPU checks, 26 mouse checks, 209 layered checks, and 6 interaction/9 preview/32 first-person/7 display GPU checks. Tall wall tops, exact placement previews, removal, native bindings and upper geometry remain covered.
- Full installed-160.7 regression passed on three maps and copied saves, with native ground build execution, FPV/Build transitions and GL error 0 (`local/compat-smoke/placement-lifecycle-final2-1607/results.json`).
- Official 160.7 server and two clients passed (`local/net-lab/placement-lifecycle-final2-1607/*-results.json`). The 105 layered authority checks explicitly confirmed reliable replay with a single resource charge, distinct placements, permissions, configuration, inventories and reconnect. The network harness uses native authority packets rather than physical client mouse events; client UI lifecycle and native Z0 dispatch were tested separately in SDL.

The held-state checks are 120 scripted polling iterations, not a manually timed physical two-second mouse hold. Hardware input/controller testing and a new 160.5 run are not claimed. Upper dragging still samples visited pointer cells as before; very fast movement can leave gaps. No cross-floor schematic or new drag-planner feature was added.

The original working 0.14.3 is retained at `local/backups/before-placement-lifecycle-0.14.3/mindustry-fpv-0.14.3.jar` (SHA-256 `0b2aa81e25d73e136e1a4ae1c2a2c562ac8fc2e7689e3d831c596970e940c50d`); the source baseline remains in Git. The exact tested package is installed; all 591 original non-FPV-JAR profile files remain byte-identical. Combined final receipts and profile-preservation checks: `local/placement-verification.json`. No push/publication.
