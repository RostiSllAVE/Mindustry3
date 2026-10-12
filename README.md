# Mindustry³ / Mindustry FPV — Alpha 1.0.1 Hotfix

An **experimental, unofficial fan-made mod** that presents Mindustry in 3D,
adds FPV flight and spatial combat, and extends construction across elevated
floors. This is not an official Mindustry release or a standalone game.

**Prepared hotfix: Alpha 1.0.1**, internal mod version **0.18.28**.
This hotfix has not been published by this task; Alpha 1.0.0 remains unchanged.
It fixes native installation on Steam's bundled Java 25.0.1, which lacks
`java.logging`. Optional Win32/JNA input now falls back to the game's own input
without aborting 3D initialization. Liftoff inspired the FPV concept;
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

The verified setup is **64-bit Windows desktop, Mindustry 160.7**, with an
OpenGL-capable GPU. Its standard Steam bundled **Java 25.0.1** can load this hotfix
directly; installing a separate JDK or running PowerShell is not required.
Metadata allows 160.5+, but other versions are not certified by this Alpha pass.
When a complete Java runtime provides `java.logging`, the existing Win32/JNA
mouse bridge remains available. A full JDK 17 is needed only to build from source.

**Fullscreen is strongly recommended.** Android, Linux/macOS, hardware FPV
controllers and independent dedicated-server packages are unverified.
Bundled JNA native libraries do not imply support for every platform.

## Installation — native Mindustry Import Mod

1. Back up saves and download **`mindustry-fpv-0.18.28.jar`**.
2. Launch Mindustry normally through Steam. Open **Mods → Import Mod → Import File**
   and select that JAR. Accept replacement of the previous Mindustry FPV version
   if updating; do not keep duplicate copies enabled.
3. Close/restart the game as requested, then launch it normally through Steam again.
4. **Enable Fullscreen in native graphics settings**. Start a map/save and press
   **F5** to switch 2D ↔ FPV/3D. **Tab** opens the existing Build Mode.

Do not import the source ZIP or whole repository as a mod. The JAR embeds GPL,
JNA/libffi license texts and third-party notices. Matching source is distributed
separately as `mindustry-fpv-0.18.28-corresponding-source.zip`.

The older `Play-*`, `Launch-Alpha.ps1` and profiling scripts retain their historical
paths/version requirements for Alpha 1.0.0. They are not needed for this hotfix's
native installation. Do not run the old `prepare-alpha.py` in the history-free
public repository to recreate historical artifacts.

### Mouse behavior on the stock Steam runtime

The host-input fallback keeps the cursor **visible**, samples actual movement
inside the game window and releases its logical flight input on focus loss,
menus, pause and Build Mode. It cannot warp/center or confine the OS cursor;
ordinary mouse look is bounded by the window edges.

Use **arrow keys** for continuous aiming at an edge. In Stabilized/ground modes,
left/right turn the view and up/down change vertical aim. In Acro they preserve
the existing pitch/roll alternatives; A/D still yaw. WASD, firing, mining, F5,
F7, Tab, building and command interactions keep their existing roles.
The HUD indicates host mouse input. Complete Java installations retain the
original captured/hidden/recentered native mouse behavior.

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

Hotfix JAR: **0.18.28**. Exact checksums and validation scope are in
[hotfix notes](docs/releases/ALPHA-1.0.1-HOTFIX.md) and the prepared `SHA256SUMS.txt`.
The corresponding-source archive identifies its exact public-repository checkpoint.
Current source retains opt-in diagnostics from the release snapshot; no legacy
compiled helper is carried into a clean hotfix build.

The owner confirmed a real **Steam Import File → restart through Steam → F5/Tab**
test, and the runtime log confirms successful initialization on Java 25.0.1.
Input regression tests run on both bundled Steam Java and full Java 17.
Physical/native mouse capture, controller hardware and multiplayer verification
limits are reported explicitly in the hotfix notes.

[Hotfix build guide](docs/releases/BUILDING-HOTFIX.md).
Historical [Alpha 1.0.0 notes](docs/releases/ALPHA-1.0.0.md),
[source provenance](docs/releases/BUILDING.md) and
[publication audit](docs/releases/PUBLICATION-READINESS.md) remain unchanged and
refer to the old accepted **0.18.27** binary, not this hotfix.

## License

Project code: **GPL-3.0-only**, [LICENSE](LICENSE). No warranty.
Mindustry is by Anuken and contributors; Arc is Apache-2.0. Bundled JNA 5.19.1
uses its Apache-2.0 option and includes libffi under its permission license.
See [third-party notices](THIRD_PARTY_NOTICES.md).

Publish the hotfix JAR **together with** its corresponding-source ZIP; license
texts are embedded in the JAR and included with the source. No game binaries,
Java runtime, profiles, saves, backups, raw recordings
or proprietary Liftoff resources are included in the prepared assets.
