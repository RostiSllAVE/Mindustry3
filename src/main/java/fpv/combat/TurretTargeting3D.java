package fpv.combat;

import arc.func.Prov;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.UnitSorts;
import mindustry.gen.*;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.*;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;

/** Native turret lifecycles stay inherited; only physical acquisition, validation and aim change. */
final class TurretTargeting3D implements AutoCloseable {
    private record Factory(Block block,Prov<Building> original,Prov<Building> replacement) {}
    private final ArrayList<Factory> factories=new ArrayList<>();
    private final World3DAdapter world;private final Combat3DSystem combat;
    TurretTargeting3D(World3DAdapter world,Combat3DSystem combat){this.world=world;this.combat=combat;}
    fpv.construction.LayerBuildingAdapter.TurretAiming layerAdapter(){return new fpv.construction.LayerBuildingAdapter.TurretAiming(){
        public Posc enemy(Turret.TurretBuild build,float range){return combat.coverage().active()?TurretTargeting3D.this.enemy(build,range):null;}
        public boolean valid(Turret.TurretBuild build,Posc target){
            if(!combat.coverage().active()||target==null||Units.invalidateTarget(target,build.team,build.x,build.y))return false;
            Turret type=(Turret)build.block;var ammo=build.peekAmmo();
            if(target instanceof Unit u&&(!type.unitFilter.get(u)||ammo!=null&&!ammo.targetMissiles&&u instanceof TimedKillc))return false;
            if(target instanceof Building b&&(!type.targetGround||!type.targetBlocks||!type.buildingFilter.get(b)||ammo!=null&&!ammo.targetBlocks))return false;
            return eligible(build,target,build.range());
        }
        public boolean aim(Turret.TurretBuild build,Posc target){return TurretTargeting3D.this.aim(build,target);}
        public WorldCoordinates.Point direction(Turret.TurretBuild build){return firingDirection(build);}
    };}
    void install(){
        if(!factories.isEmpty())return;
        for(Block block:Vars.content.blocks()){
            Prov<Building> original=block.buildType,replacement=null;
            if(block instanceof ContinuousLiquidTurret t)replacement=()->t.new ContinuousLiquidTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof LaserTurret t)replacement=()->t.new LaserTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof ContinuousTurret t)replacement=()->t.new ContinuousTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof ItemTurret t)replacement=()->t.new ItemTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof LiquidTurret t)replacement=()->t.new LiquidTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof PayloadAmmoTurret t)replacement=()->t.new PayloadTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            else if(block instanceof PowerTurret t)replacement=()->t.new PowerTurretBuild(){
                @Override protected Posc findEnemy(float range){return combat.coverage().active()?enemy(this,range):super.findEnemy(range);}
                @Override protected boolean validateTarget(){return valid(this,super.validateTarget());}
                @Override public void targetPosition(Posc target){if(!aim(this,target))super.targetPosition(target);}
            };
            if(replacement!=null){factories.add(new Factory(block,original,replacement));block.buildType=replacement;}
        }
    }
    private boolean valid(Turret.TurretBuild build,boolean nativeValid){
        if(!combat.coverage().active()||build.controlled()||build.logicControlled())return nativeValid;
        return nativeValid&&build.target!=null&&eligible(build,build.target,build.range());
    }
    private Posc enemy(Turret.TurretBuild build,float range){
        Turret type=(Turret)build.block;var ammo=build.peekAmmo();
        var sort=ammo!=null&&ammo.unitSort!=UnitSorts.closest?ammo.unitSort:type.unitSort;
        Posc selected=Units.bestTarget(build.team,build.x,build.y,range,
            unit->type.unitFilter.get(unit)&&unit.checkTarget(type.targetAir,type.targetGround)
                &&(ammo==null||ammo.targetMissiles||!(unit instanceof TimedKillc))&&eligible(build,unit,range),
            tile->type.targetGround&&type.targetBlocks&&(ammo==null||ammo.targetBlocks)&&type.buildingFilter.get(tile)&&eligible(build,tile,range),sort);
        if(selected==null&&type.targetGround&&type.targetBlocks&&(ammo==null||ammo.targetBlocks)){
            double best=Double.POSITIVE_INFINITY;
            for(var entry:combat.upperBuildings()){Building other=entry.building();if(other==build||other.dead||other.team==build.team||!type.buildingFilter.get(other)||!eligible(build,other,range))continue;
                double distance=build.dst2(other);if(distance<best){best=distance;selected=other;}}
        }
        return selected;
    }
    private boolean eligible(Turret.TurretBuild build,Posc target,float nativeRange){
        double x=build.x/(double)Vars.tilesize,y=build.y/(double)Vars.tilesize,z=world.buildingTop(build)+.4,range=nativeRange/Vars.tilesize;
        double tx=target.x()/(double)Vars.tilesize,ty=target.y()/(double)Vars.tilesize,tz=world.surfaceHeight(tx,ty);
        double multiplier=1;
        if(target instanceof Building building)tz=(world.buildingBase(building)+world.buildingTop(building))*.5;
        if(target instanceof Unit unit){
            var pose=world.unitState(unit);if(pose==null)return false;tz=pose.z();
            Turret type=(Turret)build.block;
            if(!unit.checkTarget(type.targetAir,type.targetGround))return false;
            if(unit.isFlying()&&type.targetAir)multiplier=combat.antiAirVerticalMultiplier();
        }
        if(!CombatBalance.inEnvelope(Math.hypot(tx-x,ty-y),tz-z,range,multiplier)||world.traceTerrainSegment(x,y,z,tx,ty,tz,0).hit())return false;
        if(world.construction()!=null){var hit=world.construction().sweep(x,y,z,tx,ty,tz,0,0,build);
            if(hit!=null&&(!(target instanceof Building building)||!(building.tile instanceof fpv.construction.LayerTile tile)||hit.component().x()!=tile.x||hit.component().y()!=tile.y||hit.component().layer()!=tile.layer))return false;}
        return true;
    }
    private boolean aim(Turret.TurretBuild build,Posc target){
        if(!combat.coverage().active()||!build.hasAmmo()||target==null)return false;
        if(target instanceof Unit unit){
            var pose=world.unitState(unit);if(pose==null)return false;
            double x=build.x/(double)Vars.tilesize,y=build.y/(double)Vars.tilesize;
            var predicted=prediction(build,unit,x,y,world.buildingTop(build)+.4);
            build.targetPos.set((float)(predicted.x()*Vars.tilesize),(float)(predicted.y()*Vars.tilesize));return true;
        }
        return false;
    }
    WorldCoordinates.Point prediction(Turret.TurretBuild build,Unit unit,double x,double y,double z){
        var body=world.unitState(unit);if(body==null)return new WorldCoordinates.Point(unit.x/Vars.tilesize,unit.y/Vars.tilesize,world.surfaceHeight(unit.x/Vars.tilesize,unit.y/Vars.tilesize));
        var ammo=build.peekAmmo();Turret type=(Turret)build.block;
        double speed=ammo==null?0:combat.originalType(ammo).speed*60.0/Vars.tilesize;
        if(!type.predictTarget||speed<.01)return new WorldCoordinates.Point(body.x(),body.y(),body.z());
        return CombatGeometry.intercept(x,y,z,body.x(),body.y(),body.z(),body.vx(),body.vy(),body.vz(),speed,30);
    }
    WorldCoordinates.Point firingDirection(Turret.TurretBuild build){
        if(build.controlled()){var direction=combat.controlDirection(build.unit());if(direction!=null)return direction;}
        double x=build.x/(double)Vars.tilesize,y=build.y/(double)Vars.tilesize,z=world.buildingTop(build)+.4;
        double tx=build.targetPos.x/Vars.tilesize,ty=build.targetPos.y/Vars.tilesize,tz=z;
        if(build.target instanceof Unit u){var pose=world.unitState(u);if(pose==null)return null;var p=prediction(build,u,x,y,z);tx=p.x();ty=p.y();tz=p.z();}
        else if(build.target instanceof Building b)tz=(world.buildingTop(b)+world.buildingBase(b))*.5;
        else if(!build.controlled()&&!build.logicControlled())return null;
        if(!Double.isFinite(x+y+z+tx+ty+tz)||Math.abs(tx-x)+Math.abs(ty-y)+Math.abs(tz-z)<1e-10)return null;
        return CombatGeometry.aim(Float.isFinite(build.rotation)?build.rotation:0,Math.hypot(tx-x,ty-y),tz-z);
    }
    @Override public void close(){for(var f:factories)if(f.block.buildType==f.replacement)f.block.buildType=f.original;factories.clear();}
}
