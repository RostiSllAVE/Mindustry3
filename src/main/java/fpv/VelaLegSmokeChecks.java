package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.render.GroundFamilyRenderer;
import fpv.units.ground.GroundUnitProfiles;
import fpv.units.ground.NativeMechLayout;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Mechc;
import mindustry.world.Tile;

/** Actual installed sprite, stance/Q/E boundary, production FPV/rear rendering; disposable SDL lab only. */
final class VelaLegSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        checks=0;check(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated native lab");
        var old=Vars.player.unit();var unit=UnitTypes.vela.create(old.team);unit.set(80*Vars.tilesize,80*Vars.tilesize);unit.rotation=0;((Mechc)unit).baseRotation(0);unit.add();
        int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",105);
        boolean build=mod.buildMode,suspended=mod.input.isSuspended(),smoothing=fpv.ui.CameraView.smoothing();
        boolean first=mod.renderer3d.firstPersonActive(),show=mod.renderer3d.showControlledUnit();
        var block=mod.input.block;float delta=Time.delta,hp=unit.health;Tile support=null;
        boolean free=Core.settings.getBool("fpv-layer-free-permission",false),infinite=Vars.state.rules.infiniteResources;
        mindustry.gen.Player builderActor=null;mindustry.gen.Unit builder=null;long platformId=0;
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=2000;camera.fov=44;
        try{
            Time.delta=1;Core.settings.put(fpv.ui.CameraView.SMOOTHING,false);mod.input.block=null;mod.setBuildMode(false);mod.input.setSuspended(false);Vars.player.unit(unit);
            mod.renderer3d.setFirstPersonActive(false);mod.renderer3d.setShowControlledUnit(true);
            var profile=GroundUnitProfiles.profile(unit);
            check(unit instanceof Mechc&&profile.legCount()==2,"native Vela bilateral mech, not Corvus four-leg rig");
            check(unit.type.legRegion.width==122&&unit.type.legRegion.height==104,"installed Vela whole-leg artwork resolved");
            check(UnitTypes.corvus.legCount==4,"native Corvus unchanged");
            var hull=fpv.render.ProceduralUnitModelProfile.forType(unit.type);
            check(hull!=null&&hull.hull().optimized()&&Math.abs(hull.bodyThickness()-.96)<1e-5,"Vela uses heavy optimized mech hull");
            check(fpv.flight.GroundEyeHeight.ordinary(unit),"Vela uses the improved ordinary mech eye");
            for(int tick=0;tick<200;tick++){mod.unitAltitudes.setGroundStanceControl(unit,false,0,.1);mod.unitAltitudes.update(.1);}
            var rest=mod.unitAltitudes.unitState(unit);var restPose=mod.unitAltitudes.groundFamily().pose(unit);
            check(Math.abs(rest.z()-rest.halfHeight()-restPose.supportHeight()-1.10)<.08,"compact resting stance instead of random walker height");
            camera.up.set(0,1,0);camera.position.set((float)rest.x()+6,3,(float)-rest.y()-6);camera.lookAt((float)rest.x(),(float)rest.z(),(float)-rest.y());camera.update();
            mod.renderer3d.render(camera);capture("vela-mech-resting-oblique.png");
            var canonicalBefore=mod.unitAltitudes.unitState(unit);
            check(mod.renderer3d.groundFamily().visualLegCount(unit)==3&&profile.legCount()==2,"three visual feet, only two authoritative support samples");
            for(int leg=0;leg<3;leg++)check(Double.isFinite(mod.renderer3d.groundFamily().footHeight(unit,leg)),"finite tripod foot "+leg);
            check(mod.unitAltitudes.unitState(unit).equals(canonicalBefore),"tripod render leaves canonical body/physics unchanged");
            var hullGeometry=mod.renderer3d.unitGeometry(unit.type.region);
            check(hullGeometry!=null&&hullGeometry.sideQuads()>4,"cached alpha-contour exterior hull, not a billboard or box");
            camera.position.set((float)rest.x(),(float)rest.z()+9,(float)-rest.y()+.01f);camera.lookAt((float)rest.x(),(float)rest.z(),(float)-rest.y());camera.update();mod.renderer3d.render(camera);capture("vela-mech-resting-top.png");
            camera.up.set(0,1,0);camera.position.set((float)rest.x()-7,.9f,(float)-rest.y());camera.lookAt((float)rest.x(),(float)(rest.z()*.5),(float)-rest.y());camera.update();mod.renderer3d.render(camera);capture("vela-tripod-rear.png");
            double maximum=0,minimum=0;
            for(int stance=0;stance<2;stance++){
                for(int tick=0;tick<250;tick++){mod.unitAltitudes.setGroundStanceControl(unit,true,stance==0?-1:1,.1);mod.unitAltitudes.update(.1);}
                var pose=mod.unitAltitudes.unitState(unit);var physical=mod.unitAltitudes.groundFamily().pose(unit);
                double clearance=pose.z()-pose.halfHeight()-physical.supportHeight();
                if(stance==0)minimum=clearance;else maximum=clearance;
                check(Math.abs(clearance-(stance==0?profile.normalStance():profile.maximumClearance()))<.08,"native E/Q endpoint "+stance);
                check(!pose.flying()&&!unit.isFlying(),"extension stays grounded");
                for(int direction=0;direction<3;direction++){
                    double distance=Math.max(7,pose.z()*1.8),a=Math.toRadians(direction==0?90:35);
                    camera.up.set(0,1,0);
                    if(direction==2)camera.position.set((float)pose.x(),(float)pose.z()+8,(float)-pose.y()+.01f);
                    else camera.position.set((float)(pose.x()+Math.cos(a)*distance),(float)(pose.z()*.5+1),(float)(-pose.y()-Math.sin(a)*distance));
                    camera.lookAt((float)pose.x(),(float)(direction==2?pose.z():pose.z()*.5),(float)-pose.y());camera.update();
                    mod.renderer3d.render(camera);check(mod.renderer3d.groundFamily().wasSubmitted(unit),"Vela submitted at stance/view "+stance+"/"+direction);
                    capture("vela-"+(stance==0?"min":"max")+"-"+new String[]{"side","oblique","top"}[direction]+".png");
                }
            }
            check(maximum>minimum*2,"full visual telescoping tested");
            check(Math.abs(mod.unitAltitudes.groundFamily().pose(unit).speedFraction()-profile.minSpeedFraction())<.001,"extension speed penalty unchanged");
            // One native foot on a real solid support, the other on ground. Renderer consumes the shared gait.
            double fx=unit.x/Vars.tilesize+NativeMechLayout.footX(unit.type,1,unit.rotation);
            double fy=unit.y/Vars.tilesize+NativeMechLayout.footY(unit.type,1,unit.rotation);
            support=Vars.world.tile((int)Math.floor(fx+.5),(int)Math.floor(fy+.5));check(support!=null&&support.block()==Blocks.air,"empty owned terrain fixture");support.setBlock(Blocks.copperWall,unit.team,0);
            for(int tick=0;tick<150;tick++){mod.unitAltitudes.setGroundStanceControl(unit,true,0,.1);mod.unitAltitudes.update(.1);}
            // Keep the external camera oblique while examining the independent support heights.
            var uneven=mod.unitAltitudes.unitState(unit);double distance=Math.max(7,uneven.z()*1.8);
            camera.up.set(0,1,0);camera.position.set((float)uneven.x()+ (float)(distance*.7),(float)(uneven.z()*.5+1),(float)-uneven.y()-(float)(distance*.7));
            camera.lookAt((float)uneven.x(),(float)(uneven.z()*.5),(float)-uneven.y());camera.update();
            mod.renderer3d.groundFamily().clear();mod.renderer3d.render(camera);
            var family=mod.renderer3d.groundFamily();double foot0=family.footHeight(unit,0),foot1=family.footHeight(unit,1);
            check(foot1>foot0+.15,"separate physical terrain support on actual feet");capture("vela-uneven-terrain.png");
            support.setBlock(Blocks.air);support=null;
            // Elevated support for the visual rear leg, never a third authoritative stance sample.
            double rearX=unit.x/Vars.tilesize+fpv.render.VelaTripodLayout.footX(2,Math.toRadians(unit.rotation),NativeMechLayout.spread(unit.type));
            double rearY=unit.y/Vars.tilesize+fpv.render.VelaTripodLayout.footY(2,Math.toRadians(unit.rotation),NativeMechLayout.spread(unit.type));
            Core.settings.put("fpv-layer-free-permission",true);Vars.state.rules.infiniteResources=true;
            builder=UnitTypes.alpha.create(unit.team);builder.set(unit.x,unit.y);builder.add();builderActor=mindustry.gen.Player.create();builderActor.team(unit.team);builderActor.unit(builder);builderActor.add();
            var placed=mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.place((int)Math.floor(rearX+.5),(int)Math.floor(rearY+.5),1,"",true,0,fpv.construction.LayerComponent.Mode.FREE));
            check(placed.accepted(),"owned rear elevated-platform fixture: "+placed.reason());platformId=placed.componentId();
            var bounds=mod.construction.grid().coordinates().bounds(mod.construction.grid().get(platformId));
            var unchanged=mod.unitAltitudes.unitState(unit);family.clear();mod.renderer3d.render(camera);
            check(family.footHeight(unit,2)>=bounds.maxZ()-.01,"third visual foot follows actual elevated surface");
            check(mod.unitAltitudes.unitState(unit).equals(unchanged),"rear visual support cannot raise body or change hitbox");capture("vela-tripod-elevated-support.png");
            mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.remove(platformId));platformId=0;builderActor.remove();builderActor=null;builder.remove();builder=null;
            Core.settings.put("fpv-layer-free-permission",free);Vars.state.rules.infiniteResources=infinite;
            for(int tick=0;tick<250;tick++){mod.unitAltitudes.setGroundStanceControl(unit,false,0,.1);mod.unitAltitudes.update(.1);}
            for(int mode:new int[]{0,2}){
                Core.settings.put("fpv-camera-view",mode);Core.settings.put("fpv-pov-angle",90);mod.resetPose();for(int tick=0;tick<100;tick++)mod.updateCamera(1/60d);
                mod.renderer3d.render(mod.camera);check(family.wasSubmitted(unit),"native legs visible in controlled "+mode);
                check(mod.renderer3d.firstPersonActive()==(mode==0),"correct first/rear view "+mode);capture("vela-controlled-"+(mode==0?"fpv":"rear")+".png");
                if(mode==0){
                    var body=mod.world3d.unitState(unit);double expected=fpv.flight.GroundEyeHeight.desired(unit,body.halfHeight());
                    check(Math.abs(mod.camera.position.y-mod.flight.state().y-expected)<.003,"production mech eye clears new Vela roof");
                    mod.freeLook.align(-1,-1,0);mod.updateCamera(1/60d);mod.renderer3d.render(mod.camera);
                    check(family.visualLegCount(unit)==3,"FPV uses the same tripod rig");capture("vela-tripod-fpv-rear-down.png");
                }
            }
            long builds=family.nativeLimbStats().builds(),bodyBuilds=mod.renderer3d.extrusionStats().builds();for(int frame=0;frame<12;frame++)mod.renderer3d.render(mod.camera);
            check(family.nativeLimbStats().builds()==builds,"no per-frame limb geometry generation");
            check(mod.renderer3d.extrusionStats().builds()==bodyBuilds,"no per-frame mech hull geometry generation");
            check(family.nativeLimbVertices(unit.type)>0,"cached original-art mesh exists");
            check(family.nativeLimbBatches(unit.type)==1,"three chains and base share one cached atlas batch");
            verifyPlayerMovement(mod,unit);
            check(unit.health==hp&&unit.hitSize==24&&unit.type.health==8200&&unit.type.speed==.44f,"native HP/hitbox/movement metadata unchanged");
            check(Gl.getError()==0,"articulated and FPV shader GL0");
            String result="PASS: "+checks+" Vela visual tripod/unchanged hull/compact rest/native two-foot physics/Q-E/min-max/terrain/elevated support/FPV/rear/cache checks; hull vertices="+hullGeometry.vertices()+", limb vertices="+family.nativeLimbVertices(unit.type)+", cache="+family.nativeLimbStats()+"; real forward/backward/left/right gallery phases captured separately; Corvus original four legs retained";
            new Fi(System.getProperty("fpv.smoke.output")).child("vela-leg-results.txt").writeString(result);return result;
        }finally{
            if(platformId!=0&&builderActor!=null)mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.remove(platformId));if(builderActor!=null)builderActor.remove();if(builder!=null)builder.remove();
            Core.settings.put("fpv-layer-free-permission",free);Vars.state.rules.infiniteResources=infinite;
            if(support!=null)support.setBlock(Blocks.air);mod.unitAltitudes.setGroundStanceControl(unit,false,0,0);Vars.player.unit(old);unit.remove();Time.delta=delta;
            Core.settings.put(fpv.ui.CameraView.SMOOTHING,smoothing);Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.input.block=block;mod.input.setSuspended(suspended);mod.resetPose();mod.setBuildMode(build);
            mod.renderer3d.setFirstPersonActive(first);mod.renderer3d.setShowControlledUnit(show);
        }
    }
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    /** Script the actual native pilot boundary; the real-frame gallery separately verifies visual phases. */
    private static void verifyPlayerMovement(FpvMod mod,mindustry.gen.Unit unit){
        var pilot=new fpv.control.GroundedPilot();float before=unit.x;
        for(int direction:new int[]{1,-1}){
            float start=unit.x;
            var controls=new fpv.net.NetworkControls(true,3,0,0,0,0,direction,0,0,0,Math.PI/2,1,0,0,false);
            for(int frame=0;frame<120;frame++){
                pilot.step(Vars.player,unit,controls,mod.unitAltitudes,1/60d);mod.unitAltitudes.captureGroundIntent(1/60d);unit.update();mod.unitAltitudes.update(1/60d);
            }
            check(direction*(unit.x-start)>.05,"native player moves "+(direction>0?"forward":"backward"));
            check(!unit.isFlying()&&GroundUnitProfiles.profile(unit).legCount()==2,"tripod never changes grounded native controller");
        }
        unit.vel.setZero();check(Float.isFinite(unit.x-before),"finite native player displacement");
    }
    private static void check(boolean value,String label){checks++;if(!value)throw new IllegalStateException("Vela legs: "+label);}
}
