package fpv;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import fpv.combat.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.UnitType;

/** Real native weapons on the dedicated generated lab; no user-save edits. */
final class MaintenanceCombatSmokeChecks {
    private final FpvMod mod;private final ArrayList<Unit> owned=new ArrayList<>();private int checks;
    MaintenanceCombatSmokeChecks(FpvMod mod){this.mod=mod;}
    String run(){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.world.width()==128,"dedicated lab required");
        var original=Vars.player.unit();var controller=original.controller();boolean ready=mod.ready;int id=mod.unitId;
        var s=mod.flight.state();double[] saved={s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz};
        float delta=Time.delta;var settings=mod.combat.unitRanges().settings();
        try{
            mod.combat.unitRanges().settings(UnitWeaponRanges.DEFAULT);mod.combat.clear();Time.delta=1;
            for(UnitType type:new UnitType[]{UnitTypes.dagger,UnitTypes.nova,UnitTypes.risso})range(type,type==UnitTypes.nova?2:3);
            var flare=UnitTypes.flare.weapons.first();require(mod.combat.unitRanges().horizontal(UnitTypes.flare)==1&&Math.abs(flare.range()-mod.combat.originalType(flare.bullet).range)<.001,"flying-unit native range unchanged");
            var source=spawn(UnitTypes.nova,Team.crux,16,48);var target=spawn(UnitTypes.flare,Vars.player.team(),21,48);
            target.health=100000;Vars.player.unit(target);mod.ready=true;mod.unitId=target.id;
            double base=mod.combat.originalType(source.mounts[0].weapon.bullet).range/Vars.tilesize;
            // Keep the 4.5R vertical-reach check inside the mech's new physical 70-degree traverse.
            // At X=21 this old fixture demanded an almost vertical (>85deg) barrel.
            position(target,16+1.8*base,48,mod.world3d.unitState(source).z()+4.5*base);Vars.state.teams.updateTeamStats();
            float health=target.health;fire(source,target,420);
            require(target.health<health,"native Nova projectile reaches 4.5R altitude without early falloff");
            mod.combat.clear();position(target,16+1.8*base,48,mod.world3d.unitState(source).z()+5.4*base);Vars.state.teams.updateTeamStats();
            for(var mount:source.mounts){mount.target=target;mount.shoot=true;mount.retarget=0;mount.reload=0;mount.weapon.update(source,mount);require(mount.target!=target,"native mount rejects target beyond configured vertical reach");}
            var naval=spawn(UnitTypes.navanax,Team.crux,16,70);position(target,21,70,2000);Vars.state.teams.updateTeamStats();int automatic=0;
            for(var auto:naval.mounts)if(!auto.weapon.controllable&&auto.weapon.autoTarget){
                auto.target=target;auto.retarget=100;auto.weapon.update(naval,auto);automatic++;
                require(auto.target!=target,"autonomous naval mount also rejects unreachable Z");
            }
            require(automatic>0,"actual native autonomous naval weapon exercised");naval.remove();mod.combat.clear();
            source.remove();target.remove();Vars.player.unit(original);mod.unitId=id;mod.ready=ready;
            BeamFamilySmokeChecks.run(mod.world3d,mod.combat);
            var crawler=spawn(UnitTypes.crawler,Team.sharded,28,48);var victim=spawn(UnitTypes.dagger,Team.crux,29,48);victim.health=10000;
            Vars.state.teams.updateTeamStats();Vars.player.unit(crawler);mod.unitId=crawler.id;mod.ready=true;
            crawler.aim(victim);var mount=crawler.mounts[0];mount.shoot=mount.rotate=true;mount.reload=0;
            float before=victim.health;mount.weapon.update(crawler,mount);
            require(crawler.dead,"native FPV-controlled Crawler attack kills shooter");require(victim.health<before,"Crawler native splash damages enemy in XYZ");
            float after=victim.health;for(int i=0;i<3;i++)mount.weapon.update(crawler,mount);
            require(victim.health==after&&mount.totalShots==1,"suicide/death weapon cannot duplicate attack");
            require(Vars.player.dead(),"player recognizes destroyed controlled unit and can release control");
            var visuals=new ArrayList<>(mod.combat.visuals());long count=visuals.stream().filter(v->v.kind()==CombatVisual.Kind.EXPLOSION).count();
            require(count==1,"one native-scaled three-dimensional Crawler explosion effect");
            Vars.player.unit(original);mod.unitId=id;mod.ready=ready;
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.far=1000;camera.fov=60;
            camera.position.set(33,9,-43);camera.lookAt(28,1,-48);camera.update();mod.renderer3d.render(camera);
            for(var v:visuals)if(v.kind()==CombatVisual.Kind.EXPLOSION)require(mod.renderer3d.wasCombatVisualSubmitted(v.id()),"explosive impact submitted through actual GPU path");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("maintenance-explosion.png"));
            return "PASS: "+checks+" assertions plus native Sap first-target/drain and PointLaser endpoint/pulse checks; native Dagger/Nova/Risso extended-range hits, nonstacking, flyer unchanged, 4.5R vertical hit/5.4R exclusion, controlled Crawler death/single splash, GPU explosion";
        }finally{
            mod.combat.clear();mod.combat.unitRanges().settings(settings);Time.delta=delta;Vars.player.unit(original);original.controller(controller);mod.unitId=id;mod.ready=ready;
            for(Unit unit:owned)if(unit.isAdded())unit.remove();mod.motion.reset();mod.motion.syncFromSimulation(original,s);
            s.x=saved[0];s.y=saved[1];s.z=saved[2];s.vx=saved[3];s.vy=saved[4];s.vz=saved[5];s.qx=saved[6];s.qy=saved[7];s.qz=saved[8];s.qw=saved[9];s.wx=saved[10];s.wy=saved[11];s.wz=saved[12];Vars.state.teams.updateTeamStats();
        }
    }
    private void range(UnitType type,double scale){
        mod.combat.clear();var source=spawn(type,Team.sharded,12,48);double base=mod.combat.originalType(source.mounts[0].weapon.bullet).range/Vars.tilesize;
        var target=spawn(UnitTypes.dagger,Team.crux,12+base*scale*.8,48);target.health=100000;
        require(Math.abs(source.mounts[0].weapon.range()/Vars.tilesize-base*scale)<.001,type.name+" exact nonstacked native multiplier");
        Vars.state.teams.updateTeamStats();float health=target.health;fire(source,target,480);
        require(target.health<health,type.name+" native AI/weapon/projectile hits beyond old range");
        source.remove();target.remove();
    }
    private void fire(Unit source,Unit target,int frames){
        var ai=(AIController)source.controller();float before=target.health;
        for(int frame=0;frame<frames&&target.health==before;frame++){
            Time.delta=1;ai.updateWeapons();
            for(var mount:source.mounts){
                // This isolated vertical-reach case supplies its intended target. The broader
                // horizontal cases above still exercise unassisted native AI acquisition.
                if(target==Vars.player.unit())mount.target=target;
                mount.weapon.update(source,mount);
            }
            var shots=new ArrayList<Bullet>();for(var bullet:Groups.bullet)if(bullet.owner==source&&mod.combat.managed(bullet))shots.add(bullet);
            for(var bullet:shots)if(bullet.isAdded())bullet.update();
        }
    }
    private Unit spawn(UnitType type,Team team,double x,double y){Unit u=type.create(team);u.spawnedByCore=true;u.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));u.rotation=0;u.controller(new AIController(){@Override public void updateUnit(){}});u.add();owned.add(u);mod.world3d.unitState(u);return u;}
    private void position(Unit unit,double x,double y,double z){unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.vel.setZero();mod.motion.reset();mod.motion.syncFromSimulation(unit,mod.flight.state());var s=mod.flight.state();s.x=x;s.z=-y;s.y=z;s.vx=s.vy=s.vz=0;}
    private void require(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Maintenance combat: "+label);Log.info("[FPV-MAINTENANCE] OK: @",label);}
}
