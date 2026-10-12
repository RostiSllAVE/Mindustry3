package fpv.construction;

import arc.Core;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.graphics.g3d.Camera3D;
import fpv.input.FpvDesktopInput;
import fpv.render.LayerWorldRenderer;
import mindustry.Vars;
import mindustry.input.Binding;
import java.util.HashSet;

/** Upper-layer gestures never enter vanilla BuildPlan, tileConfig or inventory packets. */
public final class LayerBuildController {
    public final KeyBind higher=KeyBind.add("fpv-layer-up",KeyCode.pageUp,"FPV construction");
    public final KeyBind lower=KeyBind.add("fpv-layer-down",KeyCode.pageDown,"FPV construction");
    private final LayerRuntime runtime;
    private final FpvDesktopInput input;
    private final LayerWorldRenderer renderer;
    private final LayerSchematicInput schematics;
    private final Vec2 pointer=new Vec2();
    private int layer,x,y;
    private boolean platform,free,valid,dragging;
    private String previewReason="";
    private final HashSet<Long> dragged=new HashSet<>();
    private long inputFrame=Long.MIN_VALUE;
    private long selectedId;
    private long bridgeSource;
    private long configSource;
    private boolean floorLocked;
    private boolean placementGesture;
    private boolean selectPressed,strokeRelease,strokeMoved,strokePlatform,strokeFree;
    private float strokeStartX,strokeStartY,strokePointerX,strokePointerY;
    private mindustry.world.Block strokeBlock;
    private MouseConstructionTarget strokeTarget;
    private boolean cancelButtonHeld;
    private BuildingMousePicker.Hit hover,mouseSource;
    private LayerVerticalTransfers.Kind riser;
    private LayerVerticalTransfers.Kind riserChoice=LayerVerticalTransfers.Kind.ITEMS;
    private String mouseReason="";
    public boolean floorLocked(){return floorLocked;}
    public void setFloorLocked(boolean value){floorLocked=value;cancel();}
    public boolean connecting(){return mouseSource!=null;}
    public BuildingMousePicker.Hit hovered(){return hover;}
    public void resolveHover(BuildingMousePicker.Hit hit){hover=hit;if(!floorLocked&&!dragging&&!placementGesture&&!platform&&hit!=null)resolveLayer(hit.layer());}
    public String riserChoice(){return "Shift-click riser: "+riserChoice.name();}
    public void cycleRiser(){riserChoice=riserChoice==LayerVerticalTransfers.Kind.ITEMS?LayerVerticalTransfers.Kind.LIQUIDS:riserChoice==LayerVerticalTransfers.Kind.LIQUIDS?LayerVerticalTransfers.Kind.POWER:LayerVerticalTransfers.Kind.ITEMS;cancelConnection();}
    public String hoverLabel(){
        if(removalTarget!=null)return "Dismantle: "+(removalTarget.block()==null?"Platform":removalTarget.block().localizedName)+" — Z"+removalTarget.layer()+"\n"+previewReason;
        if(hover!=null&&hover.block()!=null&&hover.building()==null)return hover.block().localizedName+" — Z"+hover.layer()+"\nClick to inspect";
        if(hover==null||hover.building()==null)return connecting()?"Choose destination — "+mouseReason:"";
        var b=hover.building();return b.block.localizedName+" — Z"+hover.layer()+"\n"+(connecting()?mouseReason:b.team!=Vars.player.team()?"Other team":MouseConnection.ordinarySource(b)?"Click source, then destination · RMB / Esc cancel":"Click to inspect/configure · Shift-click: "+riserChoice.name()+" riser");
    }
    public void cancelConnection(){mouseSource=null;riser=null;mouseReason="";input.cancelPointerGesture();renderer.interaction(hover,null,null,false);}
    public boolean cancelGesture(boolean deselect,boolean menu){
        if(!(deselect||menu)||!connecting()&&input.block==null&&!platform&&!removalGesture)return false;
        input.block=null;platform=false;removalGesture=false;pendingRemoval.clear();removed.clear();cancelConnection();cancel();placementGesture=false;cancelButtonHeld=deselect;input.setCancelConsumed(true);return true;
    }
    public void endInteraction(){schematics.suspend();placement=null;removalTarget=null;removalGesture=false;pendingRemoval.clear();removed.clear();hover=null;cancelConnection();cancel();placementGesture=false;cancelButtonHeld=false;input.setInteractionOwned(false);input.setCancelConsumed(false);renderer.interaction(null,null,null,false);}
    /** Same click boundary used by native-input update and isolated SDL integration checks. */
    public void interact(BuildingMousePicker.Hit hit,boolean shift){
        if(connecting()){
            var result=MouseConnection.submit(runtime,mouseSource,hit,riser);runtime.acceptResult(result);mouseReason=result.reason();
            if(result.accepted()||result.pending()){selectedId=hit.id();cancelConnection();}return;
        }
        selectedId=hit==null?0:hit.id();
        if(hit==null||hit.building()==null||hit.building().team!=Vars.player.team())return;
        if(!floorLocked)resolveLayer(hit.layer());
        if(shift||MouseConnection.ordinarySource(hit.building())){mouseSource=hit;riser=shift?riserChoice:null;input.cancelPointerGesture();}
        else if(hit.building().block.configurable)configureSelected();
    }
    public LayerBuildController(LayerRuntime runtime,FpvDesktopInput input,LayerWorldRenderer renderer){this.runtime=runtime;this.input=input;this.renderer=renderer;this.schematics=new LayerSchematicInput(runtime,input,renderer);}
    public int layer(){return layer;}
    public boolean platform(){return platform;}
    public boolean free(){return free;}
    public long selectedId(){return selectedId;}
    public String selectionLabel(){var c=runtime.grid().get(selectedId);var b=runtime.building(selectedId);return c!=null?c.blockName()+" / Z"+c.layer()+" / "+Math.round(c.health())+" HP":b!=null?b.block.localizedName+" / Z0 / "+Math.round(b.health)+" HP":"Select a building on the current floor";}
    public String inventoryLabel(){var b=runtime.building(selectedId);if(b==null)return "";StringBuilder text=new StringBuilder();
        if(b.items!=null)for(var item:Vars.content.items())if(b.items.get(item)>0)text.append(item.localizedName).append(": ").append(b.items.get(item)).append("  ");
        if(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild turret)for(var ammo:turret.ammo)
            if(ammo instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry item)text.append(item.item.localizedName).append(" ammo: ").append(item.amount).append("  ");
        if(b.liquids!=null)for(var liquid:Vars.content.liquids())if(b.liquids.get(liquid)>.01)text.append(liquid.localizedName).append(": ").append(Math.round(b.liquids.get(liquid))).append("  ");
        if(b.power!=null)text.append("Power: ").append(Math.round(b.power.status*100)).append('%');return text.toString();}
    public String cargoLabel(){if(Vars.player==null||Vars.player.dead())return "";var u=Vars.player.unit();return "Unit cargo: "+(u.stack.amount==0?"empty":u.stack.item.localizedName+" "+u.stack.amount)+" / "+u.type.itemCapacity;}
    public void deposit(){if(selectedId!=0)runtime.requestDeposit(selectedId);}
    public void withdraw(){
        var b=runtime.building(selectedId);if(b==null||b.items==null)return;long id=selectedId;
        var dialog=new mindustry.ui.dialogs.BaseDialog("Withdraw to unit cargo");dialog.addCloseButton();
        dialog.cont.pane(table->{for(var item:Vars.content.items())if(b.items.get(item)>0){
            table.image(item.uiIcon).size(24);table.label(()->item.localizedName+"  "+b.items.get(item)).width(180).left();
            table.button("1",()->runtime.requestWithdraw(id,item.id,1)).width(48);
            table.button("10",()->runtime.requestWithdraw(id,item.id,10)).width(48);
            table.button("Fill",()->runtime.requestWithdraw(id,item.id,1000)).width(65);table.row();
        }}).maxHeight(420);dialog.show();
    }
    public void configureSelected(){var b=runtime.building(selectedId);if(b==null||!b.block.configurable)return;
        // Ground configuration keeps the native UI and RPC; upper configure() routes to our authority packet.
        var dialog=new mindustry.ui.dialogs.BaseDialog(b.block.localizedName);dialog.addCloseButton();b.buildConfiguration(dialog.cont);dialog.show();}
    public void rememberConfigurationSource(){if(selectedId>0&&runtime.building(selectedId)!=null)configSource=selectedId;}
    public String configurationLinkLabel(){var b=runtime.building(configSource);return b==null?"Local links: processor / driver / bridge":"Link source: "+b.block.localizedName;}
    public void linkConfigurationTarget(){var from=runtime.grid().get(configSource);var to=runtime.grid().get(selectedId);if(from==null||to==null||from.layer()!=to.layer()){runtime.acceptResult(LayerActions.Result.rejected("Select source and target on the same floor; use cross-floor bridge controls for other floors"));return;}runtime.requestConfigure(configSource,arc.math.geom.Point2.pack(to.x(),to.y()));}
    public void rememberBridge(){var b=runtime.building(selectedId);if(LayerVerticalTransfers.isBridge(b))bridgeSource=selectedId;else runtime.acceptResult(LayerActions.Result.rejected("Select an item or liquid bridge first"));}
    public String bridgeLabel(){var b=runtime.building(bridgeSource);return b==null?"Cross-floor bridge: select source, then target":"Bridge source: "+b.block.localizedName+" / Z"+(bridgeSource>0?runtime.grid().get(bridgeSource).layer():0);}
    public void connectBridge(boolean remove){var source=runtime.building(bridgeSource);if(source==null||selectedId==0)return;var kind=source.block.hasLiquids?LayerVerticalTransfers.Kind.BRIDGE_LIQUIDS:LayerVerticalTransfers.Kind.BRIDGE_ITEMS;
        if(remove)runtime.requestUnlink(bridgeSource,selectedId,kind);else runtime.requestLink(bridgeSource,selectedId,kind);}
    public void rotateSelected(){var c=runtime.grid().get(selectedId);if(c!=null)runtime.requestRotate(c.id(),(c.rotation()+1)%4);}
    public String connectorTarget(){long below=lowerEndpoint();if(below==0)return "No aligned building below";var c=below>0?runtime.grid().get(below):null;return "Connector target: Z"+(c==null?0:c.layer());}
    public void connect(LayerVerticalTransfers.Kind kind,boolean up,boolean remove){long below=lowerEndpoint();if(below==0)return;long from=up?below:selectedId,to=up?selectedId:below;if(remove)runtime.requestUnlink(from,to,kind);else runtime.requestLink(from,to,kind);}
    private long lowerEndpoint(){
        var c=runtime.grid().get(selectedId);if(c==null||runtime.building(selectedId)==null)return 0;
        for(int floor=c.layer()-1;floor>0;floor--){var b=runtime.grid().at(c.x(),c.y(),floor,LayerComponent.Kind.BLOCK);if(b!=null&&runtime.building(b.id())!=null&&LayerCoordinates.center(c.x(),c.size())==LayerCoordinates.center(b.x(),b.size())&&LayerCoordinates.center(c.y(),c.size())==LayerCoordinates.center(b.y(),b.size()))return b.id();}
        var tile=Vars.world.tile(c.x(),c.y());var building=tile==null?null:tile.build;
        if(building==null||building.x/Vars.tilesize!=LayerCoordinates.center(c.x(),c.size())||building.y/Vars.tilesize!=LayerCoordinates.center(c.y(),c.size()))return 0;
        return LayerVerticalTransfers.groundId(building.tile.x,building.tile.y);
    }
    public String label(){return "Z"+layer+(layer==0?" / native world":" / "+(free?"FREE":"STRUCTURAL"));}
    public String status(){
        String reason=schematics.active()?schematics.status():previewReason.isBlank()?runtime.status():previewReason;
        if(!Boolean.getBoolean("fpv.picking.diagnostics")||hover==null)return reason;
        return reason+"\nhitXYZ="+hover.x()+","+hover.y()+","+hover.z()+" normal="+hover.nx()+","+hover.ny()+","+hover.nz()+" surface="+hover.surface()+" buildingLayer="+hover.layer()+" targetLayer="+(placement==null?hover.constructionLayer():placement.layer())+" meshTopZ="+hover.bounds().maxZ()+" validPlacement="+valid;
    }
    public void setLayer(int value){cancel();floorLocked=true;resolveLayer(value);cancelConnection();}
    private void resolveLayer(int value){int next=Math.max(0,Math.min(runtime.grid().coordinates().maxLayers(),value));if(next==layer)return;cancel();layer=next;input.cancelPointerGesture();}
    public void setPlatform(boolean value){platform=value;cancel();cancelConnection();}
    public void setFree(boolean value){free=value&&runtime.allowedFree();cancel();}
    public void reset(){resolveLayer(0);floorLocked=false;free=false;platform=false;selectedId=0;bridgeSource=0;configSource=0;endInteraction();}
    private void cancel(){dragging=false;dragged.clear();placementGesture=false;strokeRelease=false;strokeTarget=null;strokeBlock=null;}
    public record Buttons(boolean selectTap,boolean selectHeld,boolean breakTap,boolean breakHeld,boolean cancelTap,boolean menuTap,boolean control,boolean shift,int rotation,boolean ui,boolean selectReleased){
        public Buttons(boolean selectTap,boolean selectHeld,boolean breakTap,boolean breakHeld,boolean cancelTap,boolean menuTap,boolean control,boolean shift,int rotation,boolean ui){this(selectTap,selectHeld,breakTap,breakHeld,cancelTap,menuTap,control,shift,rotation,ui,!selectTap&&!selectHeld);}
    }
    private MouseConstructionTarget placement;
    private BuildingMousePicker.Hit removalTarget;
    private boolean removalGesture;
    private float lastRemoveScreenX,lastRemoveScreenY;
    private final HashSet<Long> removed=new HashSet<>();
    private final java.util.LinkedHashMap<Long,BuildingMousePicker.Hit> pendingRemoval=new java.util.LinkedHashMap<>();
    public MouseConstructionTarget placementTarget(){return placement;}
    public BuildingMousePicker.Hit removalTarget(){return removalTarget;}
    public boolean possessionAvailable(){return input.block==null&&!platform&&!schematics.active();}
    public String dismantleBindingLabel(){var key=Binding.breakBlock.value;return "Dismantle: "+(key.key==null?Binding.breakBlock.name:key.key.toString())+" / visible object on any floor";}
    public void update(Camera3D camera,boolean active,boolean suspended,fpv.render.FpvWorldRenderer.PickResult ground){
        if(!active||suspended){input.setUpperLayerOwned(false);if(input.isBuildMode()||hover!=null||connecting())endInteraction();selectPressed=input.originalInput().keyDown(Binding.select);placement=null;removalTarget=null;removalGesture=false;renderer.preview(false,layer,0,0,1,false,false);return;}
        long frame=Core.graphics.getFrameId();if(inputFrame==frame)return;inputFrame=frame;
        var keys=input.originalInput();
        if(keys.keyTap(higher))setLayer(layer+1);
        if(keys.keyTap(lower))setLayer(layer-1);
        processPointer(camera,ground,keys.mouseX(),keys.mouseY(),new Buttons(keys.keyTap(Binding.select),keys.keyDown(Binding.select),keys.keyTap(Binding.breakBlock),keys.keyDown(Binding.breakBlock),keys.keyTap(Binding.deselect),keys.keyTap(Binding.menu),keys.keyDown(Binding.control),keys.keyDown(KeyCode.shiftLeft)||keys.keyDown(KeyCode.shiftRight),(int)keys.axisTap(Binding.rotate),interfaceOwnsPointer(),keys.keyRelease(Binding.select)));
    }
    /** Shared native input boundary; scripted SDL tests feed registered-binding states here without OS automation. */
    public void processPointer(Camera3D camera,fpv.render.FpvWorldRenderer.PickResult ground,float sx,float sy,Buttons keys){
        // A press is one lifecycle, even if the backend tap flag is sampled more than once.
        // Keep native Z0 ownership through the release poll so DesktopInput can flush its line.
        if(strokeRelease){cancel();selectPressed=false;}
        // An up/down pair can arrive in one poll. Its release belongs to the old press;
        // a tap without that up edge remains a repeated sample of the current press.
        if(selectPressed&&keys.selectReleased()&&keys.selectTap()){cancel();selectPressed=false;}
        boolean begin=keys.selectTap()&&!selectPressed;
        selectPressed=keys.selectHeld()||keys.selectTap();
        if(!runtime.allowedFree())free=false;
        if(placementGesture&&(input.block!=strokeBlock||platform!=strokePlatform||free!=strokeFree)){cancel();input.cancelPointerGesture();}
        if(!keys.cancelTap()&&!keys.breakHeld()&&!input.originalInput().keyDown(Binding.deselect))cancelButtonHeld=false;
        input.setCancelConsumed(cancelButtonHeld);
        camera.update();var ray=camera.getPickRay(sx,sy);
        hover=keys.ui()?null:BuildingMousePicker.pick(runtime,ray,camera.far/Math.max(.001,ray.direction.dot(camera.direction)),ground);
        if(schematics.process(hover,ray,layer,floorLocked,free,begin,keys)){
            if(!floorLocked&&schematics.active())resolveLayer(schematics.layer());
            if(connecting())cancelConnection();renderer.interaction(null,null,null,false);
            placement=null;removalTarget=null;removalGesture=false;pendingRemoval.clear();removed.clear();cancel();return;
        }
        if(mouseSource!=null&&!BuildingMousePicker.present(runtime,mouseSource))cancelConnection();
        if(connecting()&&(keys.control()||input.block!=null||platform))cancelConnection();
        boolean cancelled=(!keys.ui()||connecting())&&cancelGesture(keys.cancelTap(),keys.menuTap());
        if(!keys.breakHeld()){
            if(removalGesture&&!cancelled&&!keys.ui()&&!keys.menuTap())for(var candidate:pendingRemoval.values())dismantle(candidate);
            removalGesture=false;pendingRemoval.clear();removed.clear();
        }
        boolean possession=keys.control()&&keys.selectTap()&&possessionAvailable();
        if(possession){removalGesture=false;pendingRemoval.clear();removed.clear();}
        // Cancellation has priority over the overlapping native RMB dismantle binding.
        if(!cancelled&&!cancelButtonHeld&&!keys.ui()&&!possession&&keys.breakTap())removalGesture=true;
        boolean removing=removalGesture&&keys.breakHeld();
        input.setInteractionOwned(cancelled||cancelButtonHeld||removing||possession||connecting()||input.block==null&&!platform);
        removalTarget=null;placement=null;previewReason="";
        if(removing){
            cancel();
            removalTarget=hover;var result=previewRemoval(hover);previewReason=result.accepted()?"Release dismantle button to confirm; Escape cancels":result.reason();
            renderer.preview(false,layer,0,0,1,false,false);renderer.removalPreview(hover,result.accepted());
            if(result.accepted()&&(removed.isEmpty()||Math.hypot(sx-lastRemoveScreenX,sy-lastRemoveScreenY)>4)&&removed.add(hover.id())){
                if(pendingRemoval.size()<256)pendingRemoval.put(hover.id(),hover);lastRemoveScreenX=sx;lastRemoveScreenY=sy;
            }
            return;
        }
        renderer.interaction(hover!=null&&(hover.block()!=null||hover.platform())?hover:null,mouseSource,hover,false);
        if(cancelled||cancelButtonHeld||keys.ui()||possession){renderer.preview(false,layer,0,0,1,false,false);cancel();return;}
        if(input.block==null&&!platform){
            if(!floorLocked&&hover!=null)resolveLayer(hover.layer());
            if(keys.selectTap())interact(hover,keys.shift());
            var plan=connecting()?MouseConnection.preview(runtime,mouseSource,hover,riser):null;
            mouseReason=plan==null?"":plan.reason();renderer.interaction(hover!=null&&(hover.block()!=null||hover.platform())?hover:null,mouseSource,hover,plan!=null&&plan.valid());
            renderer.preview(false,layer,0,0,1,false,false);input.setUpperLayerOwned(layer>0);return;
        }
        if(placementGesture){
            if(!strokeMoved&&Math.hypot(sx-strokeStartX,sy-strokeStartY)>=3)strokeMoved=true;
            if(strokeMoved&&(sx!=strokePointerX||sy!=strokePointerY)){
                // Reuse the existing plane resolver. Newly constructed faces cannot move
                // this press upward or outward; its floor is pinned until release.
                strokeTarget=MouseConstructionTarget.resolve(hover,ray,runtime.grid().coordinates(),input.block,platform,true,layer);
                strokePointerX=sx;strokePointerY=sy;
            }
            placement=strokeTarget;
        }else placement=MouseConstructionTarget.resolve(hover,ray,runtime.grid().coordinates(),input.block,platform,floorLocked,layer);
        if(placement.resolved()){
            if(placement.layer()!=layer)resolveLayer(placement.layer());
            x=placement.x();y=placement.y();
        }
        input.setUpperLayerOwned(layer>0);
        // Native Z0 input rotates once itself. Upper gestures rotate through the same registered binding.
        if(layer>0&&keys.rotation()!=0)input.rotation=Math.floorMod(input.rotation+keys.rotation(),4);
        if(begin&&placement.resolved()){
            placementGesture=true;dragging=layer>0;dragged.clear();strokeTarget=placement;strokeBlock=input.block;strokePlatform=platform;strokeFree=free;
            strokeStartX=strokePointerX=sx;strokeStartY=strokePointerY=sy;strokeMoved=false;
        }
        // An interrupted/invalid press cannot hand its held button to the other builder.
        if(selectPressed&&!placementGesture&&!begin)input.setInteractionOwned(true);
        if(placementGesture&&!keys.selectHeld())strokeRelease=true;
        int size=platform||input.block==null?1:input.block.size;
        boolean picked=placement.resolved()&&x>=0&&y>=0&&x<Vars.world.width()&&y<Vars.world.height();
        if(layer==0){
            renderer.preview(false,0,0,0,1,false,false);
            if(platform){previewReason="Platforms need an elevated surface or a locked upper floor";input.setInteractionOwned(true);}
            else if(!picked)previewReason=placement.resolved()?"Outside map bounds":placement.reason();
            else if(!input.validPlace(x,y,input.block,input.rotation)){var tile=Vars.world.tile(x,y);previewReason=tile!=null&&tile.block()!=mindustry.content.Blocks.air?"Native Z0 placement denied on occupied tile: "+tile.block().localizedName:"Native Z0 build rule prevents placement here";}
            return;
        }
        valid=false;
        if(picked){var result=runtime.preview(Vars.player,action(x,y));valid=result.accepted();previewReason=valid?"":result.reason();}
        else previewReason=placement.resolved()?"Outside map bounds":placement.reason();
        renderer.preview(true,layer,x,y,size,picked,valid,input.block,input.rotation,platform);
        if(dragging&&(begin||keys.selectHeld())&&picked){
            // Paint only the visible resolved target; interpolating hidden cells can dismantle/build another floor.
            apply(x,y);
        }
    }
    public LayerActions.Result previewRemoval(BuildingMousePicker.Hit hit){
        if(hit==null||hit.id()==0||!BuildingMousePicker.present(runtime,hit))return LayerActions.Result.rejected("Aim at a visible building or platform to dismantle");
        return hit.id()>0?runtime.preview(Vars.player,LayerActions.Action.remove(hit.id())):runtime.previewGroundRemoval(Vars.player,hit.building());
    }
    public LayerActions.Result dismantle(BuildingMousePicker.Hit hit){
        var result=previewRemoval(hit);if(!result.accepted()){runtime.acceptResult(result);return result;}
        if(hit.id()>0){var c=runtime.grid().get(hit.id());result=runtime.requestRemove(hit.id());if(result.accepted()||result.pending())renderer.constructionActivity(Vars.player.unit(),c.x(),c.y(),c.layer(),c.size(),true);}
        else {input.tryBreakBlock(hit.building().tileX(),hit.building().tileY());result=new LayerActions.Result(true,false,"Native dismantling plan queued — Z0",0);}
        runtime.acceptResult(result);return result;
    }
    private LayerActions.Action action(int tx,int ty){return new LayerActions.Action(LayerActions.Op.PLACE,tx,ty,layer,
        platform?LayerComponent.Kind.PLATFORM:LayerComponent.Kind.BLOCK,platform?"fpv-platform":input.block==null?"":input.block.name,input.rotation,
        free?LayerComponent.Mode.FREE:LayerComponent.Mode.STRUCTURAL,0);}
    private void apply(int tx,int ty){
        long key=((long)tx<<32)^(ty&0xffffffffL);if(!dragged.add(key))return;
        if(platform||input.block!=null){int size=platform?1:input.block.size;var result=runtime.requestPlace(tx,ty,layer,platform?"fpv-platform":input.block.name,platform,input.rotation,free?LayerComponent.Mode.FREE:LayerComponent.Mode.STRUCTURAL);if(result.accepted()||result.pending())renderer.constructionActivity(Vars.player.unit(),tx,ty,layer,size,false);}
    }
    private boolean interfaceOwnsPointer(){
        Core.scene.screenToStageCoordinates(pointer.set(input.originalInput().mouseX(),input.originalInput().mouseY()));
        return Core.scene.hasDialog()||Core.scene.hasKeyboard()||Core.scene.hit(pointer.x,pointer.y,true)!=null;
    }
}
