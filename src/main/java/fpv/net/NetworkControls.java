package fpv.net;

/** Inputs only. No client coordinates, physical tuning, hit claims or health values are accepted. */
public record NetworkControls(boolean active,int mode,double throttle,double pitch,double roll,double yaw,
                              double forward,double right,double climb,double turn,double headingRadians,
                              double aimX,double aimY,double aimZ,boolean shooting,boolean creatorFlight,boolean boostFlight) {
    public NetworkControls(boolean active,int mode,double throttle,double pitch,double roll,double yaw,
                           double forward,double right,double climb,double turn,double headingRadians,
                           double aimX,double aimY,double aimZ,boolean shooting,boolean creatorFlight){
        this(active,mode,throttle,pitch,roll,yaw,forward,right,climb,turn,headingRadians,aimX,aimY,aimZ,shooting,creatorFlight,false);
    }
    public NetworkControls(boolean active,int mode,double throttle,double pitch,double roll,double yaw,
                           double forward,double right,double climb,double turn,double headingRadians,
                           double aimX,double aimY,double aimZ,boolean shooting){
        this(active,mode,throttle,pitch,roll,yaw,forward,right,climb,turn,headingRadians,aimX,aimY,aimZ,shooting,false);
    }
    public NetworkControls {
        if(creatorFlight&&boostFlight)throw new IllegalArgumentException("Creator and native boost are separate modes");
        if(mode<0||mode>3)throw new IllegalArgumentException("Unknown flight mode");
        if(!Double.isFinite(throttle)||throttle<0||throttle>1)throw new IllegalArgumentException("Throttle outside [0,1]");
        double[] axes={pitch,roll,yaw,forward,right,climb,turn,aimX,aimY,aimZ};
        for(double value:axes)if(!Double.isFinite(value)||Math.abs(value)>1)throw new IllegalArgumentException("Nonfinite or unbounded control axis");
        if(!Double.isFinite(headingRadians)||Math.abs(headingRadians)>Math.PI*1_000_000)throw new IllegalArgumentException("Unbounded camera heading");
        double length=Math.sqrt(aimX*aimX+aimY*aimY+aimZ*aimZ);
        if(length<.99||length>1.01)throw new IllegalArgumentException("Normalized weapon direction required");
        aimX/=length;aimY/=length;aimZ/=length;
        headingRadians=Math.IEEEremainder(headingRadians,Math.PI*2);
    }
    public static NetworkControls hold(){return new NetworkControls(false,1,0,0,0,0,0,0,0,0,0,0,1,0,false);}
}
