package fpv;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.entities.units.BuildPlan;

/** Actual registered-binding state boundary and native build execution on a disposable SDL map. */
final class AutomaticConstructionSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.placement.smoke"))throw new IllegalStateException("Explicit construction lab required");
        var runtime=mod.construction;var layers=mod.layerBuild;var input=mod.input;var unit=Vars.player.unit();
        if(runtime.grid().size()!=0)throw new IllegalStateException("Empty disposable upper world required");
        boolean infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor,instant=Vars.state.rules.instantBuild,oldBuild=mod.buildMode;
        boolean oldFree=Core.settings.getBool(LayerRuntime.FREE_PERMISSION,false);float delta=Time.delta,px=unit.x,py=unit.y;var block=input.block;int rotation=input.rotation;var plans=new arc.struct.Queue<BuildPlan>();for(var p:unit.plans())plans.addLast(p);unit.clearBuilding();checks=0;
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=60;camera.near=.04f;camera.far=100;camera.up.set(0,1,0);
        try{
            Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.rules.instantBuild=true;Time.delta=1;unit.updateBuilding(true);unit.set(35*8,35*8);mod.setBuildMode(true);layers.reset();
            input.block=Blocks.conveyor;input.rotation=2;
            step(mod,camera,34,6,34,34,0,34,buttons(true,true,false,false,false,false,false,0));
            var ground=layers.placementTarget();require(ground.resolved()&&ground.layer()==0&&ground.x()==34&&ground.y()==34,"automatic ground anchor uses visible XYZ surface");
            input.setAimWorld(ground.x()*8+input.block.offset,ground.y()*8+input.block.offset,true);require(input.pickedTile()==Vars.world.tile(34,34),"resolved anchor maps to native Z0 preview without double offset");
            unit.addBuild(new BuildPlan(ground.x(),ground.y(),input.rotation,input.block));execute(unit);
            require(Vars.world.tile(34,34).block()==Blocks.conveyor&&Vars.world.build(34,34).rotation==2,"actual native Z0 placement and rotation execute");
            input.block=null;step(mod,camera,34,6,34,34,0,34,buttons(false,false,true,true,true,false,false,0));
            require(layers.removalTarget()!=null&&layers.removalTarget().layer()==0&&unit.plans().size==0,"RMB previews native ground removal before committing");release(mod,camera);require(unit.plans().size>0,"release submits the native ground removal plan");execute(unit);require(Vars.world.tile(34,34).block()==Blocks.air,"actual native queued Z0 dismantling executes");
            release(mod,camera);
            Vars.world.tile(40,40).setBlock(Blocks.titaniumWall,Team.sharded,0);
            require(runtime.requestPlace(40,40,1,"",true,0,LayerComponent.Mode.STRUCTURAL).accepted(),"real supported Z1 platform");
            require(runtime.requestPlace(40,40,1,"titanium-wall",false,0,LayerComponent.Mode.STRUCTURAL).accepted(),"real supported column");
            layers.setPlatform(true);layers.setFloorLocked(false);
            step(mod,camera,40,8,40,40,0,40,buttons(true,true,false,false,false,false,false,0));
            require(layers.placementTarget().layer()==2&&runtime.grid().at(40,40,2,LayerComponent.Kind.PLATFORM)!=null,"wall cap automatically grows next floor with real support validation; target="+layers.placementTarget()+"; hover="+layers.hovered()+"; reason="+layers.status());
            release(mod,camera);input.block=Blocks.conveyor;layers.setPlatform(false);input.rotation=0;
            step(mod,camera,40,8,40,40,0,40,buttons(true,true,false,false,false,false,false,1));
            var upper=runtime.grid().at(40,40,2,LayerComponent.Kind.BLOCK);
            require(upper!=null&&upper.rotation()==1&&runtime.building(upper.id()).block==Blocks.conveyor,"actual automatic Z2 placement retains rotated native block");
            release(mod,camera);input.block=Blocks.conveyor;
            step(mod,camera,40,8,40,40,0,40,buttons(true,true,false,false,false,false,false,0));
            require(runtime.grid().at(40,40,2,LayerComponent.Kind.BLOCK).id()==upper.id()&&!layers.status().isBlank(),"occupied surface gives invalid reason rather than relocating");
            release(mod,camera);input.block=null;layers.setLayer(1);
            step(mod,camera,40,8,40,40,0,40,buttons(false,false,true,true,true,false,false,0));
            require(runtime.grid().get(upper.id())!=null&&layers.removalTarget().layer()==2,"dismantling preview ignores manual floor lock and targets visible Z2 instance");
            int size=runtime.grid().size();step(mod,camera,40,8,40,40,0,40,buttons(false,false,false,true,false,false,false,0));
            require(runtime.grid().size()==size,"holding removal only previews geometry");release(mod,camera);require(runtime.grid().get(upper.id())==null&&runtime.grid().at(40,40,1,LayerComponent.Kind.BLOCK)!=null&&Vars.world.tile(40,40).block()==Blocks.titaniumWall,"release removes only intended stacked Z2 instance");
            layers.setFloorLocked(false);layers.setPlatform(true);
            step(mod,camera,43,2.72,40,40,2.72,40,buttons(true,true,false,false,false,false,false,0));
            require(layers.placementTarget().layer()==2&&layers.placementTarget().x()==41&&runtime.grid().at(41,40,2,LayerComponent.Kind.PLATFORM)!=null,"exposed slab edge expands to the adjacent supported cell on Z2");
            release(mod,camera);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
            require(runtime.requestPlace(48,40,2,"",true,0,LayerComponent.Mode.FREE).accepted(),"isolated Free fixture authorized by host");Core.settings.put(LayerRuntime.FREE_PERMISSION,false);
            layers.setPlatform(false);input.block=Blocks.conveyor;
            step(mod,camera,48,8,40,48,0,40,buttons(true,true,false,false,false,false,false,0));
            require(runtime.grid().at(48,40,2,LayerComponent.Kind.BLOCK)==null&&layers.status().contains("Unsupported"),"Free slab does not bypass Structural support limits");
            release(mod,camera);layers.setFree(true);require(!layers.free(),"unauthorized Free mode refused");Core.settings.put(LayerRuntime.FREE_PERMISSION,true);layers.setFree(true);
            step(mod,camera,48,8,40,48,0,40,buttons(true,true,false,false,false,false,false,0));
            require(runtime.grid().at(48,40,2,LayerComponent.Kind.BLOCK).mode()==LayerComponent.Mode.FREE,"authorized Free placement executes on the actual surface");release(mod,camera);
            input.block=null;var freeBlock=runtime.grid().at(48,40,2,LayerComponent.Kind.BLOCK);
            step(mod,camera,48,8,40,48,0,40,buttons(false,false,true,true,true,false,false,0));
            require(runtime.grid().get(freeBlock.id())!=null&&layers.hoverLabel().contains("Dismantle"),"hold provides a visible dismantling preview before removal");
            step(mod,camera,48,8,40,48,0,40,buttons(false,false,false,true,false,true,false,0));release(mod,camera);
            require(runtime.grid().get(freeBlock.id())!=null,"Escape cancels pending dismantling without damage");
            input.block=Blocks.conveyor;int before=runtime.grid().size();
            step(mod,camera,48,8,40,48,0,40,buttons(false,false,true,true,true,false,false,0));
            require(input.block==null&&runtime.grid().size()==before,"RMB clears placement without dismantling the block behind it");
            step(mod,camera,48,8,40,48,0,40,buttons(false,false,false,true,false,false,false,0));require(runtime.grid().size()==before,"held cancellation cannot become dismantling");release(mod,camera);
            step(mod,camera,48,8,40,48,0,40,buttons(true,true,false,false,false,false,true,0));require(runtime.grid().size()==before&&layers.removalTarget()==null,"Ctrl+LMB possession does not dismantle buildings");
            input.block=Blocks.conveyor;step(mod,camera,48,8,40,48,0,40,buttons(false,false,false,false,false,true,false,0));require(input.block==null,"Escape cancels placement");
            mod.setBuildMode(false);require(!input.isBuildMode()&&layers.placementTarget()==null&&layers.removalTarget()==null,"FPV exit clears construction state");mod.setBuildMode(true);layers.reset();
            input.block=null;var support=runtime.grid().at(40,40,1,LayerComponent.Kind.BLOCK);
            // Use the shared visible side ray to remove the actual load-bearing column.
            step(mod,camera,38,1.8,40,40,1.8,40,buttons(false,false,true,true,true,false,false,0));
            release(mod,camera);require(runtime.grid().get(support.id())==null,"visible Z1 support dismantled on release");runtime.update(10);
            require(!runtime.falling().isEmpty()||runtime.grid().at(40,40,2,LayerComponent.Kind.PLATFORM)==null||runtime.warning(runtime.grid().at(40,40,2,LayerComponent.Kind.PLATFORM).id()),"normal structural warning/collapse remains active after support removal");
            runtime.reset();layers.reset();input.block=null;
            Vars.world.tile(48,46).setBlock(Blocks.titaniumWallLarge,Team.sharded,0);
            input.block=Blocks.titaniumWallLarge;layers.setFloorLocked(false);
            step(mod,camera,48.5,12,46.5,48.5,0,46.5,buttons(true,true,false,false,false,false,false,0));
            var tall=runtime.grid().at(48,46,1,LayerComponent.Kind.BLOCK);
            require(tall!=null&&tall.height()==2.8,"large ground wall top supports tall Z1 wall without embedding");release(mod,camera);
            step(mod,camera,48.5,12,46.5,48.5,0,46.5,buttons(true,true,false,false,false,false,false,0));
            var stack=runtime.grid().at(48,46,3,LayerComponent.Kind.BLOCK);
            require(stack!=null&&layers.placementTarget().layer()==3,"large wall top advances by actual two-storey solid height");release(mod,camera);
            require(!runtime.requestPlace(48,46,2,"conveyor",false,0,LayerComponent.Mode.STRUCTURAL).accepted(),"locked placement cannot put a conveyor inside a tall wall");
            input.block=Blocks.conveyor;input.rotation=0;
            step(mod,camera,48,12,46,48,0,46,buttons(false,false,false,false,false,false,false,1));
            var surface=layers.hovered();var target=layers.placementTarget();
            require(surface.surface()==BuildingMousePicker.Surface.TOP&&surface.nz()==1&&surface.nx()==0&&surface.ny()==0&&surface.layer()==3&&surface.constructionLayer()==5&&surface.supportsConstruction(),"exact wall-top normal, instance and target floor metadata");
            require(target.resolved()&&target.layer()==5&&target.x()==48&&input.rotation==1&&runtime.preview(Vars.player,LayerActions.Action.place(target.x(),target.y(),target.layer(),Blocks.conveyor.name,false,input.rotation,LayerComponent.Mode.STRUCTURAL)).accepted(),"rotated conveyor preview valid at exact aligned wall top");
            step(mod,camera,48,12,46,48,0,46,buttons(true,true,false,false,false,false,false,0));
            var conveyor=runtime.grid().at(48,46,5,LayerComponent.Kind.BLOCK);
            require(conveyor!=null&&conveyor.rotation()==1&&Math.abs(runtime.grid().coordinates().bounds(stack).maxZ()-runtime.grid().coordinates().bounds(conveyor).minZ())<1e-7,"actual server placement matches preview and conveyor sits above solid cap");release(mod,camera);
            String wallVisuals=fpv.render.LayerWallVisualChecks.run(runtime,conveyor.id());
            input.block=null;
            step(mod,camera,45,5,46,48,5,46,buttons(false,false,false,false,false,false,false,0));
            require(layers.hovered().id()==stack.id()&&layers.hovered().surface()==BuildingMousePicker.Surface.SIDE&&layers.hovered().nx()==-1&&layers.hovered().nz()==0,"side face targets same exact tall component with horizontal normal");
            layers.setLayer(1);
            step(mod,camera,48,12,46,48,0,46,buttons(false,false,true,true,true,false,false,0));
            require(layers.removalTarget().id()==conveyor.id()&&layers.removalTarget().layer()==5,"locked lower floor never removes hidden wall under top conveyor");release(mod,camera);
            require(runtime.grid().get(conveyor.id())==null&&runtime.grid().get(stack.id())!=null&&runtime.grid().get(tall.id())!=null,"top dismantling preserves both stacked supporting walls");
            return "PASS: "+checks+" actual SDL scripted construction checks; "+wallVisuals+"; native Z0 build/break execution, Z1/Z2 structural placement/edge expansion, tall Z1/Z3 wall stack and Z5 conveyor, rotation, occupied/unsupported/Free validation, exact stacked removal, cancellation/hold/Control/FPV and support consequences. Physical input not automated.";
        }finally{layers.reset();runtime.reset();for(int[] p:new int[][]{{34,34},{40,40},{48,46}})Vars.world.tile(p[0],p[1]).setAir();unit.clearBuilding();for(var p:plans)unit.plans().addLast(p);input.block=block;input.rotation=rotation;unit.set(px,py);mod.setBuildMode(oldBuild);mod.resetPose();Vars.state.rules.infiniteResources=infinite;Vars.state.rules.editor=editor;Vars.state.rules.instantBuild=instant;Core.settings.put(LayerRuntime.FREE_PERMISSION,oldFree);Time.delta=delta;}
    }
    private static void execute(mindustry.gen.Unit unit){for(int i=0;i<400&&unit.plans().size>0;i++)unit.updateBuildLogic();}
    private static LayerBuildController.Buttons buttons(boolean tap,boolean held,boolean removeTap,boolean removeHeld,boolean cancel,boolean menu,boolean ctrl,int rotation){return new LayerBuildController.Buttons(tap,held,removeTap,removeHeld,cancel,menu,ctrl,false,rotation,false);}
    private static void release(FpvMod mod,Camera3D camera){step(mod,camera,35,8,35,35,0,35,buttons(false,false,false,false,false,false,false,0));}
    private static void step(FpvMod mod,Camera3D c,double x,double z,double y,double tx,double tz,double ty,LayerBuildController.Buttons buttons){c.position.set((float)x,(float)z,(float)-y);c.lookAt((float)tx,(float)tz,(float)-ty);c.update();float sx=c.width/2,sy=c.height/2;mod.layerBuild.processPointer(c,mod.renderer3d.pickInteraction(c,sx,sy),sx,sy,buttons);}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Automatic construction SDL: "+label);}
}
