package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.math.geom.Point2;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.logic.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;

/** Late-join one-shot display replay plus executable proof that mixed processor writes stay disabled. */
public final class LayerClientLogicNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,320,320);Vars.content.init();Vars.logicVars=new GlobalVars();Vars.logicVars.init();
        LayerBuildingAdapter owner=new LayerBuildingAdapter(null);
        var processor=(LogicBlock.LogicBuild)owner.create(1,5,5,2,Blocks.microProcessor,Team.sharded,0);
        var display=(LogicDisplay.LogicDisplayBuild)owner.create(2,8,5,2,Blocks.logicDisplay,Team.sharded,0);
        var memory=(MemoryBlock.MemoryBuild)owner.create(3,10,5,2,Blocks.memoryCell,Team.sharded,0);
        var button=(SwitchBlock.SwitchBuild)owner.create(4,11,5,2,Blocks.switchBlock,Team.sharded,0);
        owner.configureNative(1,Point2.pack(8,5));owner.configureNative(1,Point2.pack(10,5));owner.configureNative(1,Point2.pack(11,5));
        String code="draw clear 0 0 0 0 0 0\ndraw color 255 200 0 255 0 0\ndraw rect 4 4 12 12 0 0\ndrawflush display1\nwrite 99 cell1 0\ncontrol enabled switch1 0 0 0 0\njump 6 always 0 0";
        require(owner.configureNative(1,LogicBlock.compress(code,processor.relativeConnections())),"native draw and side-effect program assembled");
        processor.executor.counter.numval=6;double serverCounter=processor.executor.counter.numval;Vars.headless=false;
        Vars.net=new Net(null){@Override public boolean client(){return true;}};
        LayerClientLogicPresentation.update(owner,.1);
        require(display.commands.size>=3,"late client renders one-shot graphics even when server counter is already in final loop");
        require(processor.executor.counter.numval==serverCounter,"client presentation has independent counter and never alters replicated executor");
        LVar position=new LVar("position"),value=new LVar("value");position.setnum(0);memory.read(position,value);
        require(value.num()==0,"presentation skips native memory writes");require(button.enabled,"presentation skips native building control");
        int commands=display.commands.size;LayerClientLogicPresentation.update(owner,.1);require(display.commands.size==commands,"independent local program retains terminal loop rather than replaying side effects every frame");
        int[] unsafe={0};LExecutor probe=new LExecutor();probe.load(LAssembler.assemble("set a 1",false));probe.instructions=new LExecutor.LInstruction[]{executor->unsafe[0]++};LayerClientLogicPresentation.replay(probe,4096);
        require(unsafe[0]==0,"unknown or wrapped authority instructions never execute");
        display.commands.clear();Vars.net=new Net(null);LayerClientLogicPresentation.update(owner,.1);require(display.commands.isEmpty(),"presentation replay cannot execute on server");
        Vars.headless=true;owner.clear();System.out.println("LayerClientLogicNativeTest: "+checks+" checks passed");
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
