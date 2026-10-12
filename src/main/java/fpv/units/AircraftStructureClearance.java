package fpv.units;

import fpv.construction.LayerCoordinates.Bounds;
import fpv.construction.LayerWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Supplier;

/** Aircraft route planning only: never replaces the physical world's hollow construction volumes. */
public final class AircraftStructureClearance {
    private static final int CHUNK=16;
    private final Supplier<LayerWorld> source;
    private final HashMap<Long,ArrayList<Obstacle>> chunks=new HashMap<>();
    private LayerWorld cached;
    private long revision=Long.MIN_VALUE,queryId,rebuilds;
    private double support,ahead,ceiling;
    private static final class Obstacle {final Bounds bounds;long seen;Obstacle(Bounds bounds){this.bounds=bounds;}}
    public AircraftStructureClearance(Supplier<LayerWorld> source){if(source==null)throw new IllegalArgumentException("Construction source required");this.source=source;}
    public long rebuilds(){return rebuilds;}
    public double support(){return support;}
    public double ahead(){return ahead;}
    public double ceiling(){return ceiling;}
    public void clear(){chunks.clear();cached=null;revision=Long.MIN_VALUE;}

    /** Continuous square-footprint corridor in canonical XY. Cached buckets avoid scanning empty floors. */
    public void sample(double x,double y,double targetX,double targetY,double centerZ,double radius,double halfHeight){
        if(!Double.isFinite(x+y+targetX+targetY+centerZ+radius+halfHeight)||radius<0||halfHeight<0)
            throw new IllegalArgumentException("Finite aircraft clearance query required");
        synchronize();support=ahead=Double.NEGATIVE_INFINITY;ceiling=Double.POSITIVE_INFINITY;
        double minX=Math.min(x,targetX)-radius,maxX=Math.max(x,targetX)+radius,minY=Math.min(y,targetY)-radius,maxY=Math.max(y,targetY)+radius;
        // First find actual support and an existing overhead roof. A unit already under a bridge
        // can leave through its air gap instead of being instructed to punch upwards through it.
        long stamp=++queryId;
        for(int cx=chunk(x-radius);cx<=chunk(x+radius);cx++)for(int cy=chunk(y-radius);cy<=chunk(y+radius);cy++){
            var bucket=chunks.get(key(cx,cy));if(bucket==null)continue;
            for(var obstacle:bucket){if(obstacle.seen==stamp)continue;obstacle.seen=stamp;var b=obstacle.bounds;
                if(!contains(b,x,y,radius))continue;
                if(b.maxZ()<=centerZ-halfHeight+.02)support=Math.max(support,b.maxZ());
                if(b.minZ()>=centerZ+halfHeight-.02)ceiling=Math.min(ceiling,b.minZ());
            }
        }
        stamp=++queryId;
        for(int cx=chunk(minX);cx<=chunk(maxX);cx++)for(int cy=chunk(minY);cy<=chunk(maxY);cy++){
            var bucket=chunks.get(key(cx,cy));if(bucket==null)continue;
            for(var obstacle:bucket){if(obstacle.seen==stamp)continue;obstacle.seen=stamp;var b=obstacle.bounds;
                if(b.minZ()>=ceiling-.02||!crosses(b,x,y,targetX,targetY,radius))continue;
                ahead=Math.max(ahead,b.maxZ());
            }
        }
    }
    private void synchronize(){
        LayerWorld current=source.get();long next=current==null?-1:current.geometryRevision();
        if(cached==current&&revision==next)return;
        chunks.clear();cached=current;revision=next;rebuilds++;
        if(current==null)return;
        for(var component:current.components()){
            if(component.health()<=0)continue;var obstacle=new Obstacle(current.coordinates().bounds(component));var b=obstacle.bounds;
            for(int x=chunk(b.minX());x<=chunk(b.maxX());x++)for(int y=chunk(b.minY());y<=chunk(b.maxY());y++)
                chunks.computeIfAbsent(key(x,y),ignored->new ArrayList<>()).add(obstacle);
        }
    }
    private static boolean contains(Bounds b,double x,double y,double radius){return x+radius>b.minX()+1e-5&&x-radius<b.maxX()-1e-5&&y+radius>b.minY()+1e-5&&y-radius<b.maxY()-1e-5;}
    private static boolean crosses(Bounds b,double x,double y,double tx,double ty,double r){
        double enter=0,exit=1;
        for(int axis=0;axis<2;axis++){
            double start=axis==0?x:y,delta=axis==0?tx-x:ty-y,low=(axis==0?b.minX():b.minY())-r,high=(axis==0?b.maxX():b.maxY())+r;
            if(Math.abs(delta)<1e-12){if(start<low||start>high)return false;continue;}
            double a=(low-start)/delta,c=(high-start)/delta;if(a>c){double swap=a;a=c;c=swap;}
            enter=Math.max(enter,a);exit=Math.min(exit,c);if(enter>exit)return false;
        }
        return exit>=0&&enter<=1;
    }
    private static int chunk(double coordinate){return (int)Math.floor(coordinate/CHUNK);}
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
}
