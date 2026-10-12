package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import java.util.Arrays;
import java.util.Random;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.logic.MemoryBlock;

/** An overflowing live memory bank must not terminate simulation or replace its last valid save state. */
public final class LayerStateLimitRuntimeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,320,320);Vars.content.init();Vars.logicVars=new GlobalVars();Vars.logicVars.init();Vars.state.rules.editor=true;Vars.state.rules.infiniteResources=true;Vars.state.set(GameState.State.playing);
        var altitude=new UnitAltitudeSystem((x,y)->0);var physical=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitude);var runtime=new LayerRuntime(physical);physical.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        runtime.grid().add(component(1,10,10,Blocks.memoryBank));runtime.grid().add(component(2,23,24,Blocks.conveyor));runtime.grid().add(component(3,24,24,Blocks.router));runtime.grid().add(component(4,25,24,Blocks.container));
        var memory=(MemoryBlock.MemoryBuild)runtime.building(1);LVar position=new LVar("position"),value=new LVar("value");position.setnum(0);value.setnum(42);memory.write(position,value);Time.delta=1;runtime.update(.1);
        byte[] previous=runtime.grid().get(1).state();require(previous.length>0,"first successful runtime snapshot records native memory state");
        Random random=new Random(879121);int capacity=((MemoryBlock)memory.block).memoryCapacity;
        for(int i=0;i<capacity;i++){char[] text=new char[1024];for(int n=0;n<text.length;n++)text[n]=(char)(33+random.nextInt(90));position.setnum(i);value.setobj(new String(text));memory.write(position,value);}
        boolean limit=false;try{runtime.nativeBuildings().writeState(1);}catch(LayerBuildingAdapter.StateLimitException expected){limit=true;}require(limit,"random native memory data genuinely exceeds compressed component budget");
        Building router=runtime.building(3),container=runtime.building(4);router.handleItem(runtime.building(2),Items.copper);
        for(int i=0;i<10;i++){Time.time++;runtime.update(.1);}
        require(!memory.enabled,"runtime disables only the overflowing native block");
        require(Arrays.equals(previous,runtime.grid().get(1).state()),"overflow snapshot retains the exact last valid component bytes");
        require(runtime.status().contains("state limit")&&runtime.status().contains("paused"),"runtime reports an actionable state limit warning");
        require(router.enabled&&container.enabled&&container.items.get(Items.copper)==1&&router.items.total()==0,"ordinary native item routing continues while another block is paused");
        byte[] recoverySave=runtime.replication();require(recoverySave.length>0,"world save and network snapshot remain available after overflow");
        runtime.restore(recoverySave,false);memory=(MemoryBlock.MemoryBuild)runtime.building(1);position.setnum(0);memory.read(position,value);require(!value.isobj&&value.num()==42,"reload restores the last valid data rather than corrupted or truncated large state");
        require(runtime.building(4).items.get(Items.copper)==1,"unaffected building changes survive the same recovery snapshot");
        runtime.update(.1);require(!runtime.status().contains("state limit"),"valid restored data clears the transient overflow warning");
        require(Vars.world.tile(10,10).build==null&&Vars.world.tile(25,24).build==null,"overflow recovery never alters ground tile buildings");
        runtime.close();altitude.close();Groups.clear();System.out.println("LayerStateLimitRuntime: "+checks+" assertions passed.");
    }
    private static LayerComponent component(long id,int x,int y,Block block){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,2,block.name,block.size,0,Team.sharded.id,LayerComponent.Mode.FREE,block.health,block.health,1,fpv.net.HeadlessGeometry.heightFor(block),LayerComponent.SupportProfile.NONE,null,null);}
    private static void require(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
}
