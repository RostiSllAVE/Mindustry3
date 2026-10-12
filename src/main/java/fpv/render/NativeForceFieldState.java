package fpv.render;

import java.lang.reflect.Field;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.gen.Unit;

/** Version-pinned read-only view of each native ability instance; never advances its animation. */
public final class NativeForceFieldState {
    private static final Field SCALE=field("radiusScale"),FLASH=field("alpha");
    private NativeForceFieldState() {}
    private static Field field(String name){
        try{Field result=ForceFieldAbility.class.getDeclaredField(name);return result.trySetAccessible()?result:null;}
        catch(ReflectiveOperationException|SecurityException ignored){return null;}
    }
    public static boolean available(){return SCALE!=null&&FLASH!=null;}
    public static float radius(Unit unit,ForceFieldAbility ability){
        if(unit==null||unit.dead||unit.shield<=0||!available())return 0;
        try{float value=SCALE.getFloat(ability)*ability.radius;return Float.isFinite(value)?Math.max(0,value):0;}
        catch(IllegalAccessException ignored){return 0;}
    }
    public static float flash(ForceFieldAbility ability){
        if(!available())return 0;
        try{float value=FLASH.getFloat(ability);return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
        catch(IllegalAccessException ignored){return 0;}
    }
}
