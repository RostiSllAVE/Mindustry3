package fpv;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.Log;
import arc.util.ScreenUtils;
import arc.util.serialization.Jval;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.io.SaveIO;
import java.util.Arrays;

/** Opt-in SDL integration on a generated disposable map; never opens a user profile or user save. */
final class LayerSmokeHarness {
    private final FpvMod mod;
    private final Fi output=new Fi(System.getProperty("fpv.smoke.output"));
    private final Jval report=Jval.newObject();
    private final Camera3D camera=new Camera3D();
    private int frame,stage,stageFrame,checks;
    private boolean finished,powerComplete;
    private long mapHash,stableBuilds;
    private byte[] layerSave;
    private double cpuTotal,cpuMax;
    LayerSmokeHarness(FpvMod mod){this.mod=mod;}
    void start(){
        try{
            String lab=System.getProperty("fpv.lab.path","");
            if(lab.isEmpty()||!Vars.dataDirectory.file().toPath().toAbsolutePath().normalize().startsWith(new java.io.File(lab).toPath().toAbsolutePath().normalize()))throw new IllegalStateException("Explicit isolated lab required");
            output.mkdirs();put("runStatus","running");put("host",fpv.platform.HostCompatibility.currentVersion());put("started",java.time.Instant.now().toString());write();
            Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);Core.settings.put("backgroundpause",false);Core.settings.put("fpscap",60);Core.settings.put("savecreate",false);
            Core.settings.put("fpv-time",5);Core.settings.put("fpv-layer-free-permission",false);
            while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();
            Events.run(Trigger.update,this::tick);Events.run(Trigger.postDraw,this::draw);
        }catch(Throwable error){fail(error);}
    }
    private void tick(){
        if(finished)return;frame++;
        try{
            if(frame>2400)throw new AssertionError("Layer smoke timeout, stage "+stage);
            if(!Vars.state.isGame()||Vars.player.dead()||!mod.ready)return;
            if(stage==0&&frame>30){
                if(Boolean.getBoolean("fpv.xyzPower.smoke")&&!powerComplete){put("xyzPower",XyzPowerSmokeChecks.run(mod));powerComplete=true;stageFrame=frame;write();return;}
                if(powerComplete&&frame-stageFrame<15)return; // Let native WorldLoad callbacks finish after the power save/load fixture.
                if(Boolean.getBoolean("fpv.elevatedTurrets.smoke")){put("elevatedTurrets",ElevatedTurretSmokeChecks.run(mod));put("elevatedTurretVisuals",fpv.render.ElevatedTurretVisualChecks.run());put("turretModels",fpv.render.TurretModelVisualChecks.run());write();}
                if(Boolean.getBoolean("fpv.placement.smoke")){put("layerSchematics",LayerSchematicSmokeChecks.run(mod));put("placementLifecycle",PlacementLifecycleSmokeChecks.run(mod));put("automaticConstruction",AutomaticConstructionSmokeChecks.run(mod));write();}
                if(Boolean.getBoolean("fpv.mouse.smoke")){put("mouseInteraction",MouseInteractionSmokeChecks.run(mod));put("mouseVisuals",fpv.render.BuildingInteractionVisualChecks.run());write();}
                put("unitCommands",UnitCommandSmokeChecks.run(mod));
                put("groundEye",GroundEyeSmokeChecks.run(mod));
                put("cameraModes",CameraModesSmokeChecks.run(mod));write();
                put("firstPersonVisuals",fpv.render.FirstPersonVisualChecks.run());
                put("miningVisuals",MiningSmokeChecks.run(mod));write();
                put("laserVolumes",fpv.render.VolumetricBeamVisualChecks.run());put("vfxCombat",VfxSmokeChecks.run(mod));write();
                put("constructionVisuals",fpv.render.ConstructionVisualChecks.run());write();
                put("displayVisuals",fpv.render.LayerDisplayVisualChecks.run());write();
                Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;
                Vars.world.tile(40,40).setBlock(Blocks.titaniumWall,Team.sharded,0);
                mapHash=mapHash();
                var shield=mod.projectedWalls.trace(Team.crux,37,40,2,43,40,2,0);
                check(shield!=null,"Z0 projection starts active");
                for(int layer=1;layer<=4;layer++){
                    for(int x=40;x<=45;x++)place(x,40,layer,"",true,LayerComponent.Mode.STRUCTURAL);
                    place(40,40,layer,"titanium-wall",false,LayerComponent.Mode.STRUCTURAL);
                }
                check(mod.construction.grid().size()==28,"four independently occupied construction levels");
                check(mapHash()==mapHash,"construction leaves every Z0 tile unchanged");
                mod.projectedWalls.update(0);
                check(mod.projectedWalls.trace(Team.crux,37,40,2,43,40,2,0)==null,"upper coverage disables projected interception");
                double gap=mod.construction.planeHeight(2)+mod.construction.grid().coordinates().spacing()*.5,slab=mod.construction.planeHeight(3);
                check(mod.layerQueries.sweep(41,36,gap,41,44,gap,.2,.2)==null,"real air gap between floors stays traversable");
                check(mod.layerQueries.sweep(41,40,slab+.3,41,40,slab-.7,.2,.2)!=null,"solid slab intercepts descending drone");
                Core.settings.put("fpv-layer-free-permission",true);place(50,40,8,"copper-wall",false,LayerComponent.Mode.FREE);Core.settings.put("fpv-layer-free-permission",false);
                check(mod.construction.grid().size()==29,"existing free structure retains placement mode");
                layerSave=LayerCodec.encode(mod.construction.grid());
                SaveIO.write(output.child("four-storey-roundtrip.msav"));check(SaveIO.isSaveValid(output.child("four-storey-roundtrip.msav")),"native save valid with custom layer chunk");
                view(44,40,4,14);stage=1;stageFrame=frame;
            }else if(stage==1&&frame-stageFrame>30){
                check(mod.renderer3d.constructionRenderer().submittedChunks()>0,"upper geometry submitted through original renderer");
                check(Gl.getError()==0,"no GL error from construction geometry");capture("four-storey-base.png");
                SaveIO.load(output.child("four-storey-roundtrip.msav"));Vars.state.set(mindustry.core.GameState.State.playing);stage=2;stageFrame=frame;
            }else if(stage==2&&frame-stageFrame>30){
                check(Arrays.equals(layerSave,LayerCodec.encode(mod.construction.grid())),"native save round trip retains complete upper descriptor state");
                check(mapHash()==mapHash,"native world preserved through layer save round trip");
                mod.setEnabled(true);mod.setBuildMode(true);mod.layerBuild.setLayer(3);
                check(mod.buildControls.visible(),"Build controls remain available");check(mod.layerBuild.layer()==3,"selected construction level retained");mod.setBuildMode(false);
                Core.settings.put("fpv-collapse-grace-ms",100);Vars.world.tile(40,40).setAir();stage=3;stageFrame=frame;
            }else if(stage==3&& !mod.construction.falling().isEmpty()){
                check(mod.construction.grid().size()==1,"lost support detaches structural region, free wall remains");
                check(mod.construction.falling().size()==1,"connected four-storey base becomes one aggregate chunk");
                capture("falling-section.png");stage=4;stageFrame=frame;
            }else if(stage==4&&frame-stageFrame>80){
                check(mod.construction.falling().isEmpty(),"aggregate impact resolves and removes debris");
                check(mod.construction.grid().size()==1,"collapse does not duplicate any component");
                mod.construction.reset();Core.settings.put("fpv-layer-free-permission",true);
                var grid=mod.construction.grid();
                for(int y=60;y<85;y++)for(int x=40;x<80;x++)grid.add(new LayerComponent(grid.nextId(),LayerComponent.Kind.PLATFORM,x,y,12,LayerCatalog.PLATFORM,1,0,Team.sharded.id,LayerComponent.Mode.FREE,250,250,1,.16,LayerComponent.SupportProfile.NONE,null,null));
                check(grid.size()==1000,"stress scene contains 1000 physical platforms");
                view(59,72,16.8f,40);stage=5;stageFrame=frame;
            }else if(stage==5&&frame-stageFrame>35){
                stableBuilds=mod.renderer3d.constructionRenderer().meshBuilds();mod.renderer3d.constructionRenderer().resetProfile();cpuTotal=cpuMax=0;stage=6;stageFrame=frame;
            }else if(stage==6&&frame-stageFrame>=120){
                check(mod.renderer3d.constructionRenderer().meshBuilds()==stableBuilds,"1000 unchanged platforms reuse static meshes for 120 frames");
                check(Gl.getError()==0,"stress renderer completes without GL errors");
                put("platforms",1000);put("measuredFrames",120);put("constructionCpuMeanMs",cpuTotal/120);put("constructionCpuMaxMs",cpuMax);
                put("constructionGpuMeanMs",mod.renderer3d.constructionRenderer().gpuMeanMs());put("gpuSamples",mod.renderer3d.constructionRenderer().gpuSamples());put("visibleChunks",mod.renderer3d.constructionRenderer().submittedChunks());
                put("profileScope","Construction pass only, warm static meshes; full scene and input latency not included");capture("1000-platforms.png");
                mod.construction.reset();Core.settings.put("fpv-layer-free-permission",true);
                for(int y=65;y<=73;y++)for(int x=48;x<=62;x++)place(x,y,5,"",true,LayerComponent.Mode.FREE);
                place(50,66,5,"duo",false,LayerComponent.Mode.FREE);
                for(int x=54;x<=59;x++)place(x,66,5,"conveyor",false,LayerComponent.Mode.FREE);
                place(54,70,5,"graphite-press",false,LayerComponent.Mode.FREE);
                for(int x=58;x<=61;x++)place(x,71,5,"conduit",false,LayerComponent.Mode.FREE);
                place(61,66,5,"solar-panel",false,LayerComponent.Mode.FREE);place(60,68,5,"power-node",false,LayerComponent.Mode.FREE);
                for(var entry:mod.construction.nativeBuildings().entries()){
                    var b=entry.building();
                    if(b.block==Blocks.duo||b.block==Blocks.conveyor)b.handleItem(b,mindustry.content.Items.copper);
                    if(b.block==Blocks.graphitePress)b.items.add(mindustry.content.Items.coal,10);
                    if(b.block==Blocks.conduit)b.liquids.add(mindustry.content.Liquids.water,6);
                }
                view(55,69,8,16);stage=7;stageFrame=frame;
            }else if(stage==7&&frame-stageFrame>45){
                check(mod.construction.nativeBuildings().entries().size()==14,"native upper production and defense visual fixture");
                check(Gl.getError()==0,"native upper animation batch has no GL errors");capture("upper-production.png");
                mod.construction.reset();Core.settings.put("fpv-layer-free-permission",true);
                place(55,70,5,"shield-projector",false,LayerComponent.Mode.FREE);
                place(55,74,5,"power-source",false,LayerComponent.Mode.FREE);
                place(50,70,5,"door",false,LayerComponent.Mode.FREE);
                place(50,74,5,"blast-door",false,LayerComponent.Mode.FREE);
                place(61,74,5,"logic-display",false,LayerComponent.Mode.FREE);
                place(50,79,5,"canvas",false,LayerComponent.Mode.FREE);
                for(var entry:mod.construction.nativeBuildings().entries())if(entry.building() instanceof mindustry.world.blocks.defense.Door.DoorBuild door)door.open=true;
                for(var entry:mod.construction.nativeBuildings().entries()){
                    if(entry.building() instanceof mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild display)display.commands.addLast(mindustry.gen.DisplayCmd.get(mindustry.world.blocks.logic.LogicDisplay.commandClear,35,150,230,0,0,0));
                    if(entry.building() instanceof mindustry.world.blocks.logic.CanvasBlock.CanvasBuild canvas){var type=(mindustry.world.blocks.logic.CanvasBlock)canvas.block;for(int pixel=0;pixel<type.canvasSize*type.canvasSize;pixel++)canvas.setPixel(pixel,3);}
                }
                view(55,70,9,30);stage=8;stageFrame=frame;
            }else if(stage==8&&frame-stageFrame>45){
                boolean submitted=false;for(var entry:mod.construction.nativeBuildings().entries())if(entry.building().block==Blocks.shieldProjector)submitted=mod.renderer3d.wasForceProjectorSubmitted(entry.building());
                check(submitted,"native sandbox base shield uses actual 3D shell renderer");
                check(Gl.getError()==0,"base shield and streamed doors produce no GL errors");capture("upper-base-shield-doors.png");
                put("runStatus","passed");put("checks",checks);put("completed",java.time.Instant.now().toString());write();finished=true;Core.app.post(Core.app::exit);
            }
        }catch(Throwable failure){fail(failure);}
    }
    private void place(int x,int y,int layer,String block,boolean platform,LayerComponent.Mode mode){
        var result=mod.construction.requestPlace(x,y,layer,block,platform,0,mode);check(result.accepted(),"place "+block+" Z"+layer+" @"+x+","+y+": "+result.reason());
    }
    private void view(float x,float y,float z,float distance){camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.far=500;camera.fov=65;camera.position.set(x+distance,z+distance*.7f,-y+distance);camera.lookAt(x,z,-y);camera.update();}
    private void draw(){if(finished||stage==0||!Vars.state.isGame())return;try{mod.renderer3d.render(camera);if(stage==6){double cpu=mod.renderer3d.constructionRenderer().cpuMilliseconds();cpuTotal+=cpu;cpuMax=Math.max(cpuMax,cpu);}}catch(Throwable failure){fail(failure);}}
    private long mapHash(){long hash=17;for(var t:Vars.world.tiles){hash=hash*31+t.block().id;hash=hash*31+t.floor().id;hash=hash*31+t.overlay().id;hash=hash*31+t.team().id;}return hash;}
    private void capture(String name){ScreenUtils.saveScreenshot(output.child(name));}
    private void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private void put(String key,String value){report.add(key,value);}
    private void put(String key,double value){report.add(key,Jval.valueOf(value));}
    private void write(){output.child("layers-result.json").writeString(report.toString(Jval.Jformat.formatted));}
    private void fail(Throwable error){if(finished)return;finished=true;Log.err("[FPV-LAYERS-TEST] Failure",error);put("runStatus","failed");put("error",error.toString());put("stage",stage);put("checks",checks);write();Core.app.exit();}
}
