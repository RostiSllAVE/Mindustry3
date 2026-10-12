# Support effects, Force Projector and ram limits — 0.8

Incremental extension of the verified 0.7 renderer, combat adapter and matching-mod networking. No new world simulation or save format. Before the changes, 532 files from the play profile were archived with SHA-256 and ZIP CRC verification in `local/backups/before-0.8-20261007T224213Z`. Tests use only copies, including the newly imported `26.msav` and the earlier `12.msav`.

## Native support effects

Open ordinary conduit outlets use native liquid and puddle state to draw a curved stream, moving drops, surface pools and ripples. Connected, solid-blocked and non-leaking armored outlets do not create false streams. Empty pipes stop streaming while existing native puddles remain. No liquid is created or transferred by the renderer.

Repair Point and Repair Turret render their active healing beam at the actual target altitude. Repair Tower pulses and unit repair-beam mounts also use the existing renderer and native support colors. Presentation does not apply healing or select combat targets. Healing range and balance retain the native rules.

## Force Projector

The spatial shield is a closed polygonal prism with the native radius, side count and rotation. Height equals the current radius; phase expansion and radius fading change both its horizontal and vertical extent. Rendering and swept collision share the same boundary. Friendly shots pass; hostile managed bullets, lasers and lightning contact the nearest force shield or projected wall. Shots above the finite shield can pass over it.

Native buildup, break threshold, warmup, coolant, power and phase consumption remain the source of truth. A contact updates native buildup; exhaustion breaks the shield immediately. No second shield HP pool is introduced. Force state is server-owned and replicated to matching clients. Native ForceBuild fields retain their original persistence behavior.

## Ramming limits

Settings → 3D combat provides four maximum outgoing damage limits in HP: ordinary units (3000 initially), Alpha, Beta and Gamma (0 initially for each). Range is 0–20000 HP in steps of 10. A weak physical hit remains weak; the setting only caps stronger hits. Direct contact and the custom crash burst share a per-victim budget for the collision. The two directions of an enemy unit collision use the respective attacker's cap regardless of which pilot resolves the pair. Native health multipliers cannot amplify a bounded hit beyond its HP budget. Terrain/self injury still applies.

These are host/server settings in multiplayer. A client cannot change server collision damage by changing local preferences. Existing speed/impact-strength controls remain separate.

## Validation

Build, native SDL integration and real dedicated-server/two-client validation results are recorded in `local/smoke/verification.json` after completion. Fixtures cover actual native liquid transfer, healing HP, completed GPU submissions, spatial shield contact/overflight/team rules/break/recovery, ram limits, copied saves and the existing flight/build/combat regressions. Scripted checks do not exercise physical mouse/controller input or WAN conditions.

Existing unrelated limitations remain: native abilities outside the adapters, arbitrary third-party mods, the session-only altitude/projectile/projected-wall state, and pending Liftoff/Melty integration.

Final acceptance: package `d372a05051b7397618bde923210bfe397e9a65791a23ec3b280f2b96209c4add`. All25 pure suites passed39971 assertions. Native SDL passed the complete flight/build/combat regression, new support/ForceProjector/24 ram-cap cases, original three maps, copied12/26 saves, unchanged map grids and native SaveIO roundtrip; OpenGL error0. Thirty solo captures plus two client healing captures are bound to the package; the new liquid, healing and shield images were visually inspected. `local/net-lab/v08-run2` passed in all three real processes: dedicated server142 assertions and clients37/37. Both pilots lost exactly10HP under the server cap while clients requested20000. Force shield hit/break/recovery state and authoritative healing beams were confirmed on both clients; forged force/projection writes were rejected.

Collision-fatal instances suppress native cargo/wreck damage and their own shootOnDeath mounts; living instances and shared UnitType data stay unchanged. The cap covers direct ram plus its custom XYZ burst. Arbitrary custom death abilities/type callbacks and spawned-child behavior retain native semantics and are outside that budget.
