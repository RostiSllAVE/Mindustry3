package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.render.ProceduralUnitModelProfile;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.*;
import java.util.ArrayList;

/** Reversible native ownership/camera/trigger boundary; neither model nor test replaces control logic. */
final class FortressControlSmokeChecks {
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated SDL lab required");
        var original=Vars.player.unit();var unit=UnitTypes.fortress.create(original.team);unit.set(72*8,72*8);unit.rotation=90;unit.add();
        int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",105);boolean build=mod.buildMode,suspended=mod.input.isSuspended();float delta=Time.delta;
        var block=mod.input.block;int checks=0;
        try{
            mod.input.block=null;mod.setBuildMode(false);mod.input.setSuspended(false);Vars.player.unit(unit);Core.settings.put("fpv-pov-angle",90);Core.settings.put("fpv-camera-view",0);mod.resetPose();
            var pose=mod.world3d.unitState(unit);var profile=ProceduralUnitModelProfile.forType(unit.type);
            require(profile.cameraTopOffset(pose.halfHeight(),1,1,0,0)==0,"Fortress old camera envelope unchanged");checks++;
            double size=unit.hitSize/(double)Vars.tilesize,width=Math.max(unit.type.region.width,unit.type.region.height)*unit.type.region.scl()/Vars.tilesize;
            double oldTop=Math.max(pose.halfHeight(),fpv.render.SpriteExtrusionRenderer.halfDepth(unit));
            double oldEye=fpv.flight.GroundEyeHeight.desired(size,width,oldTop,0);
            for(int i=0;i<200;i++)mod.updateCamera(1/60.0);
            require(Math.abs(mod.camera.position.y-mod.flight.state().y-oldEye)<.003,"production first-person eye is identical to pre-profile Fortress");checks++;
            mod.renderer3d.render(mod.camera);require(mod.renderer3d.firstPersonRig().stats().weapons()>0,"original native artillery guns in FPV");checks++;capture("fortress-controlled-first.png");
            Time.delta=1;mod.updatePick();mod.input.applyFlightTrigger(true);int before=shots(unit);
            for(int i=0;i<90;i++)for(var mount:unit.mounts)mount.weapon.update(unit,mount);
            require(shots(unit)>before,"possessed Fortress actually fires through native trigger/mounts");checks++;
            mod.renderer3d.render(mod.camera);capture("fortress-controlled-firing.png");mod.input.applyFlightTrigger(false);
            for(int mode=1;mode<=2;mode++){
                fpv.ui.CameraView.cycle();for(int i=0;i<200;i++)mod.updateCamera(1/60.0);mod.updatePick();mod.renderer3d.render(mod.camera);
                require(fpv.ui.CameraView.selected()==mode&&mod.renderer3d.showControlledUnit()&&!mod.renderer3d.firstPersonActive(),"normal F7 world-model mode "+mode);checks++;
                require(mod.renderer3d.firstPersonRig().stats().weapons()==0,"no duplicate viewmodel "+mode);checks++;
                capture("fortress-controlled-"+(mode==1?"front":"rear")+".png");
            }
            require(mod.world3d.unitState(unit).equals(pose)&&unit.hitSize==13&&unit.type.health==900,"control rendering never changes physical XYZ/envelope/vanilla health");checks++;
            require(Gl.getError()==0,"controlled views GL0");checks++;
            return "PASS: "+checks+" native Fortress possession/first-person/front/rear/trigger checks; original artillery art, actual native firing, unchanged eye/physics, no viewmodel duplication, GL0";
        }finally{
            var bullets=new ArrayList<Bullet>();for(var bullet:Groups.bullet)if(bullet.owner==unit)bullets.add(bullet);for(var bullet:bullets){bullet.hit=true;bullet.remove();}
            Time.delta=delta;Vars.player.unit(original);unit.remove();Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.input.block=block;mod.input.setSuspended(suspended);mod.resetPose();mod.setBuildMode(build);
        }
    }
    private static int shots(Unit unit){int count=0;for(var mount:unit.mounts)count+=mount.totalShots;return count;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("Fortress control: "+label);}
}
