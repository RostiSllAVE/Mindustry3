package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.struct.Seq;
import fpv.construction.*;
import mindustry.entities.units.BuildPlan;

/** Full native-texture clipboard ghost, streamed in bounded batches using existing hologram geometry. */
public final class LayerSchematicRenderer implements AutoCloseable {
    private final ConstructionVisualRenderer batch=new ConstructionVisualRenderer();
    private final BuildingInteractionRenderer rectangle=new BuildingInteractionRenderer();
    private Seq<BuildPlan> plans;
    private boolean[] valid;
    private int layer,lastDrawn;
    public void set(Seq<BuildPlan> plans,boolean[] valid,int layer){this.plans=plans;this.valid=valid;this.layer=layer;}
    public boolean active(){return plans!=null;}
    public int lastDrawn(){return lastDrawn;}
    public void selection(LayerCoordinates.Bounds bounds){rectangle.set(bounds==null?null:new BuildingMousePicker.Hit(0,null,0,bounds.centerX(),bounds.centerY(),bounds.maxZ(),bounds,true),null,null,true);}
    public void render(Camera3D camera,LayerRuntime runtime){
        rectangle.render(camera);lastDrawn=0;if(plans==null||runtime==null)return;batch.begin();int count=0;
        for(int i=0;i<plans.size;i++){
            var p=plans.get(i);double x=LayerCoordinates.center(p.x,p.block.size),y=LayerCoordinates.center(p.y,p.block.size),z=runtime.planeHeight(layer);
            var entry=runtime.catalog().entry(p.block.name,LayerSchematic.platform(p),runtime.grid().coordinates().spacing());
            double height=entry==null?fpv.net.HeadlessGeometry.heightFor(p.block):entry.height();boolean ok=valid!=null&&i<valid.length&&valid[i];
            if(!camera.frustum.containsBounds((float)x,(float)(z+height*.5),(float)-y,p.block.size,(float)Math.max(.1,height),p.block.size))continue;
            if(count==256){batch.draw(camera);batch.begin();count=0;}
            if(LayerSchematic.platform(p))batch.ghost(Core.atlas.white(),x,y,z-LayerCoordinates.PLATFORM_THICKNESS,1,LayerCoordinates.PLATFORM_THICKNESS,0,ok,.4f);
            else batch.ghost(p.block,x,y,z,height,p.rotation,ok,.48f);
            count++;lastDrawn++;
        }batch.draw(camera);
    }
    @Override public void close(){batch.close();rectangle.close();}
}
