package fpv.combat;

import arc.Events;
import arc.math.Mathf;
import arc.struct.IntSet;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.Fires;
import mindustry.entities.Puddles;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.LiquidBulletType;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.gen.Bullet;
import mindustry.gen.Building;
import mindustry.world.blocks.ConstructBlock;
import fpv.world.World3DAdapter;

/** Native health/status APIs behind a true XYZ area gate; never calls native XY Damage.damage. */
final class CombatPayloads {
    private final World3DAdapter world;
    private final CombatTargetIndex targets;
    private final java.util.function.Consumer<Building> healed;
    CombatPayloads(World3DAdapter world,CombatTargetIndex targets){this(world,targets,b->{});}
    CombatPayloads(World3DAdapter world,CombatTargetIndex targets,java.util.function.Consumer<Building> healed){this.world=world;this.targets=targets;this.healed=healed;}
    void healing(Building build,BulletType type){float before=build.health;heal(build,type);if(build.health>before)healed.accept(build);}
    void area(Bullet bullet,BulletType type,double x,double y,double z,double effectiveness){
        double radius=Math.min(256,Math.max(0,type.splashDamageRadius/Vars.tilesize));
        if(radius<=0||bullet.absorbed)return;
        if(effectiveness<=0)return;
        float power=(float)Math.max(0,type.splashDamage*bullet.damageMultiplier()*effectiveness);
        targets.nearby(bullet.team,x-radius,y-radius,x+radius,y+radius,0,0,unit->{
            if(unit.dead||!unit.isAdded()||!unit.hittable()||!unit.checkTarget(type.collidesAir,type.collidesGround))return;
            var body=world.unitState(unit);if(body==null)return;
            double distance=type.scaledSplashDamage?ProjectileDynamics.cylinderDistance(x,y,z,body.x(),body.y(),body.z(),body.radius(),body.halfHeight())
                :Math.sqrt(Math.pow(x-body.x(),2)+Math.pow(y-body.y(),2)+Math.pow(z-body.z(),2));
            if(distance>radius)return;
            // Native blast attenuation is retained; eligibility uses the actual finite body.
            float amount=(float)(power*Math.max(.4,1-.6*distance/radius));
            if(amount>0){
                unit.collision(bullet,(float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
                boolean alive=!unit.dead;
                unit.damageArmorMult(amount,type.armorMultiplier);
                Events.fire(new UnitDamageEvent().set(unit,bullet));
                if(alive&&unit.dead)Events.fire(new UnitBulletDestroyEvent(unit,bullet));
            }
            if(type.status!=StatusEffects.none&&Mathf.chance(type.statusChance))unit.apply(type.status,type.statusDuration);
        });
        if(!type.collidesGround)return;
        if(world.construction()!=null){world.construction().area(bullet.team,x,y,z,radius,power*bullet.buildingDamageMultiplier,.4);
            if(type.heals())world.construction().healArea(bullet.team,x,y,z,radius,type,healed);
        }
        IntSet visited=new IntSet();
        int minX=Math.max(0,(int)Math.floor(x-radius-.5)),maxX=Math.min(Vars.world.width()-1,(int)Math.ceil(x+radius+.5));
        int minY=Math.max(0,(int)Math.floor(y-radius-.5)),maxY=Math.min(Vars.world.height()-1,(int)Math.ceil(y+radius+.5));
        for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
            var tile=Vars.world.tile(tx,ty);Building build=tile==null?null:tile.build;
            if(build==null||build.dead||!visited.add(build.id))continue;
            double cx=build.x/(double)Vars.tilesize,cy=build.y/(double)Vars.tilesize,half=build.block.size*.5;
            double distance=ProjectileDynamics.boxDistance(x,y,z,cx-half,cy-half,world.terrainHeight(cx,cy),cx+half,cy+half,world.surfaceHeight(cx,cy));
            if(distance>radius)continue;
            if(build.team==bullet.team){if(type.heals())healing(build,type);}
            else{
                if(power>0)build.damage(bullet,bullet.team,(float)(power*bullet.buildingDamageMultiplier*Math.max(.4,1-.6*distance/radius)));
                if(type.makeFire)Fires.create(build.tile);
            }
        }
    }
    void surface(BulletType type,double x,double y,double z,double bodyRadius,double effectiveness){
        if(effectiveness<=0)return;
        var tile=Vars.world.tileWorld((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
        if(tile==null||Math.abs(z-world.surfaceHeight(x,y))>bodyRadius+.15)return;
        if(type instanceof LiquidBulletType liquid&&liquid.liquid!=null&&!liquid.liquid.willBoil()){
            Puddles.deposit(tile,liquid.liquid,(float)(liquid.puddleSize*effectiveness));
            if(liquid.liquid.canExtinguish())Fires.extinguish(tile,(float)(400*effectiveness));
        }
        if(type.puddles>0&&type.puddleLiquid!=null)Puddles.deposit(tile,type.puddleLiquid,(float)(type.puddleAmount*Math.min(32,type.puddles)*effectiveness));
    }
    static void heal(Building build,BulletType type){
        if(build.block instanceof ConstructBlock)return;
        build.heal(type.healPercent*.01f*build.maxHealth+type.healAmount);
        if(!Vars.headless)type.healSound.at(build,1,type.healSoundVolume);
    }
}
