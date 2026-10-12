# Incremental bridges, XYZ weapons and precision navigation — 0.5

## Audit before implementation

The accepted 0.4 architecture remains in place: original Mindustry 160.5 grid, economy, campaign, AI goals, construction, health and save format; the shared World3DAdapter supplies physical altitude to FPV, unit poses, terrain, ramming and radial explosions. Native passive unit separation and strategic target acquisition remain XY.

The missing resource bridges are a separate rendering omission. Persistent building enumeration fixes endpoint structures, but ItemBridge/DirectionBridge connections were never emitted as 3D spans. Their actual link metadata must be resolved independently of animation, inventory, power or sleeping status, and the full span must participate in culling.

Old projectile rendering used small boxes at a fixed height. Scanning Groups.bullet also misses instantaneous effects, and native 2D effects are covered by the opaque 3D pass. Raising contrast alone would not make these shots physically three-dimensional. The next migration therefore pairs readable geometry with an explicit XYZ weapon adapter for supported straight bullets and laser families; unsupported lifecycle mechanics retain native combat.

The original shared BulletType cannot be globally made noncolliding: instant lasers already deal damage synchronously during Bullet.add/type.init, and native firing does not generally emit BulletCreateEvent. Reversible per-slot proxy types intercept supported creation before XY damage while leaving original bullet metadata intact. Native ammo, cooldown and weapon selection stay in charge. Spatial shields and point defense need their own later XYZ migration.

Flight profiles already derive mass, size, drag, agility and speed from the selected unit. This pass increases the exact native Flare aerodynamic reference by1.5, reduces heavy-unit speed/response further, and increases heavy impact severity and bounded destruction magnitude. It does not replace the existing acro integrator.

## Changes and contracts

- `NativeBridgeLinks` and the existing renderer consume real linked endpoints for item/liquid and directional bridges. Presentation is independent of transfer activity.
- `Combat3DSystem`, `CombatBindings`, `CombatGeometry` and `CombatVisual` separate weapon adaptation, geometry and rendering. Public physics coordinates are X/Y native horizontal tiles and Z altitude; renderer coordinates remain X/Z/-Y.
- Terrain-only traces exclude placed buildings so combat can apply team and body rules to structures independently. Existing all-surface traces are unchanged.
- `BuildNavigator` provides stabilized WASD movement, PageUp/PageDown climb and Home/End turn. Its0–60m/s speed uses real time independently of world speed. Normal moving-world commits use UnitMotionBridge. At0% world speed, only the possessed unit moves through native Unit.move; no Unit.update, build queue or world tick is advanced. Precision collision clamps movement without ram damage/impulses.
- `BuildControls` exposes speed/time beside the existing builder, with collapsible camera settings. `ViewSettings` maps POV0° down,90° forward,180° up. Old signed tilt migrates once. FOV0–180 is selectable; projection uses1–179 at endpoints to avoid singular matrices. Build retains its independent downward tilt.
- Heavy blast bounds become40tiles/12000 additional damage. The reusable explosion and normal native death consequences remain separate; reactor destruction code is not invoked.

## Preservation and verification plan

Working 0.4 source/package are checkpointed under `local/checkpoints/0.4.0-working`. The170-file play profile is archived under `local/backups/before-0.5-20261007T145827Z`; its copied `saves/12.msav` is the isolated imported-save fixture. Tests never load the live play directory.

Design sheets precede generated configuration/build. New checks cover native bridge submission and invalidation, supported projectile altitude hits/misses and beam interception, paused Build navigation, camera endpoint matrices, Flare/heavy performance and impact scaling. Existing construction/time-preset, terrain, high-speed sweep, extrusion, ramming, multiple-map and native save roundtrip checks remain required.

Final package verified: `5eb7f4b59adda93ffffdbcf1b6a36ea2d085ff7a71e500361f254661336a82e8`. The isolated SDL/OpenGL scenario beginning 2026-10-07T15:48:03.814933300Z passed; the exact source/package/evidence receipt is `local/smoke/verification.json`.

## Deliberate remaining scope

Unsupported weapon families retain native XY mechanics. Full target selection, passive async separation, spatial shields/point defense, articulated unit parts, controller calibration and exhaustive mod compatibility are later work. This pass does not integrate Liftoff runtime assets or publish to Melty.

## Final validation

All13 pure suites passed (26597 assertions). The real game passed the three native maps, copied500x500 user save, unchanged native grid and SaveIO roundtrip, original construction/time/pause/UI checks,377 navigation/camera assertions,24 bridge submissions, ramming/explosion/extrusion/altitude checks and GL error0. Actual native Flare, Duo copper and Meltdown adapters are exercised, plus low-building targeting, two-team hit/miss, instant/continuous beam lifecycle, ordinary-wall piercing and absorber stop, surface-gated incendiary behavior and terrain collision. Two managed projectiles and two exactly vertical beams from both teams reached the production GPU path.

Runtime testing exposed and fixed default puddle metadata eligibility, Bullet.add setting `added` only after init, and aiming below the projectile radius at thin ground bodies. Low-building aiming uses the same body-constrained clearance. Visual review removed a coplanar beam-core overlay that caused depth fighting. Unsupported mechanics still use native fallback; the runtime count is38 native weapon/ammo bindings, not all content. The34 adapted-type count includes seven lab-only types.

The profile backup contains170 files; its copied12.msav hash remains unchanged. Fifteen fresh captures and the installed-lab JAR hash match the final receipt. Hardware input, subjective flight feel, all mod-defined weapons, large-base maximum blast damage and long-duration performance are not inferred from these scripted checks.
