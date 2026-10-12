package fpv.render;

/** Pure presentation math. No native weapon, velocity, aim or combat state is changed. */
public final class FirstPersonRigPose {
    private FirstPersonRigPose(){}
    public static double recoil(double nativeRecoil,double power,double nativeDistance){
        if(!Double.isFinite(nativeRecoil)||!Double.isFinite(power)||!Double.isFinite(nativeDistance))return 0;
        return Math.pow(clamp(nativeRecoil,0,1),clamp(power,.1,8))*clamp(nativeDistance,.025,.24);
    }
    public static double falloff(double distance,double reach){
        if(!Double.isFinite(distance)||!Double.isFinite(reach)||reach<=0)return 0;
        double t=clamp(distance/reach,0,1);return 1-t*t*(3-2*t);
    }
    public static double width(double nativeAspect){return clamp(nativeAspect*.86,.24,.95);}
    public static double thickness(double width){return clamp(width*.32,.11,.23);}
    public static double lateral(double mountX,double largestMountX){
        return largestMountX<=.01?0:clamp(mountX/largestMountX,-1,1)*.64;
    }
    public static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
    /** Damped return inertia is kept separate from the immediate native recoil kick. */
    public static final class Spring {
        public double value,velocity;
        public void reset(){value=velocity=0;}
        public double update(double target,double seconds){
            if(!Double.isFinite(target)||!Double.isFinite(seconds))return value;
            seconds=clamp(seconds,0,.1);int steps=Math.max(1,(int)Math.ceil(seconds*240));double dt=seconds/steps;
            for(int i=0;i<steps;i++){velocity+=(clamp(target,-1,1)-value)*100*dt;velocity*=Math.exp(-18*dt);value+=velocity*dt;}
            return value;
        }
    }
}
