package fpv.control;

import fpv.flight.FlightController;

/** Preserve resolved mass/inertia/rates; calibrate boost drag to native speed and boost multiplier. */
public final class NativeBoostProfile {
    private NativeBoostProfile(){}
    public static double speed(double nativeSpeed,double multiplier,double metersPerTile,double userScale){
        if(!Double.isFinite(nativeSpeed+multiplier+metersPerTile+userScale)||nativeSpeed<0||multiplier<=0||metersPerTile<=0||userScale<=0)
            throw new IllegalArgumentException("Finite native boost performance required");
        return Math.max(.1,Math.min(400,nativeSpeed*multiplier*60/8*metersPerTile*userScale));
    }
    public static FlightController.Profile resolve(FlightController.Profile p,double speed){
        if(p==null||!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Positive boost speed required");
        double thrust=p.maxThrustNewtons/p.massKg;
        double horizontal=Math.sqrt(Math.max(.01,thrust*thrust-p.gravityMps2*p.gravityMps2));
        double drag=Math.min(p.linearDragPerSecond,horizontal/speed*.5);
        return new FlightController.Profile(p.metersPerTile,p.massKg,p.inertiaXKgM2,p.inertiaYKgM2,p.inertiaZKgM2,
            p.maxThrustNewtons,p.gravityMps2,drag,(horizontal-drag*speed)/(speed*speed),p.angularDragPerSecond,
            p.motorTimeConstantSeconds,p.rateResponseSeconds,p.maxTorqueNewtonMeters,p.pitchRateDegreesPerSecond,
            p.rollRateDegreesPerSecond,p.yawRateDegreesPerSecond,p.rateExpo,p.fixedStepSeconds,p.maxFrameSeconds);
    }
}
