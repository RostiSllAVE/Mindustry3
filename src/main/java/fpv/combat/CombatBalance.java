package fpv.combat;

/** Pure balance functions; all ages are simulated seconds after powered flight ends. */
public final class CombatBalance {
    public static final double DEFAULT_AA_VERTICAL_MULTIPLIER=5;
    public static final RangeFalloff DEFAULT_RANGE_FALLOFF=new RangeFalloff(1.2,1.5,.1);
    public record RangeFalloff(double kneeRatio,double endRatio,double kneePower){
        public RangeFalloff{
            if(!Double.isFinite(kneeRatio)||!Double.isFinite(endRatio)||!Double.isFinite(kneePower)
                ||kneeRatio<=1||endRatio<=kneeRatio||kneePower<0||kneePower>1)
                throw new IllegalArgumentException("Range falloff requires finite 1 < knee < end and 0 <= knee power <= 1");
        }
        public double factor(double distance,double range){
            if(!Double.isFinite(distance)||distance<0||!Double.isFinite(range)||range<=0)throw new IllegalArgumentException("Finite nonnegative distance and positive range required");
            double ratio=distance/range;
            if(ratio<=1)return 1;
            if(ratio>=endRatio)return 0;
            return ratio<=kneeRatio?1-(ratio-1)*(1-kneePower)/(kneeRatio-1):kneePower*(endRatio-ratio)/(endRatio-kneeRatio);
        }
    }
    /** Horizontal range and spent time are independent, multiplying limits. */
    public static double rangeDamage(double distance,double range){
        return DEFAULT_RANGE_FALLOFF.factor(distance,range);
    }
    public static boolean inEnvelope(double horizontal,double vertical,double range,double multiplier){
        return horizontal>=0&&range>0&&multiplier>=1&&horizontal<=range&&Math.abs(vertical)<=range*multiplier;
    }
    /** A child ray receives already attenuated launch damage and cannot recover that lost power. */
    public static double additionalRangeFactor(double launchFactor,double contactFactor){
        return launchFactor>0?Math.max(0,Math.min(1,contactFactor/launchFactor)):0;
    }
    public static final double SPENT_GRAVITY_MULTIPLIER=4,SPENT_ZERO_SECONDS=10;
    private CombatBalance() {}
    public static double spentDamage(double age){
        if(!Double.isFinite(age)||age<0)throw new IllegalArgumentException("Nonnegative finite spent age required");
        if(age>=SPENT_ZERO_SECONDS)return 0;
        return age<=3?Math.pow(.1,age/3):.1*(SPENT_ZERO_SECONDS-age)/(SPENT_ZERO_SECONDS-3);
    }
}
