package fpv.world;

/** Exact point segments through piecewise planar tile tops, with optional sampled projectile radius. */
public final class HeightRaycast {
    /** Corner order SW,SE,NE,NW; each tile uses triangles 0,1,2 and 0,2,3. */
    @FunctionalInterface public interface Surface { double corner(int tileX,int tileY,int corner); }
    public record Hit(boolean hit,double fraction,double x,double y,double z,
                      double nx,double ny,double nz,int tileX,int tileY) {
        public static final Hit NONE=new Hit(false,1,0,0,0,0,0,1,0,0);
    }
    private HeightRaycast() {}

    /** No uniform step sampling: DDA crosses every tile and tests both linear triangle intervals. */
    public static Hit trace(Surface surface,double ax,double ay,double az,double bx,double by,double bz,double radius) {
        fpv.profile.CameraPerformanceProfile.ray();
        if(surface==null||!Double.isFinite(radius)||radius<0||radius>128)throw new IllegalArgumentException("Valid surface/radius required");
        WorldCoordinates.requireFinite(ax,ay,az);WorldCoordinates.requireFinite(bx,by,bz);
        if(Math.max(Math.max(Math.abs(ax),Math.abs(ay)),Math.max(Math.abs(bx),Math.abs(by)))>1e7)
            throw new IllegalArgumentException("Segment coordinates exceed guarded world bounds");
        Hit earliest=Hit.NONE;
        int probes=radius==0?1:9;
        for(int i=0;i<probes;i++){
            double angle=(i-1)*Math.PI/4;
            double ox=i==0?0:Math.cos(angle)*radius,oy=i==0?0:Math.sin(angle)*radius;
            Hit hit=pointTrace(surface,ax+ox,ay+oy,az-radius,bx+ox,by+oy,bz-radius);
            if(hit.hit&&(!earliest.hit||hit.fraction<earliest.fraction))earliest=hit;
        }
        return earliest;
    }

    private static Hit pointTrace(Surface surface,double ax,double ay,double az,double bx,double by,double bz) {
        double dx=bx-ax,dy=by-ay,dz=bz-az;
        int x=(int)Math.floor(ax+.5),y=(int)Math.floor(ay+.5);
        int endX=(int)Math.floor(bx+.5),endY=(int)Math.floor(by+.5);
        long steps=Math.abs((long)endX-x)+Math.abs((long)endY-y)+4;
        if(steps>100000)throw new IllegalArgumentException("Segment crosses too many cells");
        int sx=Double.compare(dx,0),sy=Double.compare(dy,0);
        double nextX=sx==0?Double.POSITIVE_INFINITY:(x+(sx>0?.5:-.5)-ax)/dx;
        double nextY=sy==0?Double.POSITIVE_INFINITY:(y+(sy>0?.5:-.5)-ay)/dy;
        double deltaX=sx==0?Double.POSITIVE_INFINITY:1/Math.abs(dx),deltaY=sy==0?Double.POSITIVE_INFINITY:1/Math.abs(dy);
        double start=0;
        double sideX=0,sideY=0;
        for(long i=0;i<steps;i++){
            fpv.profile.CameraPerformanceProfile.cell();
            double end=Math.min(1,Math.min(nextX,nextY));
            double h0=surface.corner(x,y,0),h1=surface.corner(x,y,1),h2=surface.corner(x,y,2),h3=surface.corner(x,y,3);
            WorldCoordinates.requireFinite(h0,h1,h2);if(!Double.isFinite(h3))throw new IllegalArgumentException("Finite corner height required");
            double diagonal=dx==dy?Double.NaN:(x-y-ax+ay)/(dx-dy);
            Hit hit;
            if(diagonal>start+1e-12&&diagonal<end-1e-12){
                hit=interval(x,y,ax,ay,az,dx,dy,dz,start,diagonal,h0,h1,h2,h3,sideX,sideY);
                if(!hit.hit)hit=interval(x,y,ax,ay,az,dx,dy,dz,diagonal,end,h0,h1,h2,h3,0,0);
            }else hit=interval(x,y,ax,ay,az,dx,dy,dz,start,end,h0,h1,h2,h3,sideX,sideY);
            if(hit.hit)return hit;
            if(end>=1)return Hit.NONE;
            boolean crossX=nextX<=end+1e-12,crossY=nextY<=end+1e-12;
            sideX=crossX?-sx:0;sideY=crossY?-sy:0;
            if(crossX){x+=sx;nextX+=deltaX;}
            if(crossY){y+=sy;nextY+=deltaY;}
            start=end;
        }
        throw new IllegalStateException("Segment traversal failed to reach its endpoint");
    }

    private static Hit interval(int cellX,int cellY,double ax,double ay,double az,double dx,double dy,double dz,
                                double start,double end,double h0,double h1,double h2,double h3,double sideX,double sideY){
        double mid=(start+end)*.5,u=ax+dx*mid-cellX+.5,v=ay+dy*mid-cellY+.5;
        double gx=v<=u?h1-h0:h2-h3,gy=v<=u?h2-h1:h3-h0;
        double surfaceA=h0+gx*(ax+dx*start-cellX+.5)+gy*(ay+dy*start-cellY+.5);
        double gapA=az+dz*start-surfaceA;
        double gapB=gapA+(dz-gx*dx-gy*dy)*(end-start);
        if(gapA>1e-9&&gapB>1e-9)return Hit.NONE;
        double fraction=gapA<=1e-9?start:start+(end-start)*gapA/(gapA-gapB);
        double px=ax+dx*fraction,py=ay+dy*fraction,pz=az+dz*fraction;
        double top=surfaceA+(gx*dx+gy*dy)*(fraction-start);
        double nx=-gx,ny=-gy,nz=1;
        if(gapA< -1e-7&&(sideX!=0||sideY!=0)){nx=sideX;ny=sideY;nz=0;}
        double norm=Math.sqrt(nx*nx+ny*ny+nz*nz);
        return new Hit(true,Math.max(0,Math.min(1,fraction)),px,py,Math.min(top,pz),nx/norm,ny/norm,nz/norm,cellX,cellY);
    }
}
