package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.math.geom.Vec3;
import arc.util.ScreenUtils;
import fpv.construction.*;
import fpv.flight.ObserverCamera;
import fpv.ui.CameraView;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.*;
import java.util.ArrayList;

/** Actual possession, production camera, terrain/layer queries and GPU in a disposable native SDL world. */
final class CameraModesSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated SDL lab required");checks=0;
        var original=Vars.player.unit();var owned=new ArrayList<Unit>();var components=new ArrayList<Long>();
        String[] keys={"fpv-camera-view",CameraView.DISTANCE,"fpv-pov-angle","fpv-layer-free-permission","fpv-camera-layout-v2",CameraView.SMOOTHING,CameraView.MODIFIER};
        Object[] saved=new Object[keys.length];for(int i=0;i<keys.length;i++)saved[i]=Core.settings.get(keys[i],null);
        boolean build=mod.buildMode,infinite=Vars.state.rules.infiniteResources;Player builder=null;
        StringBuilder report=new StringBuilder("Native 160.7 production camera measurements (tiles)\n");
        try{
            Core.settings.remove(CameraView.SMOOTHING);check(!CameraView.smoothing(),"smoothing defaults OFF for old and new profiles");
            shortcut(mod);
            Core.settings.put(CameraView.SMOOTHING,true); // Keep the previous animated-mode coverage.
            for(int old:new int[]{0,1,2}){
                Core.settings.put("fpv-camera-layout-v2",false);Core.settings.put("fpv-camera-view",old);
                int expected=old==1?CameraView.REAR:old==2?CameraView.FRONT:CameraView.FIRST;
                check(CameraView.selected()==expected&&CameraView.selected()==expected,"old camera preference migrates once without changing observer choice");
            }
            Core.settings.put(CameraView.DISTANCE,100);CameraView.zoom(1);check(CameraView.distance()==90,"wheel up brings camera closer");
            CameraView.zoom(Float.NaN);check(CameraView.distance()==90,"invalid wheel ignored");
            CameraView.zoom(-1000);check(CameraView.distance()==400,"maximum preference bounded");CameraView.zoom(1000);check(CameraView.distance()==25,"minimum preference bounded");
            mod.setBuildMode(false);Core.settings.put("fpv-camera-view",CameraView.REAR);Core.settings.put(CameraView.DISTANCE,100);
            var keyboard=mod.input.originalInput().getKeyboard();boolean alt=keyboard.isPressed(arc.input.KeyCode.altLeft);
            try{
                keyboard.keyDown(arc.input.KeyCode.altLeft);keyboard.scrolled(0,-1);mod.updateObserverZoom(false);
                check(CameraView.distance()==90,"real input backend Alt + wheel reaches shared camera handler");
                mod.setBuildMode(true);mod.updateObserverZoom(false);check(CameraView.distance()==90,"Build wheel not taken by observer zoom");
                check(mod.input.originalInput().axisTap(mindustry.input.Binding.rotate)==1,"Build's native rotate wheel is still available");
                mod.setBuildMode(false);mod.updateObserverZoom(true);check(CameraView.distance()==90,"suspended input cannot zoom");
                Core.settings.put("fpv-camera-view",0);mod.updateObserverZoom(false);check(CameraView.distance()==90,"FIRST wheel not taken by observer zoom");
            }finally{keyboard.scrolled(0,0);if(!alt)keyboard.keyUp(arc.input.KeyCode.altLeft);}
            mod.setBuildMode(false);Core.settings.put("fpv-pov-angle",90);Core.settings.put(CameraView.DISTANCE,100);
            double smallRange=0,largeRange=0;
            for(var type:new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.reign,UnitTypes.flare,UnitTypes.eclipse,UnitTypes.atrax,UnitTypes.toxopid,UnitTypes.risso}){
                if(type==UnitTypes.risso)for(int x=58;x<=62;x++)for(int y=58;y<=62;y++)Vars.world.tile(x,y).setFloor(Blocks.water.asFloor());
                Unit unit=type.create(Vars.player.team());unit.set(60*Vars.tilesize,60*Vars.tilesize);unit.rotation=90;unit.add();owned.add(unit);mod.unitAltitudes.pose(unit);
                Vars.player.unit(unit);Core.settings.put("fpv-camera-view",0);mod.resetPose();if(type.flying)mod.flight.state().y=16;
                Core.settings.put(CameraView.DISTANCE,100);settle(mod);mod.updatePick();var aim=mod.controlledAim();var body=mod.world3d.unitState(unit);
                var pose=new Vec3((float)mod.flight.state().x,(float)mod.flight.state().y,(float)mod.flight.state().z);
                var nativePosition=new Vec3(unit.x,unit.y,unit.rotation);
                check(!mod.renderer3d.showControlledUnit()&&mod.renderer3d.firstPersonActive(),type.name+" FIRST uses only viewmodel path");
                mod.renderer3d.render(mod.camera);check(mod.renderer3d.firstPersonRig().stats().weapons()>0,type.name+" actual first-person weapons still submitted");capture("camera-"+type.name+"-first.png");
                for(int view:new int[]{CameraView.FRONT,CameraView.REAR}){
                    CameraView.cycle();check(CameraView.selected()==view,type.name+" F7 cycles FIRST -> FRONT -> REAR");var before=mod.camera.position.cpy();mod.updateCamera(1/60.0);
                    check(mod.camera.position.dst(before)<ObserverCamera.distance(body.radius(),body.halfHeight(),100)*.5,type.name+" transition begins smoothly");
                    settle(mod);mod.updatePick();
                    check(view==CameraView.FRONT?mod.camera.position.z<pose.z:mod.camera.position.z>pose.z,type.name+" actual front/rear position "+view);
                    check(view==CameraView.FRONT?mod.camera.direction.z>0:mod.camera.direction.z<0,type.name+" observer looks at physical body "+view+" position="+mod.camera.position+" direction="+mod.camera.direction+" eye="+mod.pilotCamera.position+" distance="+mod.observerCamera.actualDistance());
                    check(mod.renderer3d.showControlledUnit()&&!mod.renderer3d.firstPersonActive(),type.name+" native world model without viewmodels "+view);
                    check(mod.controlledAim().equals(aim),type.name+" mode never changes actual aim ray "+view);
                    clearRay(mod,type.name);finite(mod,type.name);
                    mod.renderer3d.render(mod.camera);check(mod.renderer3d.firstPersonRig().stats().weapons()==0,type.name+" no duplicate first-person weapon submission in observer mode");
                    capture("camera-"+type.name+(view==CameraView.FRONT?"-front.png":"-rear-default.png"));
                }
                Core.settings.put(CameraView.DISTANCE,25);settle(mod);double near=mod.observerCamera.actualDistance();
                Core.settings.put(CameraView.DISTANCE,400);double oldDistance=near;mod.updateCamera(1/60.0);
                check(mod.observerCamera.actualDistance()<oldDistance+ObserverCamera.distance(body.radius(),body.halfHeight(),400)*.15,type.name+" zoom is eased");
                settle(mod);mod.updatePick();double far=mod.observerCamera.actualDistance();
                check(far>near*2,type.name+" useful per-size zoom range");check(mod.controlledAim().equals(aim),type.name+" zoom never changes aim ray");
                check(nativePosition.equals(new Vec3(unit.x,unit.y,unit.rotation))&&mod.world3d.unitState(unit).equals(body),type.name+" camera leaves pose and collision unchanged");
                report.append(type.name).append(": near=").append(near).append(", far=").append(far).append('\n');
                if(type==UnitTypes.flare)smallRange=far;if(type==UnitTypes.toxopid)largeRange=far;
                mod.renderer3d.render(mod.camera);capture("camera-"+type.name+"-rear.png");check(Gl.getError()==0,type.name+" native GPU no errors");
                CameraView.cycle();check(CameraView.selected()==CameraView.FIRST,type.name+" F7 returns to FIRST");settle(mod);
                check(mod.camera.position.dst(mod.pilotCamera.position)<.001,type.name+" FIRST recovers exact existing pilot eye");
                follow(mod,unit,type.name);
                // Keep completed specimens out of the next owner's view; do not overlap seven models.
                unit.set(95*Vars.tilesize,(90+owned.size()*3)*Vars.tilesize);
                if(type==UnitTypes.risso)for(int x=58;x<=62;x++)for(int y=58;y<=62;y++)Vars.world.tile(x,y).setFloor(Blocks.sand.asFloor());
            }
            check(largeRange>smallRange*2,"Toxopid maximum range scales beyond Flare");
            // A native stationary turret uses the same shared presentation path, above its own roof.
            var site=Vars.world.tile(60,60);site.setBlock(Blocks.duo,Vars.player.team(),0);mod.renderer3d.invalidate(site);
            var turret=(mindustry.world.blocks.defense.turrets.Turret.TurretBuild)site.build;
            Vars.player.unit(turret.unit());Core.settings.put(CameraView.DISTANCE,100);Core.settings.put("fpv-camera-view",0);mod.resetPose();settle(mod);mod.updatePick();
            var turretAim=mod.controlledAim();float tx=turret.x,ty=turret.y;
            for(int view=0;view<3;view++){
                Core.settings.put("fpv-camera-view",view);settle(mod);mod.updatePick();clearRay(mod,"turret");finite(mod,"turret");
                check(mod.renderer3d.firstPersonActive()==(view==0),"turret viewmodels only FIRST");
                check(view==0||mod.observerCamera.actualDistance()>1,"stationary turret boom is not trapped in own roof");
                check(mod.controlledAim().equals(turretAim)&&turret.x==tx&&turret.y==ty&&turret.unit().vel.isZero(),"turret camera cannot alter aim or fixed position");
            }
            mod.renderer3d.render(mod.camera);capture("camera-turret-rear.png");check(Gl.getError()==0,"turret GPU no errors");
            follow(mod,turret.unit(),"turret");
            owned.get(0).set(60*Vars.tilesize,60*Vars.tilesize);Vars.player.unit(owned.get(0));site.setBlock(Blocks.air);mod.renderer3d.invalidate(site);mod.resetPose();
            Core.settings.put("fpv-camera-view",2);Core.settings.put(CameraView.DISTANCE,300);settle(mod);
            double clearDistance=mod.observerCamera.actualDistance();
            Core.settings.put("fpv-layer-free-permission",true);Vars.state.rules.infiniteResources=true;
            Unit helper=UnitTypes.alpha.create(Vars.player.team());helper.set(60*8,60*8);helper.add();owned.add(helper);
            builder=Player.create();builder.team(Vars.player.team());builder.unit(helper);builder.add();
            for(int x=58;x<=62;x++){
                var placed=mod.construction.apply(builder,LayerActions.Action.place(x,56,1,"titanium-wall",false,0,LayerComponent.Mode.FREE));
                check(placed.accepted(),"elevated obstacle fixture: "+placed.reason());components.add(placed.componentId());
            }
            mod.updateCamera(1/60.0);check(mod.observerCamera.actualDistance()<clearDistance-1,"new upper wall retracts immediately");clearRay(mod,"upper wall");
            mod.renderer3d.render(mod.camera);capture("camera-upper-wall.png");
            for(long id:components)mod.construction.apply(builder,LayerActions.Action.remove(id));components.clear();
            double blocked=mod.observerCamera.actualDistance();mod.updateCamera(1/60.0);
            check(mod.observerCamera.actualDistance()>blocked&&mod.observerCamera.actualDistance()<clearDistance-.1,"obstacle removal recovers smoothly");settle(mod);
            // Build a genuine natural-wall height field behind the unit; original terrain generation is reused.
            for(int y=51;y<=56;y++)for(int x=57;x<=63;x++)Vars.world.tile(x,y).setBlock(Blocks.duneWall);
            mod.renderer3d.terrain().invalidate();mod.renderer3d.terrain().ensureWorld();mod.updateCamera(1/60.0);
            check(mod.observerCamera.actualDistance()<clearDistance-1,"generated mountain retracts camera");clearRay(mod,"mountain");
            mod.renderer3d.render(mod.camera);capture("camera-mountain.png");check(Gl.getError()==0,"mountain camera GPU no errors");
            for(int y=51;y<=56;y++)for(int x=57;x<=63;x++)Vars.world.tile(x,y).setBlock(Blocks.air);
            mod.renderer3d.terrain().invalidate();mod.renderer3d.terrain().ensureWorld();
            // Actual overhead floor; eye and camera must stay on the near side of the slab.
            var roof=mod.construction.apply(builder,LayerActions.Action.place(60,60,2,"",true,0,LayerComponent.Mode.FREE));
            check(roof.accepted(),"enclosed floor fixture");components.add(roof.componentId());
            mod.freeLook.look(0,-70);settle(mod);clearRay(mod,"enclosed floor");
            mod.renderer3d.render(mod.camera);capture("camera-enclosed.png");check(Gl.getError()==0,"enclosed camera GPU no errors");
            for(long id:components)mod.construction.apply(builder,LayerActions.Action.remove(id));components.clear();
            mod.setBuildMode(true);mod.updateCamera();check(!mod.observerCamera.active()&&!mod.renderer3d.firstPersonActive()&&!mod.renderer3d.showControlledUnit(),"Build retains original builder camera and native wheel controls");
            mod.setBuildMode(false);settle(mod);check(CameraView.distance()==300&&mod.observerCamera.active(),"Build exit restores preferred mode/distance");
            Vars.player.unit(owned.get(2));mod.resetPose();check(CameraView.distance()==300,"possession preserves zoom preference");
            owned.get(2).dead=true;mod.resetPose();check(!mod.observerCamera.active()&&!mod.ready,"death clears temporary boom");owned.get(2).dead=false;
            Vars.player.unit(owned.get(0));mod.resetPose();settle(mod);check(mod.ready&&mod.observerCamera.active(),"normal camera resumes on live replacement");
            report.append("PASS: ").append(checks).append(" checks; 7 native unit families + stationary turret, XYZ aim invariance, zoom, GPU, upper wall, generated mountain, roof, Build, possession/death\n");
            new Fi(System.getProperty("fpv.smoke.output")).child("camera-measurements.txt").writeString(report.toString());return report.toString();
        }finally{
            if(builder!=null){for(long id:components)mod.construction.apply(builder,LayerActions.Action.remove(id));builder.remove();}
            Vars.world.tile(60,60).setBlock(Blocks.air);
            for(int x=58;x<=62;x++)for(int y=58;y<=62;y++)Vars.world.tile(x,y).setFloor(Blocks.sand.asFloor());
            for(int y=51;y<=56;y++)for(int x=57;x<=63;x++)Vars.world.tile(x,y).setBlock(Blocks.air);
            mod.renderer3d.terrain().invalidate();
            for(int i=0;i<keys.length;i++)if(saved[i]==null)Core.settings.remove(keys[i]);else Core.settings.put(keys[i],saved[i]);
            Vars.state.rules.infiniteResources=infinite;Vars.player.unit(original);mod.resetPose();for(var unit:owned)if(unit.isAdded())unit.remove();mod.setBuildMode(build);
        }
    }
    private static void settle(FpvMod mod){for(int i=0;i<200;i++)mod.updateCamera(1/60.0);}
    private static void shortcut(FpvMod mod){
        var key=CameraView.smoothingKey.value;var keyboard=mod.input.originalInput().getKeyboard();
        Core.settings.put(CameraView.MODIFIER,1);Core.settings.put("fpv-camera-view",0);
        try{
            keyboard.keyDown(arc.input.KeyCode.altLeft);keyboard.keyDown(arc.input.KeyCode.f7);
            check(mod.cameraSmoothingShortcut()&&CameraView.smoothing(),"Alt + F7 toggles smoothing ON");
            check(CameraView.selected()==0&&CameraView.feedback().equals("CAMERA SMOOTHING ON"),"toggle consumes F7 and supplies HUD confirmation");
            keyboard.keyUp(arc.input.KeyCode.f7);keyboard.keyUp(arc.input.KeyCode.altLeft);
            check(!mod.cameraSmoothingShortcut(),"plain F7 does not toggle smoothing");
            CameraView.smoothingKey.value=new arc.input.KeyBind.Axis(arc.input.KeyCode.f11);
            keyboard.keyDown(arc.input.KeyCode.altLeft);keyboard.keyDown(arc.input.KeyCode.f11);
            check(mod.cameraSmoothingShortcut()&&!CameraView.smoothing(),"rebound Alt + F11 toggles OFF");
            check(CameraView.feedback().equals("CAMERA SMOOTHING OFF"),"OFF HUD confirmation");
            Core.settings.put(CameraView.MODIFIER,2);check(!mod.cameraSmoothingShortcut(),"rebound modifier rejects Alt");
            keyboard.keyUp(arc.input.KeyCode.altLeft);keyboard.keyDown(arc.input.KeyCode.controlLeft);
            check(mod.cameraSmoothingShortcut()&&CameraView.smoothing(),"rebound Ctrl + F11 works");
            var focus=new arc.scene.Element();Core.scene.add(focus);Core.scene.setKeyboardFocus(focus);
            try{check(!mod.cameraSmoothingShortcut(),"typing/UI focus blocks shortcut");}finally{Core.scene.setKeyboardFocus(null);focus.remove();}
        }finally{
            CameraView.smoothingKey.value=key;
            for(var code:new arc.input.KeyCode[]{arc.input.KeyCode.altLeft,arc.input.KeyCode.controlLeft,arc.input.KeyCode.f7,arc.input.KeyCode.f11})keyboard.keyUp(code);
        }
    }
    private static void follow(FpvMod mod,Unit unit,String name){
        var s=mod.flight.state();double x=s.x,z=s.z,qx=s.qx,qy=s.qy,qz=s.qz,qw=s.qw;float ux=unit.x,uy=unit.y,rotation=unit.rotation;
        for(boolean smooth:new boolean[]{false,true}){
            Core.settings.put(CameraView.SMOOTHING,smooth);Core.settings.put(CameraView.DISTANCE,100);
            Core.settings.put("fpv-camera-view",CameraView.REAR);settle(mod);
            var previous=mod.camera.position.cpy();boolean movable=!(unit instanceof BlockUnitc);
            if(movable){s.x+=.35;s.z-=.2;unit.set((float)(s.x*Vars.tilesize),(float)(-s.z*Vars.tilesize));}
            mod.updateCamera(1/60.0);
            check(mod.camera.position.dst(previous.x+(movable?.35f:0),previous.y,previous.z-(movable?.2f:0))<.005,name+" current-frame translation/fixed turret, smoothing="+smooth);
            if(mod.flightMode==0){s.qx=s.qz=0;s.qy=-Math.sqrt(.5);s.qw=Math.sqrt(.5);unit.rotation=0;}
            else mod.freeLook.align(1,0,0);
            mod.updateCamera(1/60.0);centered(mod,name+" turning, smoothing="+smooth);
            check(mod.camera.position.x<mod.pilotCamera.position.x,name+" rear follows the new pilot heading without world lag");
            mod.freeLook.align(0,0,-1);s.x=x;s.z=z;s.qx=qx;s.qy=qy;s.qz=qz;s.qw=qw;unit.set(ux,uy);unit.rotation=rotation;mod.updateCamera(1/60.0);
            if(!smooth){
                for(int view:new int[]{CameraView.FRONT,CameraView.REAR}){
                    Core.settings.put("fpv-camera-view",view);mod.updateCamera(0);centered(mod,name+" immediate F7 "+view);
                    check(view==CameraView.FRONT?mod.camera.position.z<s.z:mod.camera.position.z>s.z,name+" OFF has no mode animation");
                }
                double near=mod.observerCamera.actualDistance();Core.settings.put(CameraView.DISTANCE,400);mod.updateCamera(0);
                check(mod.observerCamera.actualDistance()>near*2,name+" OFF zoom is immediate even with zero elapsed time");centered(mod,name+" immediate zoom");
                Core.settings.put("fpv-camera-view",0);mod.updateCamera(0);
                check(mod.camera.position.dst(mod.pilotCamera.position)<.0001&&!mod.observerCamera.active(),name+" OFF FIRST has no residual boom");
            }else{
                Core.settings.put("fpv-camera-view",CameraView.FRONT);var before=mod.camera.position.cpy();mod.updateCamera(1/60.0);
                check(mod.camera.position.dst(before)<ObserverCamera.distance(mod.world3d.unitState(unit).radius(),mod.world3d.unitState(unit).halfHeight(),100),name+" ON local orbit is animated");
                centered(mod,name+" ON camera rotates with its actual position during the orbit");
                settle(mod);centered(mod,name+" ON front fully centered");
            }
        }
        Core.settings.put(CameraView.SMOOTHING,true);Core.settings.put(CameraView.DISTANCE,100);
        Core.settings.put("fpv-camera-view",0);settle(mod);
    }
    private static void centered(FpvMod mod,String label){var s=mod.flight.state();var target=new Vec3((float)s.x,(float)s.y,(float)s.z).sub(mod.camera.position).nor();
        check(target.dot(mod.camera.direction)>.9999,label+" has no independent delayed recentering");clearRay(mod,label);}
    private static void clearRay(FpvMod mod,String label){var a=mod.pilotCamera.position;var b=mod.camera.position;
        check(!mod.world3d.traceSegment(a.x,-a.z,a.y,b.x,-b.z,b.y,.10).hit(),label+" camera remains in visible clear volume");}
    private static void finite(FpvMod mod,String label){for(float f:mod.camera.combined.val)check(Float.isFinite(f),label+" finite projection");}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError("Camera modes: "+label);}
}
