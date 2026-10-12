# Alpha 1.0.1 — native Steam import hotfix

Internal mod version **0.18.28**, prepared locally; no publication/push in this task.
Original Alpha 1.0.0 / 0.18.27 archives remain unchanged.

## Fixed failure and installation

Steam Mindustry **160.7** embeds Java **25.0.1** with only `java.base` and
`jdk.unsupported`. JNA's `Native` initialization uses `java.util.logging.Logger`,
which is missing in that runtime. Previously `WindowsFlightInput` was constructed
before the other systems inside the common `FpvMod.init()` try block; failure
prevented settings, input, network, rendering and F5 handlers from being installed.

`FlightMouseInput` separates the required game controls from optional Win32/JNA
capture. `OptionalFlightInput` checks the Logger class before native construction,
isolates constructor/linkage errors, and can fall back after a native runtime
failure. A full runtime keeps the existing Win32 implementation. The fallback
samples Mindustry's original pointer positions and its SDL window-focus flag;
pause/resume and mode changes fence stale motion. No JDK download or PowerShell
launcher is required for users.

Install **mindustry-fpv-0.18.28.jar** through
**Mods → Import Mod → Import File**, replace the old mod if requested, close the
game and restart normally through Steam. Keep only one version enabled.
The JAR includes GPL/JNA/libffi license texts and third-party attribution.
The matching source ZIP must be offered alongside it when distributed.

## Fallback limitations

The stock runtime uses a **visible, unconfined cursor**. Mouse movement inside the
window changes the camera; it stops at the window edges and cannot be recentered
or trapped without the native backend. Use arrows for continuous aiming there:
Stabilized/ground mode turns left/right and aims up/down; Acro retains its existing
pitch/roll arrow controls and A/D yaw. Mouse sensitivity remains applicable.
This is not equivalent to unlimited native captured mouse look.

Normal building/menu cursors, flight/build transitions, firing/mining, camera
switching and movement remain active. Complete Java installations preserve the
native hidden/recentered cursor. No combat, movement balance or network protocol
was changed. Every multiplayer participant still needs the **same exact JAR**.

## Actual verification

- **Real Import File/restart test:** performed by the owner in their normal Steam
  installation. They confirmed import/restart and F5/Tab work. The saved runtime
  log independently records Java 25.0.1, the missing-logging fallback warning,
  successful full mod/network initialization and 2D/3D transitions. This was not
  an automated native-dialog test: screenshot automation could inspect the menu
  but its injected input did not activate controls.
- **Full Java 17:** clean build, **109 Java test programs passed**, including new
  optional-backend failure/module and host-pointer lifecycle tests.
- **Actual stripped Steam Java 25.0.1:** the same **109 test programs passed**;
  Logger absence was checked explicitly.
- **Real SDL/GL regression on both runtimes:** each passed the existing 209-check
  layered fixture, 685 camera checks, 37 ground-eye checks, 48 placement lifecycle,
  37 automatic placement plus 13 GPU checks, 26 interaction plus 6 GPU checks,
  38 schematic and 40 unit-command checks, plus first-person/mining/laser/healing/
  construction/display/save regressions. No new `[E]` log entries or GL errors.
  These use scripted game APIs and isolated profiles, not physical input emulation.
- All original Alpha 1.0.0 release-file hashes were checked and preserved.

Physical native Win32 cursor capture/Alt-Tab on full Java 17 was not newly retested
by a human; native construction and API regressions passed, and its implementation
body is preserved. Controller hardware, every screen/DPI arrangement, overlays
and independent multiplayer remain unverified. The previously disclosed Flare
+85° broad-smoke failure was outside this hotfix and is not claimed fixed.

## Artifact provenance

Final JAR: **4,184,797 bytes**, SHA-256:

`aa350793618674349c9b6d99784e365838d5b119227fd4d086f0174a916b1a82`

The owner's imported candidate SHA-256 was
`ccbb20846e3ccc992fa62dc44c500154a4a21e6d96fbf1574ee8709162b07551`.
Final rebuild comparison found exactly **one entry with different bytes**:
`THIRD_PARTY_NOTICES.md`, clarified for the hotfix. Every executable class, mod
metadata, design sheet and dependency payload is identical. Because multiplayer
fingerprints cover the whole JAR, install the final artifact on all participants;
do not mix the candidate with it.

The corresponding-source ZIP is exported from the new public hotfix checkpoint,
with a file-hash/source manifest. It describes this **0.18.28** code including the
existing opt-in diagnostics. Historical 0.18.27 provenance remains separate;
its original source ZIP was not substituted or overwritten. Builds clean stale
owned classes, so the obsolete old `NativeLimbRenderer$TimeDelta` binary is absent.

See [building](BUILDING-HOTFIX.md), [validation](HOTFIX-VALIDATION.json) and
[licenses](../../THIRD_PARTY_NOTICES.md). Package hashes/source checkpoint are in
the prepared `dist/alpha-1.0.1/release-manifest.json` and `SHA256SUMS.txt`.
