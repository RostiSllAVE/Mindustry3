package fpv.physics;

import java.util.HashMap;
import java.util.Map;

/** Pure per-collision/per-victim outgoing budget. A limit never increases a weak physical hit. */
public final class RamDamageLimit {
    private final double cap;
    private final Map<Object,Double> spent=new HashMap<>();
    public RamDamageLimit(double cap){if(!Double.isFinite(cap)||cap<0)throw new IllegalArgumentException("Finite nonnegative cap required");this.cap=cap;}
    public double cap(){return cap;}
    public static double capped(double physical,double cap){
        if(!Double.isFinite(physical)||physical<=0||!Double.isFinite(cap)||cap<=0)return 0;
        return Math.min(physical,cap);
    }
    /** Native health multipliers below one amplify damage; reserve their upper-bound HP cost. */
    public double reserve(Object victim,double physical,double healthScale){
        if(victim==null||!Double.isFinite(healthScale)||healthScale<=0||!Double.isFinite(physical)||physical<=0)return 0;
        double scale=Math.min(1,healthScale),remaining=Math.max(0,cap-spent.getOrDefault(victim,0.0));
        double raw=Math.min(physical,remaining*scale);
        if(raw>0)spent.put(victim,Math.min(cap,spent.getOrDefault(victim,0.0)+raw/scale));
        return raw;
    }
    public double remaining(Object victim){return Math.max(0,cap-spent.getOrDefault(victim,0.0));}
}
