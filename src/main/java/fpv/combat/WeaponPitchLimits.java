package fpv.combat;

/** Shared vertical-only tuning. Avoid a near-vertical singularity; yaw/target rules are separate. */
public final class WeaponPitchLimits {
    public static final double EXPANSION=1.3,SAFE_MAX=85;
    private WeaponPitchLimits(){}
    public static double expanded(double degrees){return Math.copySign(Math.min(SAFE_MAX,Math.abs(degrees)*EXPANSION),degrees);}
    public static double clamp(double pitch,double minimum,double maximum){return Double.isFinite(pitch)?Math.max(minimum,Math.min(maximum,pitch)):0;}
}
