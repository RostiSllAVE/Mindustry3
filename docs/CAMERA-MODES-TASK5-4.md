# Task 5.4 — Universal camera presentation, 0.18.5

Continued the existing clean `feature/ui-polish` at `d2a688445f10e61a72289efb4a18bc46e3fde977`. Stable 0.18.4 remains in `local/backups/before-camera-modes-0.18.4/mindustry-fpv-0.18.4.jar` (SHA256 `a5d6de0ad54807ee28c1052d495a2a53d9f308d70689226cfc5fd235e69a10c4`). No model, movement, balance, layered logistics, structural physics, protocol or save-format edits.

## Behavior and implementation

- F7 uses FIRST(0) → FRONT(1) → REAR(2), for the same shared camera path. One-time preference migration preserves the previous observer choice: old chase1 becomes rear2; old reverse observer2 becomes front1. First-person remains0.
- Alt + wheel adjusts the local saved `fpv-camera-distance` preference in 10 percentage-point steps; wheel up brings the camera closer. A 25–400% slider is available in FPV settings and the existing Build camera section. Build, command selection, held-button fence, FIRST, focus/dialog/text suspension, pause and UI hover/scroll suppress observer zoom. Native wheel input remains available for Build rotation.
- `ObserverCamera` is a presentation-only reusable boom. Default distance is max(2.5 tiles, body radius ×3.6, body half-height ×3.2); minimum also respects max(.9 tiles, radius ×1.6). The saved percentage scales this size-dependent range. Reused vectors avoid per-frame presentation allocations.
- Mode orbit, direction, extension and zoom ease using real elapsed time (.18 seconds); obstruction recovery uses .24 seconds. The boom traces the existing terrain/building/layer physical queries with .12-tile probe and .03-tile skin, including its temporal path around corners. Obstruction ingress clamps immediately. Geometry is never removed or made transparent; very tight spaces shorten the boom and may hide the controlled model to avoid rendering from inside it.
- Owner handoff, death/F6 and Build reset transient boom state, preserving the preference. Ground first-person eye/profile/override/ceiling behavior is unchanged. Stationary turret eye traces start above its actual roof with a smaller initial probe, preventing the old own-roof fraction-zero obstruction. Walker extension and flying XYZ continue coming from existing poses.
- A canonical pilot sight camera is saved before observer movement. Ordinary ground-unit reticle convergence uses that eye in all modes; flying/walker/turret aim keeps the original pilot sight direction. Front/rear observer displacement cannot reverse or converge real shots toward an unrelated observer crosshair. Observer HUD labels the independent pilot aim and omits a misleading centered reticle. No muzzle, collision, damage or authoritative network changes.
- First-person keeps the private-depth volumetric weapon renderer. Third-person shows native world models/weapons and submits no first-person weapons. Existing first-person height, recoil and input behavior stay intact.

## Verification

Development used the actual installed Mindustry 160.7 SDL runtime in disposable D: profiles. Production-camera checks cover Dagger, Reign, Flare, Eclipse, Atrax, Toxopid, Risso and a native stationary Duo; an actual upper wall, generated natural-wall mountain and upper platform roof; same XYZ aim/pose across views and zoom; preferred zoom across Build and owner/death transitions. Existing group commands, placement, first-person GPU, native combat, copied saves and map-load regressions are run separately.

Development caught and fixed the installed Arc matrix rotation sign during direction interpolation. The pure camera suite now asserts that both observer views actually face their focus, in addition to finite orthonormal bases and collision/zoom easing.

Final package: **3,815,501 bytes**, SHA256 **`10277b849548de6321efef3258966000b1c90aa8b967072d1056f3058363b02e`**. `local/build-camera-final2.log` completed all **80 Java suites**. Both final native runs used exactly this SHA:

- `local/layer-smoke/camera-final2-1607/layers-result.json`: passed **492 camera checks**, **293 elevated turret checks**, **16 turret GPU checks**, **40 group command checks**, **39 ground-eye checks**, **83 first-person GPU checks**, **38 schematic**, **48 placement lifecycle**, **37 automatic construction**, **26 mouse interaction**, **6 interaction GPU**, **209 construction**, **9 construction-preview GPU** and **7 display GPU** checks.
- `local/compat-smoke/camera-final2-1607/results.json`: passed **3 maps** (Ground Zero, BoxFort, The Onset), copied saves and native SaveIO round trip, **49 flight/control assertions**, **36 actual native firing cases** across three modes, native XYZ combat/occlusion, all **20 ground-family visuals**, existing Dagger/model tests and **GL error0**.
- Mode migration, distance bounds and actual backend Alt-wheel input are covered by the camera fixture. Build rotation retains its original wheel axis; suspended and first-person gates reject observer zoom. All seven unit specimens have actual first-person weapon submissions and zero viewmodel submissions in both third-person modes. Model geometry remains untouched.
- Native SDL front/rear/first-person screenshots were inspected. Collision tests use the actual existing physical queries and generated terrain; these synchronous camera fixtures are not manual gameplay or a completed-frame obstacle pixel benchmark.
- Installed the exact tested JAR in `play/saves/mods/mindustry-fpv.jar`. **624 original non-mod profile files remain byte-identical**. Stable 0.18.4 backup retained. Build entry/exit resets transient boom, and the legacy control regression now waits for the explicitly requested smooth recovery before asserting the full world model is visible.

Near/far measured distances on the clear fixture (tiles, including boom lift): Dagger1.56–12.99; Reign5.09–44.16; Flare1.80–15.29; Eclipse11.73–101.71; Atrax2.28–19.75; Toxopid5.10–44.23; Risso2.65–23.00. No push or merge.

## Limits

Tests use native input/API fixtures in a real SDL process, not physical OS mouse/keyboard automation. The wheel is an existing zoom binding with Alt modifier; this task adds no custom binding editor. Third-person observes the unit without changing its pilot aim, so front view is an observation mode. Near an obstacle, a large requested distance cannot override available headroom. No fresh multiplayer or full-scene FPS benchmark is claimed; camera preferences/presentation are local and existing authoritative control/combat messages remain unchanged.
