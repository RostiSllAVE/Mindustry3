package fpv.render;

/** Rear trunnion articulation and native-amplitude recoil, independent of gameplay muzzles. */
public record NavalWeaponPose(double x,double y,double up,double lift){
    public static NavalWeaponPose of(double yaw,double pitch,double halfLength,double halfDepth,double gap,double rear,double recoil){
        if(!Double.isFinite(yaw+pitch+halfLength+halfDepth+gap+rear+recoil)||halfLength<=0||halfDepth<=0||gap<0||rear<0||rear>halfLength||recoil<0)throw new IllegalArgumentException("Finite naval trunnion required");
        double a=Math.toRadians(yaw),p=Math.toRadians(pitch),c=Math.cos(p),s=Math.sin(p);
        double lift=Math.max(0,halfLength*Math.abs(s)-rear*s+halfDepth*Math.abs(c)-halfDepth-gap);
        double forward=rear*(c-1)-c*recoil;
        return new NavalWeaponPose(Math.cos(a)*forward,Math.sin(a)*forward,lift+s*(rear-recoil),lift);
    }
}
