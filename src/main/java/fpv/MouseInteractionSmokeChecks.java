package fpv;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.input.KeyCode;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.gen.Building;

/** Real SDL scene and native UI, using the same hover/click API boundary as physical mouse input. */
final class MouseInteractionSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.mouse.smoke"))throw new IllegalStateException("Explicit mouse lab required");
        var runtime=mod.construction;var layers=mod.layerBuild;var input=mod.input;
        if(runtime.grid().size()!=0||!runtime.transfers().links().isEmpty())throw new IllegalStateException("Mouse smoke requires an empty disposable upper world");
        var block=input.block;int rotation=input.rotation;boolean oldBuild=mod.buildMode;
        float px=Vars.player.unit().x,py=Vars.player.unit().y;boolean infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor;
        int[][] sites={{22,24},{24,24},{30,24},{32,24}};checks=0;
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=60;camera.near=.04f;camera.far=100;camera.up.set(0,1,0);
        try{
            Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.player.unit().set(24*8,24*8);mod.setBuildMode(true);input.block=null;layers.reset();
            Vars.world.tile(24,24).setBlock(Blocks.itemBridge,Team.sharded,0);Building ground=Vars.world.build(24,24);
            runtime.grid().add(component(9001,24,24,2,Blocks.itemBridge));
            var top=pick(mod,camera,24,10,24,24,0,24);require(top!=null&&top.id()==9001&&top.layer()==2,"same XY picks visible Z2 building");
            layers.resolveHover(top);require(layers.layer()==2&&layers.hoverLabel().contains("Z2"),"hover resolves floor and contextual name");
            var low=pick(mod,camera,22,.15,24,24,.15,24);require(low!=null&&low.building()==ground&&low.layer()==0,"side ray under elevated object selects Z0 instance");
            Vars.world.tile(22,24).setBlock(Blocks.copperWall,Team.sharded,0);
            var blocked=pick(mod,camera,20,.15,24,24,.15,24);require(blocked!=null&&blocked.building()==Vars.world.build(22,24),"solid Z0 geometry prevents selecting the building behind it");
            double high=runtime.planeHeight(2)+runtime.grid().get(9001).height()*.5;
            var over=pick(mod,camera,20,high,24,24,high,24);require(over!=null&&over.id()==9001,"holographic wall region does not mask an elevated building");
            Vars.world.tile(22,24).setBlock(Blocks.shrubs);var throughDecoration=pick(mod,camera,20,.15,24,24,.15,24);require(throughDecoration!=null&&throughDecoration.building()==ground,"decorative objects do not become building selections");Vars.world.tile(22,24).setAir();
            layers.resolveHover(low);layers.interact(low,false);require(layers.connecting(),"native mouse source starts connection without a menu");
            require(!input.touchDown(0,0,0,KeyCode.mouseLeft),"selection never reaches native placement callback");
            layers.resolveHover(top);layers.interact(top,false);require(!layers.connecting()&&runtime.transfers().links().stream().anyMatch(l->l.to()==9001),"destination click submits existing cross-floor bridge link");
            int count=runtime.transfers().links().size();layers.interact(low,false);require(layers.cancelGesture(true,false),"RMB cancellation consumes the gesture");require(!layers.connecting()&&runtime.transfers().links().size()==count,"cancel leaves transport and structures unchanged");
            require(input.mode==mindustry.input.PlaceMode.none&&runtime.grid().size()==1,"cancel clears native gesture without removing the hovered structure");
            layers.interact(low,false);require(layers.cancelGesture(false,true)&&!layers.connecting(),"Escape/menu binding cancels unfinished connection");
            layers.interact(low,false);mod.setBuildMode(false);require(!layers.connecting()&&!input.isBuildMode(),"leaving Build restores flight interaction state");mod.setBuildMode(true);
            runtime.grid().add(component(9002,28,24,1,Blocks.powerNode));runtime.grid().add(component(9003,28,24,3,Blocks.powerNode));
            var first=hit(runtime,9002);var second=hit(runtime,9003);layers.interact(first,false);layers.interact(second,false);
            require(runtime.transfers().links().stream().anyMatch(l->l.kind()==LayerVerticalTransfers.Kind.POWER&&l.to()==9003),"mouse power riser reaches existing native graph registry");
            runtime.grid().add(new LayerComponent(9004,LayerComponent.Kind.PLATFORM,26,24,2,LayerCatalog.PLATFORM,1,0,Team.sharded.id,LayerComponent.Mode.FREE,100,100,1,.16,LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]));
            var slab=pick(mod,camera,26,10,24,26,0,24);require(slab!=null&&slab.platform()&&slab.layer()==2,"platform top supplies correct placement floor");
            layers.setLayer(1);layers.resolveHover(slab);require(layers.layer()==1&&layers.floorLocked(),"manual floor lock is respected");layers.setFloorLocked(false);layers.resolveHover(slab);require(layers.layer()==2,"unlock restores automatic platform detection");
            input.block=Blocks.conveyor;input.rotation=3;layers.resolveHover(slab);require(input.block==Blocks.conveyor&&input.rotation==3,"auto floor detection preserves selected block and rotation");input.block=null;
            var empty=pick(mod,camera,34,4,24,34,0,24);layers.resolveHover(empty);require(empty!=null&&empty.id()==0&&layers.layer()==0,"visible empty ground returns automatic placement to Z0");
            Vars.world.tile(30,24).setBlock(Blocks.sorter,Team.sharded,0);var sorter=pick(mod,camera,30,4,24,30,0,24);require(sorter!=null&&sorter.building().block==Blocks.sorter,"ordinary configurable native building picked");
            layers.interact(sorter,false);require(Core.scene.hasDialog(),"ordinary click opens native configuration UI");while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            sorter.building().configure(mindustry.content.Items.lead);require(((mindustry.world.blocks.distribution.Sorter.SorterBuild)sorter.building()).sortItem==mindustry.content.Items.lead,"ordinary native configuration actually changes filter");
            layers.configureSelected();require(Core.scene.hasDialog(),"advanced configure fallback still works");while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            Vars.world.tile(32,24).setBlock(Blocks.powerNode,Team.sharded,0);Vars.world.tile(30,24).setBlock(Blocks.powerNode,Team.sharded,0);
            var nodeA=pick(mod,camera,30,4,24,30,0,24);var nodeB=pick(mod,camera,32,4,24,32,0,24);layers.interact(nodeA,false);layers.interact(nodeB,false);
            require(nodeA.building().power.links.contains(nodeB.building().pos()),"Z0 node click uses functioning native configure RPC");
            layers.interact(low,false);layers.rememberBridge();layers.interact(top,false);layers.rememberBridge();require(layers.bridgeLabel().contains("Z2"),"advanced source fallback retains exact selected floor");
            return "PASS: "+checks+" real SDL/native UI checks; Z0/Z2/same XY, automatic platform floor and manual lock, direct bridge/power links, native configuration, cancellation/flight restore and advanced fallback. Input via API; physical mouse not automated.";
        }finally{
            while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            layers.reset();runtime.reset();for(var site:sites)Vars.world.tile(site[0],site[1]).setAir();
            input.block=block;input.rotation=rotation;Vars.player.unit().set(px,py);mod.setBuildMode(oldBuild);mod.resetPose();Vars.state.rules.infiniteResources=infinite;Vars.state.rules.editor=editor;
        }
    }
    private static BuildingMousePicker.Hit pick(FpvMod mod,Camera3D camera,double ax,double az,double ay,double bx,double bz,double by){camera.position.set((float)ax,(float)az,(float)-ay);camera.lookAt((float)bx,(float)bz,(float)-by);camera.update();float sx=camera.width/2,sy=camera.height/2;return BuildingMousePicker.pick(mod.construction,camera.getPickRay(sx,sy),camera.far,mod.renderer3d.pickInteraction(camera,sx,sy));}
    private static BuildingMousePicker.Hit hit(LayerRuntime runtime,long id){var c=runtime.grid().get(id);var b=runtime.grid().coordinates().bounds(c);return new BuildingMousePicker.Hit(id,runtime.building(id),c.layer(),b.centerX(),b.centerY(),b.maxZ(),b,false);}
    private static LayerComponent component(long id,int x,int y,int floor,Block block){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,floor,block.name,block.size,0,Team.sharded.id,LayerComponent.Mode.FREE,block.health,block.health,1,fpv.net.HeadlessGeometry.heightFor(block),LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]);}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Mouse SDL: "+label);}
}
