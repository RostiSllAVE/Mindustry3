# Mech weapon vertical aiming — 0.18.11

Mech guns use a shared XYZ mount solution in the world renderer, first-person viewmodel pass and authoritative projectile adapter. The previous world model gave every gun the hull's pitch while combat independently calculated elevation; the first-person pass used a fixed camera-space slope. Those disconnected transforms caused the visibly flat weapons.

## Mounts and aiming

`MechWeaponAim3D` applies to grounded `Mechc` families, including Dagger/Mace/Fortress/Scepter/Reign and the ordinary green support mechs. Flying types, `Legsc` walkers, extended Vela, block-controlled turrets, suicide/death weapons and child/explicit spatial emitters retain their existing paths. Original body profiles, cached hull/limb geometry and hitboxes are unchanged.

Native unit rotation, mount rotation/base rotation, mirrored positions and horizontal spread remain the yaw sources. Player guns consume the existing local FPV sight or server session aim. AI guns use actual canonical XYZ targets, existing lead prediction and layered-obstacle aiming. Thin ground targets retain the existing projectile-radius clearance, so rounds do not hit the floor just before a low target. Idle guns inherit the hull's pitch.

Mechanical elevation is clamped to **−20° / +70°**. This limits firing elevation as well as the visible barrel: an out-of-traverse sight cannot make a flat gun emit an impossible ray. Damage, reload, range multipliers, projectile gravity/falloff, native movement and collision envelopes are not retuned. Native inaccuracy, inherited shooter velocity and subsequent artillery gravity can naturally deviate a travelling round from its initial barrel axis.

The trunnion sits behind the gun center at 75% of its half-length. The center follows an arc around that hinge; a small hard-edged saddle lifts only as much as necessary for rear/underside hull clearance. Neutral longitudinal placement is unchanged. The existing native shoot X/Y offsets, including randomized barrel offsets, rotate into XYZ around the same articulated mount. Recoil retreats along the pitched barrel. AI artillery uses its existing lifetime-based launch arc when solving the visible angle, rather than being drawn directly toward the target with an independently lofted projectile.

The five body model profiles are not edited. A metadata-only support frame makes emitter positions consistent without a server texture atlas. Known original gun dimensions were read from the installed 160.7 atlas: large-weapon18×28, flamethrower24×50, artillery34×50, heal-weapon18×34, scepter-weapon38×88 and reign-weapon59×122, at native scale0.25. Only geometric dimensions are recorded; no textures are copied into the package. Unknown/custom weapon names fall back to native unit size and shoot offset.

## Rendering and multiplayer

World guns reuse the existing cached alpha-extrusion meshes. One shared36-vertex saddle template is created lazily; pitching does not regenerate gun or hull meshes. A conservative render-only upper culling envelope includes raised barrels without changing camera height or physical bounds.

The existing private-depth first-person pass, positions, spring/sway and recoil translation remain. Mech viewmodels use the shared world gun direction, making the mechanical stops visible in FPV; ordinary flying/walker/turret viewmodels retain their previous transforms. F7, zoom, camera smoothing and control code are unchanged.

Native Mindustry weapon synchronization does not contain XYZ target elevation. A bounded server-to-client **mech-pitch** state channel therefore supplies per-mount angles for external observers. It uses the existing matching-mod, chunked transport, not a new hit or placement request. Identity includes unit/type/team; payloads reject duplicate identities, invalid angles, oversized/truncated records and trailing bytes. World reset clears observer data. The local controlled player keeps immediate sight prediction rather than waiting for the observer channel. Damage and launches remain server-owned.

## Verification

Tests use disposable profiles and map/save copies under `local`; physical mouse/controller hardware is not automated. `MechWeaponPitchTest` checks yaw/elevation, normalization, rear hinge clearance and native metadata fallbacks. `MechWeaponPitchNativeTest` uses real host content without an atlas to check every Dagger-family mount, XYZ origins, player/AI pitch and unchanged damage/reload/range. `MechWeaponPitchCodecTest` covers immutable, bounded observer snapshots.

The native SDL fixture uses actual native weapon creation for Dagger, Mace, Fortress and Reign in controlled and scripted AI target cases; it checks muzzle XYZ, launch elevation, real advancing Z, clamps, FPV mesh orientation, side/rear submissions and GPU errors. Original all-family model/control checks remain. The previous Nova vertical-range regression now targets1.8R horizontally /4.5R vertically, inside the70° mechanical stop, and explicitly supplies that target for its reach-only case; unassisted horizontal AI acquisition remains separately tested.

Run the full build with `tools/build.py`. SDL/save checks use `tools/launch-regression-smoke.ps1`; construction/camera checks use `tools/launch-layer-smoke.ps1`; an actual headless server and two SDL clients use `tools/launch-network-smoke.ps1`. The network fixture additionally verifies replicated mech elevation and model submission on both clients.

## Limits

Unknown modded gun artwork uses an estimated mount envelope, and specialist weapon animations remain their native behavior. Very close sights have camera/barrel parallax; native spread and momentum are retained. Mechanical stops deliberately prevent aiming beyond the supported elevation. No full-game FPS improvement or physical controller test is claimed.

The working0.18.10 JAR is preserved at `local/backups/before-mech-pitch-0.18.10/mindustry-fpv.jar`; its source remains in the preceding Git commit. Release and verification receipts are recorded in MODLOG and the isolated test output directories.

## Actual release results

The final0.18.11 JAR is3909649 bytes, SHA-256 `78ca4bdf0b1e5a8e4553b4811136dd550122a6648cb584d4187485049851cd5a`. The same hash was used in every final run and installed into the ordinary play profile. All642 original non-FPV-JAR profile files retained their hashes.

| Check | Actual result |
|---|---|
| Full build | 87 Java suites passed; new2287 pure pitch,114 headless native and46 codec checks |
| Installed160.7 SDL | 176 mech weapon checks; Dagger/Mace/Fortress/Reign controlled and scripted AI; muzzle XYZ, matching launch elevation, actual upward integration, downward stops and viewmodel orientation |
| Existing content/camera | All20 native ground-family gallery entries, original five body models and control fixtures passed; GL0 |
| Maps/saves | 3 maps and copied user saves loaded; native save roundtrip and native2D/FPV recovery passed |
| Layers/input | 209 construction,685 camera,293 elevated-turret,83 first-person GPU,39 ground-eye and40 command checks passed, plus existing mouse/placement/schematic cases |
| Actual multiplayer | Official160.7 headless server and2 SDL clients passed: server157 checks,42 each client; both clients verified server mech pitch and submitted its model; authoritative combat and existing construction/logistics/creator-flight checks passed |

Raw results: `local/build-mech-pitch-tested.log`, `local/compat-smoke/mech-pitch-tested-1607/results.json`, `local/compat-smoke/mech-pitch-tested-1607/mech-pitch-verification.txt`, `local/layer-smoke/mech-pitch-tested-1607/layers-result.json`, and `local/net-lab/mech-pitch-tested-1607/*-results.json`. Side/rear/FPV captures were inspected, including the compact Reign trunnion and raised Fortress artillery. Captures are diagnostic test output, not release assets.
