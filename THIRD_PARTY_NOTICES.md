# Licensing and third-party notices

Mindustry³ / Mindustry FPV is an experimental, unofficial fan-made modification.
Original project code is distributed under **GNU GPL version 3 (GPL-3.0-only)**;
see [LICENSE](LICENSE). Copyright (C) 2026 Mindustry FPV contributors.
There is no warranty. Third-party components retain their own licenses.

## Mindustry and Arc (provided by the player's installation)

- **Mindustry**, by Anuken and contributors: [upstream source](https://github.com/Anuken/Mindustry),
  [GPL-3.0 license](https://github.com/Anuken/Mindustry/blob/master/LICENSE).
  The mod subclasses and adapts native game APIs and behavior. It is a modification,
  not an official game release. The release does not redistribute the host JAR,
  game sprites, music, sound files or the Steam runtime.
- **Arc**, by Anuken and contributors: [upstream source](https://github.com/Anuken/Arc),
  [Apache-2.0 license](https://github.com/Anuken/Arc/blob/master/LICENSE).
  Arc classes are supplied by Mindustry, not copied into this mod JAR.
- Vanilla textures/sounds are read from the running game. Their inclusion in
  screenshots does not make them original mod assets. No game art is included
  in the prepared download bundle.

## Java Native Access 5.19.1 (bundled, unmodified contents)

**JNA core**, net.java.dev.jna:jna:5.19.1, is distributed here under its
**Apache-2.0** option, permitted by the upstream Apache-2.0 OR LGPL-2.1-or-later
dual license. The JNA Java classes and native `jnidispatch` libraries are bundled.
No JNA-platform library is used. Original dependency JAR SHA-256:

`4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae`

- [JNA source, exact 5.19.1 tag](https://github.com/java-native-access/jna/tree/5.19.1)
- [Complete upstream source archive](https://github.com/java-native-access/jna/archive/refs/tags/5.19.1.zip)
- [Upstream license](https://github.com/java-native-access/jna/blob/5.19.1/LICENSE)
- Local copies: [license choice](licenses/JNA-LICENSE.txt) and
  [Apache-2.0 text](licenses/JNA-APACHE-2.0.txt).
- JNA source copyright notices include Timothy Wall and other contributors.
  Native dispatch includes Copyright (c) 1998 Sun Microsystems, Inc.;
  Copyright (c) 2007–2013 Timothy Wall; Copyright (c) 2007 Wayne Meissner.
  All Rights Reserved. These upstream notices are retained here; no claim of
  authorship of JNA is made.

JNA's native dispatch includes **libffi**, Copyright (c) 1996–2022 Anthony Green,
Red Hat, Inc. and others, under its MIT-style permission license. Full notice:
[licenses/LIBFFI-LICENSE.txt](licenses/LIBFFI-LICENSE.txt).
[Matching upstream native source](https://github.com/java-native-access/jna/tree/5.19.1/native/libffi).

The historical **0.18.27 Alpha 1.0.0** JAR predates this release paperwork. It includes the
JNA dual-license and Apache texts, but does not include the new GPL/libffi
paperwork. Distribute that old version with its prepared Alpha ZIP and corresponding-source
ZIP, which contain these notices, rather than offering that historical JAR alone.
The **0.18.28 Alpha 1.0.1 hotfix** embeds the GPL and all listed dependency license
texts and these notices directly in its importable JAR. It still requires its own
matching corresponding-source archive when distributed.

## Liftoff, Melty and external tools

Liftoff: FPV Drone Racing, its name and original content belong to their respective
owners. The flight controller here is the mod's own implementation. No Liftoff
code, physics engine, UI assets, textures or binaries are included, and Liftoff
is not a runtime requirement. This Alpha is **not** a validated Melty mashup.

Java, Python, Steam, PresentMon and Windows tracing tools are external tools;
their binaries are not included in the release. Local developer launchers and
profiling recordings are not part of the user download.

## Corresponding source

For **0.18.28**, publish `mindustry-fpv-0.18.28-corresponding-source.zip` alongside
the hotfix JAR. It contains the hotfix's exact source/tests/design/build scripts,
GPL/dependency notices and public Git checkpoint. Full Java 17 is required for
building; users can import the JAR with the stock Steam runtime. Host SDL APIs
provide the fallback input; they are not copied into the mod. No new binary
dependency or proprietary runtime is introduced by the hotfix.

For the historical **0.18.27** release, publish its original
`mindustry-fpv-0.18.27-corresponding-source.zip` alongside the Alpha
download, at no extra charge. It contains the exact mod source/design/build
scripts at commit `556df2a8f2f3a7dc8dac2392bc825c39235c8cb5`, release instructions,
licenses and the documented source for one legacy residual helper class.
The upstream dependencies' matching source is linked above and in the build guide.
Do not substitute GitHub's automatically generated current-branch source ZIP:
the release branch also has opt-in diagnostics absent from the tested binary.
