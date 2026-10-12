# Quick camera smoothing fix — 0.18.6

Started on clean `AfterCamera`, which and `feature/ui-polish` both pointed at `cd433b15484fa8cd1af9e941e27eabebe19f99b9`. Safely switched to `feature/ui-polish` and verified it before editing. Stable 0.18.5 retained in `local/backups/before-smoothing-0.18.5/mindustry-fpv-0.18.5.jar`.

## Root cause and focused change

The boom position followed the current pilot basis, but a separate stored world-direction filter slowly rotated toward the new focus. Thus a turn could relocate the camera immediately while its view still pointed in the old direction. A temporal sweep starting from an old world position could additionally hold the camera behind a moving owner.

The existing boom now keeps only local orbit/distance animation. Owner translation and heading use the current pilot frame immediately; temporal animation sweeps transform the saved local offset into that same frame. Orientation comes from the actual camera position and current unit focus in the same update. There is no second world-direction filter to recenter later. First/third transition focus height and boom lift use the same transition fraction; unit poses and the canonical pilot aiming camera remain separate and unchanged.

`fpv-camera-smoothing` defaults **false** for existing/new profiles. OFF directly resolves the selected mode, distance and direction even with zero elapsed time, including switching off during an animation. Collision still clamps immediately, but obstacle recovery is immediate. ON eases local perspective/zoom/obstacle recovery without delaying owner movement/turning. Existing first-person ground eye sizing/hull/roof limits remain; its old handoff animation is enabled only with smoothing ON, and OFF sets the same target height immediately.

Default shortcut **Alt+F7** uses a native rebindable `fpv-camera-smoothing-toggle` key in Controls → FPV camera. Modifier is selectable in FPV settings (None/Alt/Ctrl/Shift); unmodified F7 stays reserved for perspective cycling. Shortcut consumes the matching FPV hotkey action so default Alt+F7 never also cycles F7. Text/dialog/lost-window-focus gates remain. A two-second HUD confirmation shows ON/OFF. The boolean also appears in FPV settings and as a button in the Build camera panel. Preference is local and saved through existing settings; no network message, movement, weapon, model, construction or balance changes.

## Verification

`ObserverCameraTest` checks current-frame translation and turns, continuously centered animated orbit, OFF immediate mode/zoom/collision recovery/first eye at zero time, switching OFF mid-animation, finite bases, adaptive distances and input gates. Native fixtures cover Dagger/Reign, Flare/Eclipse, Atrax/Toxopid, Risso and a fixed Duo with ON and OFF, moves/turns/zoom/F7 and no viewmodel duplication. Flying turn fixtures use the actual acro quaternion; stabilized/ground fixtures use their actual free-look basis.

The existing animated ground-eye regression explicitly selects smoothing ON. Original F7/Alt-wheel, upper wall/mountain/roof collisions, commands, placement and native firing/saves are rechecked separately in isolated SDL160.7 profiles. Input uses native API fixtures, not physical OS automation. No fresh multiplayer session or whole-scene FPS benchmark is claimed; existing authoritative controls and combat are unchanged.

Final package: **3,819,871 bytes**, SHA256 **`35eb6be65d7fc43e425e862338ccca77445fba0cdfa3ce55b37e9a76eca9a554`**. `local/build-smoothing-final.log` passed all **80 Java suites**. Both final SDL runs and the installed JAR match this hash:

- `local/layer-smoke/smoothing-final-1607/layers-result.json`: **685 camera checks**, including default OFF, native backend Alt+F7, rebound Alt/Ctrl+F11, UI-focus rejection, HUD confirmation text, ON/OFF translation, turns, zoom, F7 and centered local orbits across all seven unit specimens and a fixed turret. Also passed **293 turret**, **39 animated ground-eye**, **83 first-person GPU**, **40 group command**, **209 construction** checks and the existing placement/schematic/input/preview/display regressions. Native camera screenshot inspected.
- `local/compat-smoke/smoothing-final-1607/results.json`: **3 maps**, copied saves/native roundtrip, **49 flight/control checks**, **36 actual firing cases** across three views, native XYZ combat and all **20 ground-family models**; **GL error0**.
- Installed exact tested 0.18.6 in `play/saves/mods/mindustry-fpv.jar`. All **627 original non-mod profile files remain byte-identical**. Stable 0.18.5 backup matches SHA `10277b849548de6321efef3258966000b1c90aa8b967072d1056f3058363b02e`. `master`, `Main` and the user's `AfterCamera` snapshot remain unchanged. No push or merge.
