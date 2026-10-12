package fpv.combat;

import arc.struct.ObjectMap;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import mindustry.Vars;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.pattern.*;
import mindustry.world.blocks.defense.turrets.*;

/** Reversible reference substitutions; never changes a field of an original BulletType. */
final class CombatBindings {
    private record Slot(Supplier<BulletType> get,Consumer<BulletType> set,BulletType original,BulletType proxy) {}
    private final ArrayList<Slot> slots=new ArrayList<>();
    private final BiFunction<BulletType,Boolean,BulletType> adapt;
    CombatBindings(BiFunction<BulletType,Boolean,BulletType> adapt){this.adapt=adapt;}
    void discover(){
        restore();slots.clear();
        for(var unit:Vars.content.units())for(var weapon:unit.weapons)
            if(!UnitWeaponRanges.owns(weapon)&&!AircraftWeapons.owns(weapon))add(()->weapon.bullet,value->weapon.bullet=value,true);
        for(var block:Vars.content.blocks()){
            if(block instanceof PowerTurret turret)add(()->turret.shootType,value->turret.shootType=value,true);
            if(block instanceof ContinuousTurret turret)add(()->turret.shootType,value->turret.shootType=value,true);
            if(block instanceof ItemTurret turret)map(turret.ammoTypes);
            if(block instanceof LiquidTurret turret)map(turret.ammoTypes);
            if(block instanceof ContinuousLiquidTurret turret)map(turret.ammoTypes);
            if(block instanceof PayloadAmmoTurret turret)map(turret.ammoTypes);
        }
    }
    /** Known native patterns without Mover callbacks; whole custom/helix/multi slots stay native. */
    static boolean supportedPattern(ShootPattern pattern){
        if(pattern==null)return true;
        try{
            for(var signature:new Class<?>[][]{{int.class,ShootPattern.BulletHandler.class},{int.class,ShootPattern.BulletHandler.class,Runnable.class}}){
                Class<?> owner=pattern.getClass().getMethod("shoot",signature).getDeclaringClass();
                if(owner!=ShootPattern.class&&owner!=ShootSpread.class&&owner!=ShootAlternate.class&&owner!=ShootBarrel.class&&owner!=ShootSummon.class)return false;
            }
            return true;
        }catch(ReflectiveOperationException exception){return false;}
    }
    private <K> void map(ObjectMap<K,BulletType> map){
        for(var entry:map.entries()){
            K key=entry.key;
            add(()->map.get(key),value->map.put(key,value),true);
        }
    }
    private void add(Supplier<BulletType> get,Consumer<BulletType> set,boolean turret){
        BulletType original=get.get(),proxy=adapt.apply(original,turret);
        if(original!=null&&proxy!=original)slots.add(new Slot(get,set,original,proxy));
    }
    void apply(){for(var slot:slots)if(slot.get.get()==slot.original)slot.set.accept(slot.proxy);}
    void restore(){for(var slot:slots)if(slot.get.get()==slot.proxy)slot.set.accept(slot.original);}
    int size(){return slots.size();}
}
