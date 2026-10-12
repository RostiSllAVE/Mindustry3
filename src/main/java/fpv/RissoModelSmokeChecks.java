package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.render.NavalModelVisualChecks;
import fpv.render.NavalUnitRenderer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.*;
import mindustry.world.blocks.environment.Floor;
import java.util.ArrayList;

/** Real native water movement frames and scripted production possession/trigger/F7 in disposable world. */
final class RissoModelSmokeChecks implements AutoCloseable {
    private final FpvMod mod;private final Unit original;private Unit unit;private final Floor[] floors=new Floor[64*64];
    private final Camera3D camera=new Camera3D();private int ticks,phase,checks,index;private float startX,startY,startYaw;private final StringBuilder gallery=new StringBuilder();
    private float nativeSpeed,nativeHealth,nativeHitSize;private boolean closed;
    private final int view,angle;private final boolean build;
    RissoModelSmokeChecks(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated SDL lab required");
        this.mod=mod;original=Vars.player.unit();view=fpv.ui.CameraView.selected();angle=Core.settings.getInt("fpv-pov-angle",90);build=mod.buildMode;
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){var t=Vars.world.tile(48+x,48+y);floors[x+y*64]=t.floor();t.setFloor(Blocks.water.asFloor());}
        mod.renderer3d.invalidate();next();
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.015f;camera.far=2000;camera.fov=44;
    }
    private void next(){unit=fpv.render.NavalHullProfile.types().get(index).create(original.team);nativeSpeed=unit.type.speed;nativeHealth=unit.type.health;nativeHitSize=unit.hitSize;unit.set(80*8,80*8);unit.rotation=90;unit.controller(new Sailing(false));unit.add();mod.unitAltitudes.pose(unit);ticks=phase=0;}
    boolean update(){
        ticks++;var p=mod.unitAltitudes.unitState(unit);NavalModelVisualChecks.view(camera,unit,p,2);mod.renderer3d.render(camera);
        if(phase==0&&ticks>=30){
            check(unit instanceof WaterMovec&&!unit.type.canBoost,"native water class/no new boost");check(unit.mounts.length>0&&NavalUnitRenderer.supports(unit),"native ship mounts");
            if(index==0)for(var type:Vars.content.units())check(NavalUnitRenderer.supports(type.create(unit.team))==fpv.render.NavalHullProfile.supports(type),"dispatch "+type.name);
            gallery.append(NavalModelVisualChecks.run(mod.renderer3d,mod.unitAltitudes,unit)).append('\n');startX=unit.x;startY=unit.y;startYaw=unit.rotation;unit.controller(new Sailing(true));phase=1;ticks=0;
        }else if(phase==1&&ticks>=110){
            check(Math.hypot(unit.x-startX,unit.y-startY)>Vars.tilesize,"real native water movement");check(Math.abs(unit.rotation-startYaw)>5,"native turns drive ship model");
            check(unit.floorOn().isLiquid&&!unit.onSolid(),"native shoreline collision keeps ship on water");capture(unit.type.name+"-native-moving-turning-shore.png");
            if(attacks())check(shots(unit)>0,"AI-style native controller fires while sailing");unit.controller(new Sailing(false));unit.vel.setZero();controlChecks();removeCurrent();if(++index<fpv.render.NavalHullProfile.types().size()){next();return false;}close();return true;
        }
        if(ticks>1200)throw new IllegalStateException("Risso native fixture timeout");return false;
    }
    private void controlChecks(){
        boolean suspended=mod.input.isSuspended();var block=mod.input.block;float delta=Time.delta;
        try{
            mod.input.block=null;mod.input.setSuspended(false);mod.setBuildMode(false);Vars.player.unit(unit);Core.settings.put("fpv-pov-angle",90);Core.settings.put("fpv-camera-view",0);mod.resetPose();
            var pose=mod.world3d.unitState(unit);double eye=fpv.flight.GroundEyeHeight.desired(unit,pose.halfHeight());for(int i=0;i<200;i++)mod.updateCamera(1/60.0);
            check(Math.abs(mod.camera.position.y-mod.flight.state().y-eye)<.003,"existing Risso first-person eye unchanged");
            mod.renderer3d.render(mod.camera);check(mod.renderer3d.firstPersonRig().stats().weapons()==java.util.Arrays.stream(unit.mounts).filter(m->!m.weapon.shootOnDeath&&m.weapon.bullet!=null&&!m.weapon.bullet.killShooter).limit(12).count(),"original perspective viewmodels");capture(unit.type.name+"-controlled-first.png");
            Time.delta=1;mod.updatePick();mod.input.applyFlightTrigger(true);int before=shots(unit);for(int i=0;i<100;i++){for(var mount:unit.mounts)mount.weapon.update(unit,mount);Time.update();}
            if(attacks())check(shots(unit)>before,"production FPV trigger fires native guns");for(var mount:unit.mounts)mount.recoil=1;mod.renderer3d.render(mod.camera);capture(unit.type.name+"-controlled-firing.png");mod.input.applyFlightTrigger(false);
            for(int mode=1;mode<=2;mode++){
                fpv.ui.CameraView.cycle();for(int i=0;i<200;i++)mod.updateCamera(1/60.0);mod.updatePick();mod.renderer3d.render(mod.camera);
                check(fpv.ui.CameraView.selected()==mode&&mod.renderer3d.showControlledUnit()&&!mod.renderer3d.firstPersonActive(),"F7 perspective "+mode);
                check(mod.renderer3d.firstPersonRig().stats().weapons()==0,"no duplicate viewmodels");capture(unit.type.name+"-controlled-"+(mode==1?"front":"rear")+".png");
            }
            check(mod.world3d.unitState(unit).equals(pose)&&unit.type.speed==nativeSpeed&&unit.type.health==nativeHealth&&unit.hitSize==nativeHitSize,"physical XYZ/native stats unchanged");check(Gl.getError()==0,"controlled views GL0");
        }finally{Time.delta=delta;mod.input.block=block;mod.input.setSuspended(suspended);Vars.player.unit(original);mod.resetPose();}
    }
    String result(){return gallery+"; PASS: "+checks+" ten native ships eligibility/real sailing/turn/shore/fire/FPV/F7/eye/physics checks";}
    private void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static int shots(Unit unit){int n=0;for(var mount:unit.mounts)n+=mount.totalShots;return n;}
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Risso native: "+label);}
    private boolean attacks(){return java.util.Arrays.stream(unit.mounts).anyMatch(m->m.weapon.controllable&&!m.weapon.noAttack);}
    private void removeCurrent(){
        var bullets=new ArrayList<Bullet>();for(var b:Groups.bullet)if(b.owner==unit)bullets.add(b);for(var b:bullets){b.hit=true;b.remove();}unit.remove();
    }
    public void close(){
        if(closed)return;closed=true;removeCurrent();
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)Vars.world.tile(48+x,48+y).setFloor(floors[x+y*64]);mod.renderer3d.invalidate();Vars.player.unit(original);
        Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.resetPose();mod.setBuildMode(build);
    }
    private static final class Sailing implements UnitController {
        private Unit unit;private final boolean moving;private int tick;Sailing(boolean moving){this.moving=moving;}public Unit unit(){return unit;}public void unit(Unit value){unit=value;}
        public void updateUnit(){
            if(!moving){unit.vel.setZero();unit.controlWeapons(false,false);return;}
            unit.rotation+=tick++<60?.35f:-.15f;unit.vel.trns(unit.rotation,unit.speed());double a=Math.toRadians(unit.rotation);
            unit.aim(unit.x+(float)Math.cos(a)*100,unit.y+(float)Math.sin(a)*100);unit.controlWeapons(true,true);
        }
    }
}
