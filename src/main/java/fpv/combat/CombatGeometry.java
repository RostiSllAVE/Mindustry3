package fpv.combat;

import fpv.world.WorldCoordinates;

/** Pure continuous XYZ geometry for the migrated weapon families. Fractions are in [0,1]. */
public final class CombatGeometry {
    /** Constant-velocity XYZ lead, bounded when the target cannot be intercepted. */
    public static WorldCoordinates.Point intercept(double x,double y,double z,double tx,double ty,double tz,
            double vx,double vy,double vz,double speed,double maxSeconds){
        double dx=tx-x,dy=ty-y,dz=tz-z,a=vx*vx+vy*vy+vz*vz-speed*speed,b=2*(dx*vx+dy*vy+dz*vz),c=dx*dx+dy*dy+dz*dz,t=0;
        if(speed>1e-9){
            if(Math.abs(a)<1e-9){if(b<0)t=-c/b;}
            else{double disc=b*b-4*a*c;if(disc>=0){double root=Math.sqrt(disc),one=(-b-root)/(2*a),two=(-b+root)/(2*a);
                t=one>0&&two>0?Math.min(one,two):Math.max(0,Math.max(one,two));}}
        }
        t=Math.min(Math.max(0,maxSeconds),t);
        return new WorldCoordinates.Point(tx+vx*t,ty+vy*t,tz+vz*t);
    }
    private CombatGeometry() {}
    /** Target coordinates are at frame end; endLag locates this earlier projectile substep. */
    public static double movingCylinder(double ax,double ay,double az,double bx,double by,double bz,
                                        double cx,double cy,double cz,double vx,double vy,double vz,double radius,double halfHeight,double step,double endLag){
        return cylinder(ax-cx+vx*(step+endLag),ay-cy+vy*(step+endLag),az-cz+vz*(step+endLag),
            bx-cx+vx*endLag,by-cy+vy*endLag,bz-cz+vz*endLag,0,0,0,radius,halfHeight);
    }
    /** First segment overlap with an upright finite cylinder, including an initial overlap. */
    public static double cylinder(double ax,double ay,double az,double bx,double by,double bz,
                                  double cx,double cy,double cz,double radius,double halfHeight){
        if(!(radius>=0&&halfHeight>=0))throw new IllegalArgumentException("Nonnegative body dimensions required");
        double x=ax-cx,y=ay-cy,z=az-cz,dx=bx-ax,dy=by-ay,dz=bz-az;
        double enter=0,exit=1;
        if(Math.abs(dz)<1e-12){if(Math.abs(z)>halfHeight)return Double.POSITIVE_INFINITY;}
        else{
            double a=(-halfHeight-z)/dz,b=(halfHeight-z)/dz;
            if(a>b){double t=a;a=b;b=t;}
            enter=Math.max(enter,a);exit=Math.min(exit,b);
        }
        double a=dx*dx+dy*dy,c=x*x+y*y-radius*radius;
        if(a<1e-20){if(c>1e-12)return Double.POSITIVE_INFINITY;}
        else{
            double b=2*(x*dx+y*dy),d=b*b-4*a*c;
            if(d<0)return Double.POSITIVE_INFINITY;
            double s=Math.sqrt(Math.max(0,d));
            enter=Math.max(enter,(-b-s)/(2*a));exit=Math.min(exit,(-b+s)/(2*a));
        }
        return enter<=exit+1e-12&&exit>=0&&enter<=1?Math.max(0,enter):Double.POSITIVE_INFINITY;
    }
    /** First segment overlap with an expanded structure box, including an initial overlap. */
    public static double box(double ax,double ay,double az,double bx,double by,double bz,
                             double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        double enter=0,exit=1;
        for(int axis=0;axis<3;axis++){
            double a=axis==0?ax:axis==1?ay:az,d=axis==0?bx-ax:axis==1?by-ay:bz-az;
            double low=axis==0?minX:axis==1?minY:minZ,high=axis==0?maxX:axis==1?maxY:maxZ;
            if(low>high)throw new IllegalArgumentException("Ordered bounds required");
            if(Math.abs(d)<1e-12){if(a<low||a>high)return Double.POSITIVE_INFINITY;continue;}
            double first=(low-a)/d,last=(high-a)/d;
            if(first>last){double t=first;first=last;last=t;}
            enter=Math.max(enter,first);exit=Math.min(exit,last);
            if(enter>exit)return Double.POSITIVE_INFINITY;
        }
        return exit>=0&&enter<=1?Math.max(0,enter):Double.POSITIVE_INFINITY;
    }
    public static WorldCoordinates.Point direction(double dx,double dy,double dz){
        WorldCoordinates.requireFinite(dx,dy,dz);
        double largest=Math.max(Math.abs(dx),Math.max(Math.abs(dy),Math.abs(dz)));
        if(largest<1e-12)throw new IllegalArgumentException("Nonzero direction required");
        dx/=largest;dy/=largest;dz/=largest;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        return new WorldCoordinates.Point(dx/length,dy/length,dz/length);
    }
    /** Keep native azimuth/spread, with vertical pitch derived from a physical target. */
    public static WorldCoordinates.Point aim(double nativeAzimuthDegrees,double horizontalDistance,double verticalDifference){
        WorldCoordinates.requireFinite(nativeAzimuthDegrees,horizontalDistance,verticalDifference);
        double yaw=Math.toRadians(nativeAzimuthDegrees),distance=Math.max(0,horizontalDistance);
        double pitch=Math.atan2(verticalDifference,Math.max(1e-9,distance));
        return direction(Math.cos(yaw)*Math.cos(pitch),Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch));
    }
    public static WorldCoordinates.Point rotateAzimuth(WorldCoordinates.Point direction,double degrees){
        double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);
        return direction(direction.x()*c-direction.y()*s,direction.x()*s+direction.y()*c,direction.z());
    }
}
