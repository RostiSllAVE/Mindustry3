# Mindustry × FPV — proposed technical plan

Research date: 2026-10-06. The original engineering proposal is retained below. Incremental prototype 0.4 is now implemented; current scope, verification and remaining work are in [README](README.md), [0.4 milestone](docs/MILESTONE-0.4.md) and [MASHUP_AUDIT](MASHUP_AUDIT.md). It is not a published Melty release. Working folder: D:\Linux\MindustryFPV.

Current architectural target (2026-10-07): native grid/economy systems remain authoritative, while physical systems progressively migrate to canonical XYZ through World3DAdapter. The presentation-only recommendation below is historical; 0.4 already uses real altitude for new rams, explosions and spatial queries. Native weapon migration remains future work.

## Recommendation

Keep the installed Mindustry process and simulation authoritative. Use its native JVM mod support to add a 3D world renderer, an original FPV controller and camera, and adapters for building and simulation time. Liftoff supplies the target flight feel; the recommended runtime does not require or execute Liftoff.

This is technically plausible, but full renderer replacement is not a ready-made supported switch. Prove the necessary hooks in a small prototype before expanding the implementation. Melty publication is currently blocked by its host-game catalog, independently of the renderer work.

## Installed games and supported routes

| Item | Mindustry | Liftoff |
|---|---|---|
| Installation | D:\SteamLibrary\steamapps\common\Mindustry | C:\Program Files (x86)\Steam\steamapps\common\Liftoff |
| Steam app / build | 1127400 / 25421068 | 410340 / 25599583 |
| Verified technology | Java, Arc; official Steam v8 build 160.5 | Unity 2022.3.62f3, Mono/C# |
| Relevant extension | Native Java mod JAR and mod.hjson | Official output telemetry and built-in customization; no public reusable physics SDK found |
| Proposed use | Required running host | Reference for independently implemented FPV behavior |

Mindustry source is available under GPLv3. Use pinned v160.5 and its Arc revision 8eb00ffff0 for hook research. The local JAR has an unknown source commit, so runtime compatibility must still be tested. The installed stripped Java 25 runtime lacks a listed java.instrument module; do not assume a standard Java agent will work. Resolve compiler/bytecode compatibility against the official Java mod template before obtaining build dependencies. [Mindustry source](https://github.com/Anuken/Mindustry/tree/v160.5), [Java mod template](https://github.com/Anuken/MindustryJavaModTemplate), [license](https://github.com/Anuken/Mindustry/blob/v160.5/LICENSE).

Liftoff includes ACTk.Runtime.dll, and its developer documents mod detection that may disable competitive features. No ban outcome was established by this investigation. BepInEx is a generic Unity Mono candidate, not a verified or Melty-supported route for this build. The proposed route does not inject into Liftoff. [Developer changelog](https://www.liftoff-game.com/news/milestone-160-released).

## Simulation boundary

Leave maps, sector generation, campaign progression, research, items, recipes, conveyors, power/liquids, factories, turrets, enemies, waves, teams, normal damage rules, save serialization and building validation in original Mindustry code. Do not copy those definitions into a second simulation or maintain one hand-built map.

Read world tiles and entity state from the game. Send player actions back through the native movement/build/configuration paths. Retain the original player unit, team, health, inventory, mining/build capabilities, build range, costs, death and respawn; change the control of its horizontal movement through an adapter. Avoid both vanilla movement and FPV integration moving the same unit twice.

The drone's altitude/orientation and visual collision shape are additional state. Mindustry combat remains XY plus its existing air/ground targeting. Flying visually higher will not grant altitude-based immunity or make vanilla bullets fully 3D. True XYZ combat would be a separate gameplay change. Unit possession, non-flying units and collision/camera behavior need an explicit follow-up policy; retain the ordinary 2D interface as a recovery option while that coverage is incomplete.

## 3D renderer

Use Arc's existing OpenGL context, Camera3D, Mesh, Shader, Vec3 and Quat in the same JVM. Map Mindustry XY to horizontal XZ and put altitude on Y. Define scale once and use it for rendering, input, collision and flight physics.

Build chunked meshes from Vars.world.tiles: floors, resources, walls and building footprints. Invalidate chunks on world load and tile changes. Render dynamic units, rotating turrets, conveyor items, projectiles and effects from original state. Respect visibility/fog and content registries. Keep block identifiers and visual family mappings in JSON sheets. Unknown content gets a visible footprint/sprite-based fallback rather than disappearing. This supports arbitrary loaded maps; detailed models and complex effects require incremental visual coverage.

First hook test: draw a small perspective scene at Trigger.postDraw before UI. This can retain the original renderer lifecycle, but wastes the existing 2D pass; it is only a feasibility experiment. Production needs a version-checked bridge that suppresses world drawing while retaining renderer bookkeeping, minimap, menus, resizing and disposal. Assigning Vars.renderer alone is insufficient because ClientLauncher has already registered the original renderer in ApplicationCore.modules. If a reliable native-mod bridge cannot be established, evaluate a narrow maintained patch of presentation classes from the open source project, without replacing the simulation. That fallback changes packaging and licensing obligations and is not yet approved or validated.

Sources: [ClientLauncher](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/ClientLauncher.java), [Renderer](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/core/Renderer.java), [Arc module lifecycle](https://github.com/Anuken/Arc/blob/8eb00ffff0/arc-core/src/arc/ApplicationCore.java), [world](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/core/World.java).

## FPV flight and camera

Implement original rigid-body state: position, velocity, quaternion orientation, angular velocity, mass and inertia. Throttle drives thrust along the drone's local up axis; gravity, drag and motor response determine acceleration. Pitch/roll/yaw request angular rates through a tuned rate controller. In Acro, centering input stops requested rotation; it does not level the horizon. Use a fixed physics timestep with bounded substeps, initially evaluate 250 Hz, and profile before choosing a final frequency.

Relative mouse motion supplies a configurable pitch/roll rate adapter; keys or analog axes supply yaw/throttle. Mouse behavior, sensitivity, recentering and axis assignment are explicit settings, not a claim of transmitter equivalence. Add gamepad/USB transmitter calibration, axis inversion, deadzones, rates and expo for the closest controllable FPV feel. Camera follows drone orientation, with configurable camera tilt and field of view; artificial horizon leveling is off in Acro.

Liftoff's official UDP telemetry provides output measurements, not a bidirectional world/physics API. Optional later comparison sessions can measure similar maneuvers and guide tuning. No proprietary DLLs, code, models or sounds are redistributed. No permission to redistribute Liftoff physics was found; literal reuse needs an explicit license/SDK from LuGus. [Telemetry guide](https://steamcommunity.com/sharedfiles/filedetails/?id=3160488434), [Betaflight modes](https://betaflight.com/docs/wiki/guides/current/Modes), [rate concepts](https://betaflight.com/docs/wiki/guides/current/Rate-Calculator), [Steam terms](https://store.steampowered.com/subscriber_agreement/).

## Build Mode

Use a rebindable toggle, provisionally B, with three distinct input states: Flight, Build, Menu. Flight captures the cursor. Build releases the cursor, stops consuming mouse input for FPV, holds the drone pose and keeps the existing building UI interactive. Menu/focus loss/death always releases capture safely. On return, restore the proper prior pause state and normal 100% gameplay time when appropriate.

Keep native block selection, research locks, BuildPlan queues, costs, range checks, demolition, line dragging, rotation, configurations and schematics. A camera pick ray hits terrain or building geometry, then maps its footprint to Mindustry coordinates and World.toTile. Handle multiblock offsets, bounds, UI occlusion and rays that miss the world. Never build behind an active UI panel.

Control.setInput is a public hook, but merely overriding tileAt is insufficient: tileX/tileY are package-private orthographic assumptions and DesktopInput has direct mouseWorld calls. Audit every world-coordinate input path and supply one consistent perspective-to-grid adapter; leave screen coordinates unchanged for UI.

Time presets: 0%, 5%, 10%, 25%, 50%, 100%; proposed default 10%. At 0%, pause simulation while allowing plan editing; actual construction waits until time resumes. Do not multiply only Time.delta: Logic also advances state.tick from real frame time. Audit a simulation-only clock bridge, preserving UI responsiveness and async/update ordering. Single-player is the initial proposal. Cooperative time changes must be host-authoritative and shared; each player cannot independently slow the same world.

Sources: [Control](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/core/Control.java), [InputHandler](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/input/InputHandler.java), [DesktopInput](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/input/DesktopInput.java), [Camera3D picking](https://github.com/Anuken/Arc/blob/8eb00ffff0/extensions/g3d/src/arc/graphics/g3d/Camera3D.java), [Logic clocks](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/core/Logic.java), [BuilderComp](https://github.com/Anuken/Mindustry/blob/v160.5/core/src/mindustry/entities/comp/BuilderComp.java).

## Checkable milestones

1. Resolve first-release solo/co-op scope; verify a D: lab profile and back up actual saves there. Pin game compatibility and establish build/cache/temp paths on D:.
2. Small hook spike in installed Mindustry: load a native Java mod, load an existing campaign/map, show perspective 3D, pick a tile, preserve native HUD, exit cleanly. Decide production render hook from evidence before expanding.
3. General world renderer across multiple maps, including Serpulo/Erekir content families; introduce explicit coverage tracking rather than claiming all custom draw code converts automatically.
4. FPV controller, camera and player-unit adapter; verify movement integration, collisions, death/respawn and input focus transitions.
5. Full Build Mode: single placement, rotation, conveyor dragging, demolition, multiblock structures, insufficient resources/out-of-range rejection, all six time presets and restoration. Test equal simulated intervals for production, waves, projectiles and campaign clocks.
6. Compatibility/performance regression: saved games, different map sizes, mod on/off and orderly disposal. Measure frame times on this PC before promising a target FPS. Add detailed visuals family by family.
7. Once Melty supports the actual host: package, inspect all archive entries, validate recipe and one-click checks, test a fresh install/launch, capture actual gameplay, agree listing/license/remix choices, then request the user's Melty Test and publication authorization.

The first playable slice is a real Mindustry map with running original production/waves, FPV flight and native placement through 3D picking. Its initial simpler art is a stage toward the requested full presentation, not a substitute miniature game.

## Sheets and preflight

design/architecture.sheet.json contains initial system rows with linked hooks, planned descriptor names, acceptance checks and per-field verification labels. No game code is generated yet. Add visual families, physics/control parameters and further adapters as their requirements are resolved. Each row should generate a typed descriptor/configuration object; handwritten algorithms consume those descriptors. Do not regenerate original Mindustry item/weapon/enemy logic.

Before every future build, preflight must enumerate every missing/unverified cell and unresolved cross-sheet reference. A proposed design is not a verified implementation; current unresolved cells must remain visible. For the first hook experiment, define and verify its small build scope explicitly and keep the rest outside the build. Runtime acceptance gates remain pending until tested in the real game, even after design/structural preflight passes.

## Melty packaging and current blocker

Live MCP results: no existing user listing, no related live mashups, both games returned only as custom related-game slugs. game_info could not return a supported install/loader contract. validate_recipe rejected the proposed Mindustry primary host; one_click_check rejected it at the same stage. No upload, draft or release was made, and no one-click success is claimed.

After legitimate Mindustry host support exists, the intended package is a versioned mod JAR with mod.hjson, own visual assets/shaders, sheets/configuration and required licenses/source notices. Use Mindustry's built-in loader and installed runtime where verified; do not require a manual BepInEx install. Melty must discover the actual Mindustry installation and correct mod/save destination, install before launch, and launch the host once. Steam relative saves/ differs from a generic AppData assumption, so installation location and working directory must be tested. Any launcher/helper or allowed dependency must ship in the package and be declared, not downloaded manually by players.

No unrelated supported game tag will be used to bypass the catalog check. A fork/companion route would need a separately validated legitimate host contract; it is not a proven workaround. Liftoff is inspiration in this route, not an artificial installation requirement. The mod's final license/remix settings remain the user's choice subject to dependency/source obligations.

The next decision is whether to proceed with this Mindustry-hosted, independently implemented FPV route while Melty support remains a publication prerequisite. The first-release solo/co-op question is pending.
