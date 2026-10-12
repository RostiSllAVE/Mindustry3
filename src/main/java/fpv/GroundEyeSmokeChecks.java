package fpv;

import arc.Core;
import arc.files.Fi;
import arc.util.ScreenUtils;
import fpv.flight.GroundEyeHeight;
import fpv.construction.LayerComponent;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;
import java.util.ArrayList;

/** Real possession, production camera, native flat-ground tangent and bounded upper roof fixture. */
final class GroundEyeSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        check(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated SDL lab");checks=0;
        var original=Vars.player.unit();var owned=new ArrayList<Unit>();
        int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",105);
        boolean build=mod.buildMode,free=Core.settings.getBool("fpv-layer-free-permission",false);
        boolean infinite=Vars.state.rules.infiniteResources;
        var settings=new java.util.HashMap<String,Object>();long roofId=0;mindustry.gen.Player builderActor=null;
        StringBuilder report=new StringBuilder("Native SDL ground-camera measurements (tiles; original physical body unchanged)\n");
        try{
            settings.put(fpv.ui.CameraView.SMOOTHING,Core.settings.get(fpv.ui.CameraView.SMOOTHING,null));
            Core.settings.put(fpv.ui.CameraView.SMOOTHING,true); // Exercise the preserved animated handoff explicitly.
            mod.setBuildMode(false);Core.settings.put("fpv-camera-view",0);Core.settings.put("fpv-pov-angle",90);
            double largest=0;
            for(var type:new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.fortress,UnitTypes.reign}){
                Unit unit=type.create(Vars.player.team());unit.set(60*Vars.tilesize,60*Vars.tilesize);unit.rotation=90;unit.add();owned.add(unit);mod.unitAltitudes.pose(unit);
                String key=GroundEyeHeight.settingKey(unit);settings.put(key,Core.settings.get(key,null));Core.settings.put(key,0);
                Vars.player.unit(unit);mod.resetPose();check(GroundEyeHeight.ordinary(unit),type.name+" ordinary ground classification");
                var body=mod.world3d.unitState(unit);double target=GroundEyeHeight.desired(unit,body.halfHeight(),body.pitch(),body.roll());
                double first=mod.camera.position.y-mod.flight.state().y;
                check(first+.001>=GroundEyeHeight.minimum(unit,body.halfHeight(),body.pitch(),body.roll()),type.name+" handoff starts above own model before easing");
                for(int i=0;i<90;i++)mod.updateCamera(1/60.0);
                double actual=mod.camera.position.y-mod.flight.state().y;
                check(Math.abs(actual-target)<.003,type.name+" eye reaches sized profile target instead of floor-contact fraction0");
                check(Math.abs(first-target)>.001,type.name+" control handoff smooths rather than snapping offset");
                check(mod.camera.position.y>body.z()+body.halfHeight()+GroundEyeHeight.RADIUS,type.name+" camera above own hull");
                check(unit.rotation==90&&Math.abs(mod.camera.direction.z+1)<1e-5&&Math.abs(mod.camera.direction.y)<1e-5,type.name+" yaw/aim direction preserved");
                check(mod.world3d.unitState(unit).equals(body),type.name+" camera never changes physical XYZ or hitbox");
                mod.renderer3d.render(mod.camera);capture("ground-eye-"+type.name+"-fpv.png");
                mod.freeLook.align(0,-.4,-1);mod.updateCamera(1/60.0);mod.updatePick();
                var hit=mod.renderer3d.pick(mod.camera,Core.graphics.getWidth()/2f,Core.graphics.getHeight()/2f);
                check(hit!=null,type.name+" elevated eye resolves actual ground sight target");
                var aim=mod.controlledAim();double distance=Math.hypot(hit.worldX/Vars.tilesize-body.x(),hit.worldY/Vars.tilesize-body.y());
                double zAtTarget=body.z()+aim.z()*distance/Math.hypot(aim.x(),aim.y());
                check(Math.abs(zAtTarget-hit.height)<.005,type.name+" raised-eye reticle converges from unchanged firing height");
                mod.freeLook.align(0,0,-1);mod.updateCamera(1/60.0);mod.updatePick();
                report.append(type.name).append(": physicalZ=").append(body.z()).append(", halfHeight=").append(body.halfHeight())
                    .append(", eyeZ=").append(mod.camera.position.y).append(", eyeLift=").append(actual).append(", target=").append(target).append('\n');
                if(type==UnitTypes.reign)largest=actual;
            }
            Unit large=Vars.player.unit();String key=GroundEyeHeight.settingKey(large);Core.settings.put(key,800);
            for(int i=0;i<90;i++)mod.updateCamera(1/60.0);
            check(mod.camera.position.y-mod.flight.state().y>7.9,"per-unit override is actually applied");
            Core.settings.put("fpv-layer-free-permission",true);
            Vars.state.rules.infiniteResources=true; // Roof geometry fixture; resource accounting is tested separately.
            Unit builder=UnitTypes.alpha.create(Vars.player.team());builder.set(60*Vars.tilesize,60*Vars.tilesize);builder.add();owned.add(builder);
            builderActor=mindustry.gen.Player.create();builderActor.team(Vars.player.team());builderActor.unit(builder);builderActor.add();
            var placed=mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.place(60,60,1,"",true,0,LayerComponent.Mode.FREE));
            check(placed.accepted(),"owned test roof placement: "+placed.reason());roofId=placed.componentId();
            var bounds=mod.construction.grid().coordinates().bounds(mod.construction.grid().get(roofId));
            mod.updateCamera(1/60.0);
            check(mod.camera.position.y+GroundEyeHeight.RADIUS<bounds.minZ(),"new roof clamps immediately below ceiling without crossing slab");
            check(mod.camera.position.y>mod.flight.state().y,"eye retains available headroom");
            report.append("Reign roof: minZ=").append(bounds.minZ()).append(", eyeZ=").append(mod.camera.position.y).append(" (radius=").append(GroundEyeHeight.RADIUS).append(")\n");
            mod.renderer3d.render(mod.camera);capture("ground-eye-reign-roof.png");
            mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.remove(roofId));roofId=0;Core.settings.put(key,0);
            double low=mod.camera.position.y;mod.updateCamera(1/60.0);
            check(mod.camera.position.y>low&&mod.camera.position.y-mod.flight.state().y<largest+.01,"leaving roof recovers smoothly");
            Unit small=owned.get(0);Core.settings.put(GroundEyeHeight.settingKey(small),5);Vars.player.unit(small);mod.resetPose();
            for(int i=0;i<90;i++)mod.updateCamera(1/60.0);
            double minimum=GroundEyeHeight.desired(small,mod.world3d.unitState(small).halfHeight());
            check(Math.abs(mod.camera.position.y-mod.flight.state().y-minimum)<.003,"too-low override is bounded above actual thick Dagger hull");
            for(var type:new mindustry.type.UnitType[]{UnitTypes.flare,UnitTypes.toxopid}){
                Unit unit=type.create(Vars.player.team());unit.set(70*Vars.tilesize,70*Vars.tilesize);unit.rotation=90;unit.add();owned.add(unit);mod.unitAltitudes.pose(unit);
                check(!GroundEyeHeight.ordinary(unit),type.name+" excluded from ordinary ground camera");Vars.player.unit(unit);mod.resetPose();
                for(int i=0;i<150;i++)mod.updateCamera(1/60.0); // Ground release settles; ordinary flying/legged behavior then remains exact.
                var state=mod.flight.state();var body=mod.world3d.unitState(unit);
                double expected=state.y;
                if(!type.flying){double lift=Math.max(.72,body.halfHeight()*.35);var hit=mod.world3d.traceSegment(state.x,-state.z,state.y,state.x,-state.z,state.y+lift,.12);expected+=lift*(hit.hit()?Math.max(0,hit.fraction()-.025):1);}
                check(Math.abs(mod.camera.position.y-expected)<1e-4,type.name+" existing flying/legged camera remains exact");
            }
            report.append("PASS: ").append(checks).append(" production-camera checks; flat terrain, sizes, own hull, override, possession easing, actual upper ceiling, recovery, native aim/physics, flying/legged unchanged\n");
            new Fi(System.getProperty("fpv.smoke.output")).child("ground-eye-measurements.txt").writeString(report.toString());return report.toString();
        }finally{
            if(roofId!=0&&builderActor!=null)mod.construction.apply(builderActor,fpv.construction.LayerActions.Action.remove(roofId));
            if(builderActor!=null)builderActor.remove();
            for(var entry:settings.entrySet())if(entry.getValue()==null)Core.settings.remove(entry.getKey());else Core.settings.put(entry.getKey(),entry.getValue());
            Core.settings.put("fpv-layer-free-permission",free);Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);
            Vars.state.rules.infiniteResources=infinite;
            Vars.player.unit(original);mod.resetPose();for(Unit unit:owned)if(unit.isAdded())unit.remove();mod.setBuildMode(build);
        }
    }
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError("Ground eye: "+message);}
}
