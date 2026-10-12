# Mindustry³ — First Public Alpha (prepared locally)

Experimental unofficial fan-made 3D / FPV mod for Mindustry.
Release label: **Alpha 1.0.0**. Internal mod version: **0.18.27**.
The internal version is deliberately unchanged to preserve the tested binary
and matching-mod multiplayer fingerprint. This release has not been published.

## Included

3D maps and natural-wall mountains, procedural unit and turret models,
first/third-person cameras, Acro and Stabilized flight, XYZ combat, Build Mode,
elevated construction with structural validation, single-layer schematics,
cross-floor logistics/power, and mining/healing/laser feedback.

Mindustry remains the host game, with its existing campaign/content and native
systems integrated with the mod's spatial extensions. This is not a small
standalone remake. Liftoff content is not redistributed or required.

## Compatibility and caution

Windows desktop with Mindustry **160.7** and a complete **64-bit Java 17** is
the verified setup. `minGameVersion: 160.5` is metadata, not a guarantee for every
160.x build. Other operating systems, Android, other mod combinations and
arbitrary headless server packages are not certified for this Alpha.

Use a separate profile and back up saves. Modded saves may depend on this exact
version, especially elevated construction. Do not load your only campaign copy.
Use fullscreen: windowed 2D/vertical views can suffer severe presentation stalls
on some Windows/NVIDIA systems. Fullscreen eliminated the reported stalls on
the development machine; it is a workaround, not a universal driver fix.

Multiplayer is **experimental**: two local clients have worked with a matching
mod and authoritative server. Independent multiplayer testing is pending.
Some broader local network regression runs stalled on creator landing / elevated
turret control. Everyone, including the server, needs the exact same JAR.

Procedural models can have clipping, imperfect material/animation coverage,
and generic fallbacks for unsupported content. Single-floor copying is supported;
volumetric multi-floor schematics are not. The broad compatibility smoke fixture
still fails its Flare firing assertion at +85° pitch; practical scope is unresolved.

## Artifact and validation

Tested JAR: `mindustry-fpv-0.18.27.jar`, **4,140,308 bytes**, SHA-256:

`d4f136ab7803966970ab50ef0d0150d895514aebff26dd343bd674c729582bc0`

Gameplay source: `556df2a8f2f3a7dc8dac2392bc825c39235c8cb5`.
Fullscreen startup/profiling checkpoint: `e60f1fb13a7c4ec6448ece2ae1ea3a02a0dcfec3`.
The latter did not replace the installed gameplay JAR.

Previous validation: 107 Java test programs passed, targeted native-game/GPU
construction/camera/combat/VFX/save tests passed, and the player confirmed
fullscreen was smooth across F5 modes and viewing angles. This release pass
does not claim a new full multiplayer or hardware-controller playtest.
New packaging/source verification results are recorded in `PUBLICATION-READINESS.md`.

Publish only the prepared Alpha bundle, corresponding-source ZIP and SHA256SUMS,
with this release note. No profiles, saves, backups, game JARs, Java runtime or
raw profiling recordings belong in the download.
