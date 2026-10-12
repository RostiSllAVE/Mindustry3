# Spatial combat balance and matching-mod multiplayer — 0.7

This extends the working0.6 implementation. Native maps/economy/construction/save format remain authoritative; physical flight, altitude and adapted projectile combat are explicitly three-dimensional. No Liftoff binaries, code or art are used. Profile backup:507 files under `local/backups/before-0.7-20261007T195839Z`; working0.6 checkpoint retained. Scripted runs use isolated profiles and copied12.msav/23.msav.

## Pilot controls

Stabilized rotates its horizontal WASD basis by pilot heading, independently of look pitch; its level body faces that heading while strafing/reversing/hovering. F7 cycles first/chase/rear observation. Rear observation positions the camera in front looking behind but preserves the forward weapon direction. Build camera/cursor and view restoration remain separate.

Native builder input previously overwrote shooting state or rejected a shot because the screen-center mouse was over configurable terrain or a queued plan. `FpvDesktopInput.applyFlightTrigger` reapplies flight trigger state after that update, preserving focus/modal/Build/death/boost guards and native ammo, reload and weapon cones. Native XY aim provides a stable forward mount target even for steep camera pitches; actual shot direction comes from canonical XYZ aim. The flight fixture updates actual Flare weapons over four headings, three pitches and all three views.

Ram trauma is committed after native movement even if the source dies during that intervening update. Offline HUD feedback reports actual HP/shield loss. Multiplayer uses a shared unordered-pair impulse/damage ledger and a frozen per-frame view of other controlled pilots, preventing reciprocal solvers from applying the same pair damage twice. Friendly damage protection is separate from collision response.

Native passive separation used a0.6×hitSize radius while the physical flying hull used0.5×hitSize. That larger invisible separation shell could prevent moderate approaches from ever reaching a real ram contact. Active3D passive separation now uses the same authoritative flying radius as collision, with the existing native mass weighting and vertical-overlap gate. Ground bodies retain native radii; native PhysicsBody metadata is not mutated. The spatial query expands for physical hulls larger than the native index rectangle.

## Combat

Every discovered native unit weapon and turret ammo/shoot slot is proxied. Unsupported custom projectile lifecycle callbacks are reinterpreted as safe spatial projectiles/beams rather than allowed to apply hidden XY collision damage. Scathe's missile metadata becomes a physical shot. Native direct-damage abilities and non-projectile environmental/death effects are separate paths and are not all migrated; no claim of complete vanilla effect parity is made.

AA acquisition uses original horizontalR and configurable vertical5R (1–20), with true XYZ lead and enough powered/beam reach for high targets. Ground-only identity remains native. Ammo/reload/pattern/speed metadata is preserved; native strategic XY AI is not replaced wholesale. Segment and tractor turrets have dedicated spatial adapters; managed proxies cannot be intercepted by an unrelated native XY point-defense pass.

Powered-range expiry begins stronger4× falling gravity. Spent power decays exponentially from1 to.1 by3 seconds, then linearly to0 by10 seconds. Horizontal launch displacement independently gives full power throughR, .1 at1.2R and0 at1.5R. The two factors multiply. Exact contacts, splash/fragments and lightning inherit this policy without counting artillery arc length. Children retain original launch/range history and spent age. Actual native `Bullet.update` fixtures require wrong-Z misses and right-Z hits, not only mathematical predicates.

`CombatSettings.apply` reads the same server-owned AA and falloff preferences in desktop and headless runtimes. The falloff knee (1.05–1.5R), endpoint (1.5–3R) and knee power (0–100%) are configurable. Ordered finite bounds avoid a degenerate knee equal to the endpoint. Each original weapon/turret's own range setsR; native artillery retains its arc behavior.

## Wall projection and legs

`ProjectedWallSystem` retains one sidecar state per native wall anchor. Natural terrain and arbitrary player blocks do not become projected walls. Default capacity is.85×native maximum HP; one combined volume rises five base-height segments above the physical building. Allied projectiles pass; enemies reduce the pool. Remaining height shrinks continuously from the top, with the partial top segment fading. Collapse leaves native base HP intact. Default regeneration starts after8 simulated seconds at2.5% of capacity per second; pause freezes it.

A16-tile spatial index rejects distant queries. Multiblock anchors index their whole footprint, normal tile changes inspect only affected chunks, rendering rejects whole chunks and hidden shared faces, and only damaged pools regenerate. There are no five extra building entities. Sparse replication sends settings and damaged pools; unchanged payloads are skipped. A full state of262144 damaged anchors is bounded before compression and fits the16MiB transport envelope. Client-origin input messages are separately capped at256bytes.

Optional projection collision applies to piloted aircraft through the existing swept-body solver; it is off by default. Native capable leg/crawl units bypass projection and use per-unit legLength/allowLegStep to support their body above eligible low bases. Ground pathfinding remains native. Opening a native door removes its upper projection immediately; the pre-existing 3D base-door collision representation still needs a separate dynamic-geometry pass.

## Multiplayer boundary

`FpvNetwork` requires identical mod JAR SHA-256 and native160.5. `hidden:false` also exposes the required mod to Mindustry's normal mod matching. Dedicated runtime creates only CPU terrain/altitude/combat/physics/network objects; it does not initialize JNA, SDL or shaders. Desktop hosting uses the same network authority.

Clients send bounded controls, mode, heading and normalized XYZ aim. They cannot submit position, HP, projectile hits or shield damage. Native ClientSnapshot handling retains validated build plans while ignoring client XY/velocity and using accepted trigger state. Native-ground and F5 movement also pass through the authority boundary. Server-owned poses are interpolated only for display; interpolation never feeds server collision. Inputs time out after.5seconds. World epochs, unit identities and exact hashes prevent stale session state from being accepted after world reload or reconnection.

Poses are sent at10Hz, combat/explosion visuals at20Hz, wall state at5Hz, with bounded chunks. Build remains available at100% shared simulation time. Physics/speed/combat parameters are server settings; camera, mode and mouse preferences are local. F6 is an offline recovery function; clients cannot request an arbitrary teleport.

## Persistence and distribution limits

Native `.msav` content is unchanged. Altitudes, active shots and projected HP are session state and regenerate on load; projection pools therefore reload full. Live-session state is replicated to joining clients. This is not permanent save support for the added state.

Native special abilities/Oct interception and some custom suppression/sticky/puddle/secondary effects are incomplete. Scripted checks do not prove physical mouse/controller feel, arbitrary third-party mods, long campaigns, WAN latency or exhaustive native effects. Broadphase assumptions for extreme external teleports/velocities remain bounded. Melty/Liftoff integration is unchanged and publication has not occurred.

## Verification receipt

Final verification is recorded in `local/smoke/verification.json`, binding the release SHA-256 to installed solo/network packages, source hashes, pure-test log, SDL screenshots, copied-save hashes and per-process network receipts. Failed developmental runs remain under `local/net-lab` and are not counted as successful final acceptance. The fixture uses actual official160.5 dedicated server and two separate official SDL clients, with all profiles and downloads onD:.

Final accepted package: `c3934695dc0eb658d4687ef3fac95b10dbb9e6e53cebad77122bf2902da7026a`. Solo SDL started `2026-10-07T21:02:58.701265900Z`, passed with OpenGL error0, three native maps, copied12/23 saves and native roundtrip. All20 pure suites passed34734 assertions;25 captures recorded. Network `run6` passed in all three processes (server118 assertions, clients21 each), with symmetric16.2165756HP ram and synchronized projection collapse/regeneration. All tested installed JARs match the release.
