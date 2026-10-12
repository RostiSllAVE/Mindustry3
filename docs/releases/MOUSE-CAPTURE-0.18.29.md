# Mouse capture follow-up — internal version 0.18.29

Local candidate only. Existing Alpha 1.0.0 and Alpha 1.0.1 assets are preserved.
Install through **Mods → Import Mod → Import File**, replace the old mod, and
restart through Steam. No separate Java installation or script is required.

## Investigation and implementation

The installed Mindustry 160.7 Arc SDL wrapper exposes absolute mouse coordinates
and derived deltas, but no relative-mode, relative-state, warp or grab API.
Its SDL JNI motion bridge drops SDL `xrel/yrel`. The extracted `sdl-arc64.dll`
exports 280 JNI symbols and no public SDL functions, so calling
`SDL_SetRelativeMouseMode` through that library is unavailable in this host.
No game DLL is copied into the mod and no second SDL instance is loaded.

On 64-bit Windows with missing `java.logging`, `ForeignWindowsInput` uses the
[Java Foreign Function and Memory API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html)
in **java.base** to bind existing Windows `user32.dll`/`kernel32.dll` functions.
The stock Steam launcher already provides `--enable-native-access=ALL-UNNAMED`.
Reflection keeps all mod classes compiled for Java 17; Java 17 never loads FFM.

`WindowsMouseCapture` shares the established foreground/PID checks, client-area
clipping and sample-before-recenter lifecycle between FFM and the retained JNA
backend. Delta signs remain right/down. Buffers/handles are initialized once.
Capture entry and resize discard stale motion. Focus loss, minimization, pause,
Build/command mode, menus, F5 and disposal release capture through existing mode
gates. Arc still handles hiding/restoring the cursor. A changed clip belonging
to another application is never overwritten during cleanup.

Complete Java 17 installations retain JNA. Missing/inaccessible FFM or native
function failures still select the bounded Arc fallback without aborting mod
initialization. That fallback remains visibly labeled HOST MOUSE and needs
arrow keys at the window edge; it is not advertised as complete capture.

This is **cursor recentering, not raw HID input**. Windows pointer acceleration,
DPI and frame rate can affect sensitivity. Linux/macOS, 32-bit hosts, overlays,
unusual multi-monitor/DPI setups and hardware controllers remain unverified.
No combat, movement, construction or multiplayer protocol changes are included.

## Actual verification

- Clean Java 17 build: all 109 existing test programs passed. Both added test
  programs also passed separately (111 total), including 218 shared capture
  lifecycle checks and safe FFM unavailability on Java 17.
- Actual stock Steam Java 25.0.1: all 111 Java test programs passed. Read-only
  probes exercised real Win32 HWND/PID/POINT/RECT/client-coordinate bindings.
- Native access permitted and explicitly denied: the real FFM binding succeeds
  with permission and reports isolated unavailability when denied.
- Actual SDL/OpenGL Mindustry 160.7 on both runtimes: full isolated regression
  fixtures passed, including camera, FPV/2D, building, commands, mining and
  save/load. Java 25 logged `Win32 / java.base FFM`; Java 17 logged `Win32 / JNA`.
  No new game errors or GL errors. These are scripted game-API checks, not
  physical mouse input tests.
- Physical Steam import/restart, continuous look, hide/confine, Build/menu and
  Alt+Tab checks: **pending owner verification**. Desktop automation could see
  the menu and hover controls but injected clicks did not open them.
- Old Alpha release hashes unchanged. Initial sandboxed game runs failed to
  open the networking selector because loopback was restricted; reruns outside
  that restriction passed. This was not a mod initialization failure.

JAR: **4,193,435 bytes**, SHA-256:
`ca38dda1c0e4eac09247cb8bb38ce0471a22bf73b0c02ee5004fcadea83fe8f4`.

GPL-3.0 and existing JNA/libffi notices remain embedded. No new redistributed
dependency or native binary is introduced. Build as in the existing hotfix guide;
the current metadata produces 0.18.29. For the Java 25 isolated regression helper
add `-ForeignAccess` to match the stock launcher; omit it on Java 17.
