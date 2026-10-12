package fpv.render;

/** Component yaw happens in the pitched assembly plane, not around a second world-space trunnion. */
public final class TurretPartPose {
    private TurretPartPose(){}
    public static void basis(double[] assembly,double deltaYaw,double[] out){
        double c=Math.cos(Math.toRadians(deltaYaw)),s=Math.sin(Math.toRadians(deltaYaw));
        for(int i=0;i<3;i++){
            out[i]=assembly[i]*c-assembly[i+6]*s;
            out[i+3]=assembly[i+3];
            out[i+6]=assembly[i]*s+assembly[i+6]*c;
        }
    }
}
