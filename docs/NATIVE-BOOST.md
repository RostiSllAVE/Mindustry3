# Native ground / boost flight — 0.18.15

Implemented on `feature/ui-polish`, continuing the existing Vela tripod, FPV integrators, ground controller and XYZ world queries.

## Controls and eligible content

- **F2**: Ground / Boost; rebind `Ground / native boost flight` in native Controls.
- **F9**: existing Acro / Stabilized; Stabilized uses WASD relative to pilot heading, E/Q altitude. Acro uses the existing throttle/pitch/roll/yaw controls.
- **F7**, observer distance, smoothing, Build Mode and command selection remain available. Build movement reuses BuildNavigator; command selection holds the craft. Grounded Vela retains its ordinary stance and Q/E extension controls.

Eligibility reads `canBoost`, excludes permanently flying, stationary and internal types, and validates native player ownership. Installed160.7 exposes **Nova, Pulsar, Quasar, Vela**. Hidden internal `dummy` also carries the native flag but is not a controllable flight vehicle. Dedicated flyers retain their existing FPV path; Dagger does not gain natural flight.

## Shared physics and safe landing

`NativeBoostSystem` is a separate capability/permission registry, sharing the existing per-instance CreatorFlightSystem integration rather than duplicating the FPV engine. Creator Flight remains host-authorized, OFF by default and separate from ordinary boost.

Boost preserves resolved mass/inertia/rates, calibrates speed/drag from native `speed × boostMultiplier`, and retains the existing user speed scale. Native shared definitions, health, hitboxes, weapons, damage, reload, range and stance profiles are not changed.

Ground requests keep the unit airborne in a landing state. NativeBoostLanding queries terrain/native physical surfaces and bounded elevated support under the actual footprint. It checks current/target body occupancy, descent barriers and ceilings; projected walls cannot supply support. It never searches another map location or teleports to Z0. A blocked descent remains airborne with HUD feedback and lets the pilot move to clear support or cancel landing. Real hard impacts still use the existing CrashSystem; pressing F2 alone is not damage.

A post-boost support flag keeps ordinary mechs on upper platforms. Native2D solidity is isolated while moving a physically supported rooftop unit; canonical grounded state governs the existing native weapon adapter. The extra support identity/height is saved separately. Vela continues through its existing stance solver.

Native AI continues through UnitAltitudeSystem and native boost decisions. Returning a boosted pilot to an AI that requests boost transfers height and XYZ momentum without replacing that controller; a ground-intending AI receives the existing safe landing handoff. No AI is commanded merely to fold its legs.

## Presentation

Vela's three existing native-textured chains interpolate between grounded and tucked endpoints over approximately **0.35 simulation seconds**. Foot planting stops while airborne and restarts against real support after touchdown. Only cached transform palettes change; no leg/hull meshes regenerate per frame. Native exhaust positions, radii and colors drive batched boost jets; artificial Creator-only jets retain their old layout.

Vela's hull remains **4608 vertices /1536 triangles**; the existing tripod limbs remain **9456 /3152**, in one cached atlas batch. No previous hull profiles or other family models were edited and no FPS improvement is claimed.

Installed native `Unit.canShoot()` forbids all four ordinary boost-capable types from firing in the air. Ground firing/reload/ammo resumes normally. Dedicated flyers remain unrestricted according to their original rules.

## Multiplayer and persistence

Protocol **3**, identical JAR required on host and clients. A bounded boost-intent bit is independent from Creator intent; contradictory intents are rejected. Eligibility/ownership/physics/landing/damage remain server-owned. Mode registries share the existing bounded state channel, avoiding a seventeenth channel. Native ClientSnapshot boost flags are overridden by authoritative mode state: delayed client flags cannot restart a completed landing.

Independent guarded save chunks preserve airborne XYZ/momentum (resuming safe descent on load) and post-boost upper support. They copy no live resources or inventories. Current native save and ground-stance formats remain intact.

## Actual verification

Final package: **3953032 bytes**, SHA-256 **061d955110edc9832ce4c348d184335188782eadd78ec2bf0bd9abacb0b722ba**.

- `tools/build.py`: all **91 Java suites** passed, including **114** new native-content/ownership/profile/XYZ/grounding/upper-platform/AI/save-codec/actual hard-impact checks. Dagger and normal Flare exclusions are exercised.
- Actual installed160.7 SDL: **35** boost checks for Vela/Nova/Quasar; real frames, ground takeoff/landing, native fire restriction, Acro pitch/roll, all F7 views, Vela fold/redeploy and cached meshes. **47** existing Vela tripod checks, all20 ground families and previous mech controls passed. Native grounded gait fixture explicitly disables unintended obstacle boost and uses native walking speed; production gait/layout is unchanged.
- Full compatibility: **three maps**, copied user saves, actual SaveIO roundtrip including airborne Quasar XYZ/momentum and safe descent; existing Creator/stance save checks passed. Original map/ore/team data retained, **GL0**.
- Independent layers: **209** construction checks, **685** camera, **293** turrets, **83** first-person GPU, **37** ground-eye and **40** commands, plus automatic placement/picking/copy/linking regressions passed.
- Actual dedicated160.7 server + **two SDL clients**, exact final JAR: all three processes passed. Native Vela/Nova boost/XYZ/exhaust/restrictions/landing replicated, independently of Creator permission. Previous forged input/handshake/reconnect/native build/resources/upper turrets/logistics/power fixtures passed. Server core156 checks; combined Creator/native-boost fixture505 checks.

Receipts: `local/compat-smoke/native-boost-ship-1607/results.json`, `local/layer-smoke/native-boost-ship-1607/layers-result.json`, `local/net-lab/native-boost-ship-1607/*-results.json`; screenshots in compatibility output. These are isolated D: profiles, not live user saves.

The final full91-suite build was followed by three small frontend intent/HUD corrections; ZIP entry comparison confirms only `fpv/FpvMod.class` changed. The exact shipping JAR reran and passed the full SDL compatibility, independent layer and two-client network fixtures. One intermediate network run reached and passed native boost, then timed out in the pre-existing unrelated layered phase13; the final rerun completed every phase.

### Limits

Checks use real native game processes with scripted production methods, not physical OS mouse/keyboard input. F2 rebinding and subjective piloting still need user playtesting. Elevated boost touchdown/takeoff is exercised in the native-content CPU fixture; SDL flight captures use ground touchdown. Multiplayer is verified on localhost, not Steam/NAT/internet transport. Current host160.7 was tested; no fresh160.5 game run. Unknown/custom native unit controllers keep their existing intent semantics. Pixel-art limb contours, cosmetic hull/collision differences and normal network interpolation remain existing visual limits.

## Recovery

Previous exact0.18.14 JAR: `local/backups/before-native-boost-0.18.14/mindustry-fpv.jar`; source baseline commit `c5a820f`. No push, merge, reset, source-tree rebuild or external publication.
