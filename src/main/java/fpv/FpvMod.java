package fpv;

import arc.*;
import arc.graphics.*;
import arc.graphics.g3d.Camera3D;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.math.geom.Vec3;
import arc.util.Log;
import fpv.flight.FlightController;
import fpv.flight.FlightSettings;
import fpv.flight.BuildNavigator;
import fpv.flight.StabilizedFlight;
import fpv.flight.FlightView;
import fpv.ui.BuildControls;
import fpv.ui.ViewSettings;
import fpv.ui.CameraView;
import fpv.net.FpvNetwork;
import fpv.net.NetworkControls;
import fpv.net.HeadlessFpvRuntime;
import fpv.control.*;
import fpv.generated.NavigationConfig;
import fpv.physics.SweptCollision;
import fpv.physics.RammingCollision;
import fpv.combat.Combat3DSystem;
import fpv.world.WorldCoordinates;
import fpv.world.World3DAdapter;
import fpv.flight.UnitMotionBridge;
import fpv.generated.FlightProfiles;
import fpv.generated.InputProfiles;
import fpv.generated.RuntimeConfig;
import fpv.input.FpvDesktopInput;
import fpv.platform.FlightMouseInput;
import fpv.platform.FlightMouseInputs;
import fpv.render.FpvWorldRenderer;
import fpv.render.SpriteExtrusionRenderer;
import fpv.units.UnitAltitudeSystem;
import fpv.units.AltitudeSeparation;
import fpv.effects.RadialExplosion;
import fpv.physics.CrashSystem;
import fpv.generated.CrashConfig;
import fpv.ui.FpvHud;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.core.Version;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.*;
import mindustry.gen.Unit;
import mindustry.mod.Mod;
import mindustry.world.Tile;

/** Native map/economy with incremental spatial physics, rendering and server-owned combat. */
public final class FpvMod extends Mod {
    final Camera3D camera = new Camera3D();
    final Camera3D pilotCamera = new Camera3D();
    final fpv.flight.ObserverCamera observerCamera=new fpv.flight.ObserverCamera();
    private final Vec3 cameraFocus=new Vec3();
    private final fpv.flight.ObserverCamera.Trace cameraTrace=(a,b,r)->{
        var hit=this.world3d.traceSegment(a.x,-a.z,a.y,b.x,-b.z,b.y,r);
        return hit.hit()?hit.fraction():1;
    };
    final FpvWorldRenderer renderer3d = new FpvWorldRenderer();
    final FlightController.Profile flightProfile = FlightProfiles.create();
    final FlightController flight = new FlightController(flightProfile);
    final FlightSettings flightSettings = new FlightSettings(flightProfile);
    final UnitMotionBridge motion = new UnitMotionBridge();
    final UnitAltitudeSystem unitAltitudes=new UnitAltitudeSystem(renderer3d.terrain()::heightAt,
        ()->this.enabled&&this.ready&&Vars.player!=null&&Vars.player.unit()!=null&&Vars.player.unit().type.flying&&Vars.player.unit().id==this.unitId?Vars.player.unit():null,SpriteExtrusionRenderer::halfDepth,SpriteExtrusionRenderer::bodyRadius);
    final World3DAdapter world3d=new World3DAdapter(renderer3d.terrain(),renderer3d::surfaceHeight,unitAltitudes);
    final fpv.units.FactorySpawnSafety factorySpawns=new fpv.units.FactorySpawnSafety(world3d,unitAltitudes);
    final fpv.defense.ProjectedWallSystem projectedWalls=new fpv.defense.ProjectedWallSystem(world3d,()->!Vars.net.client());
    final Combat3DSystem combat=new Combat3DSystem(world3d,
        ()->Vars.state.isGame()&&(this.enabled||Vars.net.server()||networkReady()||this.construction!=null&&this.construction.nativeBuildings().hasTurrets()),unit->{
            var serverAim=networkAim(unit);if(serverAim!=null)return serverAim;
            if(!this.ready||Vars.player==null||Vars.player.unit()!=unit)return null;
            return controlledAim();
        });
    final fpv.defense.ForceProjectorSystem forceProjectors=new fpv.defense.ForceProjectorSystem(world3d,()->!Vars.net.client());
    FpvNetwork network;
    fpv.construction.LayerRuntime construction;
    fpv.construction.LayerPhysicalQueries layerQueries;
    fpv.construction.LayerBuildController layerBuild;
    HeadlessFpvRuntime headlessRuntime;
    final BuildNavigator buildNavigator=new BuildNavigator(flightProfile.metersPerTile);
    final StabilizedFlight stabilizedFlight=new StabilizedFlight();
    final FlightView freeLook=new FlightView();
    final fpv.flight.GroundEyeHeight groundEye=new fpv.flight.GroundEyeHeight();
    int flightMode;
    private final GroundedPilot groundPilot=new GroundedPilot();
    private Unit trackedPilot;
    private boolean creatorRequested;
    private boolean boostRequested;
    NetworkControls smokeBoostControls; // isolated real-frame fixture only; null throughout ordinary gameplay
    final RammingCollision buildObstacles=new RammingCollision(world3d);
    final RadialExplosion explosions=new RadialExplosion(world3d);
    final CrashSystem crashes=new CrashSystem(renderer3d::surfaceHeight,explosions,flightProfile.metersPerTile);
    private final FlightController.StepObserver flightCollision=(state,ox,oy,oz,seconds,elapsed)->collide(ox,oy,oz,seconds,true,elapsed);
    FpvDesktopInput input;
    SimulationClock clock;
    FlightMouseInput mouse;
    boolean enabled, buildMode, ready, pausedBeforeBuild, buildOwnsPause;
    boolean disposed;
    int unitId = -1;
    double throttle;
    int timeIndex = 2;
    final boolean smokeMode = Boolean.getBoolean("fpv.smoke");
    private boolean autoEnable = true;
    private boolean inputSuspended;
    private Graphics.Cursor hiddenCursor;
    private boolean cursorHidden;
    private final double[] vector = new double[3];
    private final Vec3 cameraForward = new Vec3();
    private final Vec3 cameraUp = new Vec3();
    private final Vec3 weaponForward = new Vec3();
    private WorldCoordinates.Point pickedFlightAim;
    private SmokeHarness smoke;
    private WallBenchmarkHarness wallBenchmark;
    FpvHud hud;
    BuildControls buildControls;
    AltitudeSeparation separation;
    private String status = "Ready";
    private long ramFeedbackSequence,ramFeedbackUntil;
    private String ramFeedbackText="";

    @Override public void init() {
        if (!fpv.platform.HostCompatibility.supportsCurrent()) {
            Log.err("[FPV] Mindustry @ is not a verified host version. Supported: @. Staying in normal 2D.",fpv.platform.HostCompatibility.currentVersion(),fpv.platform.HostCompatibility.supportedVersions());
            return;
        }
        if(Vars.headless){headlessRuntime=new HeadlessFpvRuntime();headlessRuntime.install();return;}
        try {
            var nativeDir = Vars.tmpDirectory.child("fpv-native");
            nativeDir.mkdirs();
            System.setProperty("jna.tmpdir", nativeDir.absolutePath());
            mouse = FlightMouseInputs.create(Core.input);
            input = new FpvDesktopInput(Core.input, InputProfiles.create());
            input.setMovementOwnedSupplier(()->network!=null&&(network.controlsLocalFlight()||
                Vars.player!=null&&Vars.player.unit()!=null&&(network.creatorFlight().active(Vars.player.unit())||network.nativeBoost().active(Vars.player.unit()))));
            input.installProjection();
            Vars.control.setInput(input);
            input.setWorldProjector((x,y,out) -> renderer3d.project(camera,x,y,out));
            clock = SimulationClock.install(this::timeScale);
            unitAltitudes.setControlledStateProvider(flight::state);
            crashes.setWorld(world3d);
            renderer3d.setUnitAltitudes(unitAltitudes);
            renderer3d.setExplosions(explosions);
            renderer3d.setCombat(combat);
            renderer3d.setProjectedDefense(projectedWalls);
            renderer3d.setForceProjectors(forceProjectors);
            combat.setProjectedDefense(new fpv.combat.CompositeDefense(projectedWalls,forceProjectors));
            world3d.setUnitBarrier(forceProjectors.combine(projectedWalls::sweepUnit));
            unitAltitudes.setAircraftBarrier(world3d::traceUnitBarrier);
            unitAltitudes.setAircraftStructures(()->world3d.construction()==null?null:world3d.construction().grid(),world3d::surfaceHeight);
            unitAltitudes.setGroundSupport(new fpv.defense.LegTraversal(world3d,()->projectedWalls.settings().legStepMultiplier()));
            if(!Boolean.getBoolean("fpv.wallProfile")){
                var ground=new fpv.units.ground.GroundFamilyLocomotion(world3d);
                ground.setProjectedObstacle(projectedWalls::topAt);
                fpv.units.ground.GroundUnitProfiles.setNativeStepMultiplierSupplier(()->projectedWalls.settings().legStepMultiplier());
                unitAltitudes.setGroundFamily(ground);
            }
            factorySpawns.install();combat.install();
            network=new FpvNetwork(world3d,unitAltitudes,combat,explosions,renderer3d::surfaceHeight);
            renderer3d.supportEffects().setState(network.supportEffects());
            network.registerProjection(projectedWalls);
            network.registerForceProjectors(forceProjectors);
            network.install();
            construction=new fpv.construction.LayerRuntime(world3d);
            construction.install(network);
            input.setControlAction(unit->{
                var b=fpv.control.LayerTurretUnit.actual(unit);
                if(b!=null&&b.tile instanceof fpv.construction.LayerTile t){var c=construction.grid().at(t.x,t.y,t.layer,fpv.construction.LayerComponent.Kind.BLOCK);if(c!=null)construction.requestPossess(c.id());}
                else mindustry.gen.Call.unitControl(Vars.player,unit);
            },()->{if(Vars.player!=null&&construction.turretControls().owns(Vars.player)){construction.requestReleaseTurret();return true;}return false;});
            construction.setTurretAiming(combat.layerTurretAiming());
            construction.setUnitRelease(factorySpawns::releaseUpper);
            construction.setDefenseUpdate(combat::updateUpperDefense);
            construction.setDestructionEffect(blast->explosions.detonateXYZ(mindustry.game.Team.derelict,blast.x(),blast.y(),blast.z(),blast.spec()));
            forceProjectors.setUpperBuildings(()->construction.nativeBuildings().entries(),()->construction.grid().geometryRevision());
            combat.setUpperBuildings(()->construction.nativeBuildings().entries());
            layerQueries=new fpv.construction.LayerPhysicalQueries(construction::grid,construction::damage,construction::building);
            world3d.setConstruction(layerQueries);
            projectedWalls.setCoveredPredicate(b->construction.grid().columnsCovered(b.tile.x,b.tile.y,b.block.size));
            combat.setProjectedDefense(new fpv.combat.CompositeDefense(projectedWalls,forceProjectors,construction.baseShields(),layerQueries));
            renderer3d.setConstruction(construction);
            renderer3d.setFootSupportQueries(layerQueries);
            layerBuild=new fpv.construction.LayerBuildController(construction,input,renderer3d.constructionRenderer());
            input.setCommands(new fpv.input.FpvUnitCommands(input,world3d,renderer3d,camera,construction));
            Events.on(WorldLoadEvent.class,e->layerBuild.reset());
            Events.on(ResetEvent.class,e->layerBuild.reset());
            renderer3d.setCreatorFlightPredicate(u->{
                if(network.creatorFlight().active(u)||network.nativeBoost().active(u))return true;
                if(!UnitControlCapabilities.boostEligible(u))return false;
                var body=world3d.unitState(u);return body!=null&&body.flying();
            });
            input.setBoostOwnedSupplier(()->Vars.player!=null&&!Vars.player.dead()&&network.nativeBoost().active(Vars.player.unit()));
            separation=new AltitudeSeparation(Vars.unitPhysics,world3d::unitState,
                ()->(enabled||Vars.net.server())&&Vars.state.isGame()&&!Vars.net.client());
            separation.install();
            Events.run(Trigger.update, this::update);
            Events.run(Trigger.beforeGameUpdate,()->{
                if(!disposed&&!Vars.net.active()&&Vars.state.isGame()){
                    double dt=Math.max(0,Math.min(.1,arc.util.Time.delta/60.0));
                    network.creatorFlight().before(dt);network.nativeBoost().before(dt);unitAltitudes.captureGroundIntent(dt);
                }
            });
            Events.run(Trigger.afterGameUpdate, () -> {
                long profiled=fpv.profile.CameraPerformanceProfile.start();
                try{
                if(Vars.state.isGame()){
                    construction.update(arc.util.Time.delta/60.0);
                    if(!Vars.net.active()){network.creatorFlight().after();network.nativeBoost().after();unitAltitudes.update(arc.util.Time.delta/60.0);}
                    explosions.update(arc.util.Time.delta/60.0);
                    combat.update(arc.util.Time.delta/60.0);
                    projectedWalls.update(arc.util.Time.delta/60.0);
                    forceProjectors.update(arc.util.Time.delta/60.0);
                }
                if(enabled&&ready&&!Vars.player.dead()){
                    if(onlineFlight())network.copyLocalFlight(flight);
                    else if(Vars.player.unit().type.flying)motion.afterSimulation(Vars.player.unit(),flight.state());
                    else copyGroundPose(Vars.player.unit());
                    updateCamera();
                    updatePick();
                }
                // A resolved ram still hits its target if native combat killed the source this tick.
                if(enabled&&ready&&!onlineFlight()&&crashes.applyPending())motion.reset();
                }finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.SIM_ADAPTERS,profiled);}
            });
            Events.run(Trigger.postDraw, this::drawWorld);
            Events.run(Trigger.uiDrawEnd, this::afterUi);
            Events.on(WorldLoadEvent.class, event -> {releasePilot();combat.clear();projectedWalls.clear();forceProjectors.clear();renderer3d.invalidate();unitAltitudes.clear();explosions.clear();crashes.reset(); ready=false; unitId=-1; autoEnable=true;});
            Events.on(TileChangeEvent.class, event -> {renderer3d.invalidate(event.tile);projectedWalls.invalidate(event.tile);forceProjectors.invalidate(event.tile);});
            Events.on(TileFloorChangeEvent.class, event -> renderer3d.invalidate(event.tile));
            Events.on(TileOverlayChangeEvent.class, event -> renderer3d.invalidate(event.tile));
            Events.on(ResetEvent.class, event -> {setEnabled(false);combat.clear();projectedWalls.clear();forceProjectors.clear();unitAltitudes.clear();explosions.clear();crashes.reset(); ready=false; unitId=-1;});
            Events.on(ClientLoadEvent.class, event -> {
                settings();
                buildHud();
                new fpv.net.NetworkSmokeHarness(network,renderer3d).install();
                if(Boolean.getBoolean("fpv.cameraProfile")){Core.app.post(()->{
                    if(Boolean.getBoolean("fpv.cameraProfile.live"))new LiveCameraPerformanceHarness(this).start();
                    else new CameraPerformanceHarness(this).start();
                });}
                else if(Boolean.getBoolean("fpv.wallAi.smoke")){Core.app.post(()->new WallAiSmokeHarness(this).start());}
                else if(Boolean.getBoolean("fpv.layers.smoke")){Core.app.post(()->new LayerSmokeHarness(this).start());}
                else if(Boolean.getBoolean("fpv.wallProfile")){wallBenchmark=new WallBenchmarkHarness(this);Core.app.post(wallBenchmark::start);}
                else if (smokeMode) {smoke = new SmokeHarness(this); Core.app.post(smoke::start);}
            });
            Core.app.addListener(new ApplicationListener(){
                @Override public void pause(){if(mouse!=null)mouse.pause();restoreCursor();}
                @Override public void resume(){if(mouse!=null)mouse.resume();}
                @Override public void dispose(){close();}
                @Override public void exit(){if(mouse!=null)mouse.close();}
            });
            Log.info("[FPV] Native Java mod initialized against @. Profile: @",Version.buildString(),Vars.dataDirectory.absolutePath());
        } catch (Throwable failure) {
            Log.err("[FPV] Initialization failed; restoring normal input.",failure);
            if(input!=null)input.uninstallProjection();
            if(mouse!=null)mouse.close();
        }
    }

    private void settings() {
        Core.bundle.getProperties().put("keybind.fpv-layer-up.name","Higher construction floor");
        Core.bundle.getProperties().put("keybind.fpv-layer-down.name","Lower construction floor");
        Core.bundle.getProperties().put("setting.fpv-wall-view-distance.name","Wall projection visibility distance");
        Core.bundle.getProperties().put("setting.fpv-wall-fade-start.name","Wall projection fade begins");
        fpv.ui.GroundSettings.install();
        fpv.ui.LayerSettings.install(construction);
        if(!Core.settings.has("fpv-pov-angle"))Core.settings.put("fpv-pov-angle",
            Mathf.clamp(Core.settings.getInt("fpv-tilt",(int)RuntimeConfig.CAMERA_TILT)+90,0,180));
        Core.bundle.getProperties().put("setting.fpv-time.name","Build Mode simulation speed");
        Core.bundle.getProperties().put("setting.fpv-fov.name","FPV field of view");
        Core.bundle.getProperties().put("setting.fpv-pov-angle.name","FPV angle (0 down / 90 forward / 180 up)");
        Core.bundle.getProperties().put("setting.fpv-build-speed.name","Build Mode drone speed");
        Core.bundle.getProperties().put("setting.fpv-flight-speed.name","Flight speed (all presets)");
        Core.bundle.getProperties().put("setting.fpv-flight-mode.name","Flight controls (F9)");
        CameraView.selected();
        CameraView.smoothingKey.load();
        Core.bundle.getProperties().put("keybind.fpv-native-boost-toggle.name","Ground / native boost flight (F2)");
        fpv.input.BoostModeBinding.toggle.load(); // register and restore the native Controls preference
        Core.bundle.getProperties().put("keybind.fpv-camera-smoothing-toggle.name","Toggle camera smoothing (modifier in FPV settings)");
        Core.bundle.getProperties().put("category.FPV camera.name","FPV camera");
        Core.bundle.getProperties().put("setting.fpv-camera-smoothing.name","Camera Smoothing");
        Core.bundle.getProperties().put("setting.fpv-camera-smoothing-modifier.name","Camera smoothing shortcut modifier");
        Core.bundle.getProperties().put("setting.fpv-camera-view.name","Flight camera (F7)");
        Core.bundle.getProperties().put("setting.fpv-camera-distance.name","Third-person distance (Alt + wheel; scales with unit)");
        Core.bundle.getProperties().put("setting.fpv-mouse.name","FPV mouse rate sensitivity");
        Core.bundle.getProperties().put("setting.fpv-pitch-rate.name","Pitch rate (deg/s)");
        Core.bundle.getProperties().put("setting.fpv-roll-rate.name","Roll rate (deg/s)");
        Core.bundle.getProperties().put("setting.fpv-yaw-rate.name","Yaw rate (deg/s)");
        Core.bundle.getProperties().put("setting.fpv-expo.name","Original cubic rate expo");
        Core.bundle.getProperties().put("setting.fpv-build-tilt.name","Build camera tilt");
        Core.bundle.getProperties().put("setting.fpv-sky-clock.name","Sky clock (visual only)");
        Core.bundle.getProperties().put("setting.fpv-day-minutes.name","Visual cycle length");
        Core.bundle.getProperties().put("setting.fpv-blast-radius.name","Small-unit base blast radius (tiles)");
        Core.bundle.getProperties().put("setting.fpv-blast-damage.name","Small-unit base blast damage");
        Core.bundle.getProperties().put("setting.fpv-blast-style.name","Crash burst style");
        Core.bundle.getProperties().put("setting.fpv-blast-sound.name","Crash burst sound");
        Core.bundle.getProperties().put("setting.fpv-performance-preset.name","Flight performance");
        Core.bundle.getProperties().put("setting.fpv-power.name","Custom: motor power");
        Core.bundle.getProperties().put("setting.fpv-speed-reference.name","Custom: practical speed reference");
        Core.bundle.getProperties().put("setting.fpv-mass.name","Custom: unit mass multiplier");
        Core.bundle.getProperties().put("setting.fpv-drag.name","Custom: aerodynamic drag");
        Core.bundle.getProperties().put("setting.fpv-response.name","Custom: motor/rate response time");
        Core.bundle.getProperties().put("setting.fpv-acceleration.name","Custom: thrust acceleration reference");
        Core.bundle.getProperties().put("setting.fpv-rate-scale.name","Custom: angular rate multiplier");
        Core.bundle.getProperties().put("setting.fpv-ram-strength.name","Impact damage strength");
        Core.bundle.getProperties().put("setting.fpv-threat-markers.name","Show Unit Threat Markers");
        Vars.ui.settings.addCategory("FPV flight", table -> {
            table.checkPref("fpv-threat-markers",true);
            table.sliderPref("fpv-wall-view-distance",100,20,2000,10,i->i+" tiles (visual only)");
            table.sliderPref("fpv-wall-fade-start",80,0,1990,10,i->i+" tiles (limited by visibility)");
            table.add("Multiplayer: flight physics/speed belong to the host.\nCamera, mode and mouse settings remain local.").wrap().width(460).row();
            table.add("F2: Ground / native Boost (rebind in Controls).\nF9: Acro / Stabilized; E/Q altitude in Stabilized.\nBoost requires the unit's natural ability; Creator Flight is separate.").wrap().width(460).row();
            table.check("Allow ground-unit flight",Core.settings.getBool(CreatorFlightSystem.SETTING,false),value->{
                if(!Vars.net.client())Core.settings.put(CreatorFlightSystem.SETTING,value);
            }).update(box->{box.setDisabled(Vars.net.client());box.setChecked(network.creatorFlight().allowed());}).row();
            table.add("Ground units: F8 toggles creator flight when the host allows it.\nQ / E: walker stance on ground, descent / ascent in creator flight.").wrap().width(460).row();
            String[] presets={"Cinematic","Freestyle","Race","Insane","Custom"};
            table.sliderPref("fpv-performance-preset",1,0,4,1,i->presets[Mathf.clamp(i,0,4)]);
            table.sliderPref("fpv-flight-speed",100,25,300,5,i->i+"%");
            table.sliderPref("fpv-flight-mode",0,0,1,1,i->i==0?"Acro":"Stabilized / independent camera");
            table.sliderPref("fpv-camera-view",0,0,2,1,CameraView::label);
            table.sliderPref(CameraView.DISTANCE,100,25,400,5,i->i+"%");
            table.checkPref(CameraView.SMOOTHING,false);
            table.sliderPref(CameraView.MODIFIER,1,0,3,1,CameraView::modifierLabel);
            table.add("Default: Alt + F7. Rebind the key in Controls > FPV camera.\nOFF follows immediately; ON animates the local camera rig.").wrap().width(460).row();
            table.sliderPref("fpv-power",240,25,2000,25,i->i+"%");
            table.sliderPref("fpv-speed-reference",75,10,400,5,i->i+" m/s (before unit modifiers)");
            table.sliderPref("fpv-mass",100,25,1000,25,i->i+"%");
            table.sliderPref("fpv-drag",100,10,400,10,i->i+"%");
            table.sliderPref("fpv-response",100,25,300,25,i->i+"% time (lower = faster)");
            table.sliderPref("fpv-acceleration",95,10,1200,5,i->i+" m/s^2");
            table.sliderPref("fpv-rate-scale",100,25,250,25,i->i+"%");
            table.sliderPref("fpv-time",2,0,5,1,i->RuntimeConfig.TIME_PERCENT[i]+"%");
            table.sliderPref("fpv-build-speed",(int)NavigationConfig.DEFAULT_SPEED_MPS,0,(int)NavigationConfig.MAX_SPEED_MPS,1,i->i+" m/s");
            table.sliderPref("fpv-fov",(int)RuntimeConfig.FOV,0,180,1,i->i+" deg");
            table.sliderPref("fpv-pov-angle",(int)RuntimeConfig.CAMERA_TILT+90,0,180,1,i->i+" deg");
            table.sliderPref("fpv-mouse",100,20,250,5,i->i+"%");
            var profile=FlightProfiles.create();
            table.sliderPref("fpv-pitch-rate",(int)profile.pitchRateDegreesPerSecond,20,1000,20,i->i+" deg/s");
            table.sliderPref("fpv-roll-rate",(int)profile.rollRateDegreesPerSecond,20,1000,20,i->i+" deg/s");
            table.sliderPref("fpv-yaw-rate",(int)profile.yawRateDegreesPerSecond,20,1000,20,i->i+" deg/s");
            table.sliderPref("fpv-expo",(int)(profile.rateExpo*100),0,100,5,i->i+"%");
            table.sliderPref("fpv-build-tilt",(int)RuntimeConfig.BUILD_CAMERA_TILT,-75,-10,1,i->i+" deg");
            table.sliderPref("fpv-sky-clock",0,0,1,1,i->i==0?"Native / map auto":"Simulation cycle");
            table.sliderPref("fpv-day-minutes",(int)(fpv.generated.EnvironmentConfig.CYCLE_SECONDS/60),2,60,2,i->i+" min");
            table.sliderPref("fpv-blast-radius",(int)(CrashConfig.EXPLOSION_RADIUS_TILES*10),5,60,5,i->i/10f+" tiles");
            table.sliderPref("fpv-blast-damage",(int)CrashConfig.EXPLOSION_DAMAGE,0,100,2,i->Integer.toString(i));
            table.sliderPref("fpv-blast-style",0,0,1,1,i->i==0?"Sparks":"Pulse");
            table.checkPref("fpv-blast-sound",true);
            table.sliderPref("fpv-ram-strength",100,0,300,10,i->i+"%");
        });
        String[][] combatNames={
            {"fpv-ram-cap-units","Maximum ram damage: ordinary units"},
            {"fpv-ram-cap-alpha","Maximum ram damage: Alpha"},
            {"fpv-ram-cap-beta","Maximum ram damage: Beta"},
            {"fpv-ram-cap-gamma","Maximum ram damage: Gamma"},
            {"fpv-aa-vertical-multiplier","AA vertical reach / native horizontal range"},
            {"fpv-ground-range","Ground weapon horizontal range"},
            {"fpv-dagger-range","Dagger family horizontal range"},
            {"fpv-naval-range","Naval weapon horizontal range"},
            {"fpv-unit-vertical-range","Ground/naval weapon vertical reach"},
            {"fpv-aircraft-fixed-pitch","Aircraft / purple walker fixed gun pitch limit"},
            {"fpv-aircraft-gimbal-pitch","Aircraft / purple walker articulated gun pitch limit"},
            {"fpv-aircraft-switch-degrees","Aircraft / purple walker mount switching dead zone"},
            {"fpv-force-aircraft-collision","Aircraft blocked by Force Projectors"},
            {"fpv-wall-hp-percent","Projected wall HP / native wall HP"},
            {"fpv-wall-segments","Projected wall upper segments"},
            {"fpv-wall-regen-delay","Wall projection regeneration delay"},
            {"fpv-wall-regen-permille","Wall projection regeneration per second"},
            {"fpv-wall-unit-collision","Projected walls block piloted aircraft"},
            {"fpv-range-knee-percent","Range falloff: transition distance"},
            {"fpv-range-end-percent","Range falloff: harmless distance"},
            {"fpv-range-knee-power-percent","Range falloff: damage at transition"},
            {"fpv-leg-step-percent","Legged unit obstacle step height"}
        };
        for(var item:combatNames)Core.bundle.getProperties().put("setting."+item[0]+".name",item[1]);
        Vars.ui.settings.addCategory("3D combat",table->{
            table.add("These are host/server rules in multiplayer.").wrap().width(460).row();
            table.add("Ram limits include direct impact and the custom crash burst.").wrap().width(460).row();
            table.sliderPref("fpv-ram-cap-units",(int)CrashConfig.RAM_ORDINARY_DAMAGE_CAP,0,(int)CrashConfig.RAM_DAMAGE_CAP_MAX,10,i->i+" HP");
            table.sliderPref("fpv-ram-cap-alpha",(int)CrashConfig.RAM_ALPHA_DAMAGE_CAP,0,(int)CrashConfig.RAM_DAMAGE_CAP_MAX,10,i->i+" HP");
            table.sliderPref("fpv-ram-cap-beta",(int)CrashConfig.RAM_BETA_DAMAGE_CAP,0,(int)CrashConfig.RAM_DAMAGE_CAP_MAX,10,i->i+" HP");
            table.sliderPref("fpv-ram-cap-gamma",(int)CrashConfig.RAM_GAMMA_DAMAGE_CAP,0,(int)CrashConfig.RAM_DAMAGE_CAP_MAX,10,i->i+" HP");
            table.sliderPref("fpv-aa-vertical-multiplier",5,1,20,1,i->i+"x");
            table.sliderPref("fpv-ground-range",2,1,20,1,i->i+"x native");
            table.sliderPref("fpv-dagger-range",3,1,20,1,i->i+"x native");
            table.sliderPref("fpv-naval-range",3,1,20,1,i->i+"x native");
            table.sliderPref("fpv-unit-vertical-range",5,1,20,1,i->i+"x native");
            table.sliderPref("fpv-aircraft-fixed-pitch",fpv.combat.AircraftWeaponCodec.Limits.DEFAULT.fixedPitch(),5,60,1,i->String.format(java.util.Locale.ROOT,"%.1f deg",fpv.combat.WeaponPitchLimits.expanded(i)));
            table.sliderPref("fpv-aircraft-gimbal-pitch",fpv.combat.AircraftWeaponCodec.Limits.DEFAULT.gimbalPitch(),5,60,1,i->String.format(java.util.Locale.ROOT,"%.1f deg",fpv.combat.WeaponPitchLimits.expanded(i)));
            table.sliderPref("fpv-aircraft-switch-degrees",fpv.combat.AircraftWeaponCodec.Limits.DEFAULT.hysteresis(),0,15,1,i->i+" deg");
            table.check("Aircraft blocked by Force Projectors",forceProjectors.aircraftCollisionEnabled(),value->{
                if(!Vars.net.client())Core.settings.put("fpv-force-aircraft-collision",value);
            }).update(box->{box.setDisabled(Vars.net.client());box.setChecked(forceProjectors.aircraftCollisionEnabled());}).row();
            table.sliderPref("fpv-range-knee-percent",120,105,150,5,i->i+"% of native range");
            table.sliderPref("fpv-range-end-percent",150,150,300,5,i->i+"% of native range");
            table.sliderPref("fpv-range-knee-power-percent",10,0,100,5,i->i+"%");
            table.sliderPref("fpv-wall-hp-percent",85,75,100,1,i->i+"%");
            table.sliderPref("fpv-wall-segments",5,1,10,1,Integer::toString);
            table.sliderPref("fpv-wall-regen-delay",8,0,60,1,i->i+" s");
            table.sliderPref("fpv-wall-regen-permille",25,0,200,5,i->(i/10f)+"% / s");
            table.checkPref("fpv-wall-unit-collision",false);
            table.sliderPref("fpv-leg-step-percent",150,50,300,10,i->i+"%");
        });
    }

    private void buildHud() {
        hud=new FpvHud(new FpvHud.Source(){
            public boolean active(){return enabled&&ready&&Vars.state.isGame()&&!Vars.player.dead();}
            public boolean buildMode(){return buildMode;}
            public boolean suspended(){return inputSuspended;}
            public double throttle(){return throttle;}
            public int buildTimePercent(){return Vars.net.active()?100:RuntimeConfig.TIME_PERCENT[timeIndex];}
            public FlightController.State state(){return flight.state();}
            public Camera3D camera(){return camera;}
            public String status(){String cameraFeedback=CameraView.feedback();String text=!cameraFeedback.isEmpty()?cameraFeedback:arc.util.Time.millis()<ramFeedbackUntil?ramFeedbackText:status;return mouse!=null&&!mouse.nativeCapture()?text+" / HOST MOUSE · arrows at edges":text;}
            public String skyClock(){return renderer3d.environment().clockLabel();}
            public String skyPhase(){return renderer3d.environment().phaseLabel();}
            public String flightMode(){return pilotModeLabel();}
            public boolean commandMode(){return input.commandActive();}
            public boolean thirdPerson(){return !buildMode&&CameraView.selected()!=CameraView.FIRST;}
            public String cameraView(){return CameraView.label(CameraView.selected()).toUpperCase(java.util.Locale.ROOT);}
            public boolean rearView(){return !buildMode&&CameraView.selected()!=CameraView.FIRST;}
            public String constructionLayer(){return layerBuild==null?"Z0":layerBuild.label();}
        },FlightProfiles.create().metersPerTile);
        hud.install();
        input.commands().install();
        buildControls=new BuildControls(()->enabled&&ready&&buildMode&&!input.commandActive()&&Vars.state.isGame()&&!Vars.player.dead(),layerBuild,construction);
    }

    private void update() {
        long profiled=fpv.profile.CameraPerformanceProfile.start();
        try{updateProfiled();}finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.CONTROLS,profiled);}
    }
    private void updateProfiled() {
        if(disposed||input==null)return;
        if(wallBenchmark!=null){wallBenchmark.update();return;}
        try {
            if(hud!=null)hud.update();
            var impact=crashes.lastFeedback();
            if(impact!=null&&impact.sequence()!=ramFeedbackSequence){
                ramFeedbackSequence=impact.sequence();ramFeedbackUntil=arc.util.Time.millis()+2200;
                ramFeedbackText=impact.friendlyProtected()?"RAM / ally protected":
                    "RAM / "+impact.targetName()+" / -"+Math.round(impact.healthLoss())+" HP"
                    +(impact.shieldLoss()>0?" / -"+Math.round(impact.shieldLoss())+" shield":"")+(impact.killed()?" / destroyed":"");
            }
            applyBuildPause();
            fpv.combat.CombatSettings.apply(combat);
            combat.syncActive();
            if(separation!=null)separation.syncActive();
            if(!Vars.state.isGame()||(onlineFlight()&&!network.allowed())) {
                if(enabled)setEnabled(false);
                if(network!=null)network.submit(NetworkControls.hold());
                return;
            }
            boolean cameraToggle=cameraSmoothingShortcut();
            if(!cameraToggle&&Core.input.keyTap(KeyCode.f5)&&!Core.scene.hasField()) {autoEnable=false;setEnabled(!enabled);}
            if(Vars.player==null||Vars.player.dead()) {releasePilot();setBuildMode(false);if(mouse!=null)mouse.setCaptured(false);input.setSuspended(true);restoreCursor();ready=false;if(network!=null)network.submit(NetworkControls.hold());return;}
            Unit unit=Vars.player.unit();
            if(unit.id!=unitId||unit!=trackedPilot||!ready) {resetPose();if(autoEnable)setEnabled(true);}
            if(!enabled){submitNativeControls();return;}
            if(!onlineFlight()){
                var performance=flightSettings.configure(flight,unit);
                crashes.setControlledMass(performance.massKg());
            }else network.copyLocalFlight(flight);
            if(!onlineFlight()&&network.nativeBoost().active(unit))network.nativeBoost().copy(unit,flight);
            if(!cameraToggle&&Core.input.keyTap(KeyCode.tab)&&!Core.scene.hasKeyboard()&&!Core.scene.hasDialog())setBuildMode(!buildMode);
            if(!cameraToggle&&Core.input.keyTap(KeyCode.f6)&&!Core.scene.hasKeyboard())resetPose();
            if(!Core.scene.hasKeyboard()&&!Core.scene.hasDialog()){
                if(!cameraToggle&&!buildMode&&!input.commandActive()&&!input.commandPointerFence()&&input.originalInput().keyTap(fpv.input.BoostModeBinding.toggle)){
                    if(UnitControlCapabilities.boostEligible(unit)&&!network.creatorFlight().active(unit)){
                        creatorRequested=false;
                        boostRequested=!boostRequested;
                        status=boostRequested?"NATIVE BOOST / F9 flight controls":"LANDING / waiting for physical support";
                    }else status=UnitControlCapabilities.boostEligible(unit)?"Land Creator Flight before ordinary boost":"This unit has no native boost capability";
                }
                if(!cameraToggle&&Core.input.keyTap(KeyCode.f7))CameraView.cycle();
                if(!cameraToggle&&Core.input.keyTap(KeyCode.f9))Core.settings.put("fpv-flight-mode",flightMode==0?1:0);
                if(!cameraToggle&&Core.input.keyTap(KeyCode.f8)&&UnitControlCapabilities.creatorEligible(unit)){
                    boostRequested=false;
                    creatorRequested=!creatorRequested&&network.creatorFlight().allowed();
                    status=creatorRequested?"Creator flight / Q E altitude":network.creatorFlight().allowed()?"Returning to ground":"Ground-unit flight is disabled by host";
                }
            }
            if(!network.creatorFlight().allowed())creatorRequested=false;
            syncFlightMode();
            if(!onlineFlight()&&unit.type.flying)motion.syncFromSimulation(unit,flight.state());
            int newIndex=Mathf.clamp(Core.settings.getInt("fpv-time",2),0,5);
            if(timeIndex!=newIndex) {timeIndex=newIndex;applyBuildPause();}
            applyBuildPause();
            boolean suspended=Core.scene.hasDialog()||Core.scene.hasKeyboard()||(!smokeMode&&!mouse.focused());
            inputSuspended=suspended;
            input.setSuspended(suspended);
            input.syncCommands();boolean commanding=input.commandActive();
            updateObserverZoom(suspended);
            if(hud!=null)hud.update();
            if(!smokeMode)mouse.setCaptured(!buildMode&&!commanding&&!input.commandPointerFence()&&!suspended&&!Vars.state.isPaused());
            mouse.poll();
            double dt=Math.max(0,Math.min(.1,clock.rawSeconds()));
            if(commanding&&!onlineFlight()){
                var held=flight.state();held.vx=held.vy=held.vz=held.wx=held.wy=held.wz=0;throttle=Math.min(1,flight.profile().hoverThrottle());
                unit.vel.setZero();
            }
            if(!unit.type.flying&&!network.nativeBoost().active(unit)){updateGroundControl(unit,dt,suspended);return;}
            boolean bodyOwned=onlineFlight()||network.nativeBoost().active(unit);
            double commandPitch=0,commandRoll=0,commandYaw=0;
            if(!buildMode&&!commanding&&!input.commandPointerFence()&&!suspended&&!Vars.state.isPaused()&&!smokeMode) {
                if(flightMode==1){
                    double look=NavigationConfig.FREELOOK_DEGREES_PER_PIXEL*Core.settings.getInt("fpv-mouse",100)/100.0;
                    freeLook.look(mouse.deltaX()*look,-mouse.deltaY()*look);
                    fallbackLook(dt);
                    if(!bodyOwned)navigateStabilized(dt,down(KeyCode.w)-down(KeyCode.s),down(KeyCode.d)-down(KeyCode.a),
                        Math.max(down(KeyCode.e),down(KeyCode.pageUp))-Math.max(down(KeyCode.q),down(KeyCode.pageDown)));
                }else{
                throttle=Math.max(0,Math.min(1,throttle+(down(KeyCode.w)-down(KeyCode.s))*RuntimeConfig.THROTTLE_RATE*dt));
                double sensitivity=RuntimeConfig.MOUSE_RATE_SCALE*Core.settings.getInt("fpv-mouse",100)/100.0;
                double sampleSeconds=Math.max(.001,dt);
                double pitch=clamp(mouse.deltaY()/sampleSeconds*sensitivity+down(KeyCode.down)-down(KeyCode.up));
                double roll=clamp(mouse.deltaX()/sampleSeconds*sensitivity+down(KeyCode.right)-down(KeyCode.left));
                double yaw=down(KeyCode.d)-down(KeyCode.a);
                commandPitch=pitch;commandRoll=roll;commandYaw=yaw;
                if(!bodyOwned)flight.step(dt,throttle,pitch,roll,yaw,flightCollision);
                }
            }
            if(buildMode&&!commanding&&!input.commandPointerFence()&&!suspended&&!smokeMode&&!bodyOwned){
                navigateBuild(dt,down(KeyCode.w)-down(KeyCode.s),down(KeyCode.d)-down(KeyCode.a),
                    down(KeyCode.e)-down(KeyCode.q),down(KeyCode.end)-down(KeyCode.home));
            }
            if(smoke!=null)smoke.update();
            // A native world load or control transfer may replace the pilot during callbacks.
            if(!ready||Vars.player==null||Vars.player.dead()||unit!=Vars.player.unit())return;
            if(enabled&&!unit.dead&&unit==Vars.player.unit()&&!Vars.state.isPaused()&&!bodyOwned) {
                motion.beforeSimulation(unit,flight.state(),arc.util.Time.delta,suspended);
                flight.getForward(vector);
                unit.rotation=Mathf.angle((float)vector[0],(float)-vector[2]);
            }
            updateCamera();
            updatePick();
            if(bodyOwned){
                var aim=controlledAim();
                boolean commandFence=commanding||input.commandPointerFence();
                NetworkControls controls=new NetworkControls(!suspended,(buildMode||commanding||input.commandPointerFence())?2:flightMode,throttle,commandPitch,commandRoll,commandYaw,
                    commandFence?0:down(KeyCode.w)-down(KeyCode.s),commandFence?0:down(KeyCode.d)-down(KeyCode.a),
                    commandFence?0:buildMode?down(KeyCode.e)-down(KeyCode.q):Math.max(down(KeyCode.e),down(KeyCode.pageUp))-Math.max(down(KeyCode.q),down(KeyCode.pageDown)),
                    commandFence?0:down(KeyCode.end)-down(KeyCode.home),freeLook.headingRadians(),aim.x(),aim.y(),aim.z(),
                    !suspended&&!buildMode&&!commanding&&!input.commandPointerFence()&&input.originalInput().keyDown(mindustry.input.Binding.select),false,boostRequested);
                if(smokeMode&&smokeBoostControls!=null)controls=smokeBoostControls;
                if(onlineFlight())network.submit(controls);else {network.controlGroundFlight(Vars.player,unit,controls);GroundedPilot.weapons(Vars.player,unit,controls);}
            }
        } catch(Throwable failure) {
            Log.err("[FPV] Controller error; returning to native 2D",failure);
            setEnabled(false);
            autoEnable=false;
            status="FPV stopped after an error; see log";
            if(smoke!=null)smoke.fail(failure.toString());
        }
    }

    private static int down(KeyCode key){return Core.input.keyDown(key)?1:0;}
    private String pilotModeLabel(){
        if(Vars.player==null||Vars.player.dead())return "NO PILOT";
        Unit unit=Vars.player.unit();
        if(unit.type.flying)return flightMode==1?"STABILIZED":"ACRO";
        if(network.nativeBoost().landingBlocked(unit))return "LANDING BLOCKED / MOVE TO CLEAR SUPPORT";
        if(network.nativeBoost().landing(unit))return "BOOST LANDING";
        if(network.nativeBoost().active(unit))return flightMode==1?"BOOST / STABILIZED":"BOOST / ACRO";
        if(network.creatorFlight().active(unit))return network.creatorFlight().landing(unit)?"AUTO LANDING":"CREATOR FLIGHT";
        if(UnitControlCapabilities.boostEligible(unit))return "GROUND / F2 BOOST";
        if(network.creatorFlight().landing(unit))return "AUTO LANDING";
        var pose=world3d.unitState(unit);
        if(!UnitControlCapabilities.stationary(unit)&&pose!=null&&pose.flying())return "CREATOR FLIGHT";
        return UnitControlCapabilities.movement(unit).name();
    }
    private void updateGroundControl(Unit unit,double dt,boolean suspended){
        boolean commanding=input.commandActive()||input.commandPointerFence();
        if(!buildMode&&!commanding&&!suspended&&!Vars.state.isPaused()&&!smokeMode){
            double look=NavigationConfig.FREELOOK_DEGREES_PER_PIXEL*Core.settings.getInt("fpv-mouse",100)/100.0;
            freeLook.look(mouse.deltaX()*look,-mouse.deltaY()*look);
            fallbackLook(dt);
        }
        if(!onlineFlight())copyGroundPose(unit);
        updateCamera();updatePick();var aim=controlledAim();
        boolean active=!commanding&&!suspended&&!Vars.state.isPaused();
        NetworkControls controls=new NetworkControls(active,buildMode?2:3,0,0,0,0,
            commanding?0:down(KeyCode.w)-down(KeyCode.s),commanding?0:down(KeyCode.d)-down(KeyCode.a),
            commanding?0:buildMode?down(KeyCode.e)-down(KeyCode.q):Math.max(down(KeyCode.e),down(KeyCode.pageUp))-Math.max(down(KeyCode.q),down(KeyCode.pageDown)),
            0,freeLook.headingRadians(),aim.x(),aim.y(),aim.z(),
            !buildMode&&active&&input.originalInput().keyDown(mindustry.input.Binding.select),creatorRequested,boostRequested);
        if(smokeMode&&smokeBoostControls!=null)controls=smokeBoostControls;
        if(onlineFlight())network.submit(controls);
        else {
            network.controlGroundFlight(Vars.player,unit,controls);
            if(network.creatorFlight().active(unit)||network.nativeBoost().active(unit))GroundedPilot.weapons(Vars.player,unit,controls);
            else groundPilot.step(Vars.player,unit,controls,unitAltitudes,Math.max(0,Math.min(.1,arc.util.Time.delta/60.0)));
        }
        if(smoke!=null)smoke.update();
    }
    private void copyGroundPose(Unit unit){
        if(network!=null&&(network.nativeBoost().copy(unit,flight)||network.creatorFlight().copy(unit,flight)))return;
        var pose=UnitControlCapabilities.stationary(unit)?GroundedPilot.fixedPose(unit,world3d):unitAltitudes.unitState(unit);
        if(pose==null)return;var s=flight.state();s.x=pose.x();s.z=-pose.y();s.y=pose.z();s.vx=pose.vx();s.vy=pose.vz();s.vz=-pose.vy();
        double yaw=Math.toRadians(unit.rotation-90)*.5;s.qx=s.qz=0;s.qy=Math.sin(yaw);s.qw=Math.cos(yaw);s.wx=s.wy=s.wz=0;
    }
    private void releasePilot(){
        observerCamera.reset();
        if(trackedPilot!=null&&!onlineFlight()){
            if(network!=null){network.creatorFlight().release(trackedPilot);network.nativeBoost().release(trackedPilot);}
            unitAltitudes.setGroundStanceControl(trackedPilot,false,0,0);motion.release(trackedPilot,flight.state());
        }
        trackedPilot=null;creatorRequested=false;boostRequested=false;
    }
    private static double clamp(double v){return Math.max(-1,Math.min(1,v));}
    private boolean onlineFlight(){return network!=null&&network.controlsLocalFlight();}
    private boolean networkReady(){return Vars.net.active()&&network!=null&&network.allowed();}
    private WorldCoordinates.Point networkAim(Unit unit){return network==null?null:network.aim(unit);}
    private void submitNativeControls(){
        if(!onlineFlight()||Vars.player==null||Vars.player.dead())return;
        boolean focused=Core.scene.hasKeyboard()||Core.scene.hasDialog()||(!smokeMode&&!mouse.focused());
        Unit unit=Vars.player.unit();
        double dx=input.originalInput().mouseWorldX()-unit.x,dy=input.originalInput().mouseWorldY()-unit.y;
        double length=Math.hypot(dx,dy);if(length<1e-6){dx=Math.cos(Math.toRadians(unit.rotation));dy=Math.sin(Math.toRadians(unit.rotation));length=1;}
        network.submit(new NetworkControls(!focused,3,0,0,0,0,focused?0:down(KeyCode.w)-down(KeyCode.s),focused?0:down(KeyCode.d)-down(KeyCode.a),0,0,0,
            dx/length,dy/length,0,!focused&&input.originalInput().keyDown(mindustry.input.Binding.select),false,
            !focused&&UnitControlCapabilities.boostEligible(unit)&&input.originalInput().keyDown(mindustry.input.Binding.boost)));
    }

    void resetPose() {
        observerCamera.reset();
        if(Vars.player==null||Vars.player.dead()){releasePilot();ready=false;unitId=-1;return;}
        Unit next=Vars.player.unit();
        boolean transferred=trackedPilot!=next;
        if(transferred)releasePilot();
        trackedPilot=next;
        motion.reset();
        crashes.reset();
        Unit unit=Vars.player.unit();unitId=unit.id;
        if(transferred&&UnitControlCapabilities.boostEligible(unit)&&!network.creatorFlight().active(unit)){
            var inherited=world3d.unitState(unit);boostRequested=inherited!=null&&inherited.flying();
            if(boostRequested&&!onlineFlight())network.controlGroundFlight(Vars.player,unit,new NetworkControls(true,1,0,0,0,0,0,0,0,0,
                Math.toRadians(90-unit.rotation),Math.cos(Math.toRadians(unit.rotation)),Math.sin(Math.toRadians(unit.rotation)),0,false,false,true));
        }
        flight.reset(unit.x/Vars.tilesize,renderer3d.surfaceHeight(unit.x/Vars.tilesize,-unit.y/Vars.tilesize)+RuntimeConfig.START_HEIGHT,-unit.y/Vars.tilesize,90-unit.rotation);
        if(!unit.type.flying)copyGroundPose(unit);
        if(!onlineFlight()){
            var performance=flightSettings.configure(flight,unit);crashes.setControlledMass(performance.massKg());
            if(unit.type.flying)motion.syncFromSimulation(unit,flight.state());
        }else network.copyLocalFlight(flight);
        throttle=Math.min(1,flight.profile().hoverThrottle());
        flightMode=(unit.type.flying||network.nativeBoost().active(unit))?Mathf.clamp(Core.settings.getInt("fpv-flight-mode",0),0,1):1;
        flight.getForward(vector);freeLook.align(vector[0],vector[1],vector[2]);
        ready=true;
        updateCamera();
        pickedFlightAim=null;
        if(separation!=null)separation.rebase();
    }

    void setEnabled(boolean value) {
        boolean entering=value&&!enabled;
        if(!value&&enabled)releasePilot();
        if(!value)setBuildMode(false);
        enabled=value;
        combat.syncActive();
        if(separation!=null)separation.syncActive();
        if(input!=null)input.setFpvEnabled(value);
        if(!value&&mouse!=null)mouse.setCaptured(false);
        if(entering&&Vars.player!=null&&!Vars.player.dead())resetPose();
        if(!value)restoreCursor();
        if(hud!=null)hud.update();
        Log.info("[FPV] Presentation: @",value?"3D FPV":"native 2D");
    }

    void setBuildMode(boolean value) {
        if(buildMode==value)return;
        if(value&&!onlineFlight()){
            pausedBeforeBuild=Vars.state.isPaused();
            flight.getForward(vector);
            double halfYaw=-Math.atan2(vector[0],-vector[2])*.5;
            var state=flight.state();
            state.qx=state.qz=0;state.qy=Math.sin(halfYaw);state.qw=Math.cos(halfYaw);
            state.vx=state.vy=state.vz=state.wx=state.wy=state.wz=0;
        }else if(!onlineFlight()){
            var state=flight.state();state.vx=state.vy=state.vz=state.wx=state.wy=state.wz=0;
            if(Vars.player!=null&&!Vars.player.dead())Vars.player.unit().vel.setZero();
            motion.reset();
            if(Vars.player!=null&&!Vars.player.dead())motion.syncFromSimulation(Vars.player.unit(),state);
        }
        buildMode=value;
        if(!value&&layerBuild!=null)layerBuild.endInteraction();
        if(input!=null)input.setBuildMode(value);
        if(mouse!=null)mouse.setCaptured(false);
        restoreCursor();
        applyBuildPause();
        if(hud!=null)hud.update();
    }

    void applyBuildPause() {
        if(!Vars.state.isGame()||Vars.net.active()){buildOwnsPause=false;return;}
        boolean zero=enabled&&buildMode&&RuntimeConfig.TIME_PERCENT[timeIndex]==0;
        if(zero&&!Vars.state.isPaused()) {Vars.state.set(State.paused);buildOwnsPause=true;}
        if(!zero&&buildOwnsPause&&!Core.scene.hasDialog()) {
            if(!pausedBeforeBuild)Vars.state.set(State.playing);
            buildOwnsPause=false;
        }
    }

    double timeScale(){return enabled&&buildMode&&!Vars.net.active()?RuntimeConfig.TIME_PERCENT[timeIndex]/100.0:1;}

    void updateCamera() {
        updateCamera(clock==null?1/60.0:Math.max(0,clock.rawSeconds()));
    }
    boolean cameraSmoothingShortcut(){
        return enabled&&Vars.player!=null&&!Vars.player.dead()&&!Core.scene.hasKeyboard()&&!Core.scene.hasDialog()
            &&(smokeMode||mouse.focused())&&CameraView.handleShortcut(input.originalInput());
    }
    void updateObserverZoom(boolean suspended){
        if(CameraView.zoomAllowed(buildMode,suspended,input.commandActive(),input.commandPointerFence(),CameraView.selected(),input.originalInput().alt())
            &&!Vars.state.isPaused()&&!Core.scene.hasMouse()&&!Core.scene.hasScroll())CameraView.zoom(input.originalInput().axisTap(mindustry.input.Binding.zoom));
    }
    void updateCamera(double cameraSeconds) {
        long profiled=fpv.profile.CameraPerformanceProfile.start();
        try{updateCameraProfiled(cameraSeconds);}finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.CAMERA,profiled);}
    }
    void updateCameraProfiled(double cameraSeconds) {
        var s=flight.state();
        boolean smoothing=CameraView.smoothing();
        camera.position.set((float)s.x,(float)s.y,(float)s.z);
        if(!buildMode&&flightMode==1)freeLook.forward(vector);else flight.getForward(vector);
        cameraForward.set((float)vector[0],(float)vector[1],(float)vector[2]);
        if(!buildMode&&flightMode==1)freeLook.up(vector);else flight.getUp(vector);
        cameraUp.set((float)vector[0],(float)vector[1],(float)vector[2]);
        float tilt=(float)(buildMode?Core.settings.getInt("fpv-build-tilt",(int)RuntimeConfig.BUILD_CAMERA_TILT):
            ViewSettings.tiltFromAngle(Core.settings.getInt("fpv-pov-angle",(int)RuntimeConfig.CAMERA_TILT+90)))*Mathf.degRad;
        camera.direction.set(cameraForward).scl(Mathf.cos(tilt)).mulAdd(cameraUp,Mathf.sin(tilt));
        camera.up.set(cameraUp).scl(Mathf.cos(tilt)).mulAdd(cameraForward,-Mathf.sin(tilt));
        weaponForward.set(camera.direction);
        boolean chase=!buildMode&&CameraView.selected()!=CameraView.FIRST;
        renderer3d.setFirstPersonActive(!buildMode&&CameraView.selected()==CameraView.FIRST);
        renderer3d.setShowControlledUnit(chase);
        if(!buildMode&&Vars.player!=null&&!Vars.player.dead()&&!Vars.player.unit().type.flying&&!network.nativeBoost().active(Vars.player.unit())){
            var unit=Vars.player.unit();var body=world3d.unitState(unit);
            if(fpv.flight.GroundEyeHeight.ordinary(unit)){
                double desired=fpv.flight.GroundEyeHeight.desired(unit,body==null?.12:body.halfHeight(),body==null?0:body.pitch(),body==null?0:body.roll());
                if(smoothing)groundEye.advance(desired,cameraSeconds);else groundEye.snap(desired);
                double lift=groundEye.clearHull(fpv.flight.GroundEyeHeight.minimum(unit,body==null?.12:body.halfHeight(),body==null?0:body.pitch(),body==null?0:body.roll()));
                var hit=world3d.traceSegment(s.x,-s.z,s.y,s.x,-s.z,s.y+lift,fpv.flight.GroundEyeHeight.RADIUS);
                double safe=hit.hit()?Math.max(0,lift*hit.fraction()-fpv.flight.GroundEyeHeight.SKIN):lift;
                camera.position.y+=(float)groundEye.constrain(safe);
            }else{
                double lift=Math.max(.72,body==null?0:body.halfHeight()*.35);
                var hit=world3d.traceSegment(s.x,-s.z,s.y,s.x,-s.z,s.y+lift,unit instanceof mindustry.gen.BlockUnitc?.08:.12);
                double nativeLift=lift*(hit.hit()?Math.max(0,hit.fraction()-.025):1);
                camera.position.y+=(float)releaseGroundEye(s,nativeLift,cameraSeconds);
            }
        }else if(!buildMode&&Vars.player!=null&&!Vars.player.dead())camera.position.y+=(float)releaseGroundEye(s,0,cameraSeconds);
        else groundEye.reset();
        camera.fov=(float)ViewSettings.perspectiveFov(Core.settings.getInt("fpv-fov",(int)RuntimeConfig.FOV));
        camera.near=(float)RuntimeConfig.NEAR_CLIP;camera.far=Math.max((float)RuntimeConfig.FAR_CLIP,renderer3d.environment().farPlane(camera));
        // The pilot sight is identical in every observer mode. Only presentation moves.
        pilotCamera.position.set(camera.position);pilotCamera.direction.set(camera.direction);pilotCamera.up.set(camera.up);
        pilotCamera.fov=camera.fov;pilotCamera.near=camera.near;pilotCamera.far=camera.far;
        pilotCamera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());pilotCamera.update();
        if(!buildMode&&Vars.player!=null&&!Vars.player.dead()){
            var body=world3d.unitState(Vars.player.unit());
            double radius=body==null?1:body.radius(),halfHeight=body==null?.3:body.halfHeight();
            cameraFocus.set((float)s.x,(float)s.y,(float)s.z);
            observerCamera.apply(camera,cameraFocus,radius,halfHeight,CameraView.selected(),CameraView.distance(),cameraSeconds,smoothing,cameraTrace);
            if(chase&&observerCamera.actualDistance()<Math.max(.35,radius*1.05))renderer3d.setShowControlledUnit(false);
        }else observerCamera.reset();
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.update();
        renderer3d.environment().update(camera);
    }
    private double releaseGroundEye(FlightController.State state,double nativeLift,double seconds){
        if(!CameraView.smoothing()){groundEye.reset();return nativeLift;}
        if(!groundEye.active())return nativeLift;
        double lift=groundEye.release(nativeLift,seconds);
        var hit=world3d.traceSegment(state.x,-state.z,state.y,state.x,-state.z,state.y+lift,fpv.flight.GroundEyeHeight.RADIUS);
        return groundEye.active()?groundEye.constrain(hit.hit()?Math.max(0,lift*hit.fraction()-fpv.flight.GroundEyeHeight.SKIN):lift):nativeLift;
    }

    void syncFlightMode(){
        int desired=Vars.player!=null&&!Vars.player.dead()&&!Vars.player.unit().type.flying&&!network.nativeBoost().active(Vars.player.unit())?1:Mathf.clamp(Core.settings.getInt("fpv-flight-mode",0),0,1);
        if(desired==flightMode)return;
        flight.getForward(vector);freeLook.align(vector[0],vector[1],vector[2]);
        var s=flight.state();
        double yaw=-Math.atan2(vector[0],-vector[2]);
        s.qx=s.qz=0;s.qy=Math.sin(yaw*.5);s.qw=Math.cos(yaw*.5);s.wx=s.wy=s.wz=0;
        flightMode=desired;
        if(desired==0)throttle=Math.min(1,flight.profile().hoverThrottle());
    }

    void navigateStabilized(double seconds,double north,double east,double climb){
        if(!enabled||!ready||buildMode||Vars.player.dead())return;
        var performance=flightSettings.configure(flight,Vars.player.unit());
        stabilizedFlight.step(flight.state(),flight.profile(),seconds,north,east,climb,
            performance.practicalSpeedMps(),freeLook.headingRadians(),flightCollision);
        throttle=Math.min(1,flight.profile().hoverThrottle());
    }

    void updatePick() {
        long profiled=fpv.profile.CameraPerformanceProfile.start();
        try{updatePickProfiled();}finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.PICK,profiled);}
    }
    void updatePickProfiled() {
        input.setMiningPick(null);
        boolean buildingActive=enabled&&ready&&buildMode&&!input.commandActive()&&!input.commandPointerFence()&&Vars.state.isGame()&&Vars.player!=null&&!Vars.player.dead();
        if(layerBuild!=null)layerBuild.update(camera,buildingActive,inputSuspended,buildingActive?renderer3d.pickInteraction(camera,input.originalInput().mouseX(),input.originalInput().mouseY()):null);
        if(Vars.player==null||Vars.player.dead()){
            pickedFlightAim=null;input.setControlPick(null);input.setAimWorld(0,0,false);renderer3d.clearSelection();return;
        }
        if(input.commandActive()||input.commandPointerFence()){
            pickedFlightAim=null;input.setControlPick(null);input.setAimWorld(0,0,false);renderer3d.clearSelection();return;
        }
        float sx=buildMode?input.originalInput().mouseX():Core.graphics.getWidth()/2f;
        float sy=buildMode?input.originalInput().mouseY():Core.graphics.getHeight()/2f;
        if(buildingActive){
            pickedFlightAim=null;var target=layerBuild.placementTarget();
            boolean nativePlacement=target!=null&&target.resolved()&&target.layer()==0&&!layerBuild.platform()&&input.block!=null&&!inputSuspended;
            float offset=input.block==null?0:input.block.offset;
            input.setAimWorld(nativePlacement?target.x()*Vars.tilesize+offset:0,nativePlacement?target.y()*Vars.tilesize+offset:0,nativePlacement);
            input.setControlPick(!inputSuspended&&layerBuild.possessionAvailable()&&input.originalInput().keyDown(mindustry.input.Binding.control)?PilotSelection.pick(camera,world3d,Vars.player,sx,sy):null);
            if(nativePlacement)renderer3d.setSelection(target.x(),target.y(),input.block,input.rotation,input.validPlace(target.x(),target.y(),input.block,input.rotation));else renderer3d.clearSelection();
            return;
        }
        if(buildMode&&layerBuild!=null&&layerBuild.layer()>0){pickedFlightAim=null;input.setControlPick(!inputSuspended&&input.originalInput().keyDown(mindustry.input.Binding.control)?PilotSelection.pick(camera,world3d,Vars.player,sx,sy):null);input.setAimWorld(0,0,false);renderer3d.clearSelection();return;}
        var sight=buildMode?camera:pilotCamera;
        var hit=renderer3d.pick(sight,sx,sy);
        if(!buildMode&&hit!=null&&!inputSuspended){
            var obstruction=world3d.traceSegment(sight.position.x,-sight.position.z,sight.position.y,hit.worldX/Vars.tilesize,hit.worldY/Vars.tilesize,hit.height,0);
            if(!obstruction.hit()||obstruction.fraction()>=.999)input.setMiningPick(hit.tile);
        }
        pickedFlightAim=null;
        if(cameraUsesSightPick()){
            double ax=sight.position.x,ay=-sight.position.z,az=sight.position.y;
            double reach=Math.min(sight.far,1500),bx=ax+sight.direction.x*reach,by=ay-sight.direction.z*reach,bz=az+sight.direction.y*reach;
            double t=hit==null?1:Math.min(1,hit.distance/reach);
            // Native pick XY are pixels and its distance starts at the near plane.
            // Ground first-person convergence needs distance from the actual eye, in tiles.
            if(hit!=null)t=Math.min(1,
                Math.hypot(Math.hypot(hit.worldX/Vars.tilesize-ax,hit.worldY/Vars.tilesize-ay),hit.height-az)/reach);
            for(var target:mindustry.gen.Groups.unit){
                if(target==Vars.player.unit()||target.dead||target.team==Vars.player.team()
                    &&!Vars.player.unit().type.canHeal)continue;
                var pose=world3d.unitState(target);if(pose==null)continue;
                t=Math.min(t,fpv.combat.CombatGeometry.cylinder(ax,ay,az,bx,by,bz,pose.x(),pose.y(),pose.z(),pose.radius(),pose.halfHeight()));
            }
            pickedFlightAim=new WorldCoordinates.Point(ax+(bx-ax)*t,ay+(by-ay)*t,az+(bz-az)*t);
        }
        input.setAimWorld(hit==null?0:hit.worldX,hit==null?0:hit.worldY,hit!=null&&!inputSuspended);
        input.setControlPick(buildMode&&!inputSuspended&&input.originalInput().keyDown(mindustry.input.Binding.control)?
            PilotSelection.pick(camera,world3d,Vars.player,sx,sy):null);
        if(!buildMode){
            // Use one azimuth for native mount cones and the spatial ray, even over a
            // configurable building, straight up, or while observing behind the craft.
            // Native fixed-mount shoot cones use the body's flat heading; Combat3D applies
            // the actual XYZ sight direction. A sight crossing zenith must not block firing.
            double angle=Math.toRadians(Vars.player.unit().rotation);
            double hx=Math.cos(angle),hy=Math.sin(angle),length=1;
            if(!Vars.player.unit().type.flying){var aim=controlledAim();if(Math.hypot(aim.x(),aim.y())>1e-6){hx=aim.x();hy=aim.y();length=Math.hypot(hx,hy);}}
            input.setFlightAim((float)(Vars.player.x+hx/length*10000),(float)(Vars.player.y+hy/length*10000));
        }
        Tile tile=input.pickedTile();
        if(buildMode&&tile!=null&&input.block!=null) {
            renderer3d.setSelection(tile.x,tile.y,input.block,input.rotation,Vars.control.input.validPlace(tile.x,tile.y,input.block,input.rotation));
        } else renderer3d.clearSelection();
    }

    WorldCoordinates.Point controlledAim(){
        if(pickedFlightAim!=null&&cameraUsesSightPick()){
            var s=flight.state();double dx=pickedFlightAim.x()-s.x,dy=pickedFlightAim.y()+s.z,dz=pickedFlightAim.z()-s.y;
            if(dx*dx+dy*dy+dz*dz>1e-8)return fpv.combat.CombatGeometry.direction(dx,dy,dz);
        }
        return new WorldCoordinates.Point(weaponForward.x,-weaponForward.z,weaponForward.y);
    }
    private boolean cameraUsesSightPick(){
        return !buildMode&&Vars.player!=null&&!Vars.player.dead()&&fpv.flight.GroundEyeHeight.ordinary(Vars.player.unit());
    }

    /** Precision movement is real-time; frozen-world navigation updates only the possessed unit. */
    void navigateBuild(double seconds,double forward,double right,double climb,double turn){
        if(!enabled||!ready||!buildMode||Vars.player.dead())return;
        var unit=Vars.player.unit();var s=flight.state();double ox=s.x,oy=s.y,oz=s.z;
        buildNavigator.step(s,seconds,forward,right,climb,turn,
            Mathf.clamp(Core.settings.getInt("fpv-build-speed",(int)NavigationConfig.DEFAULT_SPEED_MPS),0,(int)NavigationConfig.MAX_SPEED_MPS));
        s.x=Math.max(.1,Math.min(Vars.world.width()-1.1,s.x));
        s.z=Math.min(-.1,Math.max(-(Vars.world.height()-1.1),s.z));
        var body=world3d.unitState(unit);
        double radius=body==null?.3:Math.max(.22,Math.min(CrashConfig.MAX_COLLIDER_RADIUS_TILES,body.radius()));
        double halfHeight=body==null?.3:Math.max(.12,Math.min(CrashConfig.MAX_COLLIDER_HALF_HEIGHT,body.halfHeight()));
        var contact=buildObstacles.first(unit,ox,-oz,oy,s.x,-s.z,s.y,s.vx,-s.vz,s.vy,radius,halfHeight,0);
        if(contact!=null){
            double t=Math.max(0,contact.time()-1e-6);s.x=ox+(s.x-ox)*t;s.y=oy+(s.y-oy)*t;s.z=oz+(s.z-oz)*t;
            s.vx=s.vy=s.vz=0;
        }
        new SweptCollision(renderer3d::surfaceHeight,radius,halfHeight,CrashConfig.SWEEP_STEP_TILES).resolve(s,ox,oy,oz);
        s.y=Math.min(s.y,Math.max(RuntimeConfig.MAX_HEIGHT,renderer3d.terrain().maxHeight()+RuntimeConfig.START_HEIGHT));
        if(Vars.state.isPaused()){
            unit.move((float)(s.x*Vars.tilesize-unit.x),(float)(-s.z*Vars.tilesize-unit.y));
            unit.vel.setZero();motion.reset();motion.syncFromSimulation(unit,s);
        }
        flight.getForward(vector);unit.rotation=Mathf.angle((float)vector[0],(float)-vector[2]);
    }

    void collide(double oldX,double oldY,double oldZ,double dt,boolean damage) {
        collide(oldX,oldY,oldZ,dt,damage,0);
    }
    void collide(double oldX,double oldY,double oldZ,double dt,boolean damage,double elapsed) {
        var s=flight.state();
        s.x=Math.max(.1,Math.min(Vars.world.width()-1.1,s.x));
        s.z=Math.min(-.1,Math.max(-(Vars.world.height()-1.1),s.z));
        crashes.resolve(Vars.player.unit(),s,oldX,oldY,oldZ,dt,damage,elapsed);
        double ceiling=Math.max(RuntimeConfig.MAX_HEIGHT,renderer3d.terrain().maxHeight()+RuntimeConfig.START_HEIGHT);
        if(s.y>ceiling){s.y=ceiling;if(s.vy>0)s.vy=0;}
        if(Vars.player.dead())motion.reset();
    }

    private void drawWorld() {
        if(wallBenchmark!=null){wallBenchmark.render();return;}
        if(!enabled||(!ready&&!explosions.active()))return;
        long profiled=fpv.profile.CameraPerformanceProfile.start();
        try {renderer3d.render(camera);}
        catch(Throwable failure){Log.err("[FPV] Rendering failed",failure);setEnabled(false);if(smoke!=null)smoke.fail(failure.toString());}
        finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.WORLD_RENDER,profiled);}
    }

    private void fallbackLook(double dt){
        if(!mouse.nativeCapture())freeLook.look((down(KeyCode.right)-down(KeyCode.left))*90*dt,(down(KeyCode.up)-down(KeyCode.down))*90*dt);
    }

    private void afterUi() {
        if(enabled&&ready&&!Vars.player.dead()&&!buildMode&&!input.commandActive()&&!inputSuspended&&!smokeMode&&!Vars.state.isPaused()&&mouse.captured()&&mouse.nativeCapture()) {
            if(hiddenCursor==null){Pixmap empty=new Pixmap(1,1);hiddenCursor=Core.graphics.newCursor(empty,0,0);empty.dispose();}
            Core.graphics.cursor(hiddenCursor);
            cursorHidden=true;
        }else restoreCursor();
        if(smoke!=null)smoke.afterDraw();
    }

    private void close() {
        if(disposed)return;disposed=true;
        if(network!=null)network.close();
        if(mouse!=null)mouse.close();
        restoreCursor();
        if(input!=null)input.uninstallProjection();
        if(input!=null&&input.commands()!=null)input.commands().close();
        if(hud!=null)hud.close();
        if(buildControls!=null)buildControls.close();
        if(construction!=null)construction.close();
        factorySpawns.close();combat.close();
        projectedWalls.close();forceProjectors.close();
        if(separation!=null)separation.close();
        unitAltitudes.dispose();explosions.clear();
        renderer3d.dispose();
        if(hiddenCursor!=null)hiddenCursor.dispose();
    }

    private void restoreCursor(){
        if(cursorHidden){Core.graphics.cursor(Graphics.Cursor.SystemCursor.arrow);cursorHidden=false;}
    }
}
