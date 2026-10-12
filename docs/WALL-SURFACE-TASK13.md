# Task 1.3 — Wall surfaces and aligned construction (0.14.3)

Work: existing `feature/ui-polish`, clean baseline `cd75c04f3d357087328f37ddb2d9a4642e6e5163`. The full supplied Task 1.3 was read before editing. Master and Main stay at `a687673d8f704b89cab81cdc9423a8f7e7c92642`. No remote publication.

## Causes and corrections

- Large upper walls previously had a 1.8-tile body while the next floor was only 1.4 tiles above their base. The permitted decorative cap joint could embed next-floor buildings; a conveyor could disappear below the cap. Wall-top auto placement also advanced a floor only when placing a platform, so ordinary blocks targeted the wall's occupied layer.
- New upper wall heights are whole multiples of the existing floor spacing: `ceil(size / spacing) * spacing`. At the default spacing, small walls are 1.4 tiles tall and large walls 2.8. A large wall at Z1 ends at Z3. Collision, support, cached mesh, preview and placed body all use the same component height and `LayerCoordinates.bounds`.
- Z0 built wall bodies end at the first 1.4-tile construction plane. This deliberately removes their size-dependent decorative cap; ground footprint, native HP, costs and team logic remain native. Physical consumers, including the existing projected-wall segment formula, follow that aligned body height. This is a geometry correction, not a new damage or interception algorithm; ground large projections no longer gain extra height merely from the old cap.
- Local-save migration retains component IDs, floor anchors, HP, loads, configuration and native state. An exposed old large wall becomes a whole-storey column where volume validation permits it. If an occupied next storey prevents that growth, its old cap is trimmed to one storey. Existing occupants are never moved or deleted. Falling sections retain their serialized geometry. The save/network formats are unchanged; authority migrates settled geometry before sending it to matching clients.
- Existing `BuildingMousePicker` now carries the entry-face normal, top/side/bottom/platform classification, component/native building identity, XYZ contact, bounds and aligned construction layer. Open doors still use actual jamb/base geometry. The surface-support flag identifies a physical construction surface; selected footprint, Structural/Free permission, span/load, range and resources still require the existing authoritative preview.
- `MouseConstructionTarget` uses the ceiling of the actual wall top for both ordinary blocks and platforms. Undersides and targets beyond the floor limit give an explicit rejection. Manual locking remains available; unsupported or occupied targets never search another floor or redirect to Z0.
- Only a platform seated at an aligned column top may overlap its final material thickness. Old bounded cap overlaps remain decodable for migration, but new runtime placement cannot exploit them.
- An embedded platform and its supporting wall form one solid material joint. The cached renderer does not emit a duplicate coplanar platform skin there. Removing a native ground support invalidates this decision even without an upper-grid mutation. Native conveyor tops use the animated native texture rather than a second unrotated cached top. Rotation invalidates cached oriented faces. Depth testing remains enabled; the small pre-existing preview/badge offsets remain overlay offsets, not solid geometry fixes.

## Controls and scope

Tab/Build → unlock placement floor → select a block → point at the wall top. The full translucent preview, footprint, rotation and PLACE action resolve to the same anchor and floor. RMB/Escape cancellation, hold/release dismantling, Ctrl possession, advanced panel, configuration and links continue through the existing shared picker and bindings. No cross-floor schematics, AI, movement controller, debris redesign or logistics simulation changes.

Optional upper-placement diagnostics: launch with `-Dfpv.picking.diagnostics=true`. The existing status panel adds hit XYZ/normal/surface, building and target layers, mesh top and placement validity. Default is disabled.

## Verification

Final package: `dist/mindustry-fpv-0.14.3.jar`, 3,647,771 bytes, SHA-256 `0b2aa81e25d73e136e1a4ae1c2a2c562ac8fc2e7689e3d831c596970e940c50d`.

All 73 Java test suites passed (`local/build-ui-task13-verified.log`), including 35 native mouse tests and 10 wall-migration assertions. They cover tall-wall top/side/bottom normals, ceiling conversion, floor limits, native identity, exact saved state, cap repair, idempotence, support and codec round trips.

Actual installed Mindustry 160.7 SDL scene (`local/layer-smoke/ui-task13-verified/layers-result.json`) passed:

- 37 scripted construction checks: real Z0 build/break execution; small Z1 column and Z2 platform; large Z1/Z3 stack and Z5 conveyor; exact preview/rotation/placement; rejection inside a tall wall; top/side selection; stacked removal without deleting hidden supports; occupied, unsupported and host-Free cases; cancellation, Ctrl and FPV/Build transitions; structural consequences.
- 13 additional GPU pixel checks: conveyor visible over the stack at four angles, stable repeated frames, native direction change, cached face rotation invalidation, opaque occlusion, platform reappearing after native ground removal, no GL error. Captures: `wall-top-conveyor-0.png` through `wall-top-conveyor-3.png`.
- 26 existing native mouse checks; 209 layered integration checks; 6 interaction, 9 preview, 32 first-person and 7 display GPU checks.

The isolated 1,000-platform warm construction pass measured 0.092 ms mean CPU and 0.012 ms mean GPU across 120 frames. This measures the construction pass only, not the whole game or hardware input latency, and is not a general FPS guarantee.

Full installed-160.7 regression passed on three maps and copied saves, including ordinary native build execution, save round trips, FPV/Build transitions, combat/defenses and GL error 0 (`local/compat-smoke/ui-task13-verified-1607/results.json`). A real official-160.7 server and two clients all passed: 149 main server assertions, 471 creator-flight checks and 105 layered authority checks (`local/net-lab/ui-task13-verified-1607/*-results.json`). Identical tested JAR installed locally; all 588 original non-FPV-JAR profile files are byte-identical. Combined receipts: `local/ui-task13-verification.json`. Tests execute actual native code and GL through scripted API boundaries; manual physical mouse/controller testing and an additional 160.5 run are not claimed. Screenshots referenced in the supplied text were not present in this attachment; local reproducible captures were used.

Upper placement remains instant through the pre-existing authority path; native Z0 builds retain their queue. Existing native environmental restrictions still apply. Custom unsupported blocks, arbitrary custom wall heights and every possible saved falling assembly are not exhaustively tested.

Previous working package retained at `local/backups/before-ui-task13-0.14.2/mindustry-fpv-0.14.2.jar` (SHA-256 `d507494e105e69fd0640ccf38556eb14882395d61c3217befaeeb5ff8415d72c`); source baseline remains in Git. Test profiles and copied saves are on D:, separate from `play/saves`. Installation replaces only the FPV mod JAR; profile preservation is verified separately in the task receipt.
