package fpv.physics;

import arc.struct.IntSet;
import fpv.world.World3DAdapter;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Unit;

/** Canonical XYZ broad phase through native spatial indexes/tiles, then continuous shape sweeps. */
public final class RammingCollision {
    @FunctionalInterface public interface TargetPredictor {
        void predict(Unit unit,Unit3DState state,double elapsed,CollisionResponse.Motion output);
    }
    public record Contact(Unit unit,Building building,double time,double x,double y,double z,
                          double nx,double ny,double nz,double closingSpeed,double targetVx,double targetVy,double targetVz) {}
    private final World3DAdapter world;
    private final IntSet visited=new IntSet();
    private final TargetPredictor predictor;
    private final CollisionResponse.Motion targetMotion=new CollisionResponse.Motion();
    private Contact best;
    public RammingCollision(World3DAdapter world){this(world,null);}
    public RammingCollision(World3DAdapter world,TargetPredictor predictor){this.world=world;this.predictor=predictor;}

    public Contact first(Unit self,double x,double y,double z,double endX,double endY,double endZ,
                         double vx,double vy,double vz,double radius,double halfHeight,double seconds){
        return first(self,x,y,z,endX,endY,endZ,vx,vy,vz,radius,halfHeight,seconds,0);
    }
    public Contact first(Unit self,double x,double y,double z,double endX,double endY,double endZ,
                         double vx,double vy,double vz,double radius,double halfHeight,double seconds,double elapsed){
        best=null;visited.clear();
        double dx=endX-x,dy=endY-y,dz=endZ-z;
        consider(null,null,world.traceUnitBarrier(self,x,y,z,endX,endY,endZ,radius,halfHeight),
            x,y,z,dx,dy,dz,vx,vy,vz,0,0,0,radius,halfHeight);
        int minX=Math.max(0,(int)Math.floor(Math.min(x,endX)-radius-1));
        int maxX=Math.min(Vars.world.width()-1,(int)Math.ceil(Math.max(x,endX)+radius+1));
        int minY=Math.max(0,(int)Math.floor(Math.min(y,endY)-radius-1));
        int maxY=Math.min(Vars.world.height()-1,(int)Math.ceil(Math.max(y,endY)+radius+1));
        for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
            var tile=Vars.world.tile(tx,ty);var b=tile.build;
            if(b==null||b.dead||!visited.add(b.id))continue;
            double bx=b.x/(double)Vars.tilesize,by=b.y/(double)Vars.tilesize,half=b.block.size*.5;
            double base=world.terrainHeight(bx,by),top=world.surfaceHeight(bx,by);
            var hit=SweptBodies.box(x,y,z,dx,dy,dz,bx-half-radius,by-half-radius,base-halfHeight,
                bx+half+radius,by+half+radius,Math.max(base+.04,top)+halfHeight);
            consider(null,b,hit,x,y,z,dx,dy,dz,vx,vy,vz,0,0,0,radius,halfHeight);
        }
        // Native quadtree includes unit hitboxes. Padding admits fast approaching AI/knockback units.
        double pad=radius+Math.max(4,(seconds+elapsed)*320);
        float qx=(float)(Math.min(x,endX)-pad)*Vars.tilesize,qy=(float)(Math.min(y,endY)-pad)*Vars.tilesize;
        float qw=(float)(Math.abs(dx)+pad*2)*Vars.tilesize,qh=(float)(Math.abs(dy)+pad*2)*Vars.tilesize;
        Units.nearby(qx,qy,qw,qh,u->{
            if(u==self||u.dead||!u.hittable())return;
            var target=world.unitState(u);if(target==null)return;
            targetMotion.set(target.x(),target.y(),target.z(),target.vx(),target.vy(),target.vz(),elapsed);
            if(predictor!=null)predictor.predict(u,target,elapsed,targetMotion);
            var hit=SweptBodies.ellipsoid(x-targetMotion.x,y-targetMotion.y,z-targetMotion.z,
                dx-targetMotion.vx*seconds,dy-targetMotion.vy*seconds,dz-targetMotion.vz*seconds,
                radius+target.radius(),halfHeight+target.halfHeight());
            consider(u,null,hit,x,y,z,dx,dy,dz,vx,vy,vz,targetMotion.vx,targetMotion.vy,targetMotion.vz,radius,halfHeight);
        });
        return best;
    }

    private void consider(Unit unit,Building building,SweptBodies.Hit hit,double x,double y,double z,
                          double dx,double dy,double dz,double vx,double vy,double vz,
                          double tvx,double tvy,double tvz,double radius,double halfHeight){
        if(hit==null||(best!=null&&hit.time()>=best.time()))return;
        double closing=Math.max(0,-((vx-tvx)*hit.nx()+(vy-tvy)*hit.ny()+(vz-tvz)*hit.nz()));
        if(closing<1e-8)return;
        // Support point on the controlled body's simplified ellipsoid.
        double denom=Math.sqrt(radius*radius*(hit.nx()*hit.nx()+hit.ny()*hit.ny())+halfHeight*halfHeight*hit.nz()*hit.nz());
        best=new Contact(unit,building,hit.time(),x+dx*hit.time()-radius*radius*hit.nx()/denom,
            y+dy*hit.time()-radius*radius*hit.ny()/denom,z+dz*hit.time()-halfHeight*halfHeight*hit.nz()/denom,
            hit.nx(),hit.ny(),hit.nz(),closing,tvx,tvy,tvz);
    }
}
