package fpv;

import arc.math.geom.Vec3;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.physics.CrashSystem;
import mindustry.Vars;
import mindustry.ai.types.GroundAI;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import java.util.ArrayList;

/** Explicit temporary fixtures confined to the isolated scripted lab. */
final class FeatureSmokeChecks {
    private final FpvMod mod;
    private final ArrayList<Unit> owned=new ArrayList<>();
    private Tile site;
    private Unit enemy,friend;
    private float enemyHealth,friendHealth,buildingHealth;
    FeatureSmokeChecks(FpvMod mod){if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Lab only");this.mod=mod;}
    void setupBlast(){
        var core=Vars.player.core();
        search:for(int dy=-12;dy<=12;dy++)for(int dx=6;dx<=14;dx++){
            Tile tile=Vars.world.tile(core.tileX()+dx,core.tileY()+dy);
            if(tile!=null&&tile.block()==Blocks.air&&!tile.floor().isLiquid&&tile.build==null){site=tile;break search;}
        }
        require(site!=null,"Empty isolated blast site");
        site.setBlock(Blocks.copperWall,Team.crux,0);
        enemy=spawn(UnitTypes.dagger,Team.crux,site.worldx()+Vars.tilesize,site.worldy());
        friend=spawn(UnitTypes.dagger,Vars.player.team(),site.worldx()-Vars.tilesize,site.worldy());
        enemyHealth=enemy.health;friendHealth=friend.health;buildingHealth=site.build.health;
    }
    Unit spawn(mindustry.type.UnitType type,Team team,float x,float y){
        Unit unit=type.create(team);unit.set(x,y);
        unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();owned.add(unit);return unit;
    }
    void run(){
        fpv.units.UnitAltitudeSmokeChecks.run();
        // Same production solver and death API; a sacrificial native flyer is not the user's player.
        Unit drone=spawn(UnitTypes.flare,Vars.player.team(),site.worldx(),site.worldy()+4*Vars.tilesize);
        var isolatedEffects=new RadialExplosion(u->0,b->1);
        var crash=new CrashSystem((x,z)->0,isolatedEffects,mod.flightProfile.metersPerTile);
        var s=new FlightController.State();s.x=drone.x/Vars.tilesize;s.z=-drone.y/Vars.tilesize;s.y=.1;s.vy=-1;
        float health=drone.health;
        crash.resolve(drone,s,s.x,2,s.z,.1,true);
        crash.applyPending();
        require(drone.health==health&&!drone.dead,"Gentle impact does not damage");
        s.y=.1;s.vy=-50;crash.reset();crash.resolve(drone,s,s.x,3,s.z,.4,false);
        crash.applyPending();
        require(drone.health==health&&!drone.dead,"Build/recovery collision can resolve without damage");
        s.y=.1;s.vy=-50;crash.reset();crash.resolve(drone,s,s.x,3,s.z,.4,true);
        require(!drone.dead,"Lethal damage waits for native motion reconciliation");crash.applyPending();
        require(drone.dead&&crash.impactDeaths()==1&&isolatedEffects.bursts().size()==1,"Hard native impact kills once and creates one original3D burst");
        var impact=crash.lastImpact();var burst=isolatedEffects.bursts().get(0);
        require(Math.abs(burst.y-impact.y())<.00001&&Math.abs(burst.y)<.001,"Burst is at actual ground collision height");
        crash.resolve(drone,s,s.x,s.y,s.z,.4,true);
        require(crash.impactDeaths()==1&&isolatedEffects.bursts().size()==1,"Dead craft cannot repeat explosion");
        Unit moving=spawn(UnitTypes.flare,Vars.player.team(),site.worldx(),site.worldy()+6*Vars.tilesize);
        double wall=moving.x/(double)Vars.tilesize+2;
        var movingEffects=new RadialExplosion(u->0,b->1);
        var movingCrash=new CrashSystem((x,z)->x>=wall?5:0,movingEffects,mod.flightProfile.metersPerTile);
        var motion=new fpv.flight.UnitMotionBridge();var movingState=new FlightController.State();
        motion.syncFromSimulation(moving,movingState);double oldX=movingState.x,oldZ=movingState.z;
        movingState.y=1;movingState.x=oldX+4;movingState.vx=100;
        movingCrash.resolve(moving,movingState,oldX,1,oldZ,.04,true);
        double resolvedX=movingState.x;float previousDelta=arc.util.Time.delta;
        try{
            arc.util.Time.delta=1;motion.beforeSimulation(moving,movingState,1);moving.update();motion.afterSimulation(moving,movingState);
            require(Math.abs(moving.x/Vars.tilesize-resolvedX)<.001&&!moving.dead,"Native movement reaches resolved cliff contact before death");
            movingCrash.applyPending();
            require(moving.dead&&Math.abs(moving.x/Vars.tilesize-resolvedX)<.001,"Native impact death occurs at resolved XY");
            require(Math.abs(movingEffects.bursts().get(0).x-wall)<.01,"Custom burst occurs on physical cliff face");
        }finally{arc.util.Time.delta=previousDelta;}
        mod.explosions.detonate(Vars.player.team(),site.x,0,-site.y,new RadialExplosion.Spec(2.5,28,1.4,.65,RadialExplosion.Style.sparks,false));
        require(enemy.health<enemyHealth,"Radial3D blast damages nearby enemy unit");
        require(friend.health==friendHealth,"Friendly unit immune to crash blast");
        require(site.build.health<buildingHealth,"Radial3D blast damages enemy building through native damage API");
        float before=enemy.health;
        mod.explosions.detonate(Vars.player.team(),site.x,30,-site.y,new RadialExplosion.Spec(2.5,28,1.4,.65,RadialExplosion.Style.pulse,false));
        require(enemy.health==before,"High altitude blast cannot damage distant ground target");
        // Same solver on the actual mountain field, vertical impact from above peak.
        Tile peak=null;double highest=0;
        for(Tile tile:Vars.world.tiles)if(mod.renderer3d.terrain().naturalWall(tile)){
            double h=mod.renderer3d.terrain().heightAt(tile.x,-tile.y);if(h>highest){highest=h;peak=tile;}
        }
        require(peak!=null,"Native mountain fixture available");
        var solver=new fpv.physics.SweptCollision(mod.renderer3d::surfaceHeight,.22,.08);
        var probe=new FlightController.State();probe.x=peak.x;probe.z=-peak.y;probe.y=0;probe.vy=-100;
        var terrainHit=solver.resolve(probe,probe.x,highest+5,probe.z);
        require(terrainHit.hit()&&probe.y>=highest,"Actual generated mountain blocks vertical FPV collision");
        // Picking from directly over the same triangle uses the identical height surface.
        var camera=new arc.graphics.g3d.Camera3D();camera.position.set(peak.x,(float)highest+5,-peak.y);
        camera.direction.set(0,-1,0);camera.up.set(0,0,-1);camera.near=.01f;camera.far=50;camera.resize(1280,800);camera.update();
        var picked=mod.renderer3d.pick(camera,640,400);
        require(picked!=null&&picked.tileX==peak.x&&picked.tileY==peak.y&&Math.abs(picked.height-highest)<.01,"Mountain raycast agrees with collision triangle height");
    }
    void close(){for(Unit u:owned)if(u.isAdded())u.remove();owned.clear();if(site!=null&&site.block()==Blocks.copperWall)site.setBlock(Blocks.air);site=null;}
    private static void require(boolean test,String message){if(!test)throw new IllegalStateException(message);arc.util.Log.info("[FPV-FEATURE] OK: @",message);}
}
