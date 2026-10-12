# Flight views, native fields, altitude separation and expanded combat — 0.6

## Preservation

This continues the working 0.5 project against installed Mindustry 160.5. Original grids, maps, campaign, inventories, manufacturing, build queues, AI goals, native health and save format remain authoritative. Native horizontal AI movement is preserved; physical altitude, presentation and covered weapon contacts use the existing shared XYZ state.

Before changes, 490 play-profile files were archived under `local/backups/before-0.6-20261007T171223Z`. Working 0.5 source, package and evidence were checkpointed. The fresh copied `saves/17.msav` is the imported-save fixture for this pass; the prior copied `12.msav` is also rechecked in this pass (both maps500×500). Tests use the isolated lab, never the live play profile. No new gameplay state is serialized into native saves.

## Controls and camera

| Control or setting | Behavior |
|---|---|
| F5 | Existing FPV / native 2D switch |
| F7 | First-person / chase view; own controlled model becomes visible in chase |
| F9 | Acro / Stabilized flight |
| Acro | Existing W/S throttle, A/D yaw, mouse pitch/roll and arrow fallback |
| Stabilized | WASD fixed map axes: W north, D east; E/Q or PageUp/PageDown altitude |
| Stabilized mouse | Independent free look; rotating the camera does not rotate the movement basis |
| Flight speed | 25–300%, default 100%, for every performance preset; separate from Build speed |
| Build | Existing cursor, builder, WASD precision motion, PageUp/PageDown climb, Home/End heading;0–60 m/s independent of world-time percentage |

The shared flight-speed multiplier applies after per-unit scaling and recalibrates drag equilibrium without replacing mass, thrust, pose or momentum. The resulting practical-speed reference is bounded to 400 m/s. This remains an aerodynamic reference in Acro, not a universal velocity clamp after dives or external impulses. Stabilized uses it as its desired speed, with bounded acceleration/braking, level attitude, gravity compensation and the existing physical-substep collision observer. Releasing keys brakes toward position hold. It is an original assisted mode, not claimed Liftoff parity.

Chase distance scales with the actual physical body and shortens against terrain/structures; the own model hides if the camera must approach too closely. The controlled model reads `UnitAltitudeSystem.unitState`, which includes the true flight pose, instead of the ordinary pose path that deliberately excludes it. Build retains its independent downward camera and restores the chosen flight view on exit. The native UI exposes flight mode, view and speed without consuming its existing builder cursor.

The ordinary altitude ceiling changes from 45 to 720 tiles, a 16× increase above map datum. The camera far plane includes its actual altitude/chase displacement and the decorative environment bounds. Picking reaches the current frustum distance instead of a horizontal map-diagonal cap; native tile DDA remains bounded by map dimensions. UI FOV 0/180 still projects at finite 1/179 degrees.

## Force fields and resting models

`NativeForceFieldState` reads each actual ForceFieldAbility instance's protected radiusScale/alpha through cached version-pinned reflection. Positive unit shield, native radius, sides, rotation, shield color and hit flash determine the visible shell. The closed polygonal shell is faintly translucent with brighter edges, depth tested and visible inside/outside, including around the first-person owner. The final transparent pass disables depth writes and restores every touched blend/depth/cull/program setting. A bounded streaming mesh is disposed with the renderer. Broken shields disappear; presentation does not regenerate or otherwise modify native state.

The shell is cosmetic. Native force-field and point-defense interception have not been migrated into a matching XYZ volume and do not intercept managed proxy shots. Native personal shield health still absorbs damage through the normal health APIs. The visual radius is state-faithful; it is not a promise of new spatial shield gameplay.

Idle AI models use native velocity to decide travel orientation, rather than treating tiny idle wobble or passive position corrections as intentional circling. Below the travel threshold they follow native heading and level pitch/bank. Native controllers, command movement, tiny XY wobble and intentional combat circling remain intact.

## Passive separation

`AltitudeSeparation` wraps the pinned native PhysicsWorld layers while retaining the original PhysicsProcess, body registration, collision layers, native radii/masses and delayed horizontal correction. Immutable physical-height snapshots are published before worker execution. A pair whose vertical body intervals do not overlap receives no passive XY push; overlapping bodies retain native separation. Missing physical snapshots retain the native fallback.

This adds a height gate to horizontal separation, not a second ramming system, new vertical force or damage. Transitions, resets and disposal wait native workers; stale pending corrections are discarded across mode/pose changes. Original worlds are restored outside local FPV, in network sessions and on close. Reflection is tied to 160.5; arbitrary future engine versions are not promised compatible.

## Expanded XYZ weapons

The reversible per-weapon/ammo adapter now covers compatible ordinary projectiles with homing, weaving, flak, artillery arcs, bombs, splash/status/healing, fragments and interval children, alongside supported instant/continuous lasers. Original metadata, ammo/reload and native health/armor/controller callbacks remain in use after physical contact checks. Actual native examples in the new scenarios include Reign, Eclipse, Zenith, Fortress, Horizon, Spectre and Scepter. Eligibility is per mechanic and lifecycle; these examples are not universal coverage of all variants or mod content.

At native powered lifetime or range, ordinary shots continue as unpowered bodies with gravity and drag, swept contacts and their supported damage payload. Artillery has a real rising/falling Z arc; bombs fall immediately. The native host lifetime is extended by 120 simulated seconds to retain the body. World-margin, altitude and timeout cleanup bound abandoned shots and cause no late phantom explosion. Paused simulation does not move or age them.

Fragments and interval children start at the parent's actual XYZ position and inherit its pitch, with native velocity/lifetime/random azimuth metadata. Recursion and event counts are bounded. Native horizontal random fragment offsets are currently omitted. Area damage uses distance to physical cylinders/AABBs; its radial attenuation is an approximation rather than native XY tile-flood attenuation. Aerial area damage cannot hit distant ground merely sharing XY.

Compatible LightningBulletType and ordinary lightning payloads use bounded XYZ chains, including Scepter's interval lightning. Segments have physical terrain/body contacts, native damage/status/color/target categories, a length budget and insulated-structure blocking. Chains are limited to eight hops. Wandering rays approximate native visual randomness; the adapter does not emit native stationary XY lightning damage bullets.

Explicit native fallback remains for unit/spawn payloads, scheduled laser side-lightning, custom lightning payloads, unsupported descendants, special movement/lifecycle overrides, sticky/suppression/puddle mechanics and other incompatible special projectiles. AI target acquisition and native air/ground eligibility remain native. Spatial force fields/point defense, exhaustive mod content, every instant effect and complete weapon parity are outside this milestone.

## Verification status

Final combined verification passed: Mindustry160.5 SDL/OpenGL run 2026-10-07T17:51:39.040878500Z, JAR SHA-256 `4b940271d4a55284c4563fe94382f4d837ac78652f15451cdf60c5f15dc548fd`. All15 pure suites passed (29107 assertions); original construction/time/build UI,377 Build navigation/camera checks,29 flight-control checks,1017 native separation checks, ordinary XYZ combat/lightning and GPU submissions, crash/ram/altitude/extrusion,3 native maps, copied12.msav and17.msav (both500x500), unchanged grid and native SaveIO roundtrip all passed. OpenGL error0. Twenty-one fresh captures and the exact tested JAR/source hashes are bound by `local/smoke/verification.json`.152 native weapon/ammo slots are adapted;118 type diagnostics include11 lab-only types, while48 inspected special types retain explicit native fallback.

Completed final renderer checks exercised real native Oct shield expansion/break, inside/outside completed GPU submissions without render-time state mutation, controlled-body hide/show without possession changes, and 720-tile far-plane/picking. The release owner reviewed the images. Captures are `shield-outside.png`, `shield-inside.png`, `third-person.png` and `high-altitude-720.png` under the smoke output directory.

The final scenario retains native construction/resource consumption, time presets, persistent buildings/bridges, multi-map terrain, camera/controls, shared physical state, ramming/blast, native SaveIO roundtrip and fresh imported-save checks. New tests cover every-preset speed scaling, stabilized axes/free look/braking, idle orientation, actual isolated native PhysicsProcess separation at matching/different heights, expanded native ammunition, physical lightning and damaging post-range fall. API/GPU tests do not establish physical mouse, controller, DPI, Alt-Tab or subjective flight feel. Dense factories, long campaigns and arbitrary external mods still need broader testing.

No proprietary Liftoff code/assets/runtime integration or Melty publication is added. Local Play-FPV remains the launch route and is not evidence of Melty one-click compatibility.


Runtime fixture corrections preserve strict assertions: equal-mass ram probes share their target hover velocity; the showcase commands a single native velocity update rather than teleporting with zero velocity; unscaled splash uses a vertical impact within the real target center radius. Air-only lightning stops at insulated walls without applying forbidden ground damage. The final stabilized/chase HUD capture was reviewed. Hardware input and long-session performance remain unverified.
