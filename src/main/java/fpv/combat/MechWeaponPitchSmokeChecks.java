package fpv.combat;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.render.FpvWorldRenderer;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.WeaponMount;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.*;
import java.util.*;

/** Disposable native weapon/AI/GPU fixture. Preserves the installed content adapters and live profile. */
public final class MechWeaponPitchSmokeChecks {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Mech pitch: "+label);checks++;}
    private record Content(UnitType type,Weapon[] weapons,float range,float maxRange){}
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem originalAltitudes,Combat3DSystem originalCombat){
        check(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated native SDL fixture");checks=0;
        var content=new ArrayList<Content>();for(var type:Vars.content.units())content.add(new Content(type,type.weapons.toArray(Weapon.class),type.range,type.maxRange));
        Unit pilot=Vars.player.unit();boolean fpv=renderer.firstPersonActive(),body=renderer.showControlledUnit();float delta=Time.delta;
        var units=new ArrayList<Unit>();Map<Unit,Unit3DState> poses=new IdentityHashMap<>();
        var altitudes=new UnitAltitudeSystem(renderer.terrain()::heightAt);altitudes.setExternalStateProvider(poses::get);
        var world=new World3DAdapter(renderer.terrain(),renderer::surfaceHeight,altitudes);
        arc.struct.OrderedMap<String,mindustry.io.SaveFileReader.CustomChunk> chunks=arc.util.Reflect.get(mindustry.io.SaveVersion.class,"customChunks");
        var originalStanceChunk=chunks.get("fpv-ground-stances");
        altitudes.setGroundFamily(new fpv.units.ground.GroundFamilyLocomotion(world));
        Unit[] controlled={null};WorldCoordinates.Point[] sight={null};
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?sight[0]:null);
        var report=new StringBuilder();
        try{
            combat.install();combat.update(0);renderer.setUnitAltitudes(altitudes);renderer.setCombat(combat);
            check(!MechWeaponAim3D.supports(UnitTypes.flare.create(Team.sharded)),"flying guns excluded");
            check(!MechWeaponAim3D.supports(UnitTypes.atrax.create(Team.sharded)),"Legsc walkers excluded");
            check(!MechWeaponAim3D.supports(UnitTypes.vela.create(Team.sharded)),"extended Vela walker excluded");
            var target=UnitTypes.dagger.create(Team.crux);target.set(80*8,72*8);target.add();units.add(target);put(poses,target,5);
            var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=2000;camera.fov=55;
            for(var type:new UnitType[]{UnitTypes.dagger,UnitTypes.mace,UnitTypes.fortress,UnitTypes.reign}){
                Unit unit=type.create(Team.sharded);unit.set(72*8,72*8);unit.rotation=0;((Mechc)unit).baseRotation(0);unit.add();units.add(unit);put(poses,unit,.25);
                check(MechWeaponAim3D.supports(unit),"native mech "+type.name);
                for(var mount:unit.mounts){
                    double envelope=MechWeaponPitch.halfLength(mount.weapon.name,unit.hitSize/Vars.tilesize,mount.weapon.shootY/Vars.tilesize);
                    check(mount.weapon.region.height*mount.weapon.region.scl()/Vars.tilesize*.5<=envelope,"headless mount envelope covers actual gun art "+type.name);
                }
                for(boolean player:new boolean[]{true,false}){
                    controlled[0]=player?unit:null;sight[0]=MechWeaponPitch.direction(0,35);Vars.player.unit(player?unit:pilot);
                    if(!player)unit.controller(new mindustry.entities.units.AIController(){@Override public void updateUnit(){}});
                    for(var mount:unit.mounts){mount.target=target;mount.aimX=target.x;mount.aimY=target.y;mount.shoot=true;mount.rotate=true;mount.reload=0;mount.recoil=0;}
                    unit.aim(target.x,target.y);unit.controlWeapons(true,true);Time.delta=1;
                    Vars.state.teams.updateTeamStats();
                    combat.update(0);
                    for(int i=0;i<150;i++)for(var mount:unit.mounts)mount.weapon.update(unit,mount);
                    int shots=0;
                    for(var mount:unit.mounts){
                        var bullet=mount.bullet;if(bullet==null||!combat.managed(bullet))continue;shots++;
                        var state=combat.projectileState(bullet);var aim=combat.mechWeapons().visual(unit,mount);
                        check(aim!=null&&aim.pitch()>5&&aim.pitch()<=MechWeaponPitch.MAX,"raised real "+(player?"player":"AI")+" gun "+type.name);
                        double actual=Math.toDegrees(Math.atan2(state.vz(),Math.hypot(state.vx(),state.vy())));
                        check(Math.abs(actual-aim.pitch())<.2,"launch pitch agrees with visible gun "+type.name+": "+actual+" / "+aim.pitch());
                        check(Math.abs(state.z()-aim.muzzle().z())<.02,"native muzzle Z matches gun "+type.name);
                        check(Math.hypot(state.x()-aim.muzzle().x(),state.y()-aim.muzzle().y())<.02,"native muzzle XY matches gun "+type.name);
                        check(state.z()>world.terrainHeight(state.x(),state.y()),"shot starts above ground");
                        check(bullet.damage==combat.originalType(mount.weapon.bullet).damage*unit.damageMultiplier(),"native damage unchanged "+type.name);
                        // The first native proxy update only publishes its synchronized spawn;
                        // advance the second update to verify actual XYZ integration.
                        double previousZ=state.z();Time.delta=.5f;bullet.update();bullet.update();Time.delta=1;
                        var advanced=combat.projectileState(bullet);check(advanced!=null&&advanced.z()>previousZ,"real XYZ update climbs along raised barrel "+type.name);
                        report.append(type.name).append(player?" controlled":" AI").append(" mount ").append(mount.weapon.x).append(',').append(mount.weapon.y)
                            .append(" pitch=").append(aim.pitch()).append(" launch=").append(actual).append(" muzzle=").append(aim.muzzle()).append('\n');
                    }
                    check(shots>0,"real native weapon update shoots "+type.name+" player="+player);
                    // Separate readable gun captures from dozens of deliberately co-located test shots.
                    cleanup(unit);combat.update(.2);
                    renderer.setFirstPersonActive(player);renderer.setShowControlledUnit(!player);
                    if(player){
                        var pose=poses.get(unit);camera.position.set((float)pose.x(),(float)pose.z()+1.3f,(float)-pose.y());camera.direction.set((float)sight[0].x(),(float)sight[0].z(),(float)-sight[0].y());camera.up.set(0,1,0);camera.update();
                        renderer.render(camera);capture(type.name+"-pitch-fpv.png");
                        check(renderer.firstPersonRig().stats().weapons()==unit.mounts.length,"all FPV weapons "+type.name);
                        check(Math.abs(renderer.firstPersonRig().mechPitch()-35)<.001,"FPV uses actual gun pitch");
                        check(Math.abs(renderer.firstPersonRig().submittedElevation()-35)<.001,"actual FPV mesh forward uniform pitched");
                    }
                    renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(true);
                    for(int view=0;view<2;view++){
                        float distance=Math.max(6,unit.hitSize/Vars.tilesize*3.2f);
                        camera.position.set(view==0?72-distance:72,view==0?Math.max(3,unit.hitSize/Vars.tilesize*1.2f):1.8f,view==0?-72:-72+distance);camera.up.set(0,1,0);camera.lookAt(72,1,-72);camera.update();
                        renderer.render(camera);capture(type.name+"-pitch-"+(player?"controlled":"ai")+(view==0?"-rear":"-side")+".png");
                        check(renderer.groundFamily().wasSubmitted(unit),"visible world mech "+type.name);
                    }
                    // Downhill target on a lower level: no flat launch and no damage/reload/range edits.
                    put(poses,unit,5);put(poses,target,.25);sight[0]=MechWeaponPitch.direction(0,-45);
                    var down=combat.mechWeapons().visual(unit,unit.mounts[0]);
                    check(down.pitch()<0&&down.pitch()>=MechWeaponPitch.MIN||!player&&type==UnitTypes.fortress&&down.pitch()>0,"downhill/clamped ballistic solution "+type.name);
                    if(player)check(down.pitch()==MechWeaponPitch.MIN,"player downward mechanical stop");
                    put(poses,unit,.25);put(poses,target,5);
                    cleanup(unit);unit.controlWeapons(false,false);
                }
                unit.remove();poses.remove(unit);
            }
            check(Gl.getError()==0,"FPV/private depth/world pitched shaders GL0");
            String result="PASS: "+checks+" native mech pitch checks; Dagger/Mace/Fortress/Reign, actual native player/AI firing, matched XYZ muzzle and launch pitch including artillery, downward clamps, FPV/side/rear GPU captures, excluded flying/walker families, GL0";
            new Fi(System.getProperty("fpv.smoke.output")).child("mech-pitch-verification.txt").writeString(result+"\n"+report);return result;
        }finally{
            for(var unit:units){cleanup(unit);if(unit.isAdded())unit.remove();}combat.close();
            for(var state:content){state.type.weapons.clear();state.type.weapons.addAll(state.weapons);state.type.range=state.range;state.type.maxRange=state.maxRange;}
            Vars.player.unit(pilot);Time.delta=delta;renderer.setUnitAltitudes(originalAltitudes);renderer.setCombat(originalCombat);renderer.setFirstPersonActive(fpv);renderer.setShowControlledUnit(body);altitudes.close();
            if(originalStanceChunk==null)chunks.remove("fpv-ground-stances");else chunks.put("fpv-ground-stances",originalStanceChunk);
            originalCombat.syncActive();
        }
    }
    private static void cleanup(Unit unit){var shots=new ArrayList<Bullet>();for(var bullet:Groups.bullet)if(bullet.owner==unit)shots.add(bullet);for(var bullet:shots){bullet.hit=true;bullet.remove();}}
    private static void put(Map<Unit,Unit3DState> poses,Unit u,double z){double r=u.hitSize/Vars.tilesize*.5;
        poses.put(u,new Unit3DState(u.id,u.type.id,u.team.id,u.x/Vars.tilesize,u.y/Vars.tilesize,z,0,0,0,u.rotation,0,0,z,r,.15,false));}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
}
