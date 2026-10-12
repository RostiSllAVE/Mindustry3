# Building Alpha 1.0.1 / internal version 0.18.28

Installation of this hotfix uses native **Mods → Import Mod → Import File**, then
a normal Steam restart. The bundled Steam Java 25.0.1 needs no added modules.
The following tools are required only for source development/building.

Use Windows, Python 3, a complete 64-bit JDK **17**, your installed Mindustry
**160.7** host JAR, and unmodified JNA core **5.19.1**. Put JNA in
`lib/jna-5.19.1.jar`; its SHA-256 is
`4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae`.
[JNA binary](https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar)
and [complete matching Java/native/libffi source](https://github.com/java-native-access/jna/archive/refs/tags/5.19.1.zip).
[Mindustry source](https://github.com/Anuken/Mindustry), [Arc source](https://github.com/Anuken/Arc).
The game/runtime are not included in the mod or source archive.

In a fresh extracted corresponding-source directory, set your local paths:

```powershell
$env:FPV_JDK = 'E:\Tools\jdk-17'
$env:FPV_MINDUSTRY = 'E:\SteamLibrary\steamapps\common\Mindustry'
python tools/build.py
```

The builder generates configuration from the design sheets, cleans only its
owned `build/classes` directory, compiles Java 17 code, runs the Java test programs,
and produces `dist/mindustry-fpv-0.18.28.jar`. It embeds the GPL, JNA/libffi license
texts and third-party notices. Host classes/artwork and Java itself remain external.
The inherited `design/platform.sheet.json` records the original Win32 backend
contract; its complete-runtime prerequisite applies to that optional backend.
The hotfix's `FlightMouseInputs` and `OptionalFlightInput` are authoritative for
automatic fallback selection and error isolation.

Build in a disposable source directory so you do not replace a release candidate
or an installed/published artifact. Whole-JAR timestamp reproducibility is not
claimed. All multiplayer participants must use the exact same distributed JAR,
including after notice-only repackaging.

Optional native-game regression (always creates new isolated profiles):

```powershell
.\tools\launch-native-input-regression.ps1 -Run steam-test-a `
  -JavaHome 'E:\SteamLibrary\steamapps\common\Mindustry\jre' `
  -GameDir 'E:\SteamLibrary\steamapps\common\Mindustry'
.\tools\launch-native-input-regression.ps1 -Run full-test-a `
  -JavaHome 'E:\Tools\jdk-17' -GameDir 'E:\SteamLibrary\steamapps\common\Mindustry'
```

This exercises real SDL/GL game APIs, cameras, building, schematics, commands and
SaveIO. It does not emulate physical mouse input or the native Import dialog;
those require a real interactive workflow. `-ForceArc` exercises fallback on a
complete runtime. Results go to ignored `local/native-input-results/<run>/`.
