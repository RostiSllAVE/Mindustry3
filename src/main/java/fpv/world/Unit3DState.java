package fpv.world;

import fpv.flight.FlightController;

/** Immutable physical snapshot. Query on demand; renderers keep the underlying live pose instead. */
public record Unit3DState(int unitId,int typeId,int teamId,double x,double y,double z,
                          double vx,double vy,double vz,double yaw,double pitch,double roll,
                          double desiredAltitude,double radius,double halfHeight,boolean flying) {
    public Unit3DState {
        WorldCoordinates.requireFinite(x,y,z);WorldCoordinates.requireFinite(vx,vy,vz);
        WorldCoordinates.requireFinite(yaw,pitch,roll);
        if(!Double.isFinite(desiredAltitude)||!Double.isFinite(radius)||radius<0||
            !Double.isFinite(halfHeight)||halfHeight<0||teamId<0||teamId>255)
            throw new IllegalArgumentException("Valid unit dimensions, team and desired altitude required");
    }
    public double distanceSquared(Unit3DState other) { return WorldCoordinates.distanceSquared(x,y,z,other.x,other.y,other.z); }
    public double speed() { return Math.sqrt(vx*vx+vy*vy+vz*vz); }
    public double boundingRadius() { return Math.hypot(radius,halfHeight); }

    /** Real controlled-player pose; the existing flight controller stores render axes and a quaternion. */
    public static Unit3DState fromRenderFlight(int unitId,int typeId,int teamId,FlightController.State state,double radius,double halfDepth){
        if(state==null)throw new IllegalArgumentException("Controlled flight state required");
        double qx=state.qx,qy=state.qy,qz=state.qz,qw=state.qw;
        double norm=Math.sqrt(qx*qx+qy*qy+qz*qz+qw*qw);
        if(!Double.isFinite(norm)||norm<1e-9)throw new IllegalArgumentException("Finite nonzero attitude quaternion required");
        qx/=norm;qy/=norm;qz/=norm;qw/=norm;
        double fx=-2*(qx*qz+qw*qy),fy=2*(qw*qx-qy*qz),fz=-(1-2*(qx*qx+qy*qy));
        double ux=2*(qx*qy-qw*qz),uy=1-2*(qx*qx+qz*qz),uz=2*(qy*qz+qw*qx);
        double yaw=Math.atan2(-fz,fx),pitch=Math.atan2(fy,Math.hypot(fx,fz));
        double ca=Math.cos(yaw),sa=Math.sin(yaw),sp=Math.sin(pitch),cp=Math.cos(pitch);
        double roll=Math.atan2(ux*sa+uz*ca,ux*(-ca*sp)+uy*cp+uz*(sa*sp));
        double verticalExtent=halfDepth*Math.abs(uy)+radius*Math.sqrt(Math.max(0,1-uy*uy));
        return new Unit3DState(unitId,typeId,teamId,state.x,-state.z,state.y,state.vx,-state.vz,state.vy,
            (Math.toDegrees(yaw)+360)%360,Math.toDegrees(pitch),Math.toDegrees(roll),state.y,radius,verticalExtent,true);
    }
}
