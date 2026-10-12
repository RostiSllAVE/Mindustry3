package fpv.world;

/**
 * Canonical XYZ projectile foundation. Existing Mindustry bullets are not automatically migrated.
 * Numerical limits guard malformed custom adapters; they are not weapon balance settings.
 */
public record Projectile3DState(long id,int teamId,double x,double y,double z,
                                double vx,double vy,double vz,double radius,double damage,double remainingSeconds) {
    public Projectile3DState {
        WorldCoordinates.requireFinite(x,y,z);WorldCoordinates.requireFinite(vx,vy,vz);
        if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>1e9||
            Math.max(Math.max(Math.abs(vx),Math.abs(vy)),Math.abs(vz))>100000||
            teamId<0||teamId>255||!Double.isFinite(radius)||radius<0||radius>128||
            !Double.isFinite(damage)||damage<0||damage>1e9||
            !Double.isFinite(remainingSeconds)||remainingSeconds<0||remainingSeconds>600)
            throw new IllegalArgumentException("Invalid bounded XYZ projectile state");
    }
    public Projectile3DState advance(double seconds) {
        if(!Double.isFinite(seconds)||seconds<0||seconds>1)throw new IllegalArgumentException("Projectile step must be 0..1 seconds");
        double dt=Math.min(seconds,remainingSeconds);
        return new Projectile3DState(id,teamId,x+vx*dt,y+vy*dt,z+vz*dt,vx,vy,vz,radius,damage,remainingSeconds-dt);
    }
    /** Actual XYZ overlap, so a projectile above/below a unit can miss at identical native X/Y. */
    public boolean overlaps(Unit3DState unit) {
        double dz=Math.max(0,Math.abs(z-unit.z())-unit.halfHeight());
        double planar=Math.max(0,Math.hypot(x-unit.x(),y-unit.y())-unit.radius());
        return planar*planar+dz*dz<=radius*radius;
    }
}
