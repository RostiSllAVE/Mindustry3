# First Public Alpha — publication readiness

Prepared locally on **Alpha-1.0.0**, based on `e60f1fb`. No push, remote creation,
tag publication, repository visibility change or history rewrite was performed.
The existing clean Alpha branch was reused. All development branches/checkpoints
and ignored local backups are preserved. No gameplay systems were changed.

## Pending decisions before publication

1. **Git author privacy:** reachable history contains the owner's real name and
   personal email address in commit identities. File-content credential checks
   did not find credentials, but this identity data will become public if the
   existing history is pushed. Decide whether that is acceptable. No email or
   name was rewritten automatically. A privacy-preserving publication strategy
   would require a separately approved operation; changing .gitignore or current
Git user settings cannot hide historical identities.
2. **Destination and authorization:** no Git remote is configured. The owner must
   choose the public GitHub repository and explicitly authorize publication later.
   Do not push all local checkpoint branches by default.
3. **Distribution conditions:** retain the prepared GPL-3.0 licensing declaration,
   JNA/libffi attribution and publish the exact corresponding-source archive
   alongside the notice-bearing Alpha download. The historical bare JAR lacks
   the new GPL/libffi paperwork; do not offer it alone as the release download.
4. **Acceptance limits:** independent multiplayer and a fresh-user interactive
   installation test are pending. These are documented Alpha limitations rather
   than claims of completed verification. The unresolved Flare +85° smoke failure
   and local network regression stalls remain disclosed.

## Repository/history audit (2026-10-12)

- Starting HEAD: `e60f1fb13a7c4ec6448ece2ae1ea3a02a0dcfec3`; clean tree.
- **39 reachable commits, 1121 unique historical blobs, 548 tracked files**
  before release paperwork. All reachable branches were included.
- Largest historical blob: **195,798 bytes**, `src/main/java/fpv/construction/LayerNativeFamilies.java`.
  No blobs exceed GitHub's 50 MiB warning or 100 MiB rejection thresholds.
  Git loose object storage was approximately 7 MiB; no packs/garbage were reported.
- No tracked binary blobs, game JARs, backups, saves, profiles, logs, raw tracing
  recordings or suspicious credential filenames were found in reachable history.
- Automated text checks covered private-key headers, common GitHub/OpenAI/Google/
  AWS token patterns, quoted credential assignments, local user paths, emails and
  Steam IDs. No matches in historical file contents. These are scoped checks,
   not a guarantee against every possible secret encoding; commit-message checks
   also found no credentials/emails/Steam IDs, while commit identities are
  explicitly reported above. Local audit details remain under ignored `local/`.
- .gitignore already excludes research/local/lab/play/build/dist/lib, logs,
  temporary files and Python caches. Release preparation additionally ignores
  accidental root Alpha profiles, credential files and common recordings/binaries.
- Ignored local directories contain large game/profile/backups and the sibling
  development-folder ZIP is over 1 GB. They were neither deleted nor staged and
  must never be uploaded as the release. .gitignore does not remove old history;
  historical checks above were performed independently of it.

Authoritative limits: [GitHub large-file documentation](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).

## Tested build and source provenance

Accepted gameplay JAR is **0.18.27**, 4,140,308 bytes, SHA-256
`d4f136ab7803966970ab50ef0d0150d895514aebff26dd343bd674c729582bc0`.
The development `dist`, installed `play` and preserved accepted backup copies
are identical. Binary is not relabeled, rebuilt, optimized or modified here.

Gameplay code: `556df2a8f2f3a7dc8dac2392bc825c39235c8cb5`. Checkpoint `e60f1fb`
adds the verified fullscreen startup workaround and optional source diagnostics,
without replacing that tested binary. The prepared source ZIP selects the exact
gameplay commit instead of silently shipping newer diagnostics as its source.

JAR CRC/entry audit: **982 entries**, **805 fpv classes**, **152 JNA entries**,
**22 design sheets**, mod metadata and two original JNA license texts.
Every dependency entry matches `jna-5.19.1.jar` byte-for-byte. No Mindustry/Arc
classes, Liftoff assemblies/assets, saves or game artwork/audio are bundled.
Native DLL/SO/JNILIB/A files are JNA's licensed dispatch libraries, not game files.

A clean source compilation previously matched **804/804** current classes. The
805th class, `NativeLimbRenderer$TimeDelta`, was a leftover from historical
incremental compilation and has no caller in the accepted current classes.
No original source for it was found in reachable Git or inspected backup sources.
A documented minimal source reconstruction preserves its nested class/name/line;
compiling it with the verified JDK yields **byte-identical** class data, SHA-256
`74d47d5190f24124580e70204b8a61c65bbe51215ede371c7d4c2a434e69afd1`.
It is included outside the active source tree and does not replace the renderer.
This is disclosed provenance, not a claim that its original source was recovered.
Whole-JAR reproducibility is not claimed: legacy ZIP timestamps/order vary.

## Licenses

Added root GNU GPL v3 text and GPL-3.0-only declaration for project code;
Mindustry attribution and upstream GPL source links; Arc attribution; JNA
Apache-2.0 election and upstream copyrights; full libffi permission/copyright
notice. Only game-installed art is used at runtime. Liftoff is inspiration,
not a redistributed dependency or an officially integrated Melty requirement.
[Third-party notices](../../THIRD_PARTY_NOTICES.md) describe all bundled components.

## Verification scope

Previously recorded: 107 passing Java test programs; passing targeted native
game/GPU camera, layered construction, commands, mining, healing, lasers and
save/load checks. Broad smoke fixture still fails the stated Flare assertion;
network regression stalls are not hidden by narrower passing results.
The player verified fullscreen removes the reproduced presentation stalls.

Release-pass checks: accepted binary checksum/CRC/prefix whitelist, JNA byte
comparison, full historical blob/message/path scan, clean isolated source build
and **107 passing Java test programs**, **804/804 current class bytes matched**
plus the byte-identical legacy helper, **8 passing Windows PowerShell 5.1 launcher
prerequisite scenarios** without launching/changing profiles, archive whitelist
and per-entry checksum verification. Initial launcher checks exposed and fixed
PowerShell default-parameter/module/stderr compatibility issues; final cases passed.
Structured results are recorded in `VALIDATION.json` alongside this report.
No new remote multiplayer or manual visual playthrough is claimed.

## Prepared upload set

Under ignored `dist/alpha-1.0.0/`:

- `mindustry3-alpha-1.0.0-windows.zip`: exact tested JAR, portable launcher,
  README, licenses, notices and release documentation.
- `mindustry-fpv-0.18.27-corresponding-source.zip`: mod source/tests/design/build
  scripts pinned to 556df2a, documented residual helper, source manifest and
  licensing/install/build documentation.
- `SHA256SUMS.txt`: integrity checks for prepared files.
- `RELEASE-NOTES.md`: text for the future GitHub prerelease description.

The loose JAR and `release-manifest.json` are local inspection/provenance outputs.
Only the listed upload assets should be used. Upload the corresponding source
at the same time as the binary bundle and mark the GitHub release **prerelease**.
The Alpha label does not change internal version 0.18.27 or its network fingerprint.
