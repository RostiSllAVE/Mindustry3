package fpv;

import arc.Core;
import arc.files.Fi;
import arc.math.geom.Vec3;
import arc.util.Log;
import arc.util.ScreenUtils;
import arc.util.Time;
import arc.util.serialization.Jval;
import fpv.generated.RuntimeConfig;
import fpv.generated.RenderConfig;
import fpv.input.InputSmokeChecks;
import fpv.flight.UnitMotionSmokeChecks;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.SectorPresets;
import mindustry.content.Planets;
import mindustry.core.GameState.State;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Gamemode;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;

/** Opt-in integration checks in a real running game, restricted to a separate lab profile. */
final class SmokeHarness {
    private final FpvMod mod;
    private final Fi output;
    private final Jval results=Jval.newObject();
    private final long started=System.nanoTime();
    private int frame,stage,stageFrame,timePreset;
    private int constructionX=-1,constructionY=-1,copperBefore;
    private double tickBefore,waveBefore,expectedTicks;
    private double previousRawSeconds,previousNativeDelta,expectedNativeTicks,itemBefore;
    private boolean done,capture;
    private String captureName;
    private int mapsSeen;
    private RissoModelSmokeChecks rissoShowcase;
    private double skySecondsBefore, nativeSkyBefore;
    private FeatureSmokeChecks features;
    private PhysicalSmokeChecks physics;
    private fpv.units.UnitShowcase showcase;
    private fpv.render.GroundFamilyVisualChecks groundShowcase;
    private NativeBoostSmokeChecks boostShowcase;
    private fpv.render.AircraftWeaponVisualChecks aircraftShowcase;

    SmokeHarness(FpvMod mod){
        this.mod=mod;
        output=new Fi(System.getProperty("fpv.smoke.output",Vars.dataDirectory.child("fpv-smoke").absolutePath()));
        output.mkdirs();
        put("gameVersion",fpv.platform.HostCompatibility.currentVersion());
        put("modVersion",Vars.mods.getMod(FpvMod.class).meta.version);
        put("profile",Vars.dataDirectory.absolutePath());
        put("mode","real SDL game with scripted integration checks; no OS mouse/keyboard automation");
        put("physicalInputTested",false);
        put("runStatus","running");
        put("startedAtUtc",java.time.Instant.now().toString());
        write();
    }

    void start(){
        try{
            String lab=System.getProperty("fpv.lab.path","");
            if(lab.isEmpty()||!Vars.dataDirectory.file().getCanonicalPath().startsWith(new java.io.File(lab).getCanonicalPath())){
                throw new IllegalStateException("Smoke checks require the explicitly selected isolated lab profile");
            }
            Core.settings.put("backgroundpause",false);
            Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);
            Core.settings.put("hints",false);
            Core.settings.put("skipcoreanimation",true);
            Core.settings.put("savecreate",false);
            Core.settings.put("fpscap",60);
            Core.settings.put("fpv-pov-angle",58);
            Core.settings.put("fpv-time",2);
            Core.settings.put("fpv-fov",95);
            Core.settings.put("fpv-build-speed",12);
            Core.settings.put("fpv-flight-speed",100);
            Core.settings.put("fpv-flight-mode",0);
            Core.settings.put("fpv-camera-view",0);
            Core.settings.put("fpv-sky-clock",0);
            Core.settings.put("fpv-performance-preset",1);
            Core.settings.put("fpv-ram-strength",100);
            while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            if(Boolean.getBoolean("fpv.smoke.aircraftOnly")||Boolean.getBoolean("fpv.smoke.walkerWeaponsOnly")){
                put("scope","aircraft local weapon/XYZ/FPV GPU regression in a disposable map");
                fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();stage=35;stageFrame=frame;return;
            }
            if(Boolean.getBoolean("fpv.smoke.productionOnly")){
                put("scope","native naval production and dynamic-block GPU regression in a disposable map");
                fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();stage=34;stageFrame=frame;return;
            }
            if(Boolean.getBoolean("fpv.smoke.walkerGalleryOnly")){
                put("scope","isolated native walker visual QA only; not the full integration suite");
                fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();stage=31;stageFrame=frame;return;
            }
            Log.info("[FPV-TEST] Loading original campaign sector Ground Zero.");
            Vars.control.playSector(SectorPresets.groundZero.sector);
        }catch(Throwable error){fail(error.toString());}
    }

    void update(){
        if(done)return;
        frame++;
        try{
            if(showcase!=null)showcase.update(Vars.state.isPaused()?0:Time.delta/60.0);
            if(stage==35&&frame-stageFrame>120&&mod.ready){
                if(aircraftShowcase==null)aircraftShowcase=new fpv.render.AircraftWeaponVisualChecks(mod.renderer3d,mod.unitAltitudes,mod.combat);
                if(!aircraftShowcase.update())return;
                put(Boolean.getBoolean("fpv.smoke.walkerWeaponsOnly")?"walkerWeapons":"aircraftWeapons",aircraftShowcase.result());aircraftShowcase.close();aircraftShowcase=null;
                put("glError",arc.graphics.Gl.getError());put("runStatus","passed");put("passed",true);done=true;write();Core.app.post(Core.app::exit);return;
            }
            if(stage==34&&frame-stageFrame>120&&mod.ready){
                put("navalProduction",fpv.units.NavalProductionSmokeChecks.run(mod.factorySpawns,mod.unitAltitudes));
                put("factorySpawnSafety",fpv.units.FactorySpawnSmokeChecks.run(mod.factorySpawns,mod.world3d,mod.unitAltitudes));
                put("nativeBlockAnimations",fpv.render.NativeBlockAnimationVisualChecks.run());
                put("glError",arc.graphics.Gl.getError());put("runStatus","passed");put("passed",true);done=true;write();Core.app.post(Core.app::exit);return;
            }
            if(stage==0&&frame>100){
                while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
                check(Vars.state.isCampaign(),"Original campaign sector active");
                check(Vars.world.width()>20&&Vars.world.height()>20,"Native map dimensions valid");
                check(mod.enabled&&mod.ready,"FPV initialized with original player unit");
                put("campaignMap",Vars.state.map.name());
                put("campaignWidth",Vars.world.width());
                put("campaignHeight",Vars.world.height());
                mapsSeen++;
                setSceneView();
                checkPick();
                checkTerrain("groundZero");
                put("inputAdapter",InputSmokeChecks.run(mod.input));
                put("unitMotion",UnitMotionSmokeChecks.run());
                checkPauseDialogRestoration();
                check(mod.hud.nativePanelCount()==5&&mod.hud.nativePanelsSuppressed()&&mod.hud.instrumentsVisible(),"FPV instruments replace the five bulky native panels");
                put("fpvHud",true);
                requestCapture("campaign-fpv.png");
                findConstruction();
                stage=1;stageFrame=frame;
            }else if(stage==1){
                setSceneView();
                Tile tile=Vars.world.tile(constructionX,constructionY);
                if(tile.block()==Blocks.conveyor){
                    check(Vars.player.core().items.get(Items.copper)<copperBefore,"Original builder consumed copper");
                    put("nativeConstruction",true);
                    // The real conveyor moves an actual item through the native building simulation.
                    tile.build.handleStack(Items.copper,1,null);
                    requestCapture("native-building.png");
                    mod.setBuildMode(true);
                    timePreset=0;beginTimePreset();stage=2;stageFrame=frame;
                }else if(frame-stageFrame>600)throw new IllegalStateException("Native conveyor construction did not finish within600 frames");
            }else if(stage==2){
                setSceneView();
                int elapsed=frame-stageFrame;
                // Read the previous completed step, excluding the preset-transition frame.
                var conveyor=(ConveyorBuild)Vars.world.build(constructionX,constructionY);
                if(elapsed==1){tickBefore=Vars.state.tick;itemBefore=conveyor.ys[0];nativeSkyBefore=mod.renderer3d.environment().seconds();}
                if(elapsed>1){
                    expectedTicks+=previousRawSeconds*60*RuntimeConfig.TIME_PERCENT[timePreset]/100.0;
                    expectedNativeTicks+=previousNativeDelta;
                }
                previousRawSeconds=mod.clock.rawSeconds();previousNativeDelta=Time.delta;
                if(elapsed>=20){
                    double advanced=Vars.state.tick-tickBefore;
                    if(RuntimeConfig.TIME_PERCENT[timePreset]==0)check(Math.abs(advanced)<.0001,"Zero-percent genuinely pauses simulation");
                    else check(Math.abs(advanced-expectedTicks)<.001+expectedTicks*.001,"Simulation tick agrees with configured speed");
                    double itemMoved=conveyor.ys[0]-itemBefore;
                    double expectedMoved=expectedNativeTicks*((mindustry.world.blocks.distribution.Conveyor)Blocks.conveyor).speed;
                    check(conveyor.len==1&&Math.abs(itemMoved-expectedMoved)<.002,"Native conveyor movement agrees with simulation delta");
                    double nativeSkyAdvanced=mod.renderer3d.environment().seconds()-nativeSkyBefore;
                    // Universe.secondsf() is a float. Imported long-running campaigns can
                    // quantize time in 1/32s steps; a fixed2ms assertion is then impossible.
                    double skyResolution=Math.max(Math.ulp((float)nativeSkyBefore),Math.ulp((float)mod.renderer3d.environment().seconds()));
                    check(Math.abs(nativeSkyAdvanced-expectedNativeTicks/60.0)<.002+skyResolution,"Campaign sky clock agrees with native simulation speed within native float resolution");
                    put("campaignSkyClockResolutionSeconds",skyResolution);
                    put("time"+RuntimeConfig.TIME_PERCENT[timePreset],advanced);
                    put("conveyorProgress"+RuntimeConfig.TIME_PERCENT[timePreset],itemMoved);
                    put("skySeconds"+RuntimeConfig.TIME_PERCENT[timePreset],nativeSkyAdvanced);
                    if(++timePreset<RuntimeConfig.TIME_PERCENT.length){beginTimePreset();stageFrame=frame;}
                    else{
                        mod.setBuildMode(false);
                        check(Math.abs(mod.timeScale()-1)<.00001,"Exit restores100 percent");
                        check(!Vars.state.isPaused(),"Exit restores play state");
                        put("timeRestoration",true);
                        stage=3;stageFrame=frame;
                    }
                }
            }else if(stage==3&&frame-stageFrame>30){
                setSceneView();mod.setBuildMode(true);
                mod.input.block=Blocks.conveyor;
                check(!mod.hud.nativePanelsSuppressed()&&mod.hud.instrumentsVisible(),"Build restores native panel predicates with compact FPV instruments");
                requestCapture("build-mode.png");
                stage=4;stageFrame=frame;
            }else if(stage==4&&frame-stageFrame>20){
                mod.setBuildMode(false);
                var candidates=Vars.maps.defaultMaps();
                check(candidates.size>0,"Native built-in maps are available");
                var map=candidates.first();
                put("secondMapRequested",map.name());
                Vars.control.playMap(map,map.applyRules(Gamemode.survival));
                stage=5;stageFrame=frame;
            }else if(stage==5&&frame-stageFrame>120&&!Vars.state.isCampaign()){
                setSceneView();checkPick();mapsSeen++;
                checkTerrain("caldera");
                put("secondMap",Vars.state.map.name());
                put("secondMapWidth",Vars.world.width());
                requestCapture("second-map-fpv.png");
                stage=6;stageFrame=frame;
            }else if(stage==6&&frame-stageFrame>15){
                put("persistentBuildingVisibility",fpv.render.BuildingVisibilitySmokeChecks.verify(mod.renderer3d));
                put("bridgeVisibility",fpv.render.NativeBridgeVisibilitySmokeChecks.verify(mod.renderer3d));
                put("flightPerformance",fpv.flight.FlightSettingsSmokeChecks.run());
                put("buildNavigation",new BuildNavigationSmokeChecks(mod).run());
                put("flightControls",new FlightControlsSmokeChecks(mod).run());
                put("flightFiring",FlightFiringSmokeChecks.run(mod));
                put("altitudeSeparation",fpv.units.AltitudeSeparationSmokeChecks.verify());
                put("shieldAndCamera",fpv.render.NativeShieldCameraSmokeChecks.verify(mod.renderer3d,mod.unitAltitudes));
                fpv.world.World3DSmokeChecks.run(mod.world3d);put("world3dQueries",true);
                put("blast3dVolumes",fpv.effects.RadialExplosionSmokeChecks.verify(mod.world3d));
                put("ramDamage",fpv.physics.RamDamageSmokeChecks.verify(mod.world3d));
                put("ramDamageCaps",fpv.physics.RamCapSmokeChecks.verify(mod.world3d));
                put("simulationClock",SimulationClockSmokeChecks.run());
                put("combat3d",fpv.combat.CombatSmokeChecks.run(mod.world3d,mod.combat));
                put("combatRendering",fpv.render.CombatVisibilitySmokeChecks.verify(mod.renderer3d,mod.combat));
                put("combatExpansion",fpv.combat.CombatExpansionSmokeChecks.run(mod.world3d,mod.combat));
                put("turretCombat",fpv.combat.TurretCombatSmokeChecks.run(mod.world3d,mod.combat));
                put("turretBalance",fpv.combat.TurretBalanceSmokeChecks.run(mod.world3d,mod.combat));
                put("highAltitudeTurrets",new HighAltitudeTurretSmokeChecks(mod).run());
                put("projectedWalls",fpv.defense.ProjectedWallSmokeChecks.verify(mod.projectedWalls,mod.renderer3d,mod.world3d,mod.unitAltitudes,mod.combat));
                put("supportEffects",fpv.render.NativeSupportEffectsSmokeChecks.verify(mod.renderer3d,mod.unitAltitudes));
                put("forceProjectors",fpv.defense.ForceProjectorSmokeChecks.verify(mod.forceProjectors,mod.renderer3d,mod.world3d,mod.combat));
                put("forceAircraft",fpv.defense.ForceAircraftSmokeChecks.verify(mod.forceProjectors,mod.world3d));
                put("groundFamilyPhysics",fpv.units.ground.GroundFamilySmokeChecks.verify(mod.world3d,mod.unitAltitudes,mod.projectedWalls));
                put("sharedPilotControl",ControlSmokeChecks.run(mod));
                put("creatorDeferredRam",fpv.control.CreatorSaveSmokeChecks.verifyDeferredDeath(mod.network.creatorFlight(),mod.world3d));
                put("walkerStance",fpv.units.ground.GroundStanceSmokeChecks.verify(mod.unitAltitudes,mod.network.creatorFlight()));
                put("projectionPixelComposition",fpv.render.ProjectionCompositeChecks.verify());
                put("nativeProjectionViews",fpv.render.ProjectedWallViewChecks.verify(mod.projectedWalls,mod.renderer3d));
                mod.combat.clear();
                addVisualFixtures();
                features=new FeatureSmokeChecks(mod);
                physics=new PhysicalSmokeChecks(mod);physics.setup();
                showcase=new fpv.units.UnitShowcase(mod.unitAltitudes,mod.renderer3d.terrain()::heightAt);showcase.spawn();
                mod.renderer3d.environment().setPreviewPhase(.5f);
                Core.settings.put("fpv-pov-angle",72);
                setSceneView();
                stage=7;stageFrame=frame;
            }else if(stage==7&&frame-stageFrame>90&&showcase.metrics().simulationSeconds()>4){
                check(mod.renderer3d.environment().dayAmount()>.9f,"Day sky lights the real native world");
                check(mod.camera.far>Math.hypot(Vars.world.width(),Vars.world.height()),"Far plane covers the entire map diagonal");
                requestCapture("showcase-day.png");
                // Build the damage fixture immediately before measuring it. The visual
                // showcase runs live combat for several seconds and can otherwise
                // destroy its wall before the isolated explosion under test exists.
                features.setupBlast();features.run();put("crashAndExplosion",true);features.close();features=null;
                put("physicalRamming",physics.run());physics.close();physics=null;
                var metrics=showcase.verify();put("unitShowcase",metrics.toString());put("unitAltitude",true);
                var extrusion=mod.renderer3d.extrusionStats();
                check(extrusion.builds()>=4&&extrusion.rendered()>0,"Actual native unit sprites rendered as cached extruded meshes");
                put("extrusionMeshBuilds",extrusion.builds());put("extrusionCacheBytes",extrusion.estimatedCacheBytes());
                check(mod.renderer3d.extrusionTeamCellDraws()>0,"Native team-cell colors survive extrusion");
                put("extrusionTeamCells",mod.renderer3d.extrusionTeamCellDraws());
                stage=21;stageFrame=frame;
            }else if(stage==21&&frame-stageFrame>20){
                var s=mod.flight.state();s.x=showcase.cameraX();s.y=showcase.cameraY();s.z=showcase.cameraZ();
                Core.settings.put("fpv-pov-angle",90);mod.updateCamera();stage=22;stageFrame=frame;
            }else if(stage==22&&frame-stageFrame>30){
                requestCapture("units-extruded.png");stage=23;stageFrame=frame;
            }else if(stage==23&&frame-stageFrame>20){
                showcase.dispose();showcase=null;
                var core=Vars.player.core();var s=mod.flight.state();s.x=core.x/Vars.tilesize;s.y=3;s.z=-core.y/Vars.tilesize+13;
                Core.settings.put("fpv-pov-angle",78);mod.updateCamera();
                mod.explosions.detonate(Vars.player.team(),s.x,1.7,s.z-5,new fpv.effects.RadialExplosion.Spec(2.5,0,1.4,.65,fpv.effects.RadialExplosion.Style.sparks,false));
                put("explosionScreenshot","Explicit zero-damage preview of the same production3D burst; physical impact was asserted separately");
                stage=24;stageFrame=frame;
            }else if(stage==24&&frame-stageFrame>4){
                requestCapture("crash-burst.png");stage=8;stageFrame=frame;
            }else if(stage==8&&frame-stageFrame>15){
                setSceneView();
                mod.renderer3d.environment().setPreviewPhase(.02f);
                Core.settings.put("fpv-pov-angle",102);mod.updateCamera();
                stage=9;stageFrame=frame;
            }else if(stage==9&&frame-stageFrame>30){
                check(mod.renderer3d.environment().dayAmount()<.1f,"Night sky reaches its star/planet state");
                put("companionOnSerpulo",mod.renderer3d.environment().companionName());
                requestCapture("night-sky.png");
                stage=10;stageFrame=frame;
            }else if(stage==10&&frame-stageFrame>15){
                mod.renderer3d.environment().setPreviewPhase(Float.NaN);
                mod.timeIndex=0;Core.settings.put("fpv-time",0);mod.setBuildMode(true);
                mod.updateCamera();skySecondsBefore=mod.renderer3d.environment().seconds();
                stage=11;stageFrame=frame;
            }else if(stage==11&&frame-stageFrame>20){
                check(Math.abs(mod.renderer3d.environment().seconds()-skySecondsBefore)<.00001,"Sky clock also freezes in Build0");
                put("skyPause",true);
                mod.setBuildMode(false);mod.timeIndex=2;Core.settings.put("fpv-time",2);
                mod.renderer3d.environment().setPreviewPhase(.5f);
                var view=mod.flight.state();view.x=(Vars.world.width()-1)*.5;view.z=-(Vars.world.height()-1)*.5;view.y=Math.max(Vars.world.width(),Vars.world.height())*.8;
                Core.settings.put("fpv-pov-angle",15);mod.updateCamera();
                stage=12;stageFrame=frame;
            }else if(stage==12&&frame-stageFrame>40){
                requestCapture("full-map-overview.png");
                stage=13;stageFrame=frame;
            }else if(stage==13&&frame-stageFrame>15){
                mod.renderer3d.environment().setPreviewPhase(Float.NaN);
                Core.settings.put("fpv-pov-angle",70);
                Vars.control.playSector(SectorPresets.onset.sector);
                stage=14;stageFrame=frame;
            }else if(stage==14&&frame-stageFrame>150&&Vars.state.getPlanet()==Planets.erekir){
                setSceneView();checkPick();mapsSeen++;
                checkTerrain("erekir");
                put("thirdMap",Vars.state.map.name());put("thirdMapWidth",Vars.world.width());
                put("companionOnErekir",mod.renderer3d.environment().companionName());
                check(mod.renderer3d.environment().companionName().equals("serpulo"),"Erekir selects Serpulo as companion planet");
                requestCapture("erekir-fpv.png");stage=15;stageFrame=frame;
            }else if(stage==15&&frame-stageFrame>20){
                String noCore=System.getProperty("fpv.smoke.noCoreSave","");
                if(!noCore.isBlank()){
                    Fi save=new Fi(noCore);check(save.exists(),"Copied lost-core save exists");
                    check(mindustry.io.SaveIO.getMeta(save).tags.getBool("nocores"),"Latest fixture explicitly records loss of core before this release");
                    Vars.control.saves.resetSave();mindustry.io.SaveIO.load(save);Vars.state.set(State.playing);
                    put("lostCoreSaveSource",save.absolutePath());stage=151;stageFrame=frame;
                }else loadImportedSave();
            }else if(stage==151&&frame-stageFrame>30){
                check(Vars.world.width()>20&&Vars.player.core()==null,"Lost-core save loads and preserves its original no-core state");
                check(mod.construction.grid().size()==0,"Old lost-core save contains no invented elevated buildings");
                put("lostCoreSaveLoaded",true);loadImportedSave();
            }else if(stage==16&&frame-stageFrame>150){
                check(Vars.player.core()!=null&&Vars.world.width()>20,"Imported save copy loaded through native SaveIO");
                while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
                setSceneView();checkPick();checkTerrain("importedSave");
                check(mod.enabled&&mod.ready,"FPV active on imported user save copy");
                put("importedSaveMap",Vars.state.map.name());put("importedSaveWidth",Vars.world.width());put("importedSaveHeight",Vars.world.height());
                put("importedSaveLoaded",true);requestCapture("imported-save.png");stage=17;stageFrame=frame;
            }else if(stage==17&&frame-stageFrame>20){
                long before=tileFingerprint();
                Fi roundtrip=output.child("roundtrip.msav");
                var stanceSave=fpv.units.ground.GroundStanceSmokeChecks.prepareSave(mod.unitAltitudes);
                var creatorSave=fpv.control.CreatorSaveSmokeChecks.prepare(mod.network.creatorFlight(),mod.unitAltitudes);
                var boostSave=fpv.control.NativeBoostSaveSmokeChecks.prepare(mod.network.nativeBoost(),mod.unitAltitudes);
                mindustry.io.SaveIO.write(roundtrip);
                check(mindustry.io.SaveIO.isSaveValid(roundtrip),"Native save writer produces valid isolated roundtrip file");
                mindustry.io.SaveIO.load(roundtrip);Vars.state.set(State.playing);
                put("groundStanceSave",fpv.units.ground.GroundStanceSmokeChecks.verifySaved(mod.unitAltitudes,stanceSave));
                put("creatorSave",fpv.control.CreatorSaveSmokeChecks.verify(mod.network.creatorFlight(),mod.unitAltitudes,creatorSave));
                put("nativeBoostSave",fpv.control.NativeBoostSaveSmokeChecks.verify(mod.network.nativeBoost(),mod.unitAltitudes,boostSave));
                check(tileFingerprint()==before,"Native save/load roundtrip preserves block/floor/ore/team data");
                put("saveRoundTrip",true);stage=18;stageFrame=frame;
            }else if(stage==18&&frame-stageFrame>90){
                while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
                setSceneView();checkPick();checkTerrain("saveRoundTrip");
                check(mod.enabled&&mod.ready,"FPV and physical adapters recover after native save roundtrip");
                put("saveRoundTripFpv",true);requestCapture("save-roundtrip.png");stage=19;stageFrame=frame;
            }else if(stage==19&&frame-stageFrame>20){
                Fi latest=new Fi(System.getProperty("fpv.smoke.latestSave",""));
                check(latest.exists(),"Fresh read-only backup copy of latest user save exists");
                Vars.control.saves.resetSave();mindustry.io.SaveIO.load(latest);Vars.state.set(State.playing);
                put("latestSaveSource",latest.absolutePath());stage=20;stageFrame=frame;
            }else if(stage==20&&frame-stageFrame>150){
                while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
                check(Vars.player.core()!=null,"Latest saved game loads with native core");
                setSceneView();checkPick();checkTerrain("latestSave");
                check(mod.enabled&&mod.ready,"FPV recovers on latest saved game copy");
                put("latestSaveLoaded",true);put("latestSaveWidth",Vars.world.width());put("latestSaveHeight",Vars.world.height());
                requestCapture("latest-save.png");stage=26;stageFrame=frame;
            }else if(stage==26&&frame-stageFrame>20){
                Core.settings.put("fpv-flight-mode",1);Core.settings.put("fpv-camera-view",1);
                mod.syncFlightMode();mod.freeLook.align(0,-.3,-1);mod.updateCamera();
                requestCapture("stabilized-third-person.png");stage=27;stageFrame=frame;
            }else if(stage==27&&frame-stageFrame>20){
                Core.settings.put("fpv-camera-view",2);mod.freeLook.align(0,.2,-1);Core.settings.put("fpv-pov-angle",90);
                mod.updateCamera();requestCapture("stabilized-rear-view.png");stage=28;stageFrame=frame;
            }else if(stage==28&&frame-stageFrame>20){
                mod.setBuildMode(false); // Walker showcase advances stance at ordinary simulation time.
                fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();stage=31;stageFrame=frame;
            }else if(stage==31){
                if(frame-stageFrame<90||Vars.player.unit()==null||Vars.player.unit().dead||!mod.ready){
                    if(frame-stageFrame>600)throw new IllegalStateException("Native walker lab core/player did not initialize");
                    return;
                }
                put("walkerVisualMap","dedicated128x128 native generated sand lab; latest user save tested separately");
                // Dense imported maps need not contain a vacant46x9 rectangle. Build this
                // synthetic cache fixture only after entering our generated disposable map.
                put("projectedWallOptimization",fpv.defense.ProjectedWallOptimizationChecks.verify(mod.projectedWalls,mod.renderer3d));
                boolean visualOnly=Boolean.getBoolean("fpv.smoke.walkerGalleryOnly")&&Boolean.getBoolean("fpv.smoke.visualOnly");
                if(!visualOnly)put("maintenanceCombat",new MaintenanceCombatSmokeChecks(mod).run());
                put("projectionDistance",fpv.render.ProjectionDistanceSmokeChecks.verify(mod.projectedWalls,mod.renderer3d));
                if(!visualOnly)put("greenWalkerHeight",fpv.units.ground.GreenWalkerHeightSmokeChecks.run(mod.world3d,mod.unitAltitudes));
                if(!visualOnly)put("factorySpawnSafety",fpv.units.FactorySpawnSmokeChecks.run(mod.factorySpawns,mod.world3d,mod.unitAltitudes));
                put("maintenanceVisuals",fpv.render.MaintenanceVisualChecks.run(mod.renderer3d,mod.world3d));
                put("threatTiers",fpv.render.UnitThreatTierSmokeChecks.verify());
                put("vegetation5x",fpv.render.VegetationVisualChecks.run(mod.renderer3d,mod.world3d));
                put("threatMarkers",fpv.render.UnitThreatVisualChecks.run(mod.renderer3d,mod.unitAltitudes));
                groundShowcase=new fpv.render.GroundFamilyVisualChecks(mod.renderer3d,mod.renderer3d.groundFamily(),mod.unitAltitudes);
                groundShowcase.setVelaControlCheck(unit->put("velaLegs",VelaLegSmokeChecks.run(mod)));
                groundShowcase.setMaceControlCheck(unit->put("maceControl",MaceControlSmokeChecks.run(mod)));
                groundShowcase.setFortressControlCheck(unit->put("fortressControl",FortressControlSmokeChecks.run(mod)));
                groundShowcase.setScepterControlCheck(unit->put("scepterControl",ScepterControlSmokeChecks.run(mod)));
                groundShowcase.setReignControlCheck(unit->put("reignControl",ReignControlSmokeChecks.run(mod)));
                groundShowcase.setGreenControlCheck(unit->put(unit.type.name+"ModelControl",GreenModelControlSmokeChecks.run(mod,unit.type)));
                stage=29;stageFrame=frame;
            }else if(stage==29){
                groundShowcase.warm();
                if(frame-stageFrame>12&&groundShowcase.ready()){
                    put("groundFamilyVisuals",groundShowcase.verifyAndCapture());groundShowcase.prepareExtension();stage=30;stageFrame=frame;
                }else if(frame-stageFrame>6000)throw new IllegalStateException("All-native walker GPU showcase did not complete");
            }else if(stage==30){
                groundShowcase.warm();
                if(!groundShowcase.extendedReady()){
                    if(frame-stageFrame>600)throw new IllegalStateException("Native walker terrain stance did not settle");
                    return;
                }
                put("groundFamilyExtendedVisuals",groundShowcase.verifyExtendedAndCapture());groundShowcase.close();groundShowcase=null;
                put("mechWeaponPitch",fpv.combat.MechWeaponPitchSmokeChecks.run(mod.renderer3d,mod.unitAltitudes,mod.combat));
                boostShowcase=new NativeBoostSmokeChecks(mod);stage=32;stageFrame=frame;
            }else if(stage==32&&boostShowcase.update()){
                put("nativeBoost",boostShowcase.result());boostShowcase=null;
                rissoShowcase=new RissoModelSmokeChecks(mod);stage=33;stageFrame=frame;
            }else if(stage==33&&rissoShowcase.update()){
                put("rissoModel",rissoShowcase.result());rissoShowcase=null;
                put("navalWeaponPitch",fpv.combat.NavalWeaponPitchSmokeChecks.run(mod.renderer3d,mod.unitAltitudes,mod.combat));
                put("mapsVerified",mapsSeen);
                int glError=arc.graphics.Gl.getError();put("glError",glError);check(glError==0,"No OpenGL error after all visual states");
                put("runStatus","passed");
                put("passed",true);done=true;write();
                Log.info("[FPV-TEST] PASS: @",output.absolutePath());
                Core.app.post(Core.app::exit);
            }
        }catch(Throwable error){fail(error.toString());}
    }

    private void loadImportedSave(){
        Fi save=new Fi(System.getProperty("fpv.smoke.userSave",""));
        check(save.exists(),"Isolated copy of imported user save exists");
        Vars.control.saves.resetSave();mindustry.io.SaveIO.load(save);Vars.state.set(State.playing);
        put("importedSaveSource",save.absolutePath());stage=16;stageFrame=frame;
    }

    private void addVisualFixtures(){
        // Explicit lab-only fixtures exercise family rendering; construction/cost checks ran separately.
        var core=Vars.player.core();int count=0;
        mindustry.world.Block[] blocks={Blocks.mechanicalDrill,Blocks.graphitePress,Blocks.combustionGenerator,Blocks.powerNode,Blocks.duo};
        for(var block:blocks){
            boolean placed=false;
            for(int dy=-4;dy<=10&&!placed;dy+=3)for(int dx=-10;dx<=10&&!placed;dx+=3){
                int x=core.tileX()+dx,y=core.tileY()+dy;
                if(Build.validPlace(block,Vars.player.team(),x,y,0)){
                    Vars.world.tile(x,y).setBlock(block,Vars.player.team(),0);
                    var building=Vars.world.build(x,y);
                    if(block==Blocks.graphitePress||block==Blocks.combustionGenerator)building.handleStack(Items.coal,20,null);
                    if(block==Blocks.duo)building.handleStack(Items.copper,10,null);
                    count++;placed=true;
                }
            }
        }
        put("explicitVisualFixtureBuildings",count);
        check(count==blocks.length,"Five native building families supplied as declared visual fixtures");
    }

    private void beginTimePreset(){
        Core.settings.put("fpv-time",timePreset);
        mod.timeIndex=timePreset;
        // Force a transition to exercise the exact production pause restoration code.
        mod.setBuildMode(false);mod.setBuildMode(true);
        tickBefore=Vars.state.tick;waveBefore=Vars.state.wavetime;expectedTicks=0;expectedNativeTicks=0;
        var conveyor=(ConveyorBuild)Vars.world.build(constructionX,constructionY);
        conveyor.removeStack(Items.copper,10);conveyor.handleStack(Items.copper,1,null);
    }

    private void checkPauseDialogRestoration(){
        mod.setBuildMode(false);
        mod.timeIndex=0;Core.settings.put("fpv-time",0);mod.setBuildMode(true);
        check(Vars.state.isPaused()&&mod.buildOwnsPause,"Build0 owns its pause");
        var dialog=new mindustry.ui.dialogs.BaseDialog("FPV scripted pause check");
        dialog.show();
        mod.hud.update();
        check(!mod.hud.nativePanelsSuppressed()&&!mod.hud.instrumentsVisible(),"Native dialogs retain UI and hide FPV instruments");
        try{
            mod.timeIndex=2;Core.settings.put("fpv-time",2);mod.applyBuildPause();
            check(Vars.state.isPaused()&&mod.buildOwnsPause,"Changing speed inside a dialog retains pause ownership");
        }finally{dialog.hide(null);}
        mod.applyBuildPause();
        check(!Vars.state.isPaused()&&!mod.buildOwnsPause,"Closing the dialog restores the requested running speed");
        mod.setBuildMode(false);
        put("pauseDialogRestoration",true);
    }

    private void setSceneView(){
        var core=Vars.player.core();
        if(core==null)return;
        var s=mod.flight.state();
        s.x=core.x/Vars.tilesize;s.y=10;s.z=-core.y/Vars.tilesize+13;
        s.vx=s.vy=s.vz=0;
        s.qx=s.qy=s.qz=0;s.qw=1;
        mod.updateCamera();mod.updatePick();
    }

    private void checkPick(){
        var core=Vars.player.core();
        Vec3 point=new Vec3(core.x/Vars.tilesize,0,-core.y/Vars.tilesize);
        var ray=mod.renderer3d.pick(mod.camera,Core.graphics.getWidth()/2f,Core.graphics.getHeight()/2f);
        check(ray!=null,"Perspective center ray hits native map geometry");
        check(Vars.world.tile(ray.tileX,ray.tileY)!=null,"Ray maps to an existing native tile");
        put("raycast",true);
    }

    private long tileFingerprint(){
        long hash=1469598103934665603L;
        for(Tile tile:Vars.world.tiles){hash=(hash^tile.block().id)*1099511628211L;hash=(hash^tile.floor().id)*1099511628211L;hash=(hash^tile.overlay().id)*1099511628211L;hash=(hash^tile.team().id)*1099511628211L;}
        return hash;
    }
    private void checkTerrain(String label){
        long before=tileFingerprint();
        var terrain=mod.renderer3d.terrain();terrain.invalidate();terrain.ensureWorld();
        check(before==tileFingerprint(),"Terrain generation preserves all native tile/block/floor/ore/team values");
        int natural=0;for(Tile tile:Vars.world.tiles)if(terrain.naturalWall(tile))natural++;
        check(terrain.naturalWallCount()==natural,"Terrain contains exactly the native natural-wall mask");
        check(natural==0?terrain.maxHeight()==0:terrain.maxHeight()>0,
            natural==0?"Map without natural walls correctly stays flat":"Natural regions generate non-flat elevation");
        check(!terrain.naturalWall(Vars.player.core().tile),"Built Core is not mountain terrain");
        put(label+"TerrainMax",terrain.maxHeight());put(label+"NaturalWallTiles",terrain.naturalWallCount());
        put(label+"TerrainBuildMilliseconds",terrain.buildMilliseconds());put(label+"TilesUnchanged",true);
    }

    private void findConstruction(){
        var core=Vars.player.core();
        check(core!=null,"Original player has a core");
        put("testFixtureConveyorUnlocked",!Blocks.conveyor.unlockedNowHost());
        Blocks.conveyor.unlock();
        mod.input.isBuilding=true;
        // Ground Zero intentionally starts empty. Seed a declared test fixture only in this isolated profile.
        int fixtureCopper=Math.max(0,20-core.items.get(Items.copper));
        core.items.add(Items.copper,fixtureCopper);
        put("testFixtureCopperAdded",fixtureCopper);
        copperBefore=core.items.get(Items.copper);
        check(copperBefore>=2,"Isolated test core has copper for construction");
        for(int dy=-6;dy<=6;dy++)for(int dx=-6;dx<=6;dx++){
            int x=core.tileX()+dx,y=core.tileY()+dy;
            Tile tile=Vars.world.tile(x,y);
            if(tile!=null&&Build.validPlace(Blocks.conveyor,Vars.player.team(),x,y,0)){
                constructionX=x;constructionY=y;
                Vars.player.unit().addBuild(new BuildPlan(x,y,0,Blocks.conveyor));
                return;
            }
        }
        throw new IllegalStateException("No legal conveyor site near native core");
    }

    private void requestCapture(String name){capture=true;captureName=name;}
    void afterDraw(){
        if(capture){
            try{
                var stats=mod.renderer3d.stats();
                check(stats.visibleChunks()>0&&stats.visibleChunks()==stats.detailedChunks()+stats.coarseChunks(),"Visible chunks are covered by detail or overview geometry");
                check(stats.cachedBytes()<=RenderConfig.DETAIL_CACHE_BYTES,"Owned cached chunk buffers remain within the design budget");
                var snapshot=Jval.newObject();
                snapshot.add("visibleChunks",Jval.valueOf(stats.visibleChunks()));
                snapshot.add("detailedChunks",Jval.valueOf(stats.detailedChunks()));
                snapshot.add("coarseChunks",Jval.valueOf(stats.coarseChunks()));
                snapshot.add("cachedChunks",Jval.valueOf(stats.cachedChunks()));
                snapshot.add("cachedMeshBytes",Jval.valueOf(stats.cachedBytes()));
                snapshot.add("cpuSubmissionMilliseconds",Jval.valueOf(stats.cpuMilliseconds()));
                snapshot.add("cameraFar",Jval.valueOf(mod.camera.far));
                snapshot.add("mapDiagonal",Jval.valueOf(Math.hypot(Vars.world.width(),Vars.world.height())));
                results.add("render-"+captureName,snapshot);
                ScreenUtils.saveScreenshot(output.child(captureName));capture=false;
            }
            catch(Throwable error){fail("Screenshot: "+error);}
        }
        if(!done&&(System.nanoTime()-started)>240_000_000_000L)fail("Timed out waiting for actual game integration checks");
    }
    void fail(String message){
        if(done)return;done=true;
        if(aircraftShowcase!=null)aircraftShowcase.close();
        if(features!=null)features.close();if(physics!=null)physics.close();if(showcase!=null)showcase.dispose();if(groundShowcase!=null)groundShowcase.close();if(rissoShowcase!=null)rissoShowcase.close();
        put("runStatus","failed");put("passed",false);put("failure",message);
        Log.err("[FPV-TEST] FAIL: @",message);write();
        if(mod.mouse!=null)mod.mouse.close();
        Core.app.post(Core.app::exit);
    }
    private void check(boolean value,String message){if(!value)throw new IllegalStateException(message);Log.info("[FPV-TEST] OK: @",message);}
    private void put(String key,String value){results.add(key,value);}
    private void put(String key,boolean value){results.add(key,Jval.valueOf(value));}
    private void put(String key,double value){results.add(key,Jval.valueOf(value));}
    private void write(){output.child("results.json").writeString(results.toString(Jval.Jformat.formatted));}
}

