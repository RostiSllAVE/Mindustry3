package fpv.combat;

import arc.func.Prov;
import arc.math.Angles;
import arc.math.Mathf;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.PointDefenseTurret;
import mindustry.world.blocks.defense.turrets.TractorBeamTurret;
import fpv.world.World3DAdapter;

/** Native-compatible building subclasses replace only direct XY interception/pull logic in 3D. */
public final class TurretDefense3D implements AutoCloseable {
    private record Factory(Block block,Prov<Building> original,Prov<Building> replacement) {}
    private final ArrayList<Factory> factories=new ArrayList<>();
    private final World3DAdapter world;
    private final Combat3DSystem combat;
    private final CombatTargetIndex targets;
    TurretDefense3D(World3DAdapter world,Combat3DSystem combat){this.world=world;this.combat=combat;targets=new CombatTargetIndex(world);}
    public void updateUpper(Building building){
        if(!combat.authoritative())return;
        if(building instanceof PointDefenseTurret.PointDefenseBuild point&&building.block instanceof PointDefenseTurret type)updatePoint(type,point);
        else if(building instanceof TractorBeamTurret.TractorBeamBuild tractor&&building.block instanceof TractorBeamTurret type)updateTractor(type,tractor);
        else if(building instanceof mindustry.world.blocks.defense.ShockwaveTower.ShockwaveTowerBuild wave&&building.block instanceof mindustry.world.blocks.defense.ShockwaveTower type)updateShockwave(type,wave);
        else if(building instanceof mindustry.world.blocks.defense.ShockMine.ShockMineBuild mine&&building.block instanceof mindustry.world.blocks.defense.ShockMine type)updateMine(type,mine);
    }
    private void updateShockwave(mindustry.world.blocks.defense.ShockwaveTower type,mindustry.world.blocks.defense.ShockwaveTower.ShockwaveTowerBuild build){
        build.heat=Mathf.clamp(build.heat-Time.delta/type.reload*type.cooldownMultiplier);
        if(build.potentialEfficiency<=0||(build.reloadCounter+=build.edelta())<type.reload||!build.timer(type.timerCheck,type.checkInterval))return;
        double x=build.x/Vars.tilesize,y=build.y/Vars.tilesize,z=world.buildingTop(build)+.08;
        var shots=combat.defensiveTargets(build.team,x,y,z,type.range/Vars.tilesize);if(shots.isEmpty())return;
        float damage=Math.min(type.bulletDamage,type.bulletDamage*type.falloffCount/shots.size());
        for(var shot:shots)if(combat.intercept(shot,damage))combat.defensiveBeam(build.team,x,y,z,shot.x(),shot.y(),shot.z(),type.waveColor);
        build.heat=1;build.reloadCounter=0;if(!Vars.headless)type.shootSound.at(build);
    }
    private void updateMine(mindustry.world.blocks.defense.ShockMine type,mindustry.world.blocks.defense.ShockMine.ShockMineBuild build){
        if(!build.enabled)return;double x=build.x/Vars.tilesize,y=build.y/Vars.tilesize,z=world.buildingTop(build);final boolean[] touch={false};
        targets.nearby(build.team,x-2,y-2,x+2,y+2,0,0,unit->{var p=world.unitState(unit);if(p!=null&&!p.flying()&&!unit.dead&&Math.abs(p.z()-z)<=p.halfHeight()+.2&&Math.hypot(p.x()-x,p.y()-y)<=p.radius()+type.size*.5)touch[0]=true;});
        if(touch[0]&&build.timer(type.timerDamage,type.cooldown)){combat.mineLightning(build,type,z+.04);build.damage(type.tileDamage);}
    }
    void install(){
        if(!factories.isEmpty())return;
        for(Block block:Vars.content.blocks()){
            Prov<Building> original=block.buildType,replacement=null;
            if(block instanceof PointDefenseTurret point)replacement=()->point.new PointDefenseBuild(){
                @Override public void updateTile(){
                    if(!combat.coverage().active()){super.updateTile();return;}
                    if(!combat.authoritative())return;
                    if(point.coolant!=null&&reloadCounter<point.reload)updateCooling();
                    updatePoint(point,this);
                }
            };
            else if(block instanceof TractorBeamTurret tractor)replacement=()->tractor.new TractorBeamBuild(){
                @Override public void updateTile(){
                    if(!combat.coverage().active()){super.updateTile();return;}
                    if(combat.authoritative())updateTractor(tractor,this);
                }
            };
            if(replacement!=null){factories.add(new Factory(block,original,replacement));block.buildType=replacement;}
        }
    }
    private void updatePoint(PointDefenseTurret type,PointDefenseTurret.PointDefenseBuild build){
        double x=build.x/Vars.tilesize,y=build.y/Vars.tilesize,z=world.buildingBase(build)+fpv.net.HeadlessGeometry.heightFor(build.block)+.08;
        var target=combat.nearestDefensiveTarget(build.team,x,y,z,type.range/Vars.tilesize);
        build.target=target==null?null:target.bullet();
        if(target==null||build.efficiency<=0)return;
        float bearing=(float)Math.toDegrees(Math.atan2(target.y()-y,target.x()-x));
        build.rotation=Angles.moveToward(build.rotation,bearing,type.rotateSpeed*build.edelta());
        build.reloadCounter+=build.edelta();
        if(build.reloadCounter<type.reload||!Angles.within(build.rotation,bearing,type.shootCone))return;
        if(world.traceTerrainSegment(x,y,z,target.x(),target.y(),target.z(),0).hit())return;
        if(combat.intercept(target,type.bulletDamage*Vars.state.rules.blockDamage(build.team))){
            combat.defensiveBeam(build.team,x,y,z,target.x(),target.y(),target.z(),type.color);
            build.reloadCounter=0;
            if(!Vars.headless)type.shootSound.at(build,1);
        }
    }
    private void updateTractor(TractorBeamTurret type,TractorBeamTurret.TractorBeamBuild build){
        if(build.activationTimer>0){build.activationTimer=Math.max(0,build.activationTimer-Time.delta);return;}
        double x=build.x/Vars.tilesize,y=build.y/Vars.tilesize,z=world.buildingBase(build)+fpv.net.HeadlessGeometry.heightFor(build.block)+.08,range=type.range/Vars.tilesize;
        final Unit[] chosen={null};final double[] best={Double.POSITIVE_INFINITY};
        targets.nearby(build.team,x-range,y-range,x+range,y+range,0,0,unit->{
            if(unit.dead||!unit.isAdded()||!unit.checkTarget(type.targetAir,type.targetGround))return;
            var body=world.unitState(unit);if(body==null)return;
            if(!CombatBalance.inEnvelope(Math.hypot(x-body.x(),y-body.y()),body.z()-z,range,type.targetAir?combat.antiAirVerticalMultiplier():1))return;
            double distance=sq(x-body.x())+sq(y-body.y())+sq(z-body.z());
            if(distance<best[0]&&!world.traceTerrainSegment(x,y,z,body.x(),body.y(),body.z(),0).hit()){best[0]=distance;chosen[0]=unit;}
        });
        build.target=chosen[0];build.any=false;
        if(build.target==null||build.efficiency<=.02f){build.strength=Mathf.lerpDelta(build.strength,0,.1f);return;}
        var body=world.unitState(build.target);float eff=build.efficiency;
        if(type.coolant!=null&&build.liquids!=null){
            var liquid=build.liquids.current();float used=Math.min(build.liquids.get(liquid),type.coolant.amount*Math.max(0,Time.delta));
            if(used>0){build.liquids.remove(liquid,used);eff*=1+liquid.heatCapacity*type.coolantMultiplier;}
        }
        float bearing=(float)Math.toDegrees(Math.atan2(body.y()-y,body.x()-x));
        build.rotation=Angles.moveToward(build.rotation,bearing,type.rotateSpeed*eff*build.delta());
        build.lastX=build.target.x;build.lastY=build.target.y;build.strength=Mathf.lerpDelta(build.strength,1,.1f);
        if(!Angles.within(build.rotation,bearing,type.shootCone))return;
        double distance=Math.sqrt(best[0]);build.any=true;
        if(type.damage>0)build.target.damagePierce(type.damage*eff*build.delta()*Vars.state.rules.blockDamage(build.team));
        if(type.status!=StatusEffects.none&&Mathf.chance(type.statusChance))build.target.apply(type.status,type.statusDuration);
        double impulse=(type.force+Math.max(0,1-Math.hypot(x-body.x(),y-body.y())/range)*type.scaledForce)*eff*build.delta()*60/Vars.tilesize/Math.max(.001,build.target.mass());
        if(distance>1e-6)world.addVelocity(build.target,(x-body.x())/distance*impulse,(y-body.y())/distance*impulse,(z-body.z())/distance*impulse);
        combat.defensiveBeam(build.team,x,y,z,body.x(),body.y(),body.z(),type.laserColor);
    }
    @Override public void close(){for(var factory:factories)if(factory.block.buildType==factory.replacement)factory.block.buildType=factory.original;factories.clear();targets.invalidate();}
    private static double sq(double value){return value*value;}
}
