package fpv.render;

/** Render-only tripod. The first two feet keep their native bilateral offsets; the third has no physics authority. */
public final class VelaTripodLayout {
    public static final int LEGS=3;
    private VelaTripodLayout(){}
    public static double footX(int leg,double yaw,double spread){
        return leg==2?-Math.cos(yaw)*spread*1.35:Math.sin(yaw)*spread*(leg==0?1:-1);
    }
    public static double footY(int leg,double yaw,double spread){
        return leg==2?-Math.sin(yaw)*spread*1.35:Math.cos(yaw)*spread*(leg==0?-1:1);
    }
    public static double hipX(int leg,double yaw,double radius){
        return leg==2?-Math.cos(yaw)*radius*.50:Math.sin(yaw)*radius*.50*(leg==0?1:-1);
    }
    public static double hipY(int leg,double yaw,double radius){
        return leg==2?-Math.sin(yaw)*radius*.50:Math.cos(yaw)*radius*.50*(leg==0?-1:1);
    }
    /** Render-only envelope: planted-foot lag, armour width and tall rear-knee bend. Not a hitbox. */
    public static double cullingRadius(double clearance,double radius,double spread){
        return spread*1.35+radius*.86+Math.max(0,clearance)*.35;
    }
}
