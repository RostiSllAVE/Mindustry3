package fpv.flight;

import fpv.generated.NavigationConfig;

/** Stable precision navigation while the native builder owns the mouse. Uses render axes. */
public final class BuildNavigator {
    private final double metersPerTile;
    public BuildNavigator(double metersPerTile){if(!(metersPerTile>0))throw new IllegalArgumentException("Positive map scale required");this.metersPerTile=metersPerTile;}
    public void step(FlightController.State s,double seconds,double forward,double right,double climb,double turn,double speedMps){
        if(s==null||!Double.isFinite(seconds)||seconds<0||seconds>.1||!Double.isFinite(speedMps)||speedMps<0||speedMps>NavigationConfig.MAX_SPEED_MPS)
            throw new IllegalArgumentException("Bounded navigation inputs required");
        double angle=2*Math.atan2(s.qy,s.qw)-clamp(turn)*Math.toRadians(NavigationConfig.TURN_DEGREES_PER_SECOND)*seconds;
        s.qx=s.qz=0;s.qy=Math.sin(angle*.5);s.qw=Math.cos(angle*.5);s.wx=s.wy=s.wz=0;
        forward=clamp(forward);right=clamp(right);climb=clamp(climb);
        double length=Math.max(1,Math.sqrt(forward*forward+right*right+climb*climb)),speed=speedMps/metersPerTile/length;
        double fx=-Math.sin(angle),fz=-Math.cos(angle),rx=Math.cos(angle),rz=-Math.sin(angle);
        s.vx=(fx*forward+rx*right)*speed;s.vy=climb*speed;s.vz=(fz*forward+rz*right)*speed;
        s.x+=s.vx*seconds;s.y+=s.vy*seconds;s.z+=s.vz*seconds;
    }
    private static double clamp(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Finite input required");return Math.max(-1,Math.min(1,value));}
}
