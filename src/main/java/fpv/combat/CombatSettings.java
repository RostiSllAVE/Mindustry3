package fpv.combat;

import arc.Core;
import mindustry.Vars;

/** Shared authoritative preferences for graphical hosts and dedicated servers. */
public final class CombatSettings {
    private CombatSettings(){}
    public static void apply(Combat3DSystem combat){
        if(Vars.net.client())return;
        combat.setAntiAirVerticalMultiplier(bound("fpv-aa-vertical-multiplier",5,1,20));
        combat.unitRanges().settings(new UnitWeaponRanges.Settings(bound("fpv-ground-range",2,1,20),bound("fpv-dagger-range",3,1,20),bound("fpv-naval-range",3,1,20),bound("fpv-unit-vertical-range",5,1,20)));
        int end=bound("fpv-range-end-percent",150,150,300);
        int knee=Math.min(end-1,bound("fpv-range-knee-percent",120,105,150));
        combat.setRangeFalloff(knee/100.0,end/100.0,bound("fpv-range-knee-power-percent",10,0,100)/100.0);
    }
    private static int bound(String key,int fallback,int low,int high){return Math.max(low,Math.min(high,Core.settings.getInt(key,fallback)));}
}
