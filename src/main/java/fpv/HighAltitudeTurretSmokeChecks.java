package fpv;

import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.*;

/** Controlled physical state drives real native turret acquisition, ammunition and projectile updates. */
final class HighAltitudeTurretSmokeChecks {
    private final FpvMod mod;private int checks;
    HighAltitudeTurretSmokeChecks(FpvMod mod){this.mod=mod;}
    String run(){
        check(Boolean.getBoolean("fpv.smoke")&&!mod.motion.pending(),"isolated lab with reconciled movement");
        var original=Vars.player.unit();var controller=original.controller();var originalTeam=Vars.player.team();
        var unitTeam=original.team;float originalHealth=original.health,originalShield=original.shield;
        var state=mod.flight.state();double[] saved={state.x,state.y,state.z,state.vx,state.vy,state.vz,state.qx,state.qy,state.qz,state.qw,state.wx,state.wy,state.wz};
        boolean ready=mod.ready,enabled=mod.enabled;int id=mod.unitId;float delta=Time.delta;double multiplier=mod.combat.antiAirVerticalMultiplier();
        Unit target=null;var placed=new ArrayList<Tile>();
        try{
            mod.combat.clear();Tile site=findSite();check(site!=null,"unoccupied high-altitude turret fixture");
            target=UnitTypes.flare.create(originalTeam);target.spawnedByCore=true;target.set((site.x+11)*Vars.tilesize,(site.y+5)*Vars.tilesize);target.add();target.health=100000;
            Vars.player.unit(target);mod.ready=mod.enabled=true;mod.unitId=target.id;mod.combat.setAntiAirVerticalMultiplier(5);
            state.qx=state.qy=state.qz=state.wx=state.wy=state.wz=0;state.qw=1;
            Team enemy=originalTeam==Team.crux?Team.sharded:Team.crux;
            Tile tile=Vars.world.tile(site.x+4,site.y+5);tile.setBlock(Blocks.fuse,enemy,0);placed.add(tile);
            var fuse=(ItemTurret.ItemTurretBuild)tile.build;var type=(ItemTurret)Blocks.fuse;
            for(int i=0;i<12;i++)fuse.handleItem(fuse,Items.thorium);
            double x=fuse.x/Vars.tilesize,y=fuse.y/Vars.tilesize,roof=mod.world3d.surfaceHeight(x,y)+.4,range=fuse.range()/Vars.tilesize;
            position(target,x+6,y,roof+4*range,0);Vars.state.teams.updateTeamStats();
            float health=target.health;int ammo=fuse.totalAmmo;fire(fuse,type);
            check(fuse.target==target,"native Fuse acquires a controlled aircraft at four times horizontal range in Z");
            check(fuse.totalAmmo<ammo&&target.health<health,"actual 4R rising beam consumes native ammo and damages the physical target");
            check(mod.combat.visuals().stream().anyMatch(v->v.kind()==fpv.combat.CombatVisual.Kind.BEAM&&v.bz()-v.az()>range*3),"beam endpoint truly reaches the high aircraft");mod.combat.clear();
            position(target,x+6,y,roof+5.2*range,0);Vars.state.teams.updateTeamStats();fire(fuse,type);
            check(fuse.target!=target,"native acquisition excludes targets above the configured five-R ceiling");mod.combat.clear();
            position(target,x+1.2*range,y,roof+range,0);Vars.state.teams.updateTeamStats();fire(fuse,type);
            check(fuse.target!=target,"native acquisition preserves the horizontal R limit");mod.combat.clear();

            Tile gunTile=Vars.world.tile(site.x+4,site.y+10);gunTile.setBlock(Blocks.duo,enemy,0);placed.add(gunTile);
            var gun=(ItemTurret.ItemTurretBuild)gunTile.build;var gunType=(ItemTurret)Blocks.duo;
            for(int i=0;i<12;i++)gun.handleItem(gun,Items.copper);
            double gx=gun.x/Vars.tilesize,gy=gun.y/Vars.tilesize,gz=mod.world3d.surfaceHeight(gx,gy)+.4;
            position(target,gx+6,gy,gz+2*gun.range()/Vars.tilesize,2);Vars.state.teams.updateTeamStats();health=target.health;fire(gun,gunType);
            var bullets=new ArrayList<Bullet>();for(Bullet bullet:Groups.bullet)if(bullet.owner==gun&&mod.combat.managed(bullet))bullets.add(bullet);
            check(!bullets.isEmpty(),"actual native Duo emits a managed projectile toward a rising target");
            var shot=mod.combat.projectileState(bullets.get(0));double travel=(state.x-shot.x())/shot.vx();
            check(travel>0&&shot.z()+shot.vz()*travel>state.y+.25,"native shot trajectory includes vertical-velocity lead");
            Time.delta=1;
            for(int frame=0;frame<240&&target.health==health;frame++){
                state.y+=state.vy/60;for(Bullet bullet:bullets)if(bullet.isAdded())bullet.update();
            }
            check(target.health<health,"native projectile updates hit the continuously rising physical aircraft");
            return "passed: "+checks+" assertions; native 4R beam hit, >5R and horizontal exclusions, actual Duo moving-Z lead/hit";
        }finally{
            mod.combat.clear();Time.delta=delta;mod.combat.setAntiAirVerticalMultiplier(multiplier);
            Vars.player.unit(original);Vars.player.team(originalTeam);original.controller(controller);original.team=unitTeam;original.health=originalHealth;original.shield=originalShield;if(target!=null&&target.isAdded())target.remove();
            for(Tile tile:placed)tile.setBlock(Blocks.air);mod.unitId=id;mod.ready=ready;mod.enabled=enabled;
            mod.motion.reset();mod.motion.syncFromSimulation(original,state);
            state.x=saved[0];state.y=saved[1];state.z=saved[2];state.vx=saved[3];state.vy=saved[4];state.vz=saved[5];state.qx=saved[6];state.qy=saved[7];state.qz=saved[8];state.qw=saved[9];state.wx=saved[10];state.wy=saved[11];state.wz=saved[12];
            Vars.state.teams.updateTeamStats();if(mod.separation!=null)mod.separation.rebase();
        }
    }
    private void position(Unit unit,double x,double y,double z,double vz){
        var s=mod.flight.state();unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.vel.setZero();
        mod.motion.reset();mod.motion.syncFromSimulation(unit,s);s.x=x;s.z=-y;s.y=z;s.vx=s.vz=0;s.vy=vz;
    }
    private static void fire(ItemTurret.ItemTurretBuild build,ItemTurret type){
        Time.delta=1;build.efficiency=build.potentialEfficiency=1;build.shootWarmup=1;build.reloadCounter=type.reload;build.rotation=0;build.timer.reset(type.timerTarget,100);build.updateTile();
    }
    private static Tile findSite(){
        for(int y=6;y<Vars.world.height()-22;y+=2)for(int x=6;x<Vars.world.width()-35;x+=2){boolean clear=true;
            for(int oy=0;oy<17&&clear;oy++)for(int ox=0;ox<30;ox++){var tile=Vars.world.tile(x+ox,y+oy);if(tile.block()!=Blocks.air||tile.build!=null){clear=false;break;}}
            if(clear)for(Unit unit:Groups.unit)if(Math.hypot(unit.x/Vars.tilesize-(x+4),unit.y/Vars.tilesize-(y+7))<22){clear=false;break;}
            if(clear)return Vars.world.tile(x,y);
        }return null;
    }
    private void check(boolean value,String label){checks++;if(!value)throw new IllegalStateException("High altitude turret smoke: "+label);Log.info("[FPV-HIGH-AA] OK: @",label);}
}
