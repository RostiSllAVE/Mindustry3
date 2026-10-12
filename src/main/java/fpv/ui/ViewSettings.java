package fpv.ui;

/** Numeric camera boundary independent of native UI/backend. UI angle0=down,90=forward,180=up. */
public final class ViewSettings {
    private ViewSettings() {}
    public static double tiltFromAngle(double angle){return clamp(angle,0,180)-90;}
    public static double perspectiveFov(double degrees){return clamp(degrees,1,179);}
    public static double clamp(double value,double low,double high){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Finite view preference required");
        return Math.max(low,Math.min(high,value));
    }
}
