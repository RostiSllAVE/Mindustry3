# Task 1 — Intuitive 3D Mouse Interaction, 0.14.1

The supplied file was read completely before development. Repository: `D:\Linux\MindustryFPV`. Initial HEAD: `a687673d8f704b89cab81cdc9423a8f7e7c92642`; branch: `feature/ui-polish`; initial working tree clean. No checkout, merge, reset, push or publication is required. Only this UI task is implemented.

## Player workflow

1. Enter **Tab / Build**. If a block is selected for placement, clear it with **RMB** first.
2. Hover a visible building. A depth-tested outline follows its actual construction bounds; the small hint shows its native name, floor and available interaction.
3. Click a bridge, Mass Driver or power node to start a connection. Hover the destination on any visible floor. The preview is green when existing validation allows the operation; otherwise red with a reason. Click to submit.
4. For aligned inventory/liquid/power risers, select the category using **Shift-click riser**, Shift-click the source, then click the target. This uses the existing connector cost, shaft and server rules.
5. **RMB / Escape** cancels an unfinished connection. RMB remains consumed until released; cancellation cannot become a removal drag. Normal pause remains separate. **Ctrl + click** retains native possession behavior.
6. An ordinary configurable building opens its own native configuration controls on click. Cargo and advanced left-panel commands remain available.
7. For block placement, visible platforms and ordinary ground determine the floor automatically. **PgUp/PgDn** or floor buttons activate **Lock placement floor** for intentional placement into empty space. Uncheck it to resume automatic detection. An active placement drag retains its starting floor and rotation.

## Architecture and limits

Picking shares the existing native terrain/block raycast and the upper world's existing bounded geometry index. It chooses the nearest surface across floors, including solid platform occlusion. Decorations and projected wall fields are excluded from ordinary building selection. Open elevated doors are picked through their visible jambs/base rather than an invisible closed slab. Fog still governs visibility.

No new transport network, packet format, blocks, layers, combat or physics is introduced. Upper interactions use existing `LayerActions` and `LayerRuntime` validation; ordinary ground configuration uses native `Building.configure`. Preview validation does not submit packets or mutate inventories. The advanced panel is a fallback, not a requirement for the ordinary source/destination workflow.

The existing gameplay does not support cross-floor Mass Drivers or ground-to-upper power links; these attempts receive an explicit invalid preview. Upper horizontal power links remain automatic, because no existing authorized manual command supports that pair; direct upper-node clicks support the existing aligned vertical power riser. Directional/duct bridges remain automatic within a floor and support their existing explicit cross-floor bridge path. Resource and liquid connectors still require aligned endpoints, a clear shaft, ownership, range and cost.

The working 0.14.0 package is retained in `dist/mindustry-fpv-0.14.0.jar` and `local/backups/before-ui-task1-0.14.0`; SHA `a9e93c2397d9209e7c50e023cad2558aba780fea39dc31e39ec852c52b144d3f`. Existing full-profile backups remain available.

## Actual verification

All results below use the exact final `dist/mindustry-fpv-0.14.1.jar`: **3,624,633 bytes**, SHA-256 **f9ce697cb1967d62c476722c8f9cf41b548d344e91bbd5a617a7cbce089becf3**.

- Build succeeded and all **73 Java suites passed** (`local/build-ui-task1-final.log`). New `MouseInteractionTest`: **23 native checks** for nearest upper/same-XY surfaces, platform occlusion, exact identity/team/orientation, read-only preview, real bridge/power-riser requests, invalid team/range/stale/self targets, same-floor drivers and open/closed door geometry.
- Actual **Mindustry 160.7 SDL client**: **26 scripted interaction/native-UI checks** for Z0/Z2 selection, solid occlusion, ignored decoration/projection regions, automatic platform/ground floor, manual lock, bridge/power clicks, configuration UI and actual sorter filter, cancellation and return to flight, preserved block rotation and advanced configuration/source controls. **6 GPU pixel checks** verify visible bounds, green/red XYZ previews, opaque depth occlusion and clearing on cancellation. Results and screenshots: `local/layer-smoke/ui-task1-final`.
- The same SDL run passed the existing **209 layered-world checks**, plus **32 first-person**, **9 construction-preview/rotation** and **7 display** GPU checks. These are regression checks, not new roadmap work.
- Full actual **160.7 SDL regression passed** (`local/compat-smoke/ui-task1-final-1607/results.json`): native construction/planner/preview, FPV and Build navigation, rotations, cameras, three maps, copied saves including the existing 13.msav fixture, save roundtrip, unchanged native terrain data and **OpenGL error 0**.
- Real **160.7 dedicated server + two SDL clients passed** (`local/net-lab/ui-task1-final-1607/*-results.json`). The server recorded **157 main**, **469 creator-flight** and **105 construction** checks, including existing configuration/inventory authority, rejection/replay protections and late join. Both clients passed. This validates the reused server commands; it is not a manual network mouse-session test.

Tests were scripted through the same native input/interaction API boundaries used by the controller. Physical OS mouse/keyboard/controller operation was **not manually tested**, and this pass did not repeat actual SDL testing on 160.5. All game runs used disposable profiles on D:. The final package was installed into the normal `play/saves/mods/mindustry-fpv.jar`; the previous package was archived, and all **584 original non-FPV-mod files under play/saves** remained byte-identical. Receipts: `local/ui-task1-verification.json`, `local/ui-task1-profile-after.json`, `local/ui-task1-install.json`.
