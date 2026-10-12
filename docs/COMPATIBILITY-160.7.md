# Mindustry FPV 0.12.1 — Mindustry 160.7 compatibility

This patch restores startup after the host update and handles duplicate FPV packages introduced by imported game data. It extends the existing 0.12 implementation. There are no production renderer changes, balance changes or save-format migrations in this pass.

## Confirmed causes and fixes

The user's play log records `[FPV] This prototype supports Mindustry 160.5 only; staying in normal 2D.` The old mod deliberately returned from initialization on 160.7. This was a version restriction, not evidence that the imported campaign had become unreadable.

`HostCompatibility` now supplies one explicit version policy to graphical and dedicated-server startup:

| Host version | Startup policy |
| --- | --- |
| 160.5 | Retained support |
| 160.7 | Added support after API and native-physics checks |
| Other revisions/builds | Remain unsupported; the warning reports the actual version |

The minimum metadata version remains 160.5. It does not imply support for every later build. Runtime test reports now record the actual host version instead of a fixed `160.5` label. Multiplayer still requires matching FPV packages and compatible native game versions on the server and clients; the patch does not bypass the native network handshake.

There was also a real physics lifecycle difference. In 160.5, `PhysicsProcess.add` immediately registers a body in its native collision layer. In 160.7, it records a reference and queues layer registration until `begin`. The FPV adapter could seed that reference, then add the same body again when the queue was flushed. `Layer.seed` now inserts by identity only once. The later native registration still reaches the preserved original layer, so disabling FPV restores correct native bodies rather than losing deferred additions.

The imported profile contained three identical 0.12 packages: `mindustry-fpv-0.12.0.zip`, `mindustry-fpv-0.12.01.zip` and `mindustry-fpv.jar`. All had SHA-256 `7ec70e7b66ffdb58e093adf68c2024450f250cb85933de8c676367ac1728f155`. Replacing only the canonical JAR could leave an older duplicate available for native mod selection. The launcher now calls `tools/install-profile-mod.ps1`: it identifies FPV by its metadata ID and Java entry point, archives recognized older/duplicate packages outside the active mods directory, then installs the verified current JAR. Archive manifests retain original names and hashes. Unrelated mods and unrecognized files remain untouched.

## Compatibility evidence

The audit compares the retained official 160.5 desktop JAR with the installed Steam 160.7 JAR:

- 160.5 SHA-256: `c2fd5a5dcb8d306525bb47ff28121237d4d25f53868562946491f2ef261dc272`.
- 160.7 SHA-256: `5b7481e090421ba180d814390d8f02d084207ad3cf612e97ae4fe15e180e48e4`.
- All 1,804 resolved host member references used by 0.12 remain available with unchanged signatures and relevant access flags. The one reference outside the filtered host catalog is `Category.name`, inherited from Java's `Enum`; it is not a missing game API.
- Bytecode review found the deferred physics queue, a force-field regeneration bookkeeping adjustment and a native homing-target priority adjustment. Core game logic, desktop input, SDL graphics, `ApplicationCore` and `Net` were byte-identical. API presence alone is not treated as proof of behavioral compatibility.

Raw audit data and comparisons are in `local/compat-1607/`.

## Verification status

Completed focused checks:

- Changed sources compile against both 160.5 and 160.7; the explicit version-policy test passes 133 checks on each.
- The isolated native `NativeSeparationProbe` fails against the original 0.12 package on 160.7 at the duplicate-body assertion. With the fix, all 38 checks pass on both hosts.
- Those native checks cover initial deferred registration, toggles before queue flush, spawning/removal during active separation, creation followed by removal before the next update, separation across different altitudes and restoration of original native layers. The regular `AltitudeSeparationSmokeChecks` also includes these lifecycle regressions.
- Duplicate-package archival was exercised on copies of the imported profile, with hashes retained in `local/backups/mod-imports-*/manifest.json`.

The source and complete imported play profile were backed up before changes in `local/backups/before-0.12.1-20261008T184509Z`. Save-loading tests use copies. An initial imported-profile run exposed a test-clock tolerance smaller than the native floating-point resolution at a large campaign time. The test comparison now allows its existing 2 ms tolerance plus the native float's resolution; production time scaling is unchanged.

## Final release verification

Release `dist/mindustry-fpv-0.12.1.jar` SHA-256: `3c1899da9d7f08aaa71f58809e2d428ccc6dbb376a25d02e3dd98ed2f00a93b3`. Installed at `play/saves/mods/mindustry-fpv.jar`; the three old packages are hash-verified in `local/backups/mod-imports-20261008T185946509Z-7f14f15a`.

- Clean build against installed160.7: 40 suites /368397 checks passed.
- Full native SDL160.7 run `local/compat-smoke/v0121-1607-final` passed on a copy of the imported profile, including its enabled Schematic Browser. Three native maps, copied Serpulo228 and Erekir10, save roundtrip, FPV/BuildMode, clocks, XYZ combat/separation and all marker/vegetation/walker checks passed; GL0.
- Full native SDL160.5 run `local/compat-smoke/v0121-1605-final2` passed with the same release and copied saves; GL0.
- Official160.7 dedicated server and two graphical clients passed in `local/net-lab/v0121-1607-final`, with exact matching mod SHA and verified host runtime hashes.
- Windows PowerShell5.1 installer:23 checks passed. The final verification stream-hashed all577 non-mod files of the original profile (including the large screenshots); all are unchanged. Unrelated mods/settings/saves remain intact.
- All100 `fpv/render` class files are byte-identical to verified0.12.0. This is a compatibility repair; no new rendering-performance claim is made.

Final receipt: `local/compat-1607/verification.json`. Release/source/evidence checkpoint: `local/checkpoints/0.12.1-verified`.

Two test-only assumptions were corrected for imported campaigns: floating-point clock resolution and a requirement for an empty46x9 rectangle on the user's map. The synthetic wall-cache fixture now runs on the existing generated disposable lab map. The first standalone160.5 test entry did not select the isolated data directory; it was stopped and superseded by `v0121-1605-final2` with an explicit data path. Superseded runs are not final acceptance evidence. Original imported saves were never used as writable test profiles. Tests are scripted native/protocol tests, not physical input or WAN tests.
