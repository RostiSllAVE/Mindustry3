package fpv.render;

/** Camera-only local presentation settings. Never consulted by authoritative protection or HP. */
public final class ProjectionViewSettings {
    public static final String DISTANCE="fpv-wall-view-distance",FADE_START="fpv-wall-fade-start";
    private ProjectionViewSettings(){}
    public static int maximum(){return Math.max(20,Math.min(2000,arc.Core.settings.getInt(DISTANCE,100)));}
    public static int fadeStart(){return Math.max(0,Math.min(maximum()-1,arc.Core.settings.getInt(FADE_START,80)));}
    /** Squared Euclidean distance from the camera to an actual gameplay XYZ AABB. */
    public static double distanceSquared(double cx,double cy,double cz,double x,double y,double z,double hx,double hy,double hz){
        double dx=Math.max(0,Math.abs(cx-x)-hx),dy=Math.max(0,Math.abs(cy-y)-hy),dz=Math.max(0,Math.abs(cz-z)-hz);
        return dx*dx+dy*dy+dz*dz;
    }
    public static double opacity(double distance,double start,double maximum){
        if(distance<=start)return 1;if(distance>=maximum)return 0;
        double t=(distance-start)/(maximum-start);return 1-t*t*(3-2*t);
    }
}
