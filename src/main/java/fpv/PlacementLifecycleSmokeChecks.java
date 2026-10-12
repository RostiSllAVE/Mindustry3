package fpv;

import arc.Core;
import arc.Input;
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
import mindustry.world.Block;

/** Polling-frame integration in the actual SDL game, including native DesktopInput plan flushing. */
final class PlacementLifecycleSmokeChecks {
    private static int checks;
    private static final class Pointer extends MockInput {
        int x,y;boolean tap,held,released;
        @Override public int mouseX(){return x;}
        @Override public int mouseY(){return y;}
        @Override public boolean keyDown(KeyBind key){return key==Binding.select&&held;}
        @Override public boolean keyTap(KeyBind key){return key==Binding.select&&tap;}
        @Override public boolean keyRelease(KeyBind key){return key==Binding.select&&released;}
    }
    private final FpvMod mod;private final Pointer pointer=new Pointer();private final FpvDesktopInput input;
    private final LayerBuildController layers;private final Camera3D camera=new Camera3D();
    private PlacementLifecycleSmokeChecks(FpvMod mod)throws Exception{
        this.mod=mod;Core.input=pointer;input=new FpvDesktopInput(pointer,InputProfiles.create());input.installProjection();input.setFpvEnabled(true);input.setBuildMode(true);
        var init=InputHandler.class.getDeclaredMethod("initQuadtrees");init.setAccessible(true);init.invoke(input);
        layers=new LayerBuildController(mod.construction,input,mod.renderer3d.constructionRenderer());
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=60;camera.near=.04f;camera.far=160;
    }
    static String run(FpvMod mod)throws Exception{
        if(!Boolean.getBoolean("fpv.placement.smoke")||mod.construction.grid().size()!=0)throw new IllegalStateException("Empty isolated construction lab required");
        Input original=Core.input;var unit=Vars.player.unit();var plans=new arc.struct.Queue<BuildPlan>();for(var p:unit.plans())plans.addLast(p);unit.clearBuilding();
        boolean infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor,instant=Vars.state.rules.instantBuild;float delta=Time.delta,px=unit.x,py=unit.y;
        boolean permission=Core.settings.getBool(LayerRuntime.FREE_PERMISSION,false);checks=0;
        try{
            Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.rules.instantBuild=true;Time.delta=1;unit.updateBuilding(true);unit.set(35*8,35*8);
            var test=new PlacementLifecycleSmokeChecks(mod);test.stationaryEdge();test.wallsAndRelease();test.upperLines();test.nativeGround();
            return "PASS: "+checks+" placement lifecycle checks; stationary/repeated-tap/120 held polls, short/fast clicks, Z0/Z1/Z2 and wall-top, auto/locked drag cells, native DesktopInput plan flushing/execution and interrupted mode transitions. Scripted polling, no OS input automation.";
        }finally{
            Core.input=original;mod.construction.reset();mod.renderer3d.constructionRenderer().preview(false,0,0,0,1,false,false);
            for(int x=34;x<=44;x++)Vars.world.tile(x,46).setAir();
            unit.clearBuilding();for(var p:plans)unit.plans().addLast(p);unit.set(px,py);Vars.state.rules.infiniteResources=infinite;Vars.state.rules.editor=editor;Vars.state.rules.instantBuild=instant;Time.delta=delta;Core.settings.put(LayerRuntime.FREE_PERMISSION,permission);
        }
    }
    private void stationaryEdge(){
        var runtime=mod.construction;Vars.world.tile(40,46).setBlock(Blocks.titaniumWall,Team.sharded,0);
        require(runtime.requestPlace(40,46,1,"",true,0,LayerComponent.Mode.STRUCTURAL).accepted(),"supported edge fixture");
        layers.setPlatform(true);layers.setFloorLocked(false);look(45,1.32,46,40,1.32,46);center();
        frame(true,true,false);int first=runtime.grid().size();var anchor=layers.placementTarget();
        require(first==2&&anchor.x()==41&&anchor.layer()==1,"one press emits exactly one adjacent placement");
        for(int i=0;i<120;i++)frame(false,true,false);
        require(runtime.grid().size()==first&&layers.placementTarget().equals(anchor),"stationary held edge must not grow additional platforms: first="+first+", now="+runtime.grid().size()+", firstTarget="+anchor+", nowTarget="+layers.placementTarget());
        release();
    }
    private void wallsAndRelease(){
        var runtime=mod.construction;runtime.reset();layers.reset();input.block=Blocks.titaniumWall;
        look(40,20,50,40,1.4,46);point(40,1.4,46);frame(true,true,false);
        require(runtime.grid().size()==1&&runtime.grid().at(40,46,1,LayerComponent.Kind.BLOCK)!=null,"short Z1 press targets the existing ground wall top");
        for(int i=0;i<120;i++)frame(i<4,true,false);
        require(runtime.grid().size()==1&&layers.placementTarget().layer()==1,"repeated tap flags and held polls cannot restack the new wall");release();
        point(40,2.8,46);frame(true,true,false);
        require(runtime.grid().size()==2&&runtime.grid().at(40,46,2,LayerComponent.Kind.BLOCK)!=null,"new Z2 click intentionally stacks exactly one wall");
        point(40,4.2,46);frame(true,true,true);
        require(runtime.grid().size()==3&&runtime.grid().at(40,46,3,LayerComponent.Kind.BLOCK)!=null,"release and next press in one poll start exactly one new gesture");
        for(int i=0;i<120;i++)frame(false,true,false);
        require(runtime.grid().size()==3,"the new held press survives a combined up/down poll without retriggering");release();
        point(40,5.6,46);frame(true,false,true);
        require(runtime.grid().size()==4,"tap and release in the same poll still create exactly one building");
        point(40,7,46);frame(true,false,true);
        require(runtime.grid().size()==5,"consecutive rapid released clicks need no cooldown or idle poll");release();
        point(40,8.4,46);frame(true,true,false);int size=runtime.grid().size();layers.endInteraction();input.setBuildMode(false);input.setBuildMode(true);
        frame(false,true,false);require(runtime.grid().size()==size,"mode interruption does not reuse a still-held placement button");release();
        point(40,9.8,46);frame(true,true,false);release();require(runtime.grid().size()==size+1,"fresh press works immediately after interrupted mode gesture");
    }
    private void upperLines(){
        var runtime=mod.construction;runtime.reset();layers.reset();input.block=Blocks.conveyor;input.rotation=1;
        require(runtime.requestPlace(40,46,1,"titanium-wall",false,0,LayerComponent.Mode.STRUCTURAL).accepted(),"upper-line supporting column");
        for(int x=40;x<=43;x++)require(runtime.requestPlace(x,46,2,"",true,0,LayerComponent.Mode.STRUCTURAL).accepted(),"supported upper-line platform "+x);
        int size=runtime.grid().size();look(41.5,12,52,41.5,2.8,46);point(40,2.8,46);frame(true,true,false);
        for(int x=41;x<=43;x++){point(x,2.8,46);frame(false,true,false);}
        point(41,2.8,46);frame(false,true,false);release();
        require(runtime.grid().size()==size+4,"automatic conveyor drag builds every visited cell once, including revisits");
        for(int x=40;x<=43;x++){var c=runtime.grid().at(x,46,2,LayerComponent.Kind.BLOCK);require(c!=null&&c.blockName().equals(Blocks.conveyor.name)&&c.rotation()==1,"conveyor drag preserves floor and rotation "+x);require(runtime.requestRemove(c.id()).accepted(),"remove disposable conveyor "+x);}
        input.block=Blocks.titaniumWall;layers.setLayer(2);point(40,2.8,46);frame(true,true,false);
        for(int x=41;x<=43;x++){point(x,2.8,46);frame(false,true,false);}
        for(int i=0;i<120;i++)frame(i<3,true,false);release();
        require(runtime.grid().size()==size+4,"locked wall drag does not omit distinct cells or stack on a held endpoint");
        for(int x=40;x<=43;x++)require(runtime.grid().at(x,46,2,LayerComponent.Kind.BLOCK)!=null&&runtime.grid().at(x,46,3,LayerComponent.Kind.BLOCK)==null,"locked wall floor remains Z2 "+x);
        input.block=Blocks.conveyor;layers.setFloorLocked(false);point(41,4.2,46);frame(true,true,false);release();
        require(runtime.grid().at(41,46,3,LayerComponent.Kind.BLOCK)!=null,"next auto gesture re-resolves actual newly built wall top");
    }
    private void nativeGround(){
        var runtime=mod.construction;var unit=Vars.player.unit();runtime.reset();layers.reset();input.block=Blocks.conveyor;input.rotation=0;
        look(35.5,10,52,35.5,0,46);point(34,0,46);frame(true,true,false);
        for(int i=0;i<120;i++)frame(false,true,false);
        require(unit.plans().size==0&&input.linePlans.size==1,"held Z0 preview does not flush duplicate native plans");release();
        require(unit.plans().size==1&&runtime.grid().size()==0,"release flushes exactly one native ground plan with no upper command");execute();
        require(Vars.world.tile(34,46).block()==Blocks.conveyor,"native Z0 short click actually constructs its block");
        for(int x=35;x<=36;x++){point(x,0,46);frame(true,false,true);require(unit.plans().size==1,"fast native click emits one plan "+x);execute();}
        release();for(int x=34;x<=36;x++)Vars.world.tile(x,46).setAir();
        layers.setLayer(0);input.block=Blocks.titaniumWall;point(34,0,46);frame(true,true,false);point(37,0,46);frame(false,true,false);release();
        require(unit.plans().size==4&&runtime.grid().size()==0,"native ground drag keeps complete wall-line planning");execute();
        for(int x=34;x<=37;x++)require(Vars.world.tile(x,46).block()==Blocks.titaniumWall,"native ground drag constructs cell "+x);
        Vars.world.tile(38,46).setAir();layers.setFloorLocked(false);input.block=Blocks.conveyor;point(38,0,46);frame(true,true,false);
        Vars.world.tile(38,46).setBlock(Blocks.titaniumWall,Team.sharded,0);
        for(int i=0;i<4;i++)frame(true,true,false);
        require(layers.placementTarget().layer()==0&&runtime.grid().size()==0,"ground press cannot transfer to upper handler after a remote/world change");release();
        require(unit.plans().size==0,"occupied ground target is rejected by native builder without upper fallback");
    }
    private void frame(boolean tap,boolean held,boolean released){
        pointer.tap=tap;pointer.held=held;pointer.released=released;
        Core.scene.mouseMoved(pointer.x,Core.graphics.getHeight()-pointer.y);Core.scene.act(0);
        var keys=new LayerBuildController.Buttons(tap,held,false,false,false,false,false,false,0,false,released);
        layers.processPointer(camera,mod.renderer3d.pickInteraction(camera,pointer.x,pointer.y),pointer.x,pointer.y,keys);
        var target=layers.placementTarget();boolean ground=target!=null&&target.resolved()&&target.layer()==0&&!layers.platform()&&input.block!=null;
        input.setAimWorld(ground?target.x()*8+input.block.offset:0,ground?target.y()*8+input.block.offset:0,ground);input.update();
    }
    private void release(){frame(false,false,true);frame(false,false,false);}
    private void execute(){var unit=Vars.player.unit();for(int i=0;i<400&&unit.plans().size>0;i++)unit.updateBuildLogic();require(unit.plans().size==0,"native plans executed completely");}
    private void center(){pointer.x=(int)camera.width/2;pointer.y=(int)camera.height/2;}
    private void look(double x,double z,double y,double tx,double tz,double ty){camera.up.set(0,1,0);camera.position.set((float)x,(float)z,(float)-y);camera.lookAt((float)tx,(float)tz,(float)-ty);camera.update();}
    private void point(double x,double z,double y){var p=camera.project(new Vec3((float)x,(float)z,(float)-y));pointer.x=Math.round(p.x);pointer.y=Math.round(p.y);}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Placement lifecycle: "+label);}
}
