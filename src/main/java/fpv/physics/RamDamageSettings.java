package fpv.physics;

import arc.Core;
import fpv.generated.CrashConfig;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.entities.Damage;
import mindustry.entities.units.WeaponMount;

/** Authority reads settings at contact; classification uses native content identity, never names. */
public final class RamDamageSettings {
    private RamDamageSettings(){}
    public static boolean coreType(Unit unit){return unit!=null&&unit.type!=null&&
        (unit.type==UnitTypes.alpha||unit.type==UnitTypes.beta||unit.type==UnitTypes.gamma);}
    public static double cap(Unit unit){
        String key="fpv-ram-cap-units";double fallback=CrashConfig.RAM_ORDINARY_DAMAGE_CAP;
        if(unit!=null&&unit.type==UnitTypes.alpha){key="fpv-ram-cap-alpha";fallback=CrashConfig.RAM_ALPHA_DAMAGE_CAP;}
        else if(unit!=null&&unit.type==UnitTypes.beta){key="fpv-ram-cap-beta";fallback=CrashConfig.RAM_BETA_DAMAGE_CAP;}
        else if(unit!=null&&unit.type==UnitTypes.gamma){key="fpv-ram-cap-gamma";fallback=CrashConfig.RAM_GAMMA_DAMAGE_CAP;}
        return Math.max(0,Math.min(CrashConfig.RAM_DAMAGE_CAP_MAX,Core.settings==null?fallback:Core.settings.getInt(key,(int)fallback)));
    }
    public static double healthScale(Unit unit){return unit.healthMultiplier*Vars.state.rules.unitHealth(unit.team);}
    public static double healthScale(Building building){
        double scale=Vars.state.rules.blockHealth(building.team);
        // Native Mathf.zero(blockHealth) intentionally makes even a zero-damage call lethal.
        return Math.abs(scale)<.000001?0:scale;
    }
    /** Collision deaths use the budgeted XYZ burst; do not also create uncapped native XY cargo/wreck hits. */
    public static void damage(Unit unit,float amount,boolean pierce){
        if(unit==null||unit.dead||!(amount>0))return;
        boolean old=unit.spawnedByCore;
        double scale=healthScale(unit),raw=pierce?amount:Damage.applyArmor(amount,unit.armorOverride>=0?unit.armorOverride:unit.armor);
        boolean lethal=unit.type.killable&&raw>0&&scale>=0&&raw/scale>=Math.max(0,unit.health)+Math.max(0,unit.shield);
        WeaponMount[] oldMounts=unit.mounts;
        if(lethal){unit.spawnedByCore=true;quietDeathMounts(unit);}
        try{if(pierce)unit.damagePierce(amount);else unit.damage(amount);}
        finally{if(!unit.dead){unit.spawnedByCore=old;unit.mounts=oldMounts;}}
    }
    private static void quietDeathMounts(Unit unit){
        WeaponMount[] copy=null;
        for(int i=0;i<unit.mounts.length;i++){
            WeaponMount old=unit.mounts[i];if(!old.weapon.shootOnDeath)continue;
            if(copy==null)copy=unit.mounts.clone();
            var weapon=old.weapon.copy();weapon.shootOnDeath=false;weapon.autoTarget=false;weapon.alwaysShooting=false;
            WeaponMount safe=weapon.mountType.get(weapon);
            safe.rotation=old.rotation;safe.targetRotation=old.targetRotation;safe.recoil=old.recoil;safe.heat=old.heat;
            safe.aimX=old.aimX;safe.aimY=old.aimY;safe.sound=old.sound;safe.bullet=old.bullet;
            safe.totalShots=old.totalShots;safe.barrelCounter=old.barrelCounter;copy[i]=safe;
        }
        if(copy!=null)unit.mounts=copy;
    }
}
