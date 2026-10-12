package fpv.construction;

import arc.math.geom.Ray;
import fpv.physics.SweptBodies;
import fpv.render.FpvWorldRenderer;
import mindustry.Vars;
import mindustry.gen.Building;

/** Presentation-only nearest surface picking; never asks the active floor to choose an object. */
public final class BuildingMousePicker {
    private BuildingMousePicker(){}
    public enum Surface {TOP,SIDE,BOTTOM,PLATFORM}
    public record Hit(long id,Building building,int layer,double x,double y,double z,
                      LayerCoordinates.Bounds bounds,boolean platform,mindustry.world.Block block,
                      double nx,double ny,double nz,Surface surface,int constructionLayer,boolean supportsConstruction){
        public Hit(long id,Building building,int layer,double x,double y,double z,LayerCoordinates.Bounds bounds,boolean platform){this(id,building,layer,x,y,z,bounds,platform,building==null?null:building.block);}
        public Hit(long id,Building building,int layer,double x,double y,double z,LayerCoordinates.Bounds bounds,boolean platform,mindustry.world.Block block){
            this(id,building,layer,x,y,z,bounds,platform,block,normal(x,y,z,bounds,0),normal(x,y,z,bounds,1),normal(x,y,z,bounds,2),
                type(normal(x,y,z,bounds,2),platform),targetLayer(layer,bounds,platform,block,normal(x,y,z,bounds,2),LayerCoordinates.DEFAULT),
                normal(x,y,z,bounds,2)>0&&(platform||block instanceof mindustry.world.blocks.defense.Wall));
        }
    }
    private static Surface type(double nz,boolean platform){return nz>0?(platform?Surface.PLATFORM:Surface.TOP):nz<0?Surface.BOTTOM:Surface.SIDE;}
    private static int targetLayer(int layer,LayerCoordinates.Bounds b,boolean platform,mindustry.world.Block block,double nz,LayerCoordinates coordinates){
        return nz>0&&!platform&&block instanceof mindustry.world.blocks.defense.Wall?coordinates.layerAtOrAbove(b.maxZ()):layer;
    }
    private static double normal(double x,double y,double z,LayerCoordinates.Bounds b,int axis){
        double best=Math.abs(z-b.maxZ());int face=0;
        for(int i=1;i<6;i++){
            double d=Math.abs(i==1?z-b.minZ():i==2?x-b.minX():i==3?x-b.maxX():i==4?y-b.minY():y-b.maxY());
            if(d<best-1e-9){best=d;face=i;}
        }
        return axis==2?(face==0?1:face==1?-1:0):axis==0?(face==2?-1:face==3?1:0):(face==4?-1:face==5?1:0);
    }
    public static Hit pick(LayerRuntime runtime,Ray ray,double limit,FpvWorldRenderer.PickResult ground){
        double ax=ray.origin.x,ay=-ray.origin.z,az=ray.origin.y;
        double bx=ax+ray.direction.x*limit,by=ay-ray.direction.z*limit,bz=az+ray.direction.y*limit;
        double best=ground==null?limit:ground.distance;
        Hit result=null;
        if(ground!=null&&ground.tile.build!=null){
            Building b=ground.tile.build;
            double x=b.x/(double)Vars.tilesize,y=b.y/(double)Vars.tilesize,half=b.block.size*.5;
            var bounds=new LayerCoordinates.Bounds(x-half,y-half,0,x+half,y+half,FpvWorldRenderer.heightFor(b.block));
            result=new Hit(LayerVerticalTransfers.groundId(b.tileX(),b.tileY()),b,0,ground.worldX/(double)Vars.tilesize,ground.worldY/(double)Vars.tilesize,ground.height,bounds,false);
        }else if(ground!=null){
            double x=ground.worldX/(double)Vars.tilesize,y=ground.worldY/(double)Vars.tilesize;
            result=new Hit(0,null,0,x,y,ground.height,new LayerCoordinates.Bounds(ground.tileX-.5,ground.tileY-.5,ground.height,ground.tileX+.5,ground.tileY+.5,ground.height),false);
        }
        var grid=runtime.grid();
        for(var c:grid.query(new LayerCoordinates.Bounds(Math.min(ax,bx),Math.min(ay,by),Math.min(az,bz),Math.max(ax,bx),Math.max(ay,by),Math.max(az,bz)))){
            if(c.health()<=0)continue;
            var bounds=grid.coordinates().bounds(c);
            Building b=runtime.building(c.id());
            var contact=intersection(b,bounds,ax,ay,az,bx,by,bz);
            if(contact==null)continue;double fraction=contact.hit().time();
            double distance=fraction*limit;
            if(!Double.isFinite(distance)||fraction<0||distance>best+1e-6)continue;
            // At a column/cap joint, the covering upper slab is the visible construction surface.
            if(Math.abs(distance-best)<1e-6&&result!=null&&(c.layer()<result.layer()||c.layer()==result.layer()&&!result.platform()&&c.kind()==LayerComponent.Kind.PLATFORM))continue;
            best=distance;
            boolean hidden=Vars.player==null||Vars.state.rules.fog&&Vars.state.rules.staticFog&&!Vars.fogControl.isDiscovered(Vars.player.team(),c.x(),c.y())||b!=null&&b.inFogTo(Vars.player.team());
            // Hidden geometry still occludes anything behind it.
            boolean platform=c.kind()==LayerComponent.Kind.PLATFORM;
            mindustry.world.Block block=platform?null:Vars.content.getByName(mindustry.ctype.ContentType.block,c.blockName());
            var h=contact.hit();
            result=hidden?null:new Hit(c.id(),b,c.layer(),ax+(bx-ax)*fraction,ay+(by-ay)*fraction,az+(bz-az)*fraction,contact.bounds(),platform,block,
                h.nx(),h.ny(),h.nz(),type(h.nz(),platform),targetLayer(c.layer(),bounds,platform,block,h.nz(),grid.coordinates()),h.nz()>0&&(platform||block instanceof mindustry.world.blocks.defense.Wall));
        }
        return result;
    }
    private record Contact(LayerCoordinates.Bounds bounds,SweptBodies.Hit hit){}
    private static Contact intersection(Building b,LayerCoordinates.Bounds bounds,double ax,double ay,double az,double bx,double by,double bz){
        boolean open=b instanceof mindustry.world.blocks.defense.Door.DoorBuild door&&door.open||b instanceof mindustry.world.blocks.defense.AutoDoor.AutoDoorBuild autoDoor&&autoDoor.open;
        if(!open)return box(bounds,ax,ay,az,bx,by,bz);
        // Match LayerWorldRenderer's open-door jambs, rather than picking an invisible closed slab.
        double jamb=Math.max(.08,(bounds.maxX()-bounds.minX())*.12);
        Contact first=box(new LayerCoordinates.Bounds(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.minX()+jamb,bounds.maxY(),bounds.maxZ()),ax,ay,az,bx,by,bz);
        Contact second=box(new LayerCoordinates.Bounds(bounds.maxX()-jamb,bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()),ax,ay,az,bx,by,bz);
        Contact floor=box(new LayerCoordinates.Bounds(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.minZ()+.02),ax,ay,az,bx,by,bz);
        return nearest(first,nearest(second,floor));
    }
    private static Contact nearest(Contact a,Contact b){return a==null?b:b==null?a:a.hit().time()<=b.hit().time()?a:b;}
    private static Contact box(LayerCoordinates.Bounds b,double ax,double ay,double az,double bx,double by,double bz){var h=SweptBodies.box(ax,ay,az,bx-ax,by-ay,bz-az,b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());return h==null?null:new Contact(b,h);}
    public static boolean present(LayerRuntime runtime,Hit hit){
        if(hit==null)return false;
        if(hit.id()>0){var c=runtime.grid().get(hit.id());return c!=null&&c.health()>0&&(hit.platform()||runtime.building(hit.id())==hit.building());}
        return hit.building()!=null&&!hit.building().dead&&Vars.world.build(hit.building().pos())==hit.building();
    }
}
