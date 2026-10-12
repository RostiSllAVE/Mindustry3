package fpv.construction;

import arc.math.geom.Ray;
import arc.struct.Seq;
import arc.util.Time;
import fpv.input.FpvDesktopInput;
import fpv.render.LayerWorldRenderer;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.input.Binding;

/** Extends the native clipboard: one fixed source floor, one stamp per mouse press. */
public final class LayerSchematicInput {
    private final LayerRuntime runtime;
    private final FpvDesktopInput input;
    private final LayerWorldRenderer renderer;
    private boolean copying,stampHeld;
    private int sourceLayer,ax,ay,bx,by;
    private double selectionPlane;
    private long signature=Long.MIN_VALUE,revision=Long.MIN_VALUE,checked;
    private LayerSchematic.Stamp stamp;
    private LayerPasteQueue.Preview preview;
    private int targetLayer;
    private String status="";
    public LayerSchematicInput(LayerRuntime runtime,FpvDesktopInput input,LayerWorldRenderer renderer){this.runtime=runtime;this.input=input;this.renderer=renderer;}
    public boolean active(){return copying||!input.selectPlans.isEmpty();}
    public int layer(){return copying?sourceLayer:targetLayer;}
    public String status(){return status;}
    public void suspend(){copying=stampHeld=false;signature=Long.MIN_VALUE;renderer.schematic(null,null,0);renderer.copySelection(null);input.setSchematicOwned(false);status="";}
    public boolean process(BuildingMousePicker.Hit hit,Ray ray,int floor,boolean locked,boolean free,boolean begin,LayerBuildController.Buttons buttons){
        var raw=input.originalInput();
        if(raw.keyTap(Binding.clearBuilding)){
            input.selectPlans.clear();input.lastSchematic=null;Vars.player.unit().clearBuilding();
            runtime.submit(LayerActions.Action.cancelPaste());suspend();input.setInteractionOwned(true);return true;
        }
        boolean start=raw.keyTap(Binding.schematicSelect)&&!copying&&!buttons.ui();
        if(input.block!=null&&!start){if(copying||!input.selectPlans.isEmpty()){input.selectPlans.clear();input.lastSchematic=null;suspend();}return false;}
        if(start){
            if(!locked&&hit==null){status="Aim at a visible floor to copy";return true;}
            sourceLayer=locked?floor:hit.layer();selectionPlane=locked?runtime.planeHeight(floor):hit.z();
            var point=point(ray,selectionPlane);if(point==null)return true;
            input.block=null;
            ax=bx=point[0];ay=by=point[1];copying=true;input.selectPlans.clear();input.lastSchematic=null;input.cancelPointerGesture();
        }
        if(!active()) {input.setSchematicOwned(false);renderer.schematic(null,null,0);return false;}
        input.setSchematicOwned(true);input.setInteractionOwned(true);input.setUpperLayerOwned(true);
        renderer.preview(false,floor,0,0,1,false,false);renderer.interaction(null,null,null,false);
        if(buttons.cancelTap()||buttons.menuTap()){
            input.selectPlans.clear();input.lastSchematic=null;suspend();input.setCancelConsumed(true);input.setInteractionOwned(true);return true;
        }
        if(copying){
            var point=point(ray,selectionPlane);if(point!=null){bx=point[0];by=point[1];}
            renderer.schematic(null,null,0);renderer.copySelection(new LayerCoordinates.Bounds(Math.min(ax,bx)-.5,Math.min(ay,by)-.5,selectionPlane,Math.max(ax,bx)+.5,Math.max(ay,by)+.5,selectionPlane+.01));
            status="Copy Z"+sourceLayer+" · release "+Binding.schematicSelect.value.key+" to finish";
            if(raw.keyRelease(Binding.schematicSelect)||!raw.keyDown(Binding.schematicSelect)&&!start){
                copying=false;renderer.copySelection(null);
                try{
                    var schematic=LayerSchematic.capture(runtime,sourceLayer,ax,ay,bx,by);
                    input.lastSchematic=schematic;input.useSchematic(schematic);
                    targetLayer=sourceLayer;
                    status=input.selectPlans.isEmpty()?"No buildings on selected floor":"Copied Z"+sourceLayer+" · "+input.selectPlans.size+" entries";
                    String warning=schematic.tags.get("fpv-copy-warning");if(warning!=null)runtime.acceptResult(LayerActions.Result.rejected(warning));
                    signature=Long.MIN_VALUE;
                }catch(IllegalArgumentException bad){status=bad.getMessage();runtime.acceptResult(LayerActions.Result.rejected(status));}
            }return true;
        }
        if(buttons.ui()){renderer.schematic(null,null,0);return true;}
        if(!buttons.selectHeld()&&!buttons.selectTap()||buttons.selectReleased())stampHeld=false;
        var target=MouseConstructionTarget.resolve(hit,ray,runtime.grid().coordinates(),null,false,locked,floor);
        if(!stampHeld&&target.resolved()){
            int dx=target.x()-input.schematicX,dy=target.y()-input.schematicY;
            for(var p:input.selectPlans){p.x+=dx;p.y+=dy;}
            input.schematicX=target.x();input.schematicY=target.y();targetLayer=target.layer();
        }
        if(!stampHeld){
            if(buttons.rotation()!=0)input.rotatePlans(input.selectPlans,Integer.signum(buttons.rotation()));
            if(raw.keyTap(Binding.schematicFlipX))input.flipPlans(input.selectPlans,true);
            if(raw.keyTap(Binding.schematicFlipY))input.flipPlans(input.selectPlans,false);
        }
        if(!target.resolved()&&!stampHeld){status=target.reason();renderer.schematic(null,null,0);return true;}
        try{
            long next=signature(input.selectPlans,targetLayer,free);long now=Time.millis();
            if(next!=signature||revision!=runtime.grid().geometryRevision()||now-checked>200){
                stamp=LayerSchematic.stamp(input.selectPlans);signature=next;revision=runtime.grid().geometryRevision();checked=now;
                if(targetLayer>0)preview=runtime.pastes().preview(Vars.player,LayerActions.Action.paste(stamp,targetLayer,free?LayerComponent.Mode.FREE:LayerComponent.Mode.STRUCTURAL));
                else {boolean[] valid=new boolean[input.selectPlans.size];int ready=0;for(int i=0;i<valid.length;i++){var p=input.selectPlans.get(i);valid[i]=!LayerSchematic.platform(p)&&mindustry.world.Build.validPlace(p.block,Vars.player.team(),p.x,p.y,p.rotation);if(valid[i])ready++;}preview=new LayerPasteQueue.Preview(input.selectPlans,valid,new double[valid.length],ready,ready==valid.length?"":"Occupied or invalid native ground position");}
            }
            renderer.schematic(preview.plans(),preview.valid(),targetLayer);
            status="Paste Z"+targetLayer+" · "+preview.ready()+" / "+preview.plans().size+" valid"+(preview.reason().isEmpty()?"":" · "+preview.reason());
            if(begin&&!stampHeld){
                stampHeld=true;
                if(targetLayer==0){input.flushGroundSchematic();status="Added valid Z0 buildings to native build queue";}
                else {var result=runtime.requestPaste(stamp,targetLayer,free?LayerComponent.Mode.FREE:LayerComponent.Mode.STRUCTURAL);runtime.acceptResult(result);status=result.reason();}
            }
        }catch(IllegalArgumentException bad){status=bad.getMessage();renderer.schematic(null,null,0);}
        return true;
    }
    private static int[] point(Ray ray,double height){double d=Math.abs(ray.direction.y)<1e-8?-1:(height-ray.origin.y)/ray.direction.y;if(!Double.isFinite(d)||d<0)return null;return new int[]{(int)Math.floor(ray.origin.x+ray.direction.x*d+.5),(int)Math.floor(-ray.origin.z-ray.direction.z*d+.5)};}
    private static long signature(Seq<BuildPlan> plans,int layer,boolean free){long h=layer*31+(free?1:0);for(var p:plans)h=h*31+java.util.Objects.hash(p.x,p.y,p.rotation,p.block.id,LayerSchematic.platform(p),p.config);return h;}
}
