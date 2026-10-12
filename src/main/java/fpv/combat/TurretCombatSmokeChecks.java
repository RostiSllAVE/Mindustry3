package fpv.combat;

import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.bullet.*;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.*;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;

/** Real vanilla turret slots, native building interception, and wrong-altitude regressions. */
public final class TurretCombatSmokeChecks {
    private TurretCombatSmokeChecks() {}
    public static String run(World3DAdapter world,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke"),"isolated laboratory only");
        var units=new ArrayList<Unit>();var tiles=new ArrayList<Tile>();float delta=Time.delta;
        try{
            combat.clear();int slots=0;
            for(var block:Vars.content.blocks()){
                if(block instanceof PowerTurret turret){bound(combat,turret.shootType);slots++;}
                if(block instanceof ContinuousTurret turret){bound(combat,turret.shootType);slots++;}
                if(block instanceof ItemTurret turret)for(var entry:turret.ammoTypes){bound(combat,entry.value);slots++;}
                if(block instanceof LiquidTurret turret)for(var entry:turret.ammoTypes){bound(combat,entry.value);slots++;}
                if(block instanceof ContinuousLiquidTurret turret)for(var entry:turret.ammoTypes){bound(combat,entry.value);slots++;}
                if(block instanceof PayloadAmmoTurret turret)for(var entry:turret.ammoTypes){bound(combat,entry.value);slots++;}
            }
            require(slots>0,"all "+slots+" enumerated vanilla turret ammo/shoot slots have XYZ interception");
            Tile site=findSite();require(site!=null,"clear turret fixture site");double x=site.x+2,y=site.y+3;
            Unit low=spawn(units,UnitTypes.dagger,Team.sharded,x,y),high=spawn(units,UnitTypes.eclipse,Team.crux,x+8,y);
            Vars.state.teams.updateTeamStats();
            BulletType[] types={((ItemTurret)Blocks.fuse).ammoTypes.get(Items.thorium),((ItemTurret)Blocks.foreshadow).ammoTypes.get(Items.surgeAlloy),
                ((LiquidTurret)Blocks.wave).ammoTypes.get(Liquids.water),((ContinuousLiquidTurret)Blocks.sublimate).ammoTypes.values().next(),
                ((ContinuousTurret)Blocks.lustre).shootType,((ItemTurret)Blocks.scathe).ammoTypes.get(Items.carbide)};
            for(BulletType type:types){
                float before=high.health;
                Bullet miss=create(type,low,null,5);advance(miss,35);
                require(high.health==before,"special turret shot projected through enemy XY cannot hit at wrong Z: "+combat.originalType(type).getClass().getName());
                combat.clear();
            }
            BulletType fuse=types[0],rail=types[1];
            // A large Eclipse's mesh clearance can put it beyond Fuse's short 12.5-tile 3D range.
            // Keep that ship for the wrong-altitude regression; a nearby Flare supplies the positive contact.
            Unit beamTarget=spawn(units,UnitTypes.flare,Team.crux,x+6,y);Vars.state.teams.updateTeamStats();
            var muzzle=world.unitState(low);var targetPose=world.unitState(beamTarget);var highPose=world.unitState(high);
            double fuseLength=((ShrapnelBulletType)combat.originalType(fuse)).length/Vars.tilesize;
            Log.info("[FPV-TURRET-COMBAT] beam fixture: source=@ target=@ high=@ length=@",muzzle,targetPose,highPose,fuseLength);
            require(Math.sqrt(Math.pow(targetPose.x()-muzzle.x(),2)+Math.pow(targetPose.y()-muzzle.y(),2)+Math.pow(targetPose.z()-muzzle.z(),2))<fuseLength,
                "positive Fuse target lies inside its full XYZ beam range");
            float before=beamTarget.health;advance(create(fuse,low,beamTarget,50),3);require(beamTarget.health<before,"Fuse shrapnel has a genuine XYZ beam hit");combat.clear();
            before=beamTarget.health;advance(create(rail,low,beamTarget,50),3);require(beamTarget.health<before,"Foreshadow rail has a genuine XYZ beam hit");combat.clear();

            Tile segmentTile=Vars.world.tile((int)x+4,(int)y+3);segmentTile.setBlock(Blocks.segment,Team.sharded,0);tiles.add(segmentTile);
            var segment=(PointDefenseTurret.PointDefenseBuild)segmentTile.build;var segmentType=(PointDefenseTurret)Blocks.segment;
            segment.efficiency=1;segment.potentialEfficiency=1;segment.reloadCounter=segmentType.reload;
            BasicBulletType plain=new BasicBulletType(.1f,4);plain.keepVelocity=false;plain.lifetime=300;plain.init();
            Unit enemy=spawn(units,UnitTypes.dagger,Team.crux,x+5,y+3);Vars.state.teams.updateTeamStats();
            Bullet intercept=create(combat.adapt(plain),enemy,null,-1);var state=combat.projectileState(intercept);
            segment.rotation=(float)Math.toDegrees(Math.atan2(state.y()-segment.y/Vars.tilesize,state.x()-segment.x/Vars.tilesize));
            Time.delta=1;segment.updateTile();
            require(!intercept.isAdded()&&!combat.managed(intercept),"actual Segment build intercepts a true XYZ managed projectile");
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM),"Segment exposes an actual 3D interception beam");combat.clear();

            BasicBulletType shortLife=new BasicBulletType(.01f,100);shortLife.keepVelocity=false;shortLife.lifetime=1;shortLife.init();
            Bullet spent=combat.launchPhysical(shortLife,high,high.team,new WorldCoordinates.Point(x,y,600),new WorldCoordinates.Point(0,0,1),-1);advance(spent,182);
            require(spent.isAdded()&&spent.damage<=10.1f&&spent.damage>=9,"native projectile retains at most ten percent damage after three spent seconds");
            require(combat.projectileState(spent).vz() < -25,"spent gravity is stronger in the real native update path");
            advance(spent,600);require(!spent.isAdded()&&!combat.managed(spent),"ten-second spent expiry cannot retain damaging bodies or delayed payloads");
            combat.clear();
            return "passed: "+slots+" native turret slots bound; Fuse/Foreshadow/Wave/Sublimate/Lustre/Scathe wrong-height misses, physical beam hits, actual Segment interception; replacements="+combat.replacementTypes();
        }finally{combat.clear();Time.delta=delta;for(Unit unit:units)if(unit.isAdded())unit.remove();for(Tile tile:tiles)tile.setBlock(Blocks.air);Vars.state.teams.updateTeamStats();}
    }
    private static void bound(Combat3DSystem combat,BulletType type){require(type!=null&&type!=combat.originalType(type),"turret slot has no native XY fallback");}
    private static Unit spawn(ArrayList<Unit> owned,mindustry.type.UnitType type,Team team,double x,double y){
        Unit unit=type.create(team);unit.spawnedByCore=true;unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();unit.health=100000;owned.add(unit);return unit;
    }
    private static Bullet create(BulletType type,Unit owner,Unit target,float damage){
        float angle=target==null?0:(float)Math.toDegrees(Math.atan2(target.y-owner.y,target.x-owner.x));
        return type.create(owner,owner,owner.team,owner.x,owner.y,angle,damage,1,1,null,null,target==null?-1:target.x,target==null?-1:target.y,target);
    }
    private static void advance(Bullet bullet,int ticks){Time.delta=1;for(int i=0;bullet!=null&&i<ticks&&bullet.isAdded();i++){bullet.timer.reset(1,20);bullet.update();}}
    private static Tile findSite(){
        for(int y=6;y<Vars.world.height()-13;y+=2)for(int x=6;x<Vars.world.width()-22;x+=2){
            boolean clear=true;for(int oy=0;oy<10&&clear;oy++)for(int ox=0;ox<19;ox++){var tile=Vars.world.tile(x+ox,y+oy);if(tile.block()!=Blocks.air||tile.build!=null){clear=false;break;}}
            if(clear)return Vars.world.tile(x,y);
        }return null;
    }
    private static void require(boolean condition,String label){if(!condition)throw new IllegalStateException("Turret combat smoke: "+label);Log.info("[FPV-TURRET-COMBAT] OK: @",label);}
}
