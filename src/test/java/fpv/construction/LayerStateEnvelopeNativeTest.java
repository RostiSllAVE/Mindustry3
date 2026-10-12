package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;

/** Large valid programs fit the component envelope; hostile inflation and dynamic overflow remain bounded. */
public final class LayerStateEnvelopeNativeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,320,320);Vars.content.init();Vars.logicVars=new GlobalVars();Vars.logicVars.init();Vars.state.rules.editor=true;Vars.state.rules.infiniteResources=true;Vars.state.set(GameState.State.playing);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit builder=UnitTypes.alpha.create(Team.sharded);builder.set(8*Vars.tilesize,8*Vars.tilesize);builder.add();Vars.player.unit(builder);
        var terrain=new TerrainElevation();var altitude=new UnitAltitudeSystem(terrain::heightAt);var physical=new World3DAdapter(terrain,(x,y)->0,altitude);var runtime=new LayerRuntime(physical);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        var placed=runtime.apply(Vars.player,LayerActions.Action.place(10,10,2,"micro-processor",false,0,LayerComponent.Mode.FREE));require(placed.accepted(),"processor fixture");
        var processor=(LogicBlock.LogicBuild)runtime.building(placed.componentId());String code="# repeated bounded comment\n".repeat(3500)+"set result 42\nend";
        byte[] config=LogicBlock.compress(code,processor.relativeConnections());require(config.length<LayerConfiguration.MAX_BYTES&&code.length()>65536,"large source fits compressed native configuration");
        require(runtime.apply(Vars.player,LayerActions.Action.configure(placed.componentId(),config)).accepted(),"large valid program config snapshots without exceeding component envelope");
        byte[] state=runtime.nativeBuildings().writeState(placed.componentId());require(state[0]==2&&state.length<=LayerComponent.MAX_STATE_BYTES,"oversize raw native state uses bounded version2 envelope");
        runtime.nativeBuildings().readState(placed.componentId(),state);require(processor.code.equals(code),"large program restored without truncation");runtime.update(.1);byte[] world=runtime.replication();require(world.length>0,"runtime snapshots large program normally");
        byte[] beforeConfiguration=runtime.nativeBuildings().writeState(placed.componentId());
        byte[] invalid=LogicBlock.compress("set "+"a".repeat(66000)+" 1\nend",processor.relativeConnections());
        require(!runtime.apply(Vars.player,LayerActions.Action.configure(placed.componentId(),invalid)).accepted(),"unserializable native variable rejects the configuration without escaping as a runtime error");
        require(processor.code.equals(code)&&java.util.Arrays.equals(beforeConfiguration,runtime.nativeBuildings().writeState(placed.componentId())),"invalid configuration rolls back the previous complete native state");
        byte[] huge=state.clone();huge[1]=127;huge[2]=-1;huge[3]=-1;huge[4]=-1;reject(runtime.nativeBuildings(),placed.componentId(),huge,"declared zip-bomb expansion rejected");
        byte[] trailing=java.util.Arrays.copyOf(state,state.length+1);reject(runtime.nativeBuildings(),placed.componentId(),trailing,"compressed trailing garbage rejected");
        var memoryPlacement=runtime.apply(Vars.player,LayerActions.Action.place(12,10,2,"memory-bank",false,0,LayerComponent.Mode.FREE));require(memoryPlacement.accepted(),"memory fixture");runtime.update(.1);
        var memory=(MemoryBlock.MemoryBuild)runtime.building(memoryPlacement.componentId());var random=new java.util.Random(6315);LVar position=new LVar("position"),value=new LVar("value");
        for(int i=0;i<((MemoryBlock)memory.block).memoryCapacity;i++){char[] data=new char[4096];for(int j=0;j<data.length;j++)data[j]=(char)(33+random.nextInt(90));position.setnum(i);value.setobj(new String(data));memory.write(position,value);}
        try{runtime.nativeBuildings().writeState(memoryPlacement.componentId());throw new AssertionError("unbounded memory state accepted");}catch(LayerBuildingAdapter.StateLimitException expected){checks++;}
        runtime.update(.1);require(runtime.grid().get(memoryPlacement.componentId()).state().length<=LayerComponent.MAX_STATE_BYTES,"dynamic oversize memory cannot crash snapshot or escape component bound");
        require(!memory.enabled,"overflowing live block is paused with previous valid state retained");runtime.close();System.out.println("LayerStateEnvelopeNativeTest: "+checks+" checks passed");
    }
    private static void reject(LayerBuildingAdapter owner,long id,byte[] bytes,String label){try{owner.readState(id,bytes);throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
