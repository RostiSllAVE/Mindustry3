package fpv.render;

/** Visual armour placement only. Physical hips, gait, feet and IK inputs are untouched. */
public final class LegPlatePose {
    public static final double WIDTH_SCALE=.68,SOCKET_SCALE=.55,DEPTH_SCALE=.65,GAP=.02;
    private static boolean enabled=true;
    private LegPlatePose(){}
    public static void labEnabled(boolean value){if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated visual comparison only");enabled=value;}
    static boolean enabled(){return enabled;}
    public static double width(double nativeWidth){return nativeWidth*WIDTH_SCALE;}
    public static double depth(double nativeDepth){return nativeDepth*DEPTH_SCALE;}
    public static double underside(double center,double halfDepth,double clearance,double radius,double pitch,double roll){
        double p=Math.toRadians(pitch),r=Math.toRadians(roll);
        return center+clearance-halfDepth*Math.abs(Math.cos(p)*Math.cos(r))-radius*(Math.abs(Math.sin(p))+Math.abs(Math.cos(p)*Math.sin(r)));
    }
    public static double centerBelow(double underside,double depth){return underside-GAP-depth*.5;}
}
