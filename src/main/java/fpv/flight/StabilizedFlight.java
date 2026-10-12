package fpv.flight;

import fpv.generated.NavigationConfig;

/** Position hold with a horizontal pilot-heading basis; looking up/down never creates lift. */
public final class StabilizedFlight {
    public void step(FlightController.State s, FlightController.Profile profile, double seconds,
                     double north, double east, double climb, double speedMps,
                     FlightController.StepObserver collision) {
        step(s,profile,seconds,north,east,climb,speedMps,0,collision);
    }
    public void step(FlightController.State s, FlightController.Profile profile, double seconds,
                     double forward, double right, double climb, double speedMps, double headingRadians,
                     FlightController.StepObserver collision) {
        if (s == null || profile == null || !Double.isFinite(seconds) || seconds < 0 || seconds > .1
                || !Double.isFinite(speedMps) || speedMps < 0 || speedMps > 400 || !Double.isFinite(headingRadians))
            throw new IllegalArgumentException("Bounded stabilized inputs required");
        forward = input(forward); right = input(right); climb = input(climb);
        double length = Math.max(1, Math.sqrt(forward*forward+right*right+climb*climb));
        double speed = speedMps/profile.metersPerTile/length;
        double sin=Math.sin(headingRadians),cos=Math.cos(headingRadians);
        double tx=(forward*sin+right*cos)*speed, ty=climb*speed, tz=(-forward*cos+right*sin)*speed;
        double acceleration=Math.max(profile.gravityMps2,profile.maxThrustNewtons/profile.massKg)
            /profile.metersPerTile;
        int steps=Math.max(1,(int)Math.ceil(seconds/profile.fixedStepSeconds));
        double dt=seconds/steps;
        for(int i=0;i<steps;i++){
            double dx=tx-s.vx,dy=ty-s.vy,dz=tz-s.vz;
            double delta=Math.sqrt(dx*dx+dy*dy+dz*dz);
            double blend=1-Math.exp(-dt/NavigationConfig.STABILIZED_RESPONSE_SECONDS);
            if(delta>0)blend=Math.min(blend,acceleration*dt/delta);
            s.vx+=dx*blend;s.vy+=dy*blend;s.vz+=dz*blend;
            double ox=s.x,oy=s.y,oz=s.z;
            s.x+=s.vx*dt;s.y+=s.vy*dt;s.z+=s.vz*dt;
            s.wx=s.wy=s.wz=0;
            s.qy=Math.sin(-headingRadians*.5);s.qw=Math.cos(headingRadians*.5);
            s.qx=s.qz=0;
            if(collision!=null)collision.afterStep(s,ox,oy,oz,dt,i*dt);
        }
    }
    private static double input(double x){
        if(!Double.isFinite(x))throw new IllegalArgumentException("Finite axis required");
        return Math.max(-1,Math.min(1,x));
    }
}
