package fpv.construction;

import arc.math.geom.Ray;
import mindustry.world.Block;
import mindustry.world.blocks.defense.Wall;

/** Resolves construction anchors from the shared visible hit; only a locked plane permits empty-space placement. */
public record MouseConstructionTarget(boolean resolved,int x,int y,int layer,String reason) {
    public static MouseConstructionTarget resolve(BuildingMousePicker.Hit hit,Ray ray,LayerCoordinates coordinates,
                                                  Block block,boolean platform,boolean locked,int manualLayer){
        int size=platform||block==null?1:block.size,layer=manualLayer;
        double x,y;
        if(locked){
            double distance=Math.abs(ray.direction.y)<1e-8?-1:(coordinates.plane(manualLayer)-ray.origin.y)/ray.direction.y;
            if(!Double.isFinite(distance)||distance<0)return none(manualLayer,"Aim at the locked construction plane");
            x=ray.origin.x+ray.direction.x*distance;y=-ray.origin.z-ray.direction.z*distance;
        }else{
            if(hit==null)return none(manualLayer,"No visible construction surface; lock a floor to build into empty space");
            layer=hit.layer();x=hit.x();y=hit.y();var b=hit.bounds();
            boolean top=hit.surface()==BuildingMousePicker.Surface.TOP||hit.surface()==BuildingMousePicker.Surface.PLATFORM;
            double side=Math.min(Math.min(Math.abs(x-b.minX()),Math.abs(x-b.maxX())),Math.min(Math.abs(y-b.minY()),Math.abs(y-b.maxY())));
            if(hit.surface()==BuildingMousePicker.Surface.BOTTOM||!top&&side>.025)return none(layer,"Aim at the top or an exposed edge, not the underside");
            if(hit.block() instanceof Wall&&top){
                layer=coordinates.layerAtOrAbove(b.maxZ());
                if(layer>coordinates.maxLayers())return none(manualLayer,"Maximum construction floor reached");
            }else if((hit.platform()||hit.block()!=null)&&(!top||platform&&nearEdge(hit))){
                // Choose only the physically struck edge, never search unrelated neighbouring cells for a green result.
                double[] distances={Math.abs(x-b.minX()),Math.abs(x-b.maxX()),Math.abs(y-b.minY()),Math.abs(y-b.maxY())};int edge=0;
                for(int i=1;i<4;i++)if(distances[i]<distances[edge])edge=i;
                if(edge==0)x=b.minX()-size*.5;else if(edge==1)x=b.maxX()+size*.5;else if(edge==2)y=b.minY()-size*.5;else y=b.maxY()+size*.5;
            }
        }
        double offset=size%2==0?.5:0;
        return new MouseConstructionTarget(true,(int)Math.floor(x-offset+.5),(int)Math.floor(y-offset+.5),layer,"");
    }
    private static boolean nearEdge(BuildingMousePicker.Hit h){var b=h.bounds();return Math.min(Math.min(Math.abs(h.x()-b.minX()),Math.abs(h.x()-b.maxX())),Math.min(Math.abs(h.y()-b.minY()),Math.abs(h.y()-b.maxY())))<.12;}
    private static MouseConstructionTarget none(int layer,String reason){return new MouseConstructionTarget(false,0,0,layer,reason);}
}
