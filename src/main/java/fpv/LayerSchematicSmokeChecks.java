package fpv;

import arc.Core;
import arc.Input;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.input.KeyBind;
import arc.math.geom.Vec3;
import arc.mock.MockInput;
import arc.util.Time;
import fpv.construction.*;
import fpv.generated.InputProfiles;
import fpv.input.FpvDesktopInput;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.input.Binding;
import mindustry.input.InputHandler;

/** Actual SDL game/GL, shared pointer controller and native schematic rotation/build execution. */
final class LayerSchematicSmokeChecks {
    private static int checks;
    private static final class Pointer extends MockInput {
        int x,y;boolean fTap,fHeld,fRelease,tap,held,released,flip,cancel;
        @Override public int mouseX(){return x;}
        @Override public int mouseY(){return y;}
        @Override public boolean keyTap(KeyBind b){return b==Binding.schematicSelect&&fTap||b==Binding.select&&tap||b==Binding.schematicFlipX&&flip||b==Binding.deselect&&cancel;}
        @Override public boolean keyDown(KeyBind b){return b==Binding.schematicSelect&&fHeld||b==Binding.select&&held||b==Binding.control;}
        @Override public boolean keyRelease(KeyBind b){return b==Binding.schematicSelect&&fRelease||b==Binding.select&&released;}
    }
    private final FpvMod mod;
    private final Pointer pointer=new Pointer();
    private final FpvDesktopInput input;
    private final LayerBuildController layers;
    private final Camera3D camera=new Camera3D();
    private LayerSchematicSmokeChecks(FpvMod mod)throws Exception{
        this.mod=mod;Core.input=pointer;input=new FpvDesktopInput(pointer,InputProfiles.create());input.installProjection();input.setFpvEnabled(true);input.setBuildMode(true);
        var init=InputHandler.class.getDeclaredMethod("initQuadtrees");init.setAccessible(true);init.invoke(input);layers=new LayerBuildController(mod.construction,input,mod.renderer3d.constructionRenderer());
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=60;camera.near=.04f;camera.far=160;
    }
    static String run(FpvMod mod)throws Exception{
        Input original=Core.input;var unit=Vars.player.unit();var plans=new arc.struct.Queue<BuildPlan>();for(var p:unit.plans())plans.addLast(p);unit.clearBuilding();
        boolean infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor,instant=Vars.state.rules.instantBuild,permission=Core.settings.getBool(LayerRuntime.FREE_PERMISSION,false);float dt=Time.delta,px=unit.x,py=unit.y;checks=0;
        try{
            Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.rules.instantBuild=true;Time.delta=1;unit.updateBuilding(true);unit.set(35*8,35*8);
            var t=new LayerSchematicSmokeChecks(mod);t.elevated();t.ground();t.largePreview();
            return "PASS: "+checks+" SDL copy/paste checks: Ctrl+F Z0/Z1/Z2/Z3, frozen drag layer, native rotate/flip and multiblocks, full GL ghost, supported/unsupported/occupied paste, 120 held polls, native Z0 queue execution, cancellation and FPV/Build transitions. Scripted bindings, no OS input automation.";
        }finally{
            Core.input=original;mod.construction.reset();mod.renderer3d.constructionRenderer().schematic(null,null,0);mod.renderer3d.constructionRenderer().copySelection(null);mod.renderer3d.constructionRenderer().preview(false,0,0,0,1,false,false);
            for(int x=34;x<=48;x++)for(int y=45;y<=53;y++)Vars.world.tile(x,y).setAir();
            unit.clearBuilding();for(var p:plans)unit.plans().addLast(p);unit.set(px,py);Vars.state.rules.infiniteResources=infinite;Vars.state.rules.editor=editor;Vars.state.rules.instantBuild=instant;Time.delta=dt;Core.settings.put(LayerRuntime.FREE_PERMISSION,permission);
        }
    }
    private void elevated(){
        var r=mod.construction;for(int x=38;x<=44;x++)for(int y=46;y<=49;y++)Vars.world.tile(x,y).setBlock(Blocks.titaniumWall,Team.sharded,0);
        require(r.requestPlace(40,46,1,Blocks.conveyor.name,false,1,LayerComponent.Mode.STRUCTURAL).accepted(),"native support under source belt");
        require(r.requestPlace(41,46,1,Blocks.sorter.name,false,0,LayerComponent.Mode.STRUCTURAL).accepted(),"source sorter");
        long id=r.grid().at(41,46,1,LayerComponent.Kind.BLOCK).id();require(r.requestConfigure(id,mindustry.content.Items.lead).accepted(),"source content config");
        for(int x=40;x<=41;x++)for(int z=1;z<=2;z++)require(r.requestPlace(x,49,z,Blocks.titaniumWall.name,false,0,LayerComponent.Mode.STRUCTURAL).accepted(),"target supporting column Z"+z);
        look(40.5,18,55,40.5,1.4,46);point(40,1.4,46);input.block=Blocks.copperWall;copyStart();require(input.block==null,"Ctrl+F can begin while a normal build block is selected");
        point(41,1.4,46);frame(false,false,false,0);copyEnd();
        require(input.lastSchematic!=null&&input.lastSchematic.tiles.size==2,"Ctrl+F automatic source layer copies both actual Z1 blocks");
        require(input.lastSchematic.tiles.get(0).block==Blocks.conveyor&&input.lastSchematic.tiles.get(1).block==Blocks.sorter,"Z0 supporting walls excluded from clipboard");
        require(input.selectPlans.get(0).rotation==1,"source belt orientation");
        look(40.5,18,56,40.5,4.2,49);point(41,4.2,49);frame(false,false,false,0);
        require(input.schematicOwned()&&mod.renderer3d.constructionRenderer().schematicActive(),"3D blueprint owns input and full preview");
        boolean depth=Gl.isEnabled(Gl.depthTest),blend=Gl.isEnabled(Gl.blend);mod.renderer3d.constructionRenderer().renderOverlay(camera);
        require(mod.renderer3d.constructionRenderer().schematicDrawn()==2,"full native textures at Z3 submitted to actual GL");require(Gl.isEnabled(Gl.depthTest)==depth&&Gl.isEnabled(Gl.blend)==blend,"ghost restores GL state");
        frame(true,true,false,0);require(r.pastes().pending(Vars.player)==2,"one upper click enqueues exactly two buildings");r.update(.1);
        require(r.grid().at(40,49,3,LayerComponent.Kind.BLOCK).blockName().equals(Blocks.conveyor.name)&&r.building(r.grid().at(41,49,3,LayerComponent.Kind.BLOCK).id()).config()==mindustry.content.Items.lead,"actual Z1-to-Z3 layout/config construction");
        int count=r.grid().size();for(int i=0;i<120;i++)frame(i<4,true,false,0);require(r.grid().size()==count&&r.pastes().pending(Vars.player)==0,"held/repeated tap cannot stamp on new upper wall layer");release();
        frame(true,false,true,0);require(r.pastes().pending(Vars.player)==0&&r.grid().size()==count,"occupied duplicate stamp rejected");release();
        cancel();layers.setLayer(3);point(40,4.2,49);copyStart();point(41,4.2,49);frame(false,false,false,0);copyEnd();
        require(input.lastSchematic.tiles.size==2&&input.lastSchematic.tiles.first().block==Blocks.conveyor,"copy from actual Z3 buildings");
        layers.setLayer(1);point(39,1.4,47);frame(false,false,false,0);frame(true,false,true,0);r.update(.1);
        require(r.grid().at(38,47,1,LayerComponent.Kind.BLOCK)!=null&&r.building(r.grid().at(39,47,1,LayerComponent.Kind.BLOCK).id()).config()==mindustry.content.Items.lead,"actual Z3-to-Z1 paste with original native ground support");release();
        point(48,4.2,49);layers.setLayer(3);frame(false,false,false,0);frame(true,false,true,0);require(r.pastes().pending(Vars.player)==0,"locked unsupported target cannot bypass structure limits");release();
        cancel();layers.setLayer(1);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);layers.setFree(true);
        require(r.requestPlace(44,48,2,Blocks.copperWallLarge.name,false,0,LayerComponent.Mode.FREE).accepted(),"elevated multiblock source");
        look(44.5,18,54,44.5,2.8,48.5);layers.setLayer(2);point(45,2.8,49);copyStart();copyEnd();require(input.lastSchematic.tiles.size==1&&input.lastSchematic.width==2&&input.lastSchematic.height==2,"copying edge cell captures one complete large wall");
        layers.setLayer(1);point(44,1.4,52);frame(false,false,false,1);require(input.selectPlans.size==1,"native rotation keeps multiblock footprint");frame(true,false,true,0);r.update(.1);require(r.grid().at(44,52,1,LayerComponent.Kind.BLOCK)!=null,"actual Z2-to-Z1 multiblock paste");release();cancel();
        layers.setLayer(1);point(40,1.4,46);copyStart();point(41,1.4,46);frame(false,false,false,0);copyEnd();
        point(38,1.4,52);frame(false,false,false,1);require(input.selectPlans.get(0).rotation==2,"native scheme rotation changes directional belt");pointer.flip=true;frame(false,false,false,0);pointer.flip=false;
        require(input.selectPlans.size==2,"native flip preserves selected blocks");
        layers.endInteraction();input.setBuildMode(false);require(input.selectPlans.size==2,"TAB preserves clipboard");input.setBuildMode(true);frame(false,false,false,0);require(input.schematicOwned(),"Build resume restores 3D clipboard ownership");cancel();require(input.selectPlans.isEmpty()&&!input.schematicOwned(),"RMB dismisses clipboard without dismantling");
        // Drag intersects a different height; source layer must remain frozen.
        layers.setFloorLocked(false);look(40,18,55,40,1.4,46);point(40,1.4,46);copyStart();point(40,4.2,49);frame(false,false,false,0);copyEnd();for(var tile:input.lastSchematic.tiles)require(tile.block!=Blocks.titaniumWall,"copy drag cannot switch to Z3 column or Z0 wall");cancel();
    }
    private void ground(){
        var u=Vars.player.unit();layers.setLayer(0);input.block=null;Vars.world.tile(35,46).setBlock(Blocks.conveyor,Team.sharded,3);Vars.world.tile(36,46).setBlock(Blocks.sorter,Team.sharded,0);Vars.world.tile(35,46).build.wasVisible=Vars.world.tile(36,46).build.wasVisible=true;Vars.world.tile(36,46).build.configure(mindustry.content.Items.copper);
        look(35.5,16,55,35.5,0,46);point(35,0,46);copyStart();point(36,0,46);frame(false,false,false,0);copyEnd();require(input.lastSchematic.tiles.size==2,"native Schematics.create Z0 still used");
        point(35,0,51);frame(false,false,false,0);frame(true,false,true,0);require(u.plans().size==2&&mod.construction.pastes().pending(Vars.player)==0,"ground paste enters actual native builder queue");for(int i=0;i<400&&u.plans().size>0;i++)u.updateBuildLogic();require(Vars.world.tile(34,51).block()==Blocks.conveyor&&Vars.world.tile(35,51).block()==Blocks.sorter,"native Z0 paste executes layout without upper commands");release();cancel();
        layers.setLayer(0);point(35,0,46);copyStart();point(36,0,46);frame(false,false,false,0);copyEnd();layers.setLayer(2);point(37,2.8,51);frame(false,false,false,0);frame(true,false,true,0);mod.construction.update(.1);
        require(mod.construction.grid().at(36,51,2,LayerComponent.Kind.BLOCK)!=null&&mod.construction.grid().at(37,51,2,LayerComponent.Kind.BLOCK)!=null,"actual native Z0-to-Z2 paste");release();cancel();
        input.setFpvEnabled(false);input.setAimWorld(35*8,46*8,true);input.useSchematic(mindustry.game.Schematics.readBase64(Vars.schematics.writeBase64(LayerSchematic.capture(mod.construction,0,35,46,36,46))));
        require(input.selectPlans.size==2&&input.selectPlans.allMatch(p->!LayerSchematic.platform(p)),"outside FPV native schematic/library path remains compatible");input.setFpvEnabled(true);input.setBuildMode(true);cancel();
    }
    private void copyStart(){pointer.fTap=pointer.fHeld=true;frame(false,false,false,0);pointer.fTap=false;}
    private void largePreview(){
        var tiles=new arc.struct.Seq<mindustry.game.Schematic.Stile>();for(int x=0;x<20;x++)for(int y=0;y<20;y++)tiles.add(new mindustry.game.Schematic.Stile(Blocks.conveyor,x,y,null,(byte)0));
        input.useSchematic(new mindustry.game.Schematic(tiles,new arc.struct.StringMap(),20,20));layers.setLayer(2);layers.setFree(true);look(24,60,24,24,2.8,24);point(24,2.8,24);frame(false,false,false,0);
        mod.renderer3d.constructionRenderer().renderOverlay(camera);require(mod.renderer3d.constructionRenderer().schematicDrawn()==400,"all 400 blueprint entries visible beyond original 256-ghost batch cap");require(Gl.getError()==0,"multi-batch schematic preview produces no GL errors");
        cancel();
    }
    private void copyEnd(){pointer.fHeld=false;pointer.fRelease=true;frame(false,false,false,0);pointer.fRelease=false;}
    private void cancel(){pointer.cancel=true;frame(false,false,false,0);pointer.cancel=false;frame(false,false,false,0);}
    private void release(){frame(false,false,true,0);frame(false,false,false,0);}
    private void frame(boolean tap,boolean held,boolean released,int rotation){pointer.tap=tap;pointer.held=held;pointer.released=released;Core.scene.mouseMoved(pointer.x,Core.graphics.getHeight()-pointer.y);Core.scene.act(0);layers.processPointer(camera,mod.renderer3d.pickInteraction(camera,pointer.x,pointer.y),pointer.x,pointer.y,new LayerBuildController.Buttons(tap,held,false,false,pointer.cancel,false,true,false,rotation,false,released));input.update();}
    private void look(double x,double z,double y,double tx,double tz,double ty){camera.up.set(0,1,0);camera.position.set((float)x,(float)z,(float)-y);camera.lookAt((float)tx,(float)tz,(float)-ty);camera.update();}
    private void point(double x,double z,double y){var p=camera.project(new Vec3((float)x,(float)z,(float)-y));pointer.x=Math.round(p.x);pointer.y=Math.round(p.y);}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Layer schematic SDL: "+label);}
}
