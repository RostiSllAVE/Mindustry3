package fpv.combat;

import fpv.world.WorldCoordinates;

/** Shared, headless-safe trunnion math. Angles and positions are native gameplay XYZ. */
public final class MechWeaponPitch {
    public static final double MIN=-26,MAX=85;
    private MechWeaponPitch(){}
    /** Native 160.7 atlas dimensions (metadata only): headless servers have no sprite atlas.
     * Unknown/modded gun art uses the content size/shot offset fallback. No texture is bundled. */
    public static double halfLength(String name,double unitSize,double shootOffset){
        double pixels=switch(name==null?"":name){
            case "large-weapon"->28;case "flamethrower","artillery"->50;
            case "scepter-weapon"->88;case "reign-weapon"->122;case "heal-weapon"->34;
            default->0;
        };
        return Math.max(.25,Math.max(Math.abs(shootOffset),pixels>0?pixels/64.0:unitSize*.5));
    }
    public static double clamp(double pitch){return Double.isFinite(pitch)?Math.max(MIN,Math.min(MAX,pitch)):0;}
    public static double elevation(WorldCoordinates.Point direction){return clamp(Math.toDegrees(Math.atan2(direction.z(),Math.hypot(direction.x(),direction.y()))));}
    public static WorldCoordinates.Point direction(double yaw,double pitch){
        double a=Math.toRadians(yaw),p=Math.toRadians(clamp(pitch)),c=Math.cos(p);
        return new WorldCoordinates.Point(Math.cos(a)*c,Math.sin(a)*c,Math.sin(p));
    }
    /** Raise the articulated saddle only enough to keep the rear/underside above the hull. */
    public static double lift(double pitch,double halfLength,double halfDepth,double neutralGap){
        return lift(pitch,halfLength,halfDepth,neutralGap,0);
    }
    public static double lift(double pitch,double halfLength,double halfDepth,double neutralGap,double rearPivot){
        double p=Math.toRadians(clamp(pitch));
        return Math.max(0,halfLength*Math.abs(Math.sin(p))-rearPivot*Math.sin(p)+halfDepth*Math.abs(Math.cos(p))-halfDepth-neutralGap);
    }
    /** Preserve native lateral spread; only the longitudinal offset is pitched. */
    public static WorldCoordinates.Point muzzle(WorldCoordinates.Point pivot,double yaw,double pitch,double side,double forward){
        var d=direction(yaw,pitch);double a=Math.toRadians(yaw);
        return new WorldCoordinates.Point(pivot.x()+Math.sin(a)*side+d.x()*forward,
            pivot.y()-Math.cos(a)*side+d.y()*forward,pivot.z()+d.z()*forward);
    }
}
