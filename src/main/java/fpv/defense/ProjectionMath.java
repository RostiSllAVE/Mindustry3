package fpv.defense;

/** Pure shared geometry/pool policy. Public coordinates are canonical XY/Z-up. */
public final class ProjectionMath {
    private ProjectionMath() {}
    public static double top(double base,double fullTop,double hp,double maxHp){
        return base+Math.max(0,fullTop-base)*Math.max(0,Math.min(1,maxHp>0?hp/maxHp:0));
    }
    public static double regenerated(double hp,double maxHp,double cooldown,double dt,double rate){
        return Math.min(maxHp,Math.max(0,hp)+Math.max(0,dt-Math.max(0,cooldown))*Math.max(0,rate)*maxHp);
    }
    /** Slab intersection of a swept sphere against one box; -1 means no contact. */
    public static double sweep(double ax,double ay,double az,double bx,double by,double bz,
                               double minX,double minY,double minZ,double maxX,double maxY,double maxZ,double radius){
        if(radius<0||!Double.isFinite(ax+ay+az+bx+by+bz+minX+minY+minZ+maxX+maxY+maxZ+radius))return -1;
        double enter=0,exit=1;
        for(int axis=0;axis<3;axis++){
            double start=axis==0?ax:axis==1?ay:az,delta=(axis==0?bx:axis==1?by:bz)-start;
            double min=(axis==0?minX:axis==1?minY:minZ)-radius,max=(axis==0?maxX:axis==1?maxY:maxZ)+radius;
            if(Math.abs(delta)<1e-12){if(start<min||start>max)return -1;continue;}
            double a=(min-start)/delta,b=(max-start)/delta;if(a>b){double s=a;a=b;b=s;}
            enter=Math.max(enter,a);exit=Math.min(exit,b);if(enter>exit)return -1;
        }
        return enter;
    }
}
