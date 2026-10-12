package fpv.construction;

import arc.math.*;
import arc.util.Time;
import java.util.Map;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.units.*;

/** Native timing/consumption parameters with XYZ target selection instead of the global XY index. */
final class LayerAreaBehavior {
    private static final Map<Building,Float> regenAmounts=new java.util.WeakHashMap<>();
    private static long regenTick=-1;
    static void regenTargets(LayerBuildingAdapter owner,RegenProjector.RegenProjectorBuild build){build.targets.clear();buildings(owner,build,((RegenProjector)build.block).range*Vars.tilesize*.5f,build.targets::add);}
    static void regen(LayerBuildingAdapter owner,RegenProjector.RegenProjectorBuild build){
        var type=(RegenProjector)build.block;
        // The owner topology revision is not a native tileChanges counter; refresh bounded targets on a timer.
        if(build.lastChange!=Vars.world.tileChanges||build.timer(0,15)){build.lastChange=Vars.world.tileChanges;regenTargets(owner,build);}
        build.warmup=Mathf.approachDelta(build.warmup,build.didRegen?1:0,1f/70);build.totalTime+=build.warmup*Time.delta;build.didRegen=false;build.anyTargets=false;
        if(build.checkSuppression())return;
        for(Building target:build.targets)if(!target.dead&&target.damaged()){build.anyTargets=true;break;}
        if(build.efficiency<=0)return;
        if((build.optionalTimer+=build.edelta()*build.optionalEfficiency)>=type.optionalUseTime){build.consume();build.optionalTimer=0;}
        if(regenTick!=Vars.state.updateId){regenAmounts.clear();regenTick=Vars.state.updateId;}
        for(Building target:build.targets)if(!target.dead&&target.damaged()&&!target.isHealSuppressed()){
            float amount=Mathf.lerp(1,type.optionalMultiplier,build.optionalEfficiency)*type.healPercent*build.edelta()*target.block.health/100;
            float previous=regenAmounts.getOrDefault(target,0f);if(amount>previous){target.heal(amount-previous);target.recentlyHealed();regenAmounts.put(target,amount);}build.didRegen=true;
        }
    }
    private static boolean near(LayerBuildingAdapter owner,Building from,Building to,float nativeRange){
        double z=(owner.buildingAltitude(from)-owner.buildingAltitude(to))*Vars.tilesize;
        return from.team==to.team&&!to.dead&&from.dst2(to)+z*z<=nativeRange*nativeRange;
    }
    private static boolean near(LayerBuildingAdapter owner,Building from,Unit to,float nativeRange){
        double z=(owner.buildingAltitude(from)-owner.unitAltitude(to))*Vars.tilesize;
        double range=nativeRange+to.hitSize/2;
        return from.team==to.team&&!to.dead&&to.isAdded()&&from.dst2(to)+z*z<=range*range;
    }
    private static void buildings(LayerBuildingAdapter owner,Building from,float range,arc.func.Cons<Building> action){
        for(var entry:owner.entries()){Building target=entry.building();if(near(owner,from,target,range))action.get(target);}
        // Sleeping buildings are absent from Groups.build. Read native tiles, never mutate adjacency/indexes.
        int minX=Math.max(0,(int)((from.x-range)/Vars.tilesize)),maxX=Math.min(Vars.world.width()-1,(int)((from.x+range)/Vars.tilesize));
        int minY=Math.max(0,(int)((from.y-range)/Vars.tilesize)),maxY=Math.min(Vars.world.height()-1,(int)((from.y+range)/Vars.tilesize));
        for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++){
            var tile=Vars.world.tile(x,y);Building target=tile==null?null:tile.build;
            if(target!=null&&target.tile==tile&&near(owner,from,target,range))action.get(target);
        }
    }
    static void mend(LayerBuildingAdapter owner,MendProjector.MendBuild build){
        MendProjector type=(MendProjector)build.block;boolean heal=!build.checkSuppression();
        build.smoothEfficiency=Mathf.lerpDelta(build.smoothEfficiency,build.efficiency,.08f);build.heat=Mathf.lerpDelta(build.heat,build.efficiency>0&&heal?1:0,.08f);build.charge+=build.heat*build.delta();
        build.phaseHeat=Mathf.lerpDelta(build.phaseHeat,build.optionalEfficiency,.1f);
        if(build.optionalEfficiency>0&&build.timer(type.timerUse,type.useTime/build.timeScale())&&heal)build.consume();
        if(build.charge>=type.reload&&heal){build.charge=0;buildings(owner,build,type.range+build.phaseHeat*type.phaseRangeBoost,target->{
            if(target.damaged()&&!target.isHealSuppressed()){target.heal(target.maxHealth()*(type.healPercent+build.phaseHeat*type.phaseBoost)/100*build.efficiency);target.recentlyHealed();}
        });}
    }
    static void overdrive(LayerBuildingAdapter owner,OverdriveProjector.OverdriveBuild build){
        OverdriveProjector type=(OverdriveProjector)build.block;
        build.smoothEfficiency=Mathf.lerpDelta(build.smoothEfficiency,build.efficiency,.08f);build.heat=Mathf.lerpDelta(build.heat,build.efficiency>0?1:0,.08f);build.charge+=build.heat*Time.delta;
        if(type.hasBoost)build.phaseHeat=Mathf.lerpDelta(build.phaseHeat,build.optionalEfficiency,.1f);
        if(build.charge>=type.reload){build.charge=0;buildings(owner,build,type.range+build.phaseHeat*type.phaseRangeBoost,target->{if(target.block.canOverdrive)target.applyBoost(build.realBoost(),type.reload+1);});}
        if(build.efficiency>0)build.useProgress+=build.delta();if(build.useProgress>=type.useTime){build.consume();build.useProgress%=type.useTime;}
    }
    static void repair(LayerBuildingAdapter owner,RepairTurret.RepairPointBuild build){
        RepairTurret type=(RepairTurret)build.block;
        if(build.target!=null&&(!near(owner,build,build.target,type.repairRadius)||!build.target.damaged()))build.target=null;
        if(build.timer(type.timerTarget,20)){
            build.target=null;double nearest=Double.POSITIVE_INFINITY;
            for(Unit unit:Groups.unit)if(unit.damaged()&&near(owner,build,unit,type.repairRadius)){
                double dz=(owner.unitAltitude(unit)-owner.buildingAltitude(build))*Vars.tilesize,d=build.dst2(unit)+dz*dz;
                if(d<nearest){nearest=d;build.target=unit;}
            }
        }
        boolean healed=false;
        if(build.target!=null&&build.efficiency>0){
            float angle=build.angleTo(build.target);if(Angles.angleDist(angle,build.rotation)<30){
                float coolant=type.acceptCoolant?1+build.liquids.current().heatCapacity*type.coolantMultiplier*build.optionalEfficiency:1;
                build.target.heal(type.repairSpeed*build.strength*build.edelta()*coolant);healed=true;
            }build.rotation=Mathf.slerpDelta(build.rotation,angle,.5f*build.efficiency*build.timeScale());
        }else build.offset.setZero();
        build.strength=Mathf.lerpDelta(build.strength,healed?1:0,.08f*Time.delta);
    }
    static void tower(LayerBuildingAdapter owner,RepairTower.RepairTowerBuild build){
        RepairTower type=(RepairTower)build.block;
        // Native RepairTower.refreshInterval is package-private constant 6 simulation frames.
        if(build.potentialEfficiency>0&&(build.refresh+=Time.delta)>=6){build.refresh=0;build.targets.clear();for(Unit unit:Groups.unit)if(unit.damaged()&&near(owner,build,unit,type.range))build.targets.add(unit);}
        if(build.checkSuppression()){build.warmup=0;return;}boolean healed=false;
        if(build.efficiency>0)for(Unit unit:build.targets)if(unit.damaged()&&near(owner,build,unit,type.range)){unit.heal(type.healAmount*build.edelta());healed=true;}
        build.warmup=Mathf.lerpDelta(build.warmup,healed?build.efficiency:0,.08f);build.totalProgress+=Time.delta/type.circleSpeed;
    }
    static boolean door(LayerBuildingAdapter owner,Door.DoorBuild door,boolean open){
        door.updateChained();for(Door.DoorBuild other:door.chained){
            if(!open)for(Unit unit:Groups.unit){
                double dz=(owner.buildingAltitude(other)-owner.unitAltitude(unit))*Vars.tilesize;
                double range=other.block.size*Vars.tilesize*.7+unit.hitSize/2;
                if(!unit.dead&&unit.isAdded()&&other.dst2(unit)+dz*dz<=range*range)return false;
            }
        }for(Door.DoorBuild other:door.chained)other.open=open;return true;
    }
    static float shieldWallDamage(ShieldWall.ShieldWallBuild build,float damage){
        float absorbed=build.broken()?0:Math.min(build.shield,damage);build.shield-=absorbed;
        if(absorbed>0&&build.shield<=.00001f)build.breakTimer=((ShieldWall)build.block).breakCooldown;
        return damage-absorbed;
    }
    static void autoDoor(LayerBuildingAdapter owner,AutoDoor.AutoDoorBuild door){
        AutoDoor type=(AutoDoor)door.block;if(!door.timer(type.timerToggle,type.checkInterval))return;
        boolean friendly=false,occupied=false;
        for(Unit unit:Groups.unit)if(!unit.dead&&unit.isAdded()){
            double dz=(owner.buildingAltitude(door)-owner.unitAltitude(unit))*Vars.tilesize;
            double range=door.block.size*Vars.tilesize*.5+unit.hitSize*.5;
            if(door.dst2(unit)+dz*dz<=range*range)occupied=true;
            double trigger=range+type.triggerMargin;
            if(unit.team==door.team&&!unit.isFlying()&&door.dst2(unit)+dz*dz<=trigger*trigger)friendly=true;
        }
        door.setOpen(friendly||door.open&&occupied);
    }
}
