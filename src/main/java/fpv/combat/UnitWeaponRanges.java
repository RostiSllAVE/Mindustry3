package fpv.combat;

import arc.Core;
import arc.math.Angles;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.AIController;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.*;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;

/** Per-weapon native baselines, reversible content adapters and XYZ acquisition. */
public final class UnitWeaponRanges {
    public record Settings(double ground,double dagger,double naval,double vertical){
        public Settings { for(double n:new double[]{ground,dagger,naval,vertical})if(!Double.isFinite(n)||n<1||n>20)throw new IllegalArgumentException("Range multiplier 1..20 required"); }
    }
    public static final Settings DEFAULT=new Settings(2,3,3,5);
    private record TypeState(UnitType type,float range,float maxRange){}
    private final ArrayList<TypeState> types=new ArrayList<>();
    private final ArrayList<RangedWeapon> weapons=new ArrayList<>();
    private final Combat3DSystem combat;private final World3DAdapter world;
    private Settings settings=DEFAULT,lastApplied;private boolean applied;
    UnitWeaponRanges(Combat3DSystem combat,World3DAdapter world){this.combat=combat;this.world=world;}
    public Settings settings(){return settings;}
    public void settings(Settings next){if(settings.equals(next))return;settings=next;if(applied)apply(true);}
    public static boolean daggerFamily(UnitType type){return type==UnitTypes.dagger||type==UnitTypes.mace||type==UnitTypes.fortress||type==UnitTypes.scepter||type==UnitTypes.reign;}
    public double horizontal(UnitType type){return type.flying?1:type.naval?settings.naval:daggerFamily(type)?settings.dagger:settings.ground;}
    public double vertical(UnitType type){return type.flying?1:settings.vertical;}
    void install(){
        if(!types.isEmpty())return;
        for(UnitType type:Vars.content.units()){
            if(type.flying||type.weapons.isEmpty())continue;
            WeaponHeightStagger.register(type,combat::originalType);
            types.add(new TypeState(type,type.range,type.maxRange));
            for(int i=0;i<type.weapons.size;i++){
                Weapon nativeWeapon=type.weapons.get(i);
                if(nativeWeapon instanceof RangedWeapon previous){nativeWeapon=previous.delegate.copy();nativeWeapon.bullet=previous.nativeBullet;}
                // Repair/point-defense weapons retain their native specialist update and range.
                if(nativeWeapon.noAttack||!nativeWeapon.useAttackRange||nativeWeapon.bullet==null)continue;
                RangedWeapon ranged=new RangedWeapon(type,nativeWeapon);weapons.add(ranged);type.weapons.set(i,ranged);
            }
        }
    }
    void apply(boolean active){
        if(lastApplied==settings&&applied==active)return;
        applied=active;
        for(var state:types){double scale=active?horizontal(state.type):1;state.type.range=(float)(state.range*scale);state.type.maxRange=(float)(state.maxRange*scale);}
        for(var weapon:weapons){weapon.bullet=active?weapon.physical:weapon.nativeBullet;weapon.physical.range=(float)(weapon.nativeBullet.range*horizontal(weapon.type));weapon.delegate.bullet=weapon.bullet;
            if(weapon.dualMount){weapon.physical.shootEffect=mindustry.content.Fx.none;weapon.physical.smokeEffect=mindustry.content.Fx.none;weapon.ejectEffect=active?mindustry.content.Fx.none:weapon.delegate.ejectEffect;}}
        lastApplied=settings;
    }
    static boolean owns(Weapon weapon){return weapon instanceof UnitWeaponRanges.RangedWeapon;}
    /** Immutable native content remains the reach baseline even after adapter range changes. */
    double reach(Unit unit,BulletType original){return Math.max(.05,original.range/Vars.tilesize);}
    WorldCoordinates.Point prediction(Unit source,Unit target,BulletType original,double x,double y,double z){
        var body=world.unitState(target);if(body==null)return null;
        double speed=original.speed*60.0/Vars.tilesize;
        if(speed<.01)return new WorldCoordinates.Point(body.x(),body.y(),body.z());
        return CombatGeometry.intercept(x,y,z,body.x(),body.y(),body.z(),body.vx(),body.vy(),body.vz(),speed,120);
    }
    private boolean eligible(Unit source,Teamc target,BulletType bullet,double nativeRange){
        if(target==null||target.team()==source.team||!(target instanceof Healthc health)||health.dead()||!Combat3DSystem.usableTarget(target))return false;
        var a=world.unitState(source);if(a==null)return false;
        double tx=target.x()/Vars.tilesize,ty=target.y()/Vars.tilesize,tz=world.surfaceHeight(tx,ty),extra=0;
        if(target instanceof Unit unit){
            if(!unit.hittable()||!unit.checkTarget(bullet.collidesAir,bullet.collidesGround)||!bullet.targetMissiles&&unit instanceof TimedKillc)return false;
            var b=world.unitState(unit);if(b==null)return false;tx=b.x();ty=b.y();tz=b.z();extra=b.radius();
        }else if(target instanceof Building building){
            if(!bullet.collidesGround||!bullet.targetBlocks||!source.type.targetUnderBlocks&&building.block.underBullets)return false;
            extra=building.block.size*.5;tz=(world.buildingBase(building)+world.buildingTop(building))*.5;
        }
        return Math.hypot(tx-a.x(),ty-a.y())<=nativeRange*horizontal(source.type)+extra
            &&Math.abs(tz-a.z())<=nativeRange*vertical(source.type)+extra;
    }
    private void aim(Unit unit,WeaponMount mount,RangedWeapon weapon){
        fpv.construction.LayerNavigationAdapter.weaponAim(unit,weapon.nativeBullet,null);
        if(!combat.authoritative()||weapon.dualMount&&combat.controlDirection(unit)!=null||!(unit.controller() instanceof AIController ai)||!weapon.controllable||!weapon.aiControllable||weapon.noAttack)return;
        if(weapon.nativeBullet.killShooter)return; // SuicideAI owns its special attack trigger.
        if(aimObstruction(unit,mount,weapon,ai))return;
        double baseline=reach(unit,weapon.nativeBullet);
        if(!eligible(unit,mount.target,weapon.nativeBullet,baseline)){
            mount.target=null;
            if((mount.retarget-=arc.util.Time.delta)<=0){
                mount.retarget=Math.max(5,weapon.targetInterval);
                mount.target=Units.closestTarget(unit.team,unit.x,unit.y,(float)(baseline*horizontal(unit.type)*Vars.tilesize),
                    target->eligible(unit,target,weapon.nativeBullet,baseline),target->eligible(unit,target,weapon.nativeBullet,baseline));
            }
        }
        if(mount.target==null){mount.shoot=false;return;}
        // Respect native command/logic decisions (hold fire etc.); ordinary AI may acquire
        // a replacement after rejecting an out-of-envelope native target.
        boolean shoot=ai.shouldShoot()&&ai.shouldFire();
        var from=world.unitState(unit);if(from==null){mount.shoot=false;return;}
        double tx=mount.target.x()/Vars.tilesize,ty=mount.target.y()/Vars.tilesize;
        if(weapon.predictTarget&&mount.target instanceof Unit target){var p=prediction(unit,target,weapon.nativeBullet,from.x(),from.y(),from.z());if(p!=null){tx=p.x();ty=p.y();}}
        mount.aimX=(float)(tx*Vars.tilesize);mount.aimY=(float)(ty*Vars.tilesize);mount.shoot=shoot;mount.rotate=shoot;
        if(shoot){unit.aimX=mount.aimX;unit.aimY=mount.aimY;unit.isShooting=true;}
    }
    private boolean aimObstruction(Unit unit,WeaponMount mount,RangedWeapon weapon,AIController ai){
        var obstruction=fpv.construction.LayerNavigationAdapter.obstacle(unit);var bullet=weapon.nativeBullet;
        if(obstruction==null||world.construction()==null||obstruction.team()==unit.team.id||!bullet.collidesGround||!bullet.targetBlocks||!ai.shouldShoot()||!ai.shouldFire()||!unit.canShoot())return false;
        if(bullet.damage<=0&&bullet.splashDamage<=0&&bullet.lightning<=0)return false;
        var from=world.unitState(unit);if(from==null)return false;
        double range=reach(unit,bullet),horizontalDistance=Math.hypot(obstruction.x()-from.x(),obstruction.y()-from.y());
        if(horizontalDistance>range*horizontal(unit.type)||Math.abs(obstruction.z()-from.z())>range*vertical(unit.type))return false;
        var first=world.construction().sweep(from.x(),from.y(),from.z(),obstruction.x(),obstruction.y(),obstruction.z(),.01,.01);
        if(first==null||first.component().id()!=obstruction.componentId())return false;
        var physical=world.traceSegment(from.x(),from.y(),from.z(),obstruction.x(),obstruction.y(),obstruction.z(),.01);
        if(physical.hit()&&physical.fraction()<first.fraction()-1e-5)return false;
        mount.target=null;mount.aimX=(float)(obstruction.x()*Vars.tilesize);mount.aimY=(float)(obstruction.y()*Vars.tilesize);mount.shoot=mount.rotate=true;
        unit.aimX=mount.aimX;unit.aimY=mount.aimY;unit.isShooting=true;
        fpv.construction.LayerNavigationAdapter.weaponAim(unit,bullet,new WorldCoordinates.Point(obstruction.x(),obstruction.y(),obstruction.z()));return true;
    }
    final class RangedWeapon extends Weapon {
        final UnitType type;final Weapon delegate;final BulletType nativeBullet,physical;
        final boolean nativeAutoUpdate,dualMount;final float nativeCone;final AircraftWeapons.Emission emission=new AircraftWeapons.Emission();AircraftWeaponGeometry.Profile profile;boolean blocked;float blockedReload;
        RangedWeapon(UnitType type,Weapon original){
            this.type=type;delegate=original.copy();nativeBullet=combat.originalType(original.bullet);
            try{for(var field:Weapon.class.getFields())if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers()))field.set(this,field.get(original));
                nativeAutoUpdate=original.getClass().getMethod("update",Unit.class,WeaponMount.class).getDeclaringClass()==Weapon.class;}
            catch(ReflectiveOperationException failure){throw new IllegalStateException("Native weapon metadata unavailable",failure);}
            dualMount=AircraftWeapons.walkerEligible(type,original,nativeBullet);nativeCone=original.shootCone;
            physical=combat.unitProxy(nativeBullet);bullet=nativeBullet;
        }
        @Override public void update(Unit unit,WeaponMount mount){
            float nativeElevation=unit.elevation;boolean correctedGroundElevation=false;
            if(applied&&combat.authoritative()&&unit.isFlying()&&(fpv.units.ground.GroundUnitProfiles.extensibleMech(unit)||world.nativeBoostGrounded(unit))
                &&!fpv.units.ground.GroundFamilyLocomotion.explicitMechBoost(unit)){
                var body=world.unitState(unit);
                // UnitComp can auto-boost a supported walker before WeaponsComp runs. Canonical
                // grounded support, not that transient 2D flag, decides the native canShoot check.
                if(body!=null&&!body.flying()&&body.z()-body.halfHeight()+1e-6>=world.surfaceHeight(body.x(),body.y())){unit.elevation=0;correctedGroundElevation=true;}
            }
            try{
                if(applied&&combat.authoritative()&&!controllable&&autoTarget&&nativeAutoUpdate){super.update(unit,mount);return;}
                if(applied)aim(unit,mount,this);
                if(applied&&dualMount){
                    combat.aircraftWeapons().advance(unit,mount);blocked=false;float cone=shootCone;shootCone=360;
                    try{super.update(unit,mount);if(blocked)mount.reload=blockedReload;}finally{shootCone=cone;}
                }else{delegate.bullet=bullet;delegate.update(unit,mount);}
            }finally{if(correctedGroundElevation)unit.elevation=nativeElevation;}
        }
        @Override protected void shoot(Unit unit,WeaponMount mount,float x,float y,float rotation){
            if(applied&&dualMount&&!combat.aircraftWeapons().canFire(unit,mount)){blocked=true;blockedReload=mount.reload;return;}
            super.shoot(unit,mount,x,y,rotation);
        }
        @Override protected void bullet(Unit unit,WeaponMount mount,float x,float y,float angle,mindustry.entities.Mover mover){
            if(applied&&dualMount&&!combat.aircraftWeapons().canFire(unit,mount))return;
            emission.firing=applied&&dualMount;try{super.bullet(unit,mount,x,y,angle,mover);}finally{emission.firing=false;}
        }
        @Override protected float bulletRotation(Unit unit,WeaponMount mount,float x,float y){
            float angle=super.bulletRotation(unit,mount,x,y);if(dualMount)AircraftWeapons.capture(unit,mount,x,y,angle);return angle;
        }
        @Override protected Teamc findTarget(Unit unit,float x,float y,float range,boolean air,boolean ground){
            if(!applied||!combat.authoritative())return super.findTarget(unit,x,y,range,air,ground);
            double base=reach(unit,nativeBullet);
            return Units.closestTarget(unit.team,x,y,range+Math.abs(shootY),u->eligible(unit,u,nativeBullet,base),b->eligible(unit,b,nativeBullet,base));
        }
        @Override protected boolean checkTarget(Unit unit,Teamc target,float x,float y,float range){
            return applied&&combat.authoritative()?!eligible(unit,target,nativeBullet,reach(unit,nativeBullet)):super.checkTarget(unit,target,x,y,range);
        }
        @Override public void draw(Unit unit,WeaponMount mount){delegate.draw(unit,mount);}
        @Override public void drawOutline(Unit unit,WeaponMount mount){delegate.drawOutline(unit,mount);}
    }
}
