package fpv.physics;

import java.util.ArrayList;

/** Pure inelastic normal response and within-frame impulse prediction, in canonical XYZ. */
public final class CollisionResponse {
    private CollisionResponse() {}
    public record Delta(double ownX,double ownY,double ownZ,double targetX,double targetY,double targetZ) {
        public static final Delta ZERO=new Delta(0,0,0,0,0,0);
        public boolean changesTarget(){return targetX!=0||targetY!=0||targetZ!=0;}
    }
    public record Kick(double time,double x,double y,double z) {}
    public static final class Motion {
        public double x,y,z,vx,vy,vz;
        public void set(double x,double y,double z,double vx,double vy,double vz,double elapsed){
            this.x=x+vx*elapsed;this.y=y+vy*elapsed;this.z=z+vz*elapsed;
            this.vx=vx;this.vy=vy;this.vz=vz;
        }
    }
    public static final class Timeline {
        private final ArrayList<Kick> kicks=new ArrayList<>();
        public int size(){return kicks.size();}
        public Kick kick(int index){return kicks.get(index);}
        public void add(double time,double x,double y,double z){
            finite(time,x,y,z);if(time<0)throw new IllegalArgumentException("Nonnegative impulse time required");
            kicks.add(new Kick(time,x,y,z));
        }
        public void apply(double elapsed,Motion motion){
            for(Kick kick:kicks)if(elapsed>=kick.time){
                double age=elapsed-kick.time;
                motion.x+=kick.x*age;motion.y+=kick.y*age;motion.z+=kick.z*age;
                motion.vx+=kick.x;motion.vy+=kick.y;motion.vz+=kick.z;
            }
        }
    }

    /** Normal points from target to controlled body; relative velocity is controlled minus target. */
    public static Delta inelastic(double ownMass,double targetMass,double nx,double ny,double nz,
                                  double rvx,double rvy,double rvz,double targetVz,boolean targetFlies,
                                  double maxHorizontalDelta,double maxVerticalDelta){
        finite(ownMass,targetMass,nx,ny,nz,rvx,rvy,rvz,targetVz,maxHorizontalDelta,maxVerticalDelta);
        if(ownMass<=0||targetMass<=0||maxHorizontalDelta<=0||maxVerticalDelta<=0)
            throw new IllegalArgumentException("Positive masses and impulse limits required");
        double normal=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(normal<1e-12)throw new IllegalArgumentException("Nonzero contact normal required");
        nx/=normal;ny/=normal;nz/=normal;
        double closing=-(rvx*nx+rvy*ny+rvz*nz);
        if(closing<=0)return Delta.ZERO;
        // Ground support absorbs vertical momentum; only horizontal target motion is free.
        double inverse=1/ownMass+(targetFlies?1:nx*nx+ny*ny)/targetMass;
        double impulse=closing/inverse;
        double ox=nx*impulse/ownMass,oy=ny*impulse/ownMass,oz=nz*impulse/ownMass;
        double tx=-nx*impulse/targetMass,ty=-ny*impulse/targetMass,tz=targetFlies?-nz*impulse/targetMass:0;
        double scale=1,horizontal=Math.hypot(tx,ty);
        if(horizontal>maxHorizontalDelta)scale=maxHorizontalDelta/horizontal;
        if(tz!=0){
            scale=Math.min(scale,maxVerticalDelta/Math.abs(tz));
            double available=tz>0?maxVerticalDelta-targetVz:targetVz+maxVerticalDelta;
            scale=Math.min(scale,Math.max(0,available)/Math.abs(tz));
        }
        return new Delta(ox*scale,oy*scale,oz*scale,tx*scale,ty*scale,tz*scale);
    }
    private static void finite(double... values){for(double value:values)if(!Double.isFinite(value))throw new IllegalArgumentException("Finite collision parameters required");}
}
