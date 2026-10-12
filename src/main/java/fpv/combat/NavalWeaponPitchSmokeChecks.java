package fpv.combat;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.render.*;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.*;
import java.util.*;

/** Isolated native atlas and shared world/FPV gun direction checks for both ship lines. */
public final class NavalWeaponPitchSmokeChecks {
    private static int checks;
    private record Content(UnitType type,Weapon[] weapons,float range,float maxRange){}
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem originalAltitudes,Combat3DSystem originalCombat){
        checks=0;check(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated SDL world");
        var content=new ArrayList<Content>();for(var type:Vars.content.units())content.add(new Content(type,type.weapons.toArray(Weapon.class),type.range,type.maxRange));
        Unit pilot=Vars.player.unit();boolean fpv=renderer.firstPersonActive(),body=renderer.showControlledUnit();
        var units=new ArrayList<Unit>();Map<Unit,Unit3DState> poses=new IdentityHashMap<>();
        var altitudes=new UnitAltitudeSystem(renderer.terrain()::heightAt);altitudes.setExternalStateProvider(poses::get);
        var world=new World3DAdapter(renderer.terrain(),renderer::surfaceHeight,altitudes);
        Unit[] controlled={null};WorldCoordinates.Point[] sight={MechWeaponPitch.direction(0,35)};
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?sight[0]:null);var report=new StringBuilder();
        try{
            combat.install();combat.update(0);renderer.setUnitAltitudes(altitudes);renderer.setCombat(combat);
            var target=UnitTypes.flare.create(Team.crux);target.set(92*8,80*8);target.add();units.add(target);put(poses,target,5);
            var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.015f;camera.far=2000;camera.fov=55;
            for(var type:NavalHullProfile.types()){
                var unit=type.create(Team.sharded);unit.set(80*8,80*8);unit.rotation=0;unit.add();units.add(unit);put(poses,unit,.25);var profile=NavalHullProfile.forType(type);
                var before=poses.get(unit);
                for(boolean player:new boolean[]{true,false}){
                    controlled[0]=player?unit:null;Vars.player.unit(player?unit:pilot);sight[0]=MechWeaponPitch.direction(0,35);
                    unit.aim(target.x,target.y);
                    for(var mount:unit.mounts){
                        mount.target=target;mount.aimX=target.x;mount.aimY=target.y;mount.rotation=0;mount.shoot=true;mount.recoil=.6f;
                        var gun=combat.navalWeapons().visual(unit,mount,profile);
                        check(gun!=null&&gun.pitch()>0&&gun.pitch()<=profile.maximumPitch(),"visible player/AI pitch "+type.name);
                        if(player&&mount.weapon.controllable)check(Math.abs(gun.pitch()-35)<1e-6,"native player sight");
                        check(gun.yaw()==0,"native yaw intact");
                        if(player&&mount.weapon.controllable){
                            var w=mount.weapon;float x=(float)(ProceduralUnitModelProfile.weaponX(unit.x,0,w.x,w.y)+w.shootY),y=(float)(ProceduralUnitModelProfile.weaponY(unit.y,0,w.x,w.y)-w.shootX);
                            var bullet=w.bullet.create(unit,unit,unit.team,x,y,0,-1,1,1,null,null,target.x,target.y,target);var shot=combat.projectileState(bullet);
                            if(shot!=null){
                                double actual;
                                if(Math.hypot(Math.hypot(shot.vx(),shot.vy()),shot.vz())>1e-8)actual=Math.toDegrees(Math.atan2(shot.vz(),Math.hypot(shot.vx(),shot.vy())));
                                else{
                                    // Rail/continuous emitters have zero projectile velocity; inspect their actual XYZ beam.
                                    bullet.update();bullet.update();var start=shot;
                                    var beam=combat.visuals().stream().filter(v->v.kind()==CombatVisual.Kind.BEAM&&Math.abs(v.ax()-start.x())+Math.abs(v.ay()-start.y())+Math.abs(v.az()-start.z())<1e-5&&Math.hypot(v.bx()-v.ax(),v.by()-v.ay())>1e-5).findFirst().orElseThrow(()->new IllegalStateException("Missing native beam "+type.name));
                                    actual=Math.toDegrees(Math.atan2(beam.bz()-beam.az(),Math.hypot(beam.bx()-beam.ax(),beam.by()-beam.ay())));
                                }
                                check(Math.abs(actual-gun.pitch())<1e-4,"preserved XYZ shot agrees with player gun "+type.name+": "+actual+" / "+gun.pitch());
                            }
                            bullet.hit=true;bullet.remove();
                        }
                    }
                    renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(true);
                    NavalModelVisualChecks.view(camera,unit,poses.get(unit),2);renderer.render(camera);capture(type.name+"-pitch-"+(player?"player":"ai")+"-oblique.png");
                    NavalModelVisualChecks.view(camera,unit,poses.get(unit),1);renderer.render(camera);capture(type.name+"-pitch-"+(player?"player":"ai")+"-side.png");
                    check(renderer.wasUnitSubmitted(unit),"world pitched model "+type.name);
                    if(player){
                        renderer.setFirstPersonActive(true);renderer.setShowControlledUnit(false);camera.position.set(80,1.5f,-80);camera.direction.set((float)sight[0].x(),(float)sight[0].z(),(float)-sight[0].y());camera.up.set(0,1,0);camera.update();renderer.render(camera);capture(type.name+"-pitch-fpv.png");
                        int count=(int)Arrays.stream(unit.mounts).filter(m->!m.weapon.shootOnDeath&&m.weapon.bullet!=null&&!m.weapon.bullet.killShooter).limit(12).count();
                        check(renderer.firstPersonRig().stats().weapons()==count,"original viewmodels "+type.name);
                        var last=unit.mounts[Math.min(unit.mounts.length,12)-1];double expected=combat.navalWeapons().visual(unit,last,profile).pitch();
                        check(Math.abs(renderer.firstPersonRig().submittedElevation()-expected)<1e-4,"FPV mesh shares pitched direction "+type.name);
                        sight[0]=MechWeaponPitch.direction(0,-20);
                        for(var mount:unit.mounts)if(mount.weapon.controllable)check(combat.navalWeapons().visual(unit,mount,profile).pitch()==profile.minimumPitch(),"downward mechanical stop");
                    }
                    report.append(type.name).append(player?" player":" AI").append(" first mount elevation=").append(combat.navalWeapons().visual(unit,unit.mounts[0],profile).pitch()).append('\n');
                }
                check(poses.get(unit).equals(before),"no physical pose mutation");unit.remove();poses.remove(unit);
            }
            check(Gl.getError()==0,"world/private-depth FPV pitched shaders GL0");String result="PASS: "+checks+" naval pitch GPU/player/AI/FPV/native shot direction/side/oblique/limits checks for ten ships";
            new Fi(System.getProperty("fpv.smoke.output")).child("naval-pitch-verification.txt").writeString(result+"\n"+report);return result;
        }finally{
            for(var unit:units)if(unit.isAdded())unit.remove();combat.close();
            for(var state:content){state.type.weapons.clear();state.type.weapons.addAll(state.weapons);state.type.range=state.range;state.type.maxRange=state.maxRange;}
            Vars.player.unit(pilot);renderer.setUnitAltitudes(originalAltitudes);renderer.setCombat(originalCombat);renderer.setFirstPersonActive(fpv);renderer.setShowControlledUnit(body);altitudes.close();originalCombat.syncActive();
        }
    }
    private static void put(Map<Unit,Unit3DState> poses,Unit u,double z){poses.put(u,new Unit3DState(u.id,u.type.id,u.team.id,u.x/8,u.y/8,z,0,0,0,u.rotation,0,0,z,u.hitSize/16,.15,u.type.flying));}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Naval pitch: "+label);}
}
