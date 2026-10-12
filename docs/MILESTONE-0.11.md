# Mindustry FPV 0.11 — stability, combat and presentation

This extends the working 0.10 architecture against the installed Mindustry 160.5 / Java 17. It does not replace the simulation, terrain, renderer, native saves, Build Mode or multiplayer protocol with a new prototype.

## Crash diagnosis and repair

The full reported `crash-report-10_08_2026_15_51_23.txt` was inspected. `SimulationClock` sampled the Build time scale before native input handling. Switching from 0% to a positive speed, or leaving Build Mode, could unpause the same update while retaining the previous zero delta. Native continuous-turret aiming includes an infinite aim-change speed; its `Mathf.approachDelta` then evaluated infinity × zero. The resulting NaN aim reached `CombatGeometry.aim` and the intentional `Finite XYZ required` validation.

The wrapper now refreshes its simulation delta at `beforeGameUpdate`, after input/pause decisions and before entities run. Both time sources are restored in `finally`. Finite validation remains. Invalid muzzle/velocity/aim data is diagnosed and cancelled, or targeting is recalculated from an available live target. Direction normalization also handles extreme finite magnitudes without overflowing. Destroyed targets and native mover/follow-aim callbacks are guarded.

The native test first reproduces NaN in the old continuous-turret calculation, then exercises 288 transitions among 0/5/10/25/50/100% through the actual clock wrapper: 1,545 assertions and 241 native beam ticks. Additional combat fixtures exercise invalid aim, missing targets and invalid native velocity without phantom damage.

## Unit geometry, movement and spawning

- Native ShockMine-derived traps use conveyor-like height in both the renderer and CPU picking/collision. Native textures, activation and damage remain; drills are unaffected.
- Crawler keeps native `killShooter` metadata. Its spatial attack resolves once, marks the native death-weapon guard, and calls the native synchronized death path. The player sees its dead unit and normal control release. Ordinary collision death remains governed by existing ram caps.
- Corvus maximum total extension is the previous configured maximum ×3. Vela is a native two-legged Mech, with no previous extensible maximum: its new shared-foot profile derives a baseline from native stride and the existing step setting, then applies the same host-configurable ×3. Other tiers and aircraft do not gain the multiplier. Feet, manual limits, smooth stance, speed penalty and physical clearance share the same state.
- Vela's native tile pushback is overridden only when replaying the native collision on one reusable, unadded scratch body produces the observed displacement and the XYZ hull has clearance. Native automatic boosting on a solid tile is distinguished from an explicit boost. This lets a physically raised walker traverse a low wall while preserving unrelated pushes and real obstacles.
- The same native automatic boost could suppress Vela's charged weapon before the after-tick correction. The weapon delegate now temporarily uses the supported canonical ground state for that check, restoring native elevation afterward. Actual Player/Unit updates verify a charged shot above a wall and preserve the shooting prohibition during an explicit boost.
- Shared Mech model alignment places the leg/base geometry beneath the same physical body transform. Dagger no longer has its thinner body buried below thicker legs. Vela's native whole-leg texture reaches the shared physical foot without detached top joints. Existing flyer bodies remain unchanged.
- Ordinary Mech limb depth is fitted into the available space between the sampled floor and the existing torso attachment ceiling. This also removes shallow Fortress/Scepter/Reign foot clipping without moving bodies or changing Dagger's already-correct proportions.
- `FactorySpawnSafety` validates the full hull and intended output direction before native payload release, including terrain, passable output surfaces, structures and already released units. Blocked exits retain their payload. Native UnitUnload initializes altitude before the first unit-spawn packet and removes random birth motion. No broad invulnerability or global collision grace is used.

The factory fixture uses actual ground factory/reconstructor/payload paths for Crawler, Spiroct, Toxopid, Flare and Eclipse, all four output directions, costs, blocked exits, conveyor support, first-frame height, subsequent movement and ordinary damage: 61 assertions.

Final multiplayer testing exposed an additional automatic-landing defect: the creator-flight autopilot could approach the ground at a cruise-scaled speed and queue lethal collision damage before its final height correction. `CreatorLandingMotion` now bounds descent using the existing flight response and braking acceleration; only already-gentle motion can use the final contact-clearance snap. The collision sweep and crash damage stay enabled. A 435-assertion integrator/sweep test covers three ground types, four profiles, three time steps and multiple heights, including unrecoverable external falls that must still cause a damaging impact. Both host-revocation and possession-release landing scenarios also pass on the real two-client server.

## Combat

Native weapon creation, ammunition, damage, reload and spread remain. A per-weapon adapter retains its native range baseline and custom weapon delegate. Host defaults are ground horizontal 2×, Dagger–Reign horizontal 3×, naval horizontal 3× and vertical 5×. Categories do not stack. Flyers and turrets keep their separate ranges. Native targeting is checked against the XYZ envelope, including autonomous naval mounts; native hold-fire decisions remain. Predicted aim, powered lifetime and horizontal damage falloff use the extended reach.

Ordinary piercing lasers cross normal buildings and wall projections; native `absorbLasers()` walls such as Plastanium stop them at their actual XYZ surface. Native positive pierce caps, damage intervals and damage attenuation remain. Distinct families retain distinct semantics:

- Rail and `laserAbsorb=false` continuous flame do not acquire Plastanium laser absorption. Their native piercing/damage rules still apply.
- Sap affects its first eligible physical target and drains health once, including a building. It uses ordinary shield contact because it is a first-target drain weapon.
- PointLaser damages bodies at its aimed endpoint on the native interval, rather than everyone along the visible line. PointBullet also uses endpoint contacts. Native endpoint families do not acquire intermediate Plastanium laser absorption.
- True terrain and Force Projector protection remain explicit spatial obstacles. Force Projectors intercept hostile shots independently of wall laser absorption.

Beam visual width is 3× without changing physical radius or damage. Explosive impacts use their native splash radius/color for a bounded custom XYZ burst; ordinary impacts use a short flash and debris. Retained effect records are pooled and capped; piercing contacts do not create repeated effects per shot, and continuous effects are throttled separately from damage pulses.

Native firing tests cover Dagger/Nova/Risso hits beyond their former ranges, Nova at 4.5 native ranges in altitude, rejection beyond 5, autonomous Navanax exclusion, unchanged flyer range, Crawler death/single splash, Sap first-target healing, PointLaser endpoint/interval, and Rail/flame base/projection/Force interactions.

## Environment and shields

Trees, shrubs and decorative rocks use deterministic low-poly silhouettes, bounded original-atlas color sampling and cached chunk geometry. Ordinary natural wall regions explicitly remain mountain terrain. Native placement/map data is unchanged. The GPU gallery includes close prop views, conveyor/mine heights, all 20 ground models and the existing extended Toxopid fixture.

Projected walls have local 100-tile maximum visibility and 80–100 fade defaults. Selection uses actual camera XYZ and frustum. Distant geometry is not submitted; an empty view skips the composition and depth-copy work. Camera movement does not rebuild meshes. Hidden projection HP, collision, regeneration and teams remain fully active.

The host-only **Aircraft blocked by Force Projectors** option defaults OFF. ON blocks hostile native flyers and authorized creator-flight bodies against the actual active polygon prism; allied aircraft pass. Existing inside bodies may exit. OFF leaves aircraft free while projectile protection continues. Pilots and unpiloted aircraft use the same nearest barrier boundary. Clients receive the host setting and cannot replace it with local preferences or forged packets.

## Measured performance

The controlled comparison uses the same copied 430×430 save with 5,292 walls, 120 warm-up frames and 300 measured frames per camera. The after artifact overlays only the distance-renderer classes onto immutable 0.10, isolating this optimization from other scene changes. Release verification checks those measured classes against the final release.

| Projection GPU mean | Before | After |
|---|---:|---:|
| Near | 0.304 ms | 0.386 ms |
| Mid | 1.893 ms | 0.341 ms |
| Overview | 2.017 ms | 0.0016 ms |

Mid geometry fell from 55,656 to 4,956 submitted vertices; overview submissions fell to zero. Cache rebuild count remained zero. Near selective-chunk work and allocations increased. Whole-frame elapsed time did **not** improve in this pair; no measured overall FPS gain is claimed. Full data, method and memory tradeoffs: [WALL-DISTANCE-0.11.md](WALL-DISTANCE-0.11.md).

## Verification, backup and limits

The final package identity, pure-test totals, fresh native captures, copied-save hashes and all three real multiplayer process results are bound in `local/smoke/verification.json`. The matching checkpoint contains the release JAR, source and verification archives with SHA-256 and ZIP CRC verification. The launcher installs this release on the next normal start; an open user game is not modified underneath it.

Before editing, working 0.10 was archived in `local/backups/before-0.11-20261008T143402Z`: 1,029 verified entries including the full play profile, source, release and evidence. Tests use isolated copies, including latest-by-write-time `29.msav`; original user saves were not used as writable test profiles.

Native SDL tests cover Ground Zero, Caldera and an Erekir map, original tile fingerprints, copied imported/latest saves and SaveIO roundtrip, FPV/Build modes, true-3D combat, shield/ram/support effects, models and OpenGL status. Network tests use a dedicated server and two separate SDL clients with the exact same JAR, including host settings, hostile local preferences, packet rejection, control handoff, build placement, reconnection, world reload, XYZ hits/misses and creator flight.

These are scripted native game/protocol runs, not physical keyboard/controller playtesting or WAN latency testing. Arbitrary third-party content, every specialized custom weapon callback and full mod combinations are not individually validated. High-level native AI route planning remains. Ordinary flyer/projectile/projection-HP sidecars remain session-only; stance and creator poses have save support. Liftoff runtime dependence and Melty publication remain outside this maintenance pass.

## Final release identity

- Package: `dist/mindustry-fpv-0.11.0.jar`.
- SHA-256: `06d399a94d632bb44c0d58118d8446685e4de082e22083919d98c9c8acf526ec`.
- Clean build: 35 pure suites, 279,934 assertions/checks.
- Final native SDL run: three maps, copied saves and roundtrip, all maintenance fixtures and 20-unit model gallery passed; OpenGL error 0.
- Actual matching-package multiplayer: `v011-final2`, server and both SDL clients passed; server 155 main checks and 477 creator-flight checks.
- Measured distance-renderer class hashes match this package exactly. The benchmark is not a whole-release FPS comparison.
- Verification: `local/smoke/verification.json`; immutable release/source/evidence checkpoint: `local/checkpoints/0.11.0-verified`.
