package fpv.defense;

import arc.Core;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.generated.FlightProfiles;
import fpv.physics.CrashSystem;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.ForceProjector;

/** Native body, production pilot sweep, unpiloted altitude correction and host-settings codec. */
public final class ForceAircraftSmokeChecks {
    private ForceAircraftSmokeChecks(){}
    public static String verify(ForceProjectorSystem fields,World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame()&&!Vars.net.active(),"isolated native offline fixture");
        Tile tile=findSite(world);require(tile!=null,"empty ForceProjector anchor");boolean old=Core.settings.getBool(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,false);
        tile.setBlock(Blocks.forceProjector,Vars.player.team(),0);var b=(ForceProjector.ForceBuild)tile.build;
        Unit enemy=null,ally=null,ground=null;
        try{
            b.broken=false;b.buildup=0;b.radscl=1;b.warmup=1;b.efficiency=1;b.phaseHeat=0;
            fields.update(0);var v=fields.volume(b);require(v!=null,"active native bubble");double x=v.x(),y=v.y(),z=(v.bottom()+v.top())*.6,left=x-v.radius()-2,right=x+v.radius()+2;
            Team foe=b.team==Team.crux?Team.sharded:Team.crux;enemy=spawn(UnitTypes.flare,foe,left,y);ally=spawn(UnitTypes.flare,b.team,left,y+8);ground=spawn(UnitTypes.dagger,foe,left,y+10);
            Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,false);
            require(fields.sweepUnit(enemy,left,y,z,right,y,z,.5,.2)==null,"OFF enemy aircraft passes");
            require(fields.trace(foe,left,y,z,right,y,z,0)!=null,"OFF still intercepts enemy projectile/beam");
            Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,true);
            var hit=fields.sweepUnit(enemy,left,y,z,right,y,z,.5,.2);require(hit!=null&&hit.nx()<0,"ON enemy aircraft receives XYZ side contact");
            require(fields.sweepUnit(ally,left,y,z,right,y,z,.5,.2)==null,"allied aircraft passes consistently with shield team rule");
            require(fields.sweepUnit(ground,left,y,z,right,y,z,.5,.2)==null,"option applies only to flying bodies");
            // Same native Dagger, but an actual canonical creator pose: flight permission must not
            // depend solely on the immutable ground UnitType.flying flag.
            Unit creator=ground;try(var creatorAltitudes=new UnitAltitudeSystem((px,pz)->world.terrainHeight(px,-pz))){
                creatorAltitudes.setExternalStateProvider(u->u==creator?new fpv.world.Unit3DState(u.id,u.type.id,u.team.id,left,y,z,0,0,0,0,0,0,z,.5,.2,true):world.unitState(u));
                var creatorWorld=new World3DAdapter(new fpv.terrain.TerrainElevation(),(px,pz)->world.surfaceHeight(px,-pz),creatorAltitudes);
                try(var creatorFields=new ForceProjectorSystem(creatorWorld,()->true)){
                    require(!creator.isFlying()&&creatorWorld.unitState(creator).flying(),"ground type has actual creator flight pose");
                    require(creatorFields.sweepUnit(creator,left,y,z,right,y,z,.5,.2)!=null,"creator airborne Dagger respects hostile ForceProjector barrier");
                }
            }
            require(fields.sweepUnit(enemy,left,y,v.top()+2,right,y,v.top()+2,.5,.2)==null,"true overflight clears bubble");
            require(fields.sweepUnit(enemy,x,y,z,right,y,z,.5,.2)==null,"preexisting inside body may exit");
            var crash=new CrashSystem((px,pz)->world.surfaceHeight(px,-pz),new RadialExplosion(world),FlightProfiles.create().metersPerTile);crash.setWorld(world);
            var state=new FlightController.State();state.x=right;state.z=-y;state.y=z;state.vx=20;
            var impact=crash.resolve(enemy,state,left,z,-y,1d/60,false);
            double inward=state.vx*impact.nx()+state.vy*impact.ny()+state.vz*impact.nz();
            require(impact.hit()&&state.x<x&&inward>=-1e-7&&Math.sqrt(state.vx*state.vx+state.vy*state.vy+state.vz*state.vz)<19.99,
                "production FPV collision clips movement, removes normal speed and retains tangent: contact="+impact+", x="+state.x+", velocity="+state.vx+","+state.vy+","+state.vz+", normalSpeed="+inward);crash.reset();
            // An independent altitude adapter exercises the real unpiloted update branch. Other
            // existing units use immutable external poses so this fixture only moves its own drone.
            Unit own=enemy;try(var altitudes=new UnitAltitudeSystem((px,pz)->world.terrainHeight(px,-pz))){
                altitudes.setExternalStateProvider(u->u==own?null:world.unitState(u));altitudes.setAircraftBarrier(fields::sweepUnit);
                enemy.set((float)left*8,(float)y*8);enemy.vel.setZero();altitudes.update(1d/60);
                require(altitudes.unitState(enemy).z()<v.top()-.3,"AI fixture flies within current bubble height");
                enemy.set((float)right*8,(float)y*8);enemy.vel.set(8,0);altitudes.update(1d/60);
                require(enemy.x<x*8&&enemy.vel.len()<7.99,"unpiloted native aircraft clips at enemy bubble and sheds inward speed: x="+enemy.x+", velocity="+enemy.vel);
                Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,false);altitudes.clear();enemy.set((float)left*8,(float)y*8);altitudes.update(1d/60);
                enemy.set((float)right*8,(float)y*8);altitudes.update(1d/60);require(Math.abs(enemy.x-right*8)<.001,"OFF leaves unpiloted flight unobstructed");
            }
            try(var replica=new ForceProjectorSystem(world,()->false)){
                replica.applySnapshot(fields.snapshot());replica.applyAircraftSettings(new byte[]{1});
                require(!Core.settings.getBool(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,false)&&replica.aircraftCollisionEnabled(),"replicated host flag overrides contradictory local setting");
                require(replica.sweepUnit(enemy,left,y,z,right,y,z,.5,.2)!=null,"replica uses host aircraft setting");
                boolean rejected=false;try{replica.applyAircraftSettings(new byte[]{2});}catch(IllegalArgumentException expected){rejected=true;}
                require(rejected&&replica.aircraftCollisionEnabled(),"malformed flag rejected atomically");replica.applyAircraftSettings(new byte[]{0});require(!replica.aircraftCollisionEnabled(),"host OFF flag replicated");
            }
            Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,true);b.broken=true;
            require(fields.sweepUnit(enemy,left,y,z,right,y,z,.5,.2)==null,"broken native shield has no aircraft barrier");
            b.broken=false;require(fields.traceLaser(foe,left,y,z,right,y,z,0)!=null,"laser protection independent of aircraft option");
            return "PASS: native ForceProjector OFF/ON, enemy/allied/ground/creator/overflight/inside/broken cases, actual FPV sweep, native AI swept correction, host flag codec and projectile/laser protection preserved";
        }finally{Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,old);if(enemy!=null)enemy.remove();if(ally!=null)ally.remove();if(ground!=null)ground.remove();if(tile.build==b)tile.setBlock(Blocks.air);fields.update(0);}
    }
    private static Unit spawn(mindustry.type.UnitType type,Team team,double x,double y){Unit u=type.create(team);u.set((float)x*8,(float)y*8);u.add();return u;}
    private static Tile findSite(World3DAdapter world){
        // The full swept corridor must be clear. A bare projector footprint alone can leave the
        // starting drone inside a Caldera mountain, which correctly triggers terrain recovery
        // before this shield fixture ever reaches its intended barrier.
        for(int y=18;y<Vars.world.height()-18;y++)for(int x=18;x<Vars.world.width()-18;x++){
            boolean free=true;for(int yy=y-3;yy<=y+3&&free;yy++)for(int xx=x-17;xx<=x+17;xx++){
                var t=Vars.world.tile(xx,yy);if(t==null||t.block()!=Blocks.air||t.floor().solid||t.floor().isDeep()||world.surfaceHeight(xx,yy)>.5){free=false;break;}
            }
            if(free)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Force aircraft smoke: "+message);}
}
