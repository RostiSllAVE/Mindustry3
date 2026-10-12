# Mindustry³ / Mindustry FPV — First Public Alpha

(Hello! before you read that repository i want to let you know that that project 99% vibe-coded but tested by human, i mean by me😁. That is actually my first project and i am new on GitHub as creator. In a few days i will publish an YouTube channel where i show how that mod works. Hope you will have fun with this mod!)

An **experimental, unofficial fan-made mod** that presents Mindustry in 3D,
adds FPV flight and spatial combat, and extends construction across elevated
floors. This is not an official Mindustry release or a standalone game.

**Prepared release: Alpha 1.0.0.** The tested mod's internal version remains
**0.18.27**. The first public Alpha is now available through GitHub Releases. This is an experimental build with known bugs and incomplete features. Liftoff inspired the FPV concept;
its code/assets are not included, it is not required, and this is not a validated
Melty two-game mashup.

## Features

- Existing Mindustry maps, campaign, resources, production, waves, research and
  units, integrated with the mod's spatial extensions.
- Natural-wall mountains, textured terrain, water, distant scenery and day/night sky.
- Procedural models from the installed game's original sprite silhouettes;
  animated weapons, mechs, walkers, aircraft and ships.
- First-person, front observer and rear-follow cameras; adjustable distance,
  FOV, camera tilt and optional smoothing.
- Acro/Stabilized flight, XYZ altitude, collision, projectiles, lasers and shields.
- Build Mode with adjustable world speed, surface-aware placement, elevated
  floors, structural support/collapse, single-layer copy/paste and cross-floor
  logistics/power connections.
- Native mining with 3D feedback, unit commands and eligible unit/turret possession.

Coverage is experimental. Unsupported custom content can use visual fallbacks;
not every native/custom rendering or combat pattern is converted identically.

## Requirements

The verified setup is **64-bit Windows desktop, Mindustry 160.7, and a complete
64-bit Java 17 runtime/JDK**, including `java.logging`, with an OpenGL-capable GPU.
Metadata allows 160.5+, but other versions are not certified by this Alpha pass.
The tested Steam installation's stripped bundled runtime is insufficient for
the JNA mouse bridge: this is not a stock-runtime drop-in mod.

**Fullscreen is strongly recommended.** Android, Linux/macOS, hardware FPV
controllers and independent dedicated-server packages are unverified.
Bundled JNA native libraries do not imply support for every platform.

## Installation (Windows Alpha bundle)

1. Back up saves. Extract the **Alpha ZIP**, not the entire development folder.
   Keep its license/notice files with the JAR. Choose a writable folder with space.
2. Install complete 64-bit Java 17 and keep your own Mindustry/Steam installation.
3. Open PowerShell in the extracted folder and use your own paths:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Launch-Alpha.ps1 `
     -GameDir 'E:\SteamLibrary\steamapps\common\Mindustry' `
     -JavaHome 'E:\Tools\jdk-17'
   ```

   The launcher verifies the JAR/runtime and creates a separate `alpha-profile`
   beside itself. It does not replace the game's runtime or ordinary saves.
   Optional `-ProfileDirectory 'E:\Games\Mindustry-Alpha-Profile'` changes the
   profile location. Keep Steam running if your installed host needs it.
4. **Enable Fullscreen in native graphics settings**. Start a fresh map/save.
   Press **F5** to switch 2D ↔ FPV/3D.

If your existing launcher already uses complete Java 17, import only
`mindustry-fpv-0.18.27.jar` through **Mods → Import Mod**, restart, and ensure
only one mod version is enabled. Do not import the Alpha/source ZIP as a mod.
Native Steam startup may still use its stripped runtime.

The repository's older `Play-*`/profiling scripts have machine-specific paths;
use the public Alpha launcher instead. It does not force fullscreen settings.

## Controls (defaults)

| Input | Action |
| --- | --- |
| F5 | Toggle original 2D presentation / FPV 3D |
| Tab | Enter/leave Build Mode; release/capture mouse |
| F7 | First person → front observer → rear follow |
| Alt + F7 | Camera smoothing ON/OFF (default OFF; saved) |
| Alt + wheel | Third-person camera distance |
| F9 | Acro / Stabilized |
| W / S in Acro | Increase/decrease persistent throttle |
| A / D in Acro | Yaw |
| Mouse in Acro | Pitch / roll; arrow keys are alternatives |
| WASD in Stabilized / ground control | Move relative to horizontal viewing direction |
| Q / E in Stabilized flight | Descend / climb |
| Left mouse | Fire through real unit weapon rules |
| Hold right mouse in FPV | Mine aimed valid ore with a capable unit |
| F2 | Native boost / landing for eligible Nova, Pulsar, Quasar, Vela |
| F8 | Creator Flight for ground units, if the host permits it |
| Q / E on walkers | Lower/raise stance; not flight |
| Ctrl + click in Build Mode | Possess eligible friendly unit / native controllable turret |
| F10 / native command binding (usually Shift) | Command/selection mode |
| Shift + drag in command mode | Select friendly units; native movement/attack commands |
| Ctrl + F in Build Mode | Single-floor area copy; paste on valid target floor |
| PageUp / PageDown in Build Mode | Choose/lock higher/lower construction floor |
| WASD, Q / E in flying Build Mode | Precision horizontal / vertical movement |
| Home / End in Build Mode | Turn heading |
| Right mouse / Esc in Build Mode | Cancel placement/linking; native dismantling binding applies when appropriate |
| F6 | Single-player pose reset; server controls multiplayer position |

Native Controls settings remain authoritative for remappable bindings,
dismantling and possession. F10 avoids a Shift/boost conflict on boost-capable
units. The front observer view **does not reverse** the firing direction.

The left Build panel exposes automatic surface selection, floor locking,
platforms, structural/host-authorized Free placement, inventory transfers and
connections. **Build movement speed**, **Flight speed** and **World speed** are
separate settings. Camera tilt/FOV and visual toggles remain in FPV settings.

## Multiplayer — experimental

Two local clients have worked with matching mod/server authority.
**Independent multiplayer testing is still pending.** Some broader local
regression runs stalled during creator landing / elevated turret control.
Every client **and server** needs the exact same tested JAR; rebuilt JARs with
the same version label can have different fingerprints. Arbitrary vanilla
servers are unsupported. Stock headless-server packages are not certified here.

## Known issues

- **Windowed performance:** on the development Windows/NVIDIA laptop, 2D and
  near-vertical/edge views could display only 2–7 FPS despite ~240 submitted
  frames/s. Fullscreen eliminated the player's reproduced stalls; recorded
  post-load segments were ~142–240 displayed FPS. VSync alone did not fix it.
  This is a workaround on one system, not a universal FPS guarantee.
  [Measured investigation](docs/performance/camera-profiling.json).
- Procedural model clipping, stepped silhouettes, incomplete special
  effects/animations and generic content fallbacks remain possible.
- A broad smoke fixture still fails a Flare fixed-gun firing assertion at +85°
  pitch. Practical scope is unresolved; narrower camera/combat checks passed.
- Copying supports **one layer at a time**, not full volumetric schematics.
  Support and occupancy validation still apply.
- Saves may depend on this exact mod version. Back up before importing campaign
  progress, removing/updating the mod or testing multiplayer. Elevated buildings
  are not preserved automatically when removing the mod.
- Long campaign playthroughs, every content combination, controller calibration
  and remote multiplayer have not received complete acceptance testing.

Report bugs with game/mod versions, mode, reproduction steps and a short relevant
log excerpt. Remove usernames, IPs, private paths and tokens before sharing.

## Release verification and source

Tested JAR: `mindustry-fpv-0.18.27.jar`, **4,140,308 bytes**, SHA-256:

```text
d4f136ab7803966970ab50ef0d0150d895514aebff26dd343bd674c729582bc0
```

Gameplay source: `556df2a8f2f3a7dc8dac2392bc825c39235c8cb5`. The release branch
is based on `e60f1fb`, additionally containing the local fullscreen startup fix
and optional diagnostics absent from the unchanged tested JAR. Rebuild from the
dedicated corresponding-source ZIP, not the automatic current-branch archive.

[Build guide](docs/releases/BUILDING.md) · [Release notes](docs/releases/ALPHA-1.0.0.md) ·
[Publication audit](docs/releases/PUBLICATION-READINESS.md).
Prior development checks included 107 passing Java test programs and targeted
native-game/GPU/save tests. Packaging checks are reported separately and do not
substitute for new independent gameplay tests.

## License

Project code: **GPL-3.0-only**, [LICENSE](LICENSE). No warranty.
Mindustry is by Anuken and contributors; Arc is Apache-2.0. Bundled JNA 5.19.1
uses its Apache-2.0 option and includes libffi under its permission license.
See [third-party notices](THIRD_PARTY_NOTICES.md).

Publish the Alpha bundle **together with** the corresponding-source ZIP and
licenses. No game binaries, Java runtime, profiles, saves, backups, raw recordings
or proprietary Liftoff resources are included in the prepared assets.
