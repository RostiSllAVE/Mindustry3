# Building the Alpha source

The playable Alpha uses internal mod version **0.18.27**. Its gameplay source is
commit `556df2a8f2f3a7dc8dac2392bc825c39235c8cb5`; the release branch additionally
contains optional diagnostics and release tooling. Use the supplied
`mindustry-fpv-0.18.27-corresponding-source.zip` to build the playable code.
Do not switch/reset your working repository merely to reproduce this version.

## Dependencies (not supplied as game/runtime binaries)

- Windows, Python 3, and a complete 64-bit JDK **17** (tested: 17.0.20.1+1).
- Your own Mindustry **160.7** installation containing `jre/desktop.jar`.
  Tested host SHA-256:
  `5b7481e090421ba180d814390d8f02d084207ad3cf612e97ae4fe15e180e48e4`.
  [Mindustry source](https://github.com/Anuken/Mindustry) and
  [160.7 source](https://github.com/Anuken/Mindustry/tree/v160.7).
  [Arc source](https://github.com/Anuken/Arc) is supplied through the host.
- JNA core **5.19.1** at `lib/jna-5.19.1.jar`:
  [upstream Maven download](https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar),
  SHA-256 `4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae`.
  [Complete JNA/native/libffi source](https://github.com/java-native-access/jna/archive/refs/tags/5.19.1.zip).

The unmodified dependencies are separately available upstream. The build never
includes Mindustry/Arc classes or game sprites in its output. See
`THIRD_PARTY_NOTICES.md` for licenses and corresponding-source distribution.

## Compile and test (fresh extracted source directory)

Set local paths; examples are placeholders, not the developer's required layout:

```powershell
$env:FPV_JDK = 'E:\Tools\jdk-17'
$env:FPV_MINDUSTRY = 'E:\SteamLibrary\steamapps\common\Mindustry'
python tools/build.py
```

`FPV_MINDUSTRY` is the installation directory, not the JAR path. Put the verified
JNA dependency in `lib` first. `tools/preflight.py` generates configuration classes
from the included `design/*.sheet.json`. The native-family adapters are already
included as editable source; development generators are not required to compile.
The build compiles Java 17 code, runs every Java test program in `src/test/java`,
and packages `dist/mindustry-fpv-0.18.27.jar`.

Always build in a fresh extracted directory: the historical builder does not
clean obsolete classes from an existing `build/classes` directory. It also
overwrites that version's local `dist` output. Never run it over your only copy
of the accepted release artifact.

## Historical residual helper and reproducibility

The accepted JAR contains **805 mod classes**. A clean compile of commit 556df2a
produces **804**, whose bytes have been compared with the accepted JAR.
The remaining `fpv/render/NativeLimbRenderer$TimeDelta.class` is an obsolete,
unreferenced helper retained by the historical non-clean build. Its original
source was not found in reachable Git history or inspected source backups.

`provenance/legacy-helper/fpv/render/NativeLimbRenderer.java` documents a minimal
reconstruction of this project's helper (`Time.delta / 60.0`), preserving the
class name, nesting and source line. It is kept outside `src` so it cannot
replace the current renderer. To regenerate **only** that historical class:

```powershell
& "$env:FPV_JDK\bin\javac.exe" --release 17 -encoding UTF-8 `
  -cp "$env:FPV_MINDUSTRY\jre\desktop.jar" -d build/legacy-helper `
  provenance/legacy-helper/fpv/render/NativeLimbRenderer.java
```

Do not copy the stub outer `NativeLimbRenderer.class` into the game JAR. Only its
nested `TimeDelta.class` is relevant. The release preparation tool verifies it
against the accepted JAR. ZIP timestamps/order/compression are not normalized by
the historical build, so a normal rebuild is not guaranteed to have the same
whole-JAR SHA-256, even when class bytes match. Rebuilt JARs must not be mixed with
the tested JAR in multiplayer.

The supplied source archive includes this build guide, all mod source/tests,
design inputs, original build/preflight scripts, licenses, notices, public
launcher and a source-file manifest. It contains no Git identity metadata,
local saves or precompiled game/runtime binaries.
