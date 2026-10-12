package fpv.flight;

/** Free-look orientation in renderer axes, independent of aircraft translation and attitude. */
public final class FlightView {
    private double yaw, pitch;
    public double headingRadians(){return yaw;}
    public double pitchRadians(){return pitch;}
    public void align(double fx,double fy,double fz){
        yaw=Math.atan2(fx,-fz);pitch=Math.atan2(fy,Math.hypot(fx,fz));
    }
    public void look(double horizontalDegrees,double verticalDegrees){
        if(!Double.isFinite(horizontalDegrees)||!Double.isFinite(verticalDegrees))return;
        yaw=Math.IEEEremainder(yaw+Math.toRadians(horizontalDegrees),Math.PI*2);
        pitch=Math.max(Math.toRadians(-89),Math.min(Math.toRadians(89),pitch+Math.toRadians(verticalDegrees)));
    }
    public void forward(double[] out){
        out[0]=Math.sin(yaw)*Math.cos(pitch);out[1]=Math.sin(pitch);out[2]=-Math.cos(yaw)*Math.cos(pitch);
    }
    public void up(double[] out){
        out[0]=-Math.sin(yaw)*Math.sin(pitch);out[1]=Math.cos(pitch);out[2]=Math.cos(yaw)*Math.sin(pitch);
    }
}
