package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** A real assembled processor program constructs/deconstructs via server transactions, never native XY plans. */
public final class LayerLogicBuildNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,320,320);
        Vars.content.init();Vars.logicVars=new mindustry.logic.GlobalVars();Vars.logicVars.init();Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.rules.logicUnitControl=true;Vars.state.rules.logicUnitBuild=true;Vars.state.rules.logicUnitDeconstruct=true;Vars.state.set(GameState.State.playing);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit unit=UnitTypes.alpha.create(Team.sharded);unit.set(8*Vars.tilesize,12*Vars.tilesize);unit.add();Vars.player.unit(unit);
        var previousController=unit.controller();TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitude=new UnitAltitudeSystem(terrain::heightAt);
        World3DAdapter physical=new World3DAdapter(terrain,(x,z)->{Tile t=Vars.world.tile((int)Math.round(x),(int)Math.round(-z));return t!=null&&t.build!=null?1.4:0;},altitude);
        LayerRuntime runtime=new LayerRuntime(physical);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        var placed=runtime.apply(Vars.player,LayerActions.Action.place(10,10,1,"micro-processor",false,0,LayerComponent.Mode.FREE));require(placed.accepted(),"processor fixture: "+placed.reason());
        LogicBuild processor=(LogicBuild)runtime.building(placed.componentId());
        Tile nativeTile=Vars.world.tile(14,12);Building foundation=Building.create();foundation.block=Blocks.copperWall;foundation.team=Team.sharded;foundation.health=Blocks.copperWall.health;foundation.tile=nativeTile;foundation.set(nativeTile.worldx(),nativeTile.worldy());nativeTile.build=foundation;
        var core=(mindustry.world.blocks.storage.CoreBlock.CoreBuild)Blocks.coreShard.newBuilding();core.block=Blocks.coreShard;core.team=Team.sharded;core.items=new mindustry.world.modules.ItemModule();core.storageCapacity=4000;core.set(unit);Team.sharded.data().cores.add(core);core.items.add(Items.copper,100);Vars.state.rules.infiniteResources=false;
        run(runtime,processor,unit,"ucontrol build 14 12 @conveyor 0 0\nend");
        LayerComponent built=runtime.grid().at(14,12,1,LayerComponent.Kind.BLOCK);require(built!=null&&built.blockName().equals("conveyor"),"logic program creates actual upper conveyor; preview="+runtime.preview(Vars.player,LayerActions.Action.place(14,12,1,"conveyor",false,0,LayerComponent.Mode.STRUCTURAL))+" unit="+processor.executor.unit.obj()+" global="+Vars.logicVars.get("@conveyor"));
        require(runtime.building(built.id())!=null,"upper conveyor has functioning native adapter");require(core.items.get(Items.copper)==99,"logic construction pays original recipe exactly once");
        require(nativeTile.build==foundation&&unit.plans.isEmpty(),"logic never overwrites ground foundation or adds native XY construction plans");
        require(unit.controller()==previousController,"logic construction authority never replaces unit controller");
        run(runtime,processor,unit,"ucontrol deconstruct 14 12 0 0 0\nend");
        require(runtime.grid().at(14,12,1,LayerComponent.Kind.BLOCK)==null&&nativeTile.build==foundation,"logic deconstruct removes only processor floor");
        require(core.items.get(Items.copper)==99+Math.round(Vars.state.rules.deconstructRefundMultiplier),"logic deconstruction uses paid receipt refund");
        Vars.state.rules.logicUnitBuild=false;run(runtime,processor,unit,"ucontrol build 14 12 @conveyor 0 0\nend");require(runtime.grid().at(14,12,1,LayerComponent.Kind.BLOCK)==null,"disabled logic building cannot bypass server rule");
        runtime.close();System.out.println("LayerLogicBuildNativeTest: "+checks+" checks passed");
    }
    private static void run(LayerRuntime runtime,LogicBuild processor,Unit unit,String code){
        require(runtime.nativeBuildings().configureNative(runtime.nativeBuildings().entries().stream().filter(e->e.building()==processor).findFirst().orElseThrow().id(),LogicBlock.compress(code,processor.relativeConnections())),"compiled native program configuration");
        processor.executor.unit.setconst(unit);for(int i=0;i<12;i++){Time.delta=1;Time.time++;Vars.state.updateId++;runtime.nativeBuildings().tick();}
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
