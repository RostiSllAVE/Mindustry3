package fpv.physics;

/** Allocation-light canonical XYZ continuous collision primitives; Z is altitude. */
public final class SweptBodies {
    private SweptBodies() {}
    public record Hit(double time,double nx,double ny,double nz) {}
    /** Sweep a point against an already expanded AABB. Existing overlap is not a new impact. */
    public static Hit box(double x,double y,double z,double dx,double dy,double dz,
                          double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        if(x>minX&&x<maxX&&y>minY&&y<maxY&&z>minZ&&z<maxZ)return null;
        double enter=0,exit=1,nx=0,ny=0,nz=0;
        for(int axis=0;axis<3;axis++){
            double start=axis==0?x:axis==1?y:z,delta=axis==0?dx:axis==1?dy:dz;
            double low=axis==0?minX:axis==1?minY:minZ,high=axis==0?maxX:axis==1?maxY:maxZ;
            if(Math.abs(delta)<1e-12){if(start<low||start>high)return null;continue;}
            double t1=(low-start)/delta,t2=(high-start)/delta,sign=-Math.signum(delta);
            if(t1>t2){double swap=t1;t1=t2;t2=swap;}
            if(t1>=enter){enter=t1;nx=axis==0?sign:0;ny=axis==1?sign:0;nz=axis==2?sign:0;}
            exit=Math.min(exit,t2);if(enter>exit)return null;
        }
        if(exit<0||enter<0||enter>1||nx*nx+ny*ny+nz*nz==0)return null;
        return new Hit(enter,nx,ny,nz);
    }
    /** Relative segment against a Minkowski ellipsoid, preserving altitude misses. */
    public static Hit ellipsoid(double rx,double ry,double rz,double dx,double dy,double dz,double radius,double halfHeight){
        if(!(radius>0&&halfHeight>0))throw new IllegalArgumentException("Positive shape required");
        double x=rx/radius,y=ry/radius,z=rz/halfHeight;
        double vx=dx/radius,vy=dy/radius,vz=dz/halfHeight;
        double c=x*x+y*y+z*z-1;
        if(c< -1e-9)return null;
        double a=vx*vx+vy*vy+vz*vz,b=2*(x*vx+y*vy+z*vz),disc=b*b-4*a*c;
        if(a<1e-18||disc<0)return null;
        double t=(-b-Math.sqrt(disc))/(2*a);
        if(t<0||t>1)return null;
        double nx=(rx+dx*t)/(radius*radius),ny=(ry+dy*t)/(radius*radius),nz=(rz+dz*t)/(halfHeight*halfHeight);
        double len=Math.sqrt(nx*nx+ny*ny+nz*nz);
        return new Hit(t,nx/len,ny/len,nz/len);
    }
}
