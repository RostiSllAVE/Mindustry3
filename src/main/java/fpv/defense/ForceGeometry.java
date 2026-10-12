package fpv.defense;

/** Exact convex clipping against the rendered regular-polygon prism. Coordinates are gameplay XYZ. */
public final class ForceGeometry {
    private ForceGeometry(){}
    public static double sweep(double ax,double ay,double az,double bx,double by,double bz,
                               double x,double y,double bottom,double top,double radius,int sides,double rotation,double padding){
        if(sides<3||sides>256||radius<=0||top<=bottom||padding<0||
            !Double.isFinite(ax+ay+az+bx+by+bz+x+y+bottom+top+radius+rotation+padding))return -1;
        double dx=bx-ax,dy=by-ay,dz=bz-az,enter=0,exit=1;
        for(int i=0;i<sides+2;i++){
            double a,b;
            if(i==sides){a=bottom-padding-az;b=-dz;}
            else if(i==sides+1){a=az-top-padding;b=dz;}
            else{
                double angle=rotation+(i+.5)*Math.PI*2/sides,nx=Math.cos(angle),ny=Math.sin(angle);
                a=(ax-x)*nx+(ay-y)*ny-radius*Math.cos(Math.PI/sides)-padding;b=dx*nx+dy*ny;
            }
            if(Math.abs(b)<1e-12){if(a>1e-10)return -1;continue;}
            double t=-a/b;
            if(b<0)enter=Math.max(enter,t);else exit=Math.min(exit,t);
            if(enter>exit+1e-10)return -1;
        }
        return enter>=0&&enter<=1&&exit>=0?enter:-1;
    }
    /** Swept aircraft footprint against the same prism, with independent vertical extent.
     * Bodies already inside may exit; enabling a field must not imprison a factory spawn. */
    public static fpv.physics.SweptBodies.Hit sweepBody(double ax,double ay,double az,double bx,double by,double bz,
            double x,double y,double bottom,double top,double radius,int sides,double rotation,double bodyRadius,double halfHeight){
        if(sides<3||sides>256||radius<=0||top<=bottom||bodyRadius<0||halfHeight<0||
            !Double.isFinite(ax+ay+az+bx+by+bz+x+y+bottom+top+radius+rotation+bodyRadius+halfHeight))return null;
        double dx=bx-ax,dy=by-ay,dz=bz-az,enter=0,exit=1,nx=0,ny=0,nz=0;boolean inside=true;
        for(int i=0;i<sides+2;i++){
            double px=0,py=0,pz=0,a,b;
            if(i==sides){pz=-1;a=bottom-halfHeight-az;b=-dz;}
            else if(i==sides+1){pz=1;a=az-top-halfHeight;b=dz;}
            else{double angle=rotation+(i+.5)*Math.PI*2/sides;px=Math.cos(angle);py=Math.sin(angle);
                a=(ax-x)*px+(ay-y)*py-radius*Math.cos(Math.PI/sides)-bodyRadius;b=dx*px+dy*py;}
            if(a>1e-9)inside=false;
            if(Math.abs(b)<1e-12){if(a>1e-9)return null;continue;}
            double t=-a/b;
            if(b<0&&t>=enter){enter=t;nx=px;ny=py;nz=pz;}else if(b>0)exit=Math.min(exit,t);
            if(enter>exit+1e-10)return null;
        }
        return !inside&&enter>=0&&enter<=1&&exit>=0&&nx*nx+ny*ny+nz*nz>.5?new fpv.physics.SweptBodies.Hit(enter,nx,ny,nz):null;
    }

}
