# Task 1.2 — Automatic 3D Placement & Dismantling, 0.14.2

Work continues on `feature/ui-polish`, from `e2d379d548145b3bb30bd6e72c02e9ed87ba4220`, with a clean starting tree. Only automatic construction targeting, dismantling and related input/preview are changed. Combat, unit movement, structural simulation, transport and schematic copying are unchanged.

## Shared surface and construction targeting

The existing `BuildingMousePicker` is reused by inspection, configuration, connections, placement and dismantling. It now retains the block type even for plain upper walls without a native Building adapter. Coplanar column/slab joints resolve to the covering upper slab. Decoration and holographic wall regions remain excluded; solids still occlude lower objects.

`MouseConstructionTarget` maps that hit into an anchor. Ground resolves to Z0; platform top/side resolves to its layer. Exposed edges determine exactly one adjacent footprint from the struck face. A wall cap in Place platform mode resolves to the next construction floor. The target is not selected from pilot altitude. Unsupported, occupied, out-of-map and underside targets do not search for unrelated green positions. Existing runtime validation provides Structural/Free/cost/range/support decisions.

Only explicit manual floor locking intersects a selected empty construction plane. Native ground input receives the same resolved anchor, including even-size offsets; upper blocks use existing PLACE requests and oriented ghosts. Upper painting samples visible targets per frame instead of interpolating through unseen cells, so very fast drags may leave gaps. There is no cross-floor schematic feature.

## Dismantling and input

The configured `Binding.breakBlock` is used without changing its value. Default RMB also overlaps `Binding.deselect`: the first press clears pending placement/link selection; it cannot become dismantling while held. With no pending placement, press/hold previews visible objects and release submits removal. Escape/menu cancels pending removal. At most 256 objects can be collected in a removal gesture; targets are checked again on release.

Manual placement floor never chooses the removal ID. A stationary hold cannot collect the lower object newly exposed by an external change. Ctrl+LMB possession has priority when no block is selected; a selected block cannot be stolen by possession. Ordinary Ctrl alone has no default deconstruction meaning, though a user-assigned dismantle binding is still honored. The panel displays the active dismantle binding and remains available for advanced controls.

Z0 removal calls the existing native `tryBreakBlock` and executes through normal build plans/server validation. A read-only gate retains native Build.validBreak and existing XYZ builder reach semantics. Upper removal uses existing REMOVE requests, preserving ownership, server filters, range, refunds and support/collapse consequences. No removal protocol or transport network is introduced.

## Verification

Final package: `dist/mindustry-fpv-0.14.2.jar`, **3,636,873 bytes**, SHA-256 `d507494e105e69fd0640ccf38556eb14882395d61c3217befaeeb5ff8415d72c`.

- All **73 Java suites passed**; **28 native mouse/construction checks** in MouseInteractionTest. Log: `local/build-ui-task12-final.log`.
- Actual isolated SDL 160.7: **28 new scripted construction checks**, including actual native Z0 queue build and break execution, automatic Z1/Z2 supported construction, wall-cap and slab-edge expansion, rotation, occupied/unsupported/Free cases, same-XY removal, preview before release, Escape/hold/Control/FPV state and normal support-removal consequences. Result: `local/layer-smoke/ui-task12-final/layers-result.json`.
- The same run passed **26 existing interaction checks**, **209 layered checks**, and **6 interaction / 9 construction / 32 first-person / 7 display GPU checks**. These existing regressions do not introduce new roadmap work.
- Full actual **160.7 SDL regression passed**, including ordinary native build execution, three maps, copied saves, save roundtrip, FPV/Build/camera controls and **OpenGL error 0**. Receipt: `local/compat-smoke/ui-task12-final-1607/results.json`.
- Real **160.7 server and two SDL clients passed** with the same package. The server recorded **156 main / 475 creator-flight / 105 construction** checks, including existing native inventory/configuration, permissions, replay protection, ownership, refunds and reconnect. Receipts: `local/net-lab/ui-task12-final-1607/*-results.json`. This verifies the reused authority paths; physical network mouse gestures were not manually tested.

These are scripted native-input/interaction API tests in the real engine, not manual physical mouse/controller testing. Native Z0 construction uses instant/infinite lab rules in the focused test; the full existing regression verifies ordinary native build execution separately. Existing scope limitations from Task 1 still apply to unsupported connection families.

Working 0.14.1 is preserved in `local/backups/before-ui-task12-0.14.1/mindustry-fpv-0.14.1.jar`, SHA `f9ce697cb1967d62c476722c8f9cf41b548d344e91bbd5a617a7cbce089becf3`.

Installed the exact tested 0.14.2 into `play/saves/mods/mindustry-fpv.jar`; the previous package was archived. All **585 original non-FPV-mod files under play/saves remain byte-identical**. Receipts: `local/ui-task12-profile-after.json`, `local/ui-task12-install.json`, `local/ui-task12-verification.json`. No 160.5 SDL rerun or manual hardware test is claimed for this pass.
