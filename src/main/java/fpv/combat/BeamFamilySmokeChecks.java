package fpv.combat;

import arc.util.Time;
import fpv.world.World3DAdapter;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.content.Blocks;
import mindustry.entities.bullet.PointLaserBulletType;
import mindustry.entities.bullet.SapBulletType;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Unit;

/** Native family-specific damage semantics exercised through the actual XYZ proxy. */
public final class BeamFamilySmokeChecks {
    public static void run(World3DAdapter world,Combat3DSystem combat){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.world.width()!=128)throw new IllegalStateException("Dedicated lab required");
        var owned=new ArrayList<Unit>();float oldDelta=Time.delta;var wall=Vars.world.tile(19,94);
        if(wall.block()!=Blocks.air)throw new IllegalStateException("Dedicated empty fixture tile required");
        try{
            Unit source=spawn(owned,Team.sharded,12,94),near=spawn(owned,Team.crux,19,94),far=spawn(owned,Team.crux,28,94);
            source.health=50;near.health=far.health=1000;Vars.state.teams.updateTeamStats();combat.update(0);
            SapBulletType sap=new SapBulletType();sap.damage=12;sap.length=30*Vars.tilesize;sap.init();
            Bullet first=create(combat.adapt(sap,true),source,far);float after=near.health;
            check(after<1000&&far.health==1000,"Sap damages first XYZ target only");
            check(source.health>50,"Sap retains native life steal");first.remove();combat.clear();
            near.remove();wall.setBlock(Blocks.plastaniumWall,Team.crux);source.health=50;float wallBefore=wall.build.health;
            first=create(combat.adapt(sap,true),source,far);
            check(wall.build.health<wallBefore&&source.health>50&&far.health==1000,"Sap stops at and drains first building once");
            first.remove();combat.clear();wall.setAir();
            near=spawn(owned,Team.crux,19,94);Vars.state.teams.updateTeamStats();combat.update(0);
            near.health=far.health=1000;
            PointLaserBulletType point=new PointLaserBulletType();point.damage=9;point.hitSize=1;point.maxRange=32*Vars.tilesize;point.init();
            Bullet beam=create(combat.adapt(point,true),source,far);Time.delta=1;beam.timer.reset(1,point.damageInterval);beam.update();
            check(near.health==1000&&far.health<1000,"PointLaser damages endpoint, not intermediate unit");
            after=far.health;beam.update();check(far.health==after,"PointLaser keeps native pulse interval");
            var end=combat.visuals().stream().filter(v->v.kind()==CombatVisual.Kind.BEAM).findFirst().orElseThrow();
            var pose=world.unitState(far);
            check(Math.abs(end.bx()-pose.x())<.05&&Math.abs(end.by()-pose.y())<.05,"PointLaser visual ends at actual aim point");
            beam.remove();
        }finally{wall.setAir();combat.clear();Time.delta=oldDelta;for(Unit unit:owned)if(unit.isAdded())unit.remove();Vars.state.teams.updateTeamStats();}
    }
    private static Bullet create(mindustry.entities.bullet.BulletType type,Unit source,Unit target){
        return type.create(source,source,source.team,source.x,source.y,0,-1,1,1,null,null,target.x,target.y,target);
    }
    private static Unit spawn(ArrayList<Unit> owned,Team team,double x,double y){
        Unit unit=UnitTypes.dagger.create(team);unit.spawnedByCore=true;unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.rotation=0;
        unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();owned.add(unit);return unit;
    }
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Beam family: "+label);arc.util.Log.info("[FPV-BEAM-FAMILY] OK: @",label);}
}
