package fpv.combat;

import mindustry.gen.Unit;
import mindustry.type.*;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.WeaponMount;
import java.util.*;
import java.util.function.Function;

/** Native type metadata only: identical/mirrored guns share a level on SDL and headless peers. */
public final class WeaponHeightStagger {
    private static final Map<UnitType,double[]> levels=new WeakHashMap<>();
    private WeaponHeightStagger(){}
    static void register(UnitType type,Function<BulletType,BulletType> original){
        var groups=new LinkedHashMap<String,Integer>();double[] offsets=new double[type.weapons.size];
        double spacing=Math.max(.04,Math.min(.12,type.hitSize/mindustry.Vars.tilesize*.025));
        for(int i=0;i<offsets.length;i++){
            var w=type.weapons.get(i);if(w.shootOnDeath||w.bullet==null)continue;
            var b=original.apply(w.bullet);String key=w.name+":"+(b==null?"":b.getClass().getName());
            int group=groups.computeIfAbsent(key,k->groups.size());offsets[i]=Math.min(3,group)*spacing;
        }
        levels.put(type,offsets);
    }
    public static double offset(Unit unit,WeaponMount mount){
        if(unit==null||mount==null||unit.mounts==null)return 0;var offsets=levels.get(unit.type);if(offsets==null)return 0;
        for(int i=0;i<Math.min(offsets.length,unit.mounts.length);i++)if(unit.mounts[i]==mount)return offsets[i];return 0;
    }
    static WeaponMount emitting(Unit unit,mindustry.gen.Bullet bullet){
        if(unit.mounts!=null)for(var m:unit.mounts)if(m.weapon.bullet==bullet.type)return m;return null;
    }
}
