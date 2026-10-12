package fpv.combat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import mindustry.entities.bullet.BulletType;

/** Derives a physical missile from vanilla unit-launch metadata without native XY death explosions. */
final class TurretProjectileAdapter {
    private TurretProjectileAdapter() {}
    static void copyMetadata(BulletType from,BulletType to){
        try{for(Field field:BulletType.class.getFields())if(field.getDeclaringClass()==BulletType.class&&!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers()))field.set(to,field.get(from));}
        catch(ReflectiveOperationException exception){throw new IllegalStateException("Native bullet metadata unavailable",exception);}
    }
    static BulletType missile(BulletType original){
        if(original.spawnUnit==null)return original;
        var unit=original.spawnUnit;BulletType payload=null;
        for(var weapon:unit.weapons)if(weapon.shootOnDeath){payload=weapon.bullet;break;}
        BulletType physical=new BulletType();copyMetadata(payload==null?original:payload,physical);
        physical.speed=Math.max(.05f,unit.speed);physical.lifetime=Math.max(1,unit.lifetime);
        physical.range=physical.speed*physical.lifetime;physical.hitSize=unit.hitSize;
        physical.collidesAir=unit.targetAir;physical.collidesGround=unit.targetGround;physical.collides=true;physical.collidesTiles=true;
        physical.homingPower=Math.max(0,unit.rotateSpeed/50f);physical.homingRange=physical.range;
        physical.spawnUnit=null;physical.killShooter=false;physical.instantDisappear=false;physical.keepVelocity=false;
        physical.ammoMultiplier=original.ammoMultiplier;physical.reloadMultiplier=original.reloadMultiplier;physical.rangeChange=original.rangeChange;
        return physical;
    }
}
