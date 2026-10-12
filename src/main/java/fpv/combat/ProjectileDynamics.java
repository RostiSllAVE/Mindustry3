package fpv.combat;

import fpv.world.WorldCoordinates;

/** Pure continuous XYZ projectile math; gravity is in tiles/s² (four metres per tile). */
public final class ProjectileDynamics {
    public static final double GRAVITY=9.81/4, MAX_FALL_SECONDS=120;
    private ProjectileDynamics() {}
    public record Step(double dx,double dy,double dz,double vx,double vy,double vz) {}
    /** Exact constant-gravity displacement with bounded nonnegative damping applied afterwards. */
    public static Step integrate(double vx,double vy,double vz,double seconds,boolean gravity,double drag){
        if(!Double.isFinite(seconds)||seconds<0||!Double.isFinite(vx)||!Double.isFinite(vy)||!Double.isFinite(vz))throw new IllegalArgumentException("Finite motion and nonnegative time required");
        double g=gravity?GRAVITY:0,decay=Math.exp(-Math.max(0,drag)*seconds);
        return new Step(vx*seconds,vy*seconds,vz*seconds-.5*g*seconds*seconds,
            vx*decay,vy*decay,(vz-g*seconds)*decay);
    }
    public static WorldCoordinates.Point arc(double azimuth,double horizontalDistance,double deltaZ,double seconds){
        double t=Math.max(1.0/60,seconds),a=Math.toRadians(azimuth),speed=Math.max(0,horizontalDistance)/t;
        return new WorldCoordinates.Point(Math.cos(a)*speed,Math.sin(a)*speed,deltaZ/t+.5*GRAVITY*t);
    }
    /** Rotate toward an arbitrary 3D direction at a bounded angular rate, keeping speed. */
    public static WorldCoordinates.Point steer(double vx,double vy,double vz,double dx,double dy,double dz,double radians){
        double speed=Math.sqrt(vx*vx+vy*vy+vz*vz),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(speed<1e-12||distance<1e-12||radians<=0)return new WorldCoordinates.Point(vx,vy,vz);
        double ax=vx/speed,ay=vy/speed,az=vz/speed,bx=dx/distance,by=dy/distance,bz=dz/distance;
        double dot=Math.max(-1,Math.min(1,ax*bx+ay*by+az*bz)),angle=Math.acos(dot);
        if(angle<=radians)return new WorldCoordinates.Point(bx*speed,by*speed,bz*speed);
        double ox=bx-ax*dot,oy=by-ay*dot,oz=bz-az*dot,len=Math.sqrt(ox*ox+oy*oy+oz*oz);
        if(len<1e-9){ // A deterministic perpendicular handles an exactly opposite target.
            ox=Math.abs(az)<.9?-ay:0;oy=Math.abs(az)<.9?ax:-az;oz=Math.abs(az)<.9?0:ay;
            len=Math.sqrt(ox*ox+oy*oy+oz*oz);
        }
        double c=Math.cos(radians),s=Math.sin(radians)/len;
        return new WorldCoordinates.Point((ax*c+ox*s)*speed,(ay*c+oy*s)*speed,(az*c+oz*s)*speed);
    }
    public static double cylinderDistance(double x,double y,double z,double cx,double cy,double cz,double radius,double halfHeight){
        return Math.hypot(Math.max(0,Math.hypot(x-cx,y-cy)-radius),Math.max(0,Math.abs(z-cz)-halfHeight));
    }
    public static double boxDistance(double x,double y,double z,double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        double dx=Math.max(0,Math.max(minX-x,x-maxX)),dy=Math.max(0,Math.max(minY-y,y-maxY)),dz=Math.max(0,Math.max(minZ-z,z-maxZ));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
}
