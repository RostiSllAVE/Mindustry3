package fpv.combat;

import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntSet;
import java.util.function.Consumer;
import mindustry.Vars;
import mindustry.content.Bullets;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.BuildingBulletDestroyEvent;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.gen.Bullet;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;

/** Bounded physical chain lightning. Never emits the native stationary XY damage bullets. */
final class Lightning3D {
    private static final int MAX_HOPS=8,MAX_SEGMENTS=64;
    private static final double SEEK_RADIUS=30.0/8,RAY_RADIUS=.025;
    private final World3DAdapter world;
    private final CombatTargetIndex targets;
    private final Consumer<CombatVisual> visual;
    private ProjectedDefense projectedDefense;
    private CombatBalance.RangeFalloff rangeFalloff=CombatBalance.DEFAULT_RANGE_FALLOFF;
    Lightning3D(World3DAdapter world,CombatTargetIndex targets,Consumer<CombatVisual> visual){this.world=world;this.targets=targets;this.visual=visual;}
    void setProjectedDefense(ProjectedDefense defense){projectedDefense=defense;}
    void setRangeFalloff(CombatBalance.RangeFalloff value){rangeFalloff=value;}
    void emit(Bullet bullet,BulletType parent,double x,double y,double z,WorldCoordinates.Point direction,float damage,int length,Color color,
              double originX,double originY,double range){
        BulletType hitType=parent.lightningType==null?Bullets.damageLightning:parent.lightningType;
        double remaining=Math.max(0,Math.min(512,length*6.0/Vars.tilesize));
        float power=Math.max(0,damage*bullet.damageMultiplier());
        double emittedFactor=rangeFalloff.factor(Math.hypot(x-originX,y-originY),range);
        if(emittedFactor<=0||power<=0)return;
        IntSet hitUnits=new IntSet(),hitBuildings=new IntSet();
        var heading=direction;
        for(int segment=0;segment<MAX_SEGMENTS&&remaining>1e-6&&hitUnits.size<MAX_HOPS;segment++){
            final double sx=x,sy=y,sz=z,reach=Math.min(SEEK_RADIUS,remaining);
            final Unit[] chosen={null};final double[] best={reach};
            if(parent.pierceCap>0&&hitUnits.size>=parent.pierceCap)break;
            targets.nearby(bullet.team,x-reach,y-reach,x+reach,y+reach,0,0,unit->{
                if(unit.dead||!unit.isAdded()||unit==bullet.owner||!unit.hittable()||hitUnits.contains(unit.id)
                    ||!unit.checkTarget(parent.collidesAir&&hitType.collidesAir,parent.collidesGround&&hitType.collidesGround))return;
                var body=world.unitState(unit);if(body==null)return;
                double distance=Math.sqrt(sq(body.x()-sx)+sq(body.y()-sy)+sq(body.z()-sz));
                if(distance<=best[0]){best[0]=distance;chosen[0]=unit;}
            });
            double distance;
            if(chosen[0]!=null){
                var body=world.unitState(chosen[0]);
                double dx=body.x()-x,dy=body.y()-y,dz=body.z()-z;
                distance=Math.sqrt(sq(dx)+sq(dy)+sq(dz));
                if(distance<1e-8){hitUnits.add(chosen[0].id);float localPower=contactPower(power,emittedFactor,x,y,originX,originY,range);
                    if(localPower<=0)break;damageUnit(bullet,hitType,chosen[0],localPower,x,y);continue;}
                heading=CombatGeometry.direction(dx,dy,dz);
            }else{
                distance=Math.min(remaining,15.0/Vars.tilesize);
                heading=CombatGeometry.rotateAzimuth(heading,Mathf.range(20f));
            }
            distance=Math.min(distance,remaining);
            double bx=x+heading.x()*distance,by=y+heading.y()*distance,bz=z+heading.z()*distance;
            var terrain=world.traceTerrainSegment(x,y,z,bx,by,bz,RAY_RADIUS);
            final double[] stop={terrain.hit()?terrain.fraction():1};
            var projection=projectedDefense==null?null:projectedDefense.trace(bullet.team,x,y,z,bx,by,bz,RAY_RADIUS,bullet);
            if(projection!=null&&projection.fraction()>=0&&projection.fraction()<=stop[0])stop[0]=projection.fraction();else projection=null;
            final Unit[] contactUnit={null};final Building[] contactBuild={null};
            targets.nearby(bullet.team,x,y,bx,by,RAY_RADIUS,0,unit->{
                if(unit.dead||!unit.isAdded()||unit==bullet.owner||!unit.hittable()||hitUnits.contains(unit.id)
                    ||!unit.checkTarget(parent.collidesAir&&hitType.collidesAir,parent.collidesGround&&hitType.collidesGround))return;
                var body=world.unitState(unit);if(body==null)return;
                double fraction=CombatGeometry.cylinder(sx,sy,sz,bx,by,bz,body.x(),body.y(),body.z(),body.radius()+RAY_RADIUS,body.halfHeight()+RAY_RADIUS);
                if(fraction<=stop[0]){stop[0]=fraction;contactUnit[0]=unit;}
            });
            {
                IntSet visited=new IntSet();
                int minX=Math.max(0,(int)Math.floor(Math.min(x,bx)-.5)),maxX=Math.min(Vars.world.width()-1,(int)Math.ceil(Math.max(x,bx)+.5));
                int minY=Math.max(0,(int)Math.floor(Math.min(y,by)-.5)),maxY=Math.min(Vars.world.height()-1,(int)Math.ceil(Math.max(y,by)+.5));
                for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
                    var tile=Vars.world.tile(tx,ty);Building build=tile==null?null:tile.build;
                    if(build==null||build.dead||build==bullet.owner||hitBuildings.contains(build.id)||!visited.add(build.id)
                        ||!(parent.collidesGround&&hitType.collidesGround)&&!build.isInsulated()
                        ||build.team==bullet.team&&!(hitType.heals()&&hitType.collidesTeam&&build.damaged()))continue;
                    double cx=build.x/(double)Vars.tilesize,cy=build.y/(double)Vars.tilesize,half=build.block.size*.5+RAY_RADIUS;
                    double fraction=CombatGeometry.box(x,y,z,bx,by,bz,cx-half,cy-half,world.terrainHeight(cx,cy)-RAY_RADIUS,cx+half,cy+half,world.surfaceHeight(cx,cy)+RAY_RADIUS);
                    if(fraction<=stop[0]){stop[0]=fraction;contactBuild[0]=build;contactUnit[0]=null;}
                }
            }
            double ex=x+(bx-x)*stop[0],ey=y+(by-y)*stop[0],ez=z+(bz-z)*stop[0];
            visual.accept(new CombatVisual(0,CombatVisual.Kind.BEAM,bullet.team.id,x,y,z,ex,ey,ez,.045,color.r,color.g,color.b,1));
            remaining-=Math.max(.02,distance*stop[0]);
            x=ex;y=ey;z=ez;
            float localPower=contactPower(power,emittedFactor,x,y,originX,originY,range);
            if(localPower<=0)break;
            if(projection!=null&&stop[0]==projection.fraction()&&projectedDefense.hit(projection,bullet,localPower))break;
            if(contactUnit[0]!=null){Unit unit=contactUnit[0];hitUnits.add(unit.id);damageUnit(bullet,hitType,unit,localPower,x,y);}
            else if(contactBuild[0]!=null){
                Building build=contactBuild[0];hitBuildings.add(build.id);boolean insulated=build.team!=bullet.team&&build.isInsulated();
                if(parent.collidesGround&&hitType.collidesGround&&build.team==bullet.team)CombatPayloads.heal(build,hitType);
                else if(parent.collidesGround&&hitType.collidesGround){
                    boolean alive=!build.dead;
                    float amount=localPower*hitType.buildingDamageMultiplier;
                    if(!hitType.pierceArmor)amount=Damage.applyArmor(amount,build.block.armor*hitType.armorMultiplier*hitType.blockArmorMultiplier);
                    build.damage(bullet,bullet.team,amount);
                    if(alive&&build.dead)Events.fire(new BuildingBulletDestroyEvent(build,bullet));
                }
                if(insulated)break;
            }else if(terrain.hit())break;
        }
    }
    /** Launch power already includes spent age and launch-range decay; add only further range loss. */
    private float contactPower(float power,double emittedFactor,double x,double y,double originX,double originY,double range){
        return (float)(power*CombatBalance.additionalRangeFactor(emittedFactor,rangeFalloff.factor(Math.hypot(x-originX,y-originY),range)));
    }
    private static void damageUnit(Bullet bullet,BulletType type,Unit unit,float power,double x,double y){
        unit.collision(bullet,(float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
        boolean alive=!unit.dead;
        if(type.pierceArmor)unit.damagePierce(power);else unit.damageArmorMult(power,type.armorMultiplier);
        if(type.status!=StatusEffects.none&&Mathf.chance(type.statusChance))unit.apply(type.status,type.statusDuration);
        Events.fire(new UnitDamageEvent().set(unit,bullet));
        if(alive&&unit.dead)Events.fire(new UnitBulletDestroyEvent(unit,bullet));
    }
    private static double sq(double value){return value*value;}
}
