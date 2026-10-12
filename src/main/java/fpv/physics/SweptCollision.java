package fpv.physics;

import java.util.function.DoubleBinaryOperator;
import fpv.flight.FlightController;

/**
 * Pure height-surface collision using a conservative cylindrical footprint sampled at nine points.
 * Positions/velocities are tiles and tiles/second. At most two swept contacts resolve a frame.
 */
public final class SweptCollision {
    public record Impact(boolean hit,double x,double y,double z,double nx,double ny,double nz,double normalSpeed) {
        public static final Impact NONE=new Impact(false,0,0,0,0,1,0,0);
    }
    private record Contact(double safeFraction,Impact impact) {}
    private final DoubleBinaryOperator surface;
    private final double radius,halfHeight,step;
    private double contactX,contactZ,contactHeight;

    public SweptCollision(DoubleBinaryOperator surface,double radius,double step){
        this(surface,radius,radius,step);
    }
    public SweptCollision(DoubleBinaryOperator surface,double radius,double halfHeight,double step){
        if(surface==null||!Double.isFinite(radius)||!(radius>0)||!Double.isFinite(halfHeight)||!(halfHeight>0)||!Double.isFinite(step)||!(step>0))
            throw new IllegalArgumentException("Valid finite surface/radius/step required");
        this.surface=surface;this.radius=radius;this.halfHeight=halfHeight;this.step=step;
    }

    public Impact resolve(FlightController.State state,double ox,double oy,double oz){
        if(state==null||!Double.isFinite(state.x)||!Double.isFinite(state.y)||!Double.isFinite(state.z)||
            !Double.isFinite(state.vx)||!Double.isFinite(state.vy)||!Double.isFinite(state.vz)||
            !Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz))
            throw new IllegalArgumentException("Finite sweep pose and velocity required");
        // A pre-existing overlap can come from a load, native correction or terrain edit.
        // It is recovery, not new kinetic contact; retained downward speed must never cause damage.
        if(penetrates(ox,oy,oz)){
            double cx=contactX,cz=contactZ,cy=contactHeight;
            state.x=ox;state.z=oz;state.y=Math.max(oy,cy+halfHeight+.001);
            if(state.vy<0)state.vy=0;
            return new Impact(true,cx,Math.min(cy,oy),cz,0,1,0,0);
        }
        double targetX=state.x,targetY=state.y,targetZ=state.z;
        Impact strongest=Impact.NONE;
        for(int pass=0;pass<2;pass++){
            double dx=targetX-ox,dy=targetY-oy,dz=targetZ-oz;
            Contact contact=sweep(state,ox,oy,oz,dx,dy,dz);
            if(contact==null){state.x=targetX;state.y=targetY;state.z=targetZ;break;}
            Impact hit=contact.impact;
            if(!strongest.hit||hit.normalSpeed>strongest.normalSpeed)strongest=hit;
            double nx=hit.nx,ny=hit.ny,nz=hit.nz;
            state.x=ox+dx*contact.safeFraction;
            state.y=oy+dy*contact.safeFraction;
            state.z=oz+dz*contact.safeFraction;
            double remainingX=targetX-state.x,remainingY=targetY-state.y,remainingZ=targetZ-state.z;
            double velocityDot=state.vx*nx+state.vy*ny+state.vz*nz;
            if(velocityDot<0){state.vx-=velocityDot*nx;state.vy-=velocityDot*ny;state.vz-=velocityDot*nz;}
            // A tiny outward skin prevents a gravity contact at t~=0 from consuming the slide pass.
            double sx=state.x+nx*1e-5,sy=state.y+ny*1e-5,sz=state.z+nz*1e-5;
            if(!penetrates(sx,sy,sz)){state.x=sx;state.y=sy;state.z=sz;}
            if(pass==1)break;
            double travelDot=remainingX*nx+remainingY*ny+remainingZ*nz;
            if(travelDot<0){remainingX-=travelDot*nx;remainingY-=travelDot*ny;remainingZ-=travelDot*nz;}
            if(remainingX*remainingX+remainingY*remainingY+remainingZ*remainingZ<1e-16)break;
            ox=state.x;oy=state.y;oz=state.z;
            targetX=ox+remainingX;targetY=oy+remainingY;targetZ=oz+remainingZ;
        }
        if(strongest.hit){
            // Apply friction once per frame, independently of whether the slide hit a second surface.
            state.vx*=.82;state.vz*=.82;
        }
        return strongest;
    }

    private Contact sweep(FlightController.State state,double ox,double oy,double oz,double dx,double dy,double dz){
        double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!Double.isFinite(distance))throw new IllegalArgumentException("Finite sweep required");
        int count=Math.max(1,(int)Math.ceil(distance/step));
        if(count>20000)throw new IllegalArgumentException("Unreasonable sweep distance");
        double last=0;
        for(int i=1;i<=count;i++){
            double t=i/(double)count;
            if(penetrates(ox+dx*t,oy+dy*t,oz+dz*t)){
                double lo=last,hi=t;
                for(int j=0;j<14;j++){double mid=(lo+hi)*.5;if(penetrates(ox+dx*mid,oy+dy*mid,oz+dz*mid))hi=mid;else lo=mid;}
                penetrates(ox+dx*hi,oy+dy*hi,oz+dz*hi);
                double cx=contactX,cz=contactZ,cy=contactHeight,e=.035;
                double hxPlus=height(cx+e,cz),hxMinus=height(cx-e,cz);
                double hzPlus=height(cx,cz+e),hzMinus=height(cx,cz-e);
                double gx=(hxPlus-hxMinus)/(2*e),gz=(hzPlus-hzMinus)/(2*e);
                boolean topContact=cy-(oy+dy*hi-halfHeight)<1e-4;
                if(topContact&&Math.hypot(gx,gz)>4){
                    // Landing on a plateau edge hits its top, not its exterior vertical face.
                    // Choose the local one-sided derivative that does not cross the height jump.
                    gx=shallower((hxPlus-cy)/e,(cy-hxMinus)/e);
                    gz=shallower((hzPlus-cy)/e,(cy-hzMinus)/e);
                }
                // Natural terrain slopes are gradual; abrupt height discontinuities are cliff faces.
                // A horizontal normal prevents a cliff from injecting artificial upward velocity.
                double nx=-gx,ny=!topContact&&Math.hypot(gx,gz)>4?0:1,nz=-gz;
                double n=Math.sqrt(nx*nx+ny*ny+nz*nz);nx/=n;ny/=n;nz/=n;
                double speed=Math.max(0,-(state.vx*nx+state.vy*ny+state.vz*nz));
                return new Contact(lo,new Impact(true,cx,Math.min(cy,oy+dy*hi),cz,nx,ny,nz,speed));
            }
            last=t;
        }
        return null;
    }

    private boolean penetrates(double x,double y,double z){
        double best=-Double.MAX_VALUE,bx=x,bz=z;
        for(int i=0;i<9;i++){
            double angle=(i-1)*Math.PI/4,px=x+(i==0?0:Math.cos(angle)*radius),pz=z+(i==0?0:Math.sin(angle)*radius);
            double h=height(px,pz);
            if(h>best){best=h;bx=px;bz=pz;}
        }
        contactX=bx;contactZ=bz;contactHeight=best;
        return y-halfHeight<best-1e-7;
    }

    private double height(double x,double z){
        double value=surface.applyAsDouble(x,z);
        if(!Double.isFinite(value))throw new IllegalArgumentException("Surface height must be finite");
        return value;
    }

    private static double shallower(double a,double b){return Math.abs(a)<Math.abs(b)?a:b;}

    public static double damageFraction(double normalMetersPerSecond,double safe,double lethal){
        if(!Double.isFinite(safe)||!(safe>=0)||!Double.isFinite(lethal)||!(lethal>safe)||!Double.isFinite(normalMetersPerSecond))
            throw new IllegalArgumentException("Invalid damage curve");
        double t=Math.max(0,Math.min(1,(normalMetersPerSecond-safe)/(lethal-safe)));
        return t*t;
    }
}
