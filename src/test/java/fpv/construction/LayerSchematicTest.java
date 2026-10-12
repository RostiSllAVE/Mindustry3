package fpv.construction;

import arc.Core;
import arc.math.geom.Point2;
import arc.mock.*;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.I18NBundle;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import java.io.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;

/** Native codec, exact-floor extraction, configurations and authoritative unpaid build queues. */
public final class LayerSchematicTest {
    private static int checks;
    private static LayerRuntime runtime;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();Core.input=new MockInput();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(60,60);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        for(Block b:new Block[]{Blocks.copperWall,Blocks.copperWallLarge,Blocks.conveyor,Blocks.sorter,Blocks.container,Blocks.itemBridge,Blocks.powerNode})b.init();
        Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.rules.instantBuild=true;Vars.state.set(GameState.State.playing);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit u=UnitTypes.alpha.create(Team.sharded);u.set(20*8,20*8);u.add();Vars.player.unit(u);
        var terrain=new TerrainElevation();var altitude=new UnitAltitudeSystem(terrain::heightAt);runtime=new LayerRuntime(new World3DAdapter(terrain,(x,z)->0,altitude));Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        long belt=place(10,10,1,Blocks.conveyor,2),sorter=place(11,10,1,Blocks.sorter,0),box=place(14,10,1,Blocks.container,0);
        runtime.apply(Vars.player,LayerActions.Action.configure(sorter,Items.lead));runtime.building(box).items.add(Items.copper,73);runtime.building(box).health-=18;
        place(10,10,2,Blocks.copperWall,0);
        var source=LayerSchematic.capture(runtime,1,10,10,11,10);
        require(source.tiles.size==2&&source.tiles.get(0).block==Blocks.conveyor,"exact source floor excludes overlapping Z2 and empty cells");
        require(source.tiles.get(0).rotation==2&&source.tiles.get(1).config==Items.lead,"native direction and sorter content configuration");
        var large=LayerSchematic.capture(runtime,1,15,11,15,11);
        require(large.tiles.size==1&&large.tiles.first().block==Blocks.container&&large.width==2&&large.height==2,"touching multiblock edge includes whole footprint exactly once");
        require(LayerSchematic.capture(runtime,1,17,10,17,10).tiles.isEmpty(),"empty layer does not include other-floor blocks");
        var decoded=LayerSchematic.decode(LayerSchematic.encode(source));require(decoded.tiles.size==2&&decoded.tiles.get(1).config==Items.lead,"standard msch roundtrip");
        var nativeRead=Schematics.read(new ByteArrayInputStream(LayerSchematic.encode(source)));require(nativeRead.width==source.width&&nativeRead.tiles.size==2,"ordinary Mindustry decoder accepts copied scheme");
        var target=new LayerSchematic.Stamp(source,26,25);var action=LayerActions.Action.paste(target,3,LayerComponent.Mode.FREE);
        var out=new ByteArrayOutputStream();LayerActions.write(new DataOutputStream(out),action);require(action.equals(LayerActions.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())))),"native payload reliable action roundtrip");
        require(runtime.apply(Vars.player,action).accepted()&&runtime.pastes().pending(Vars.player)==2,"paste enqueues unpaid plans");
        require(!runtime.apply(Vars.player,action).accepted(),"duplicate pending positions cannot create two build jobs");runtime.update(.1);
        require(runtime.pastes().pending(Vars.player)==0,"native builder finishes queued entries");var copied=runtime.grid().at(26,25,3,LayerComponent.Kind.BLOCK);
        require(copied!=null&&runtime.building(copied.id()).config()==Items.lead,"source Z1 configuration copied to Z3");
        require(runtime.grid().at(25,25,3,LayerComponent.Kind.BLOCK).rotation()==2,"paste retains relative layout and direction");
        var boxAction=LayerActions.Action.paste(new LayerSchematic.Stamp(large,32,25),2,LayerComponent.Mode.FREE);require(runtime.apply(Vars.player,boxAction).accepted(),"multitile queued paste");runtime.update(.1);
        var copiedBox=runtime.grid().at(32,25,2,LayerComponent.Kind.BLOCK);require(copiedBox!=null&&runtime.building(copiedBox.id()).items.total()==0&&runtime.building(copiedBox.id()).health==Blocks.container.health,"no live inventory or damaged health copied");
        require(!runtime.apply(Vars.player,boxAction).accepted(),"occupied footprint rejects second paste");
        Core.settings.put(LayerRuntime.FREE_PERMISSION,false);var unsupported=LayerActions.Action.paste(new LayerSchematic.Stamp(source,35,30),4,LayerComponent.Mode.STRUCTURAL);
        require(!runtime.apply(Vars.player,unsupported).accepted()&&runtime.pastes().pending(Vars.player)==0,"structural unsupported paste denied without bypass");require(!runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(source,35,30),4,LayerComponent.Mode.FREE)).accepted(),"host permission required for Free stamp");Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        var core=(CoreBlock.CoreBuild)Blocks.coreShard.newBuilding();core.block=Blocks.coreShard;core.team=Team.sharded;core.items=new mindustry.world.modules.ItemModule();core.storageCapacity=4000;core.set(20*8,20*8);Team.sharded.data().cores.add(core);core.items.add(Items.copper,100);
        Vars.state.rules.infiniteResources=false;var wall=new Schematic(Seq.with(new Schematic.Stile(Blocks.copperWall,0,0,null,(byte)0)),new StringMap(),1,1);
        int cost=Blocks.copperWall.requirements[0].amount;var paid=LayerActions.Action.paste(new LayerSchematic.Stamp(wall,21,23),1,LayerComponent.Mode.FREE);
        require(runtime.apply(Vars.player,paid).accepted()&&core.items.get(Items.copper)==100,"enqueue does not duplicate/pay resources immediately");runtime.update(.1);
        require(core.items.get(Items.copper)==100-cost&&runtime.grid().at(21,23,1,LayerComponent.Kind.BLOCK)!=null,"ordinary full wall recipe charged once at completion");runtime.update(.1);require(core.items.get(Items.copper)==100-cost,"idle ticks never charge twice");
        core.items.clear();var waiting=LayerActions.Action.paste(new LayerSchematic.Stamp(wall,23,23),1,LayerComponent.Mode.FREE);require(runtime.apply(Vars.player,waiting).accepted(),"missing resources allow unpaid queue");runtime.update(.1);require(runtime.pastes().pending(Vars.player)==1&&runtime.grid().at(23,23,1,LayerComponent.Kind.BLOCK)==null,"missing resources wait without gifted wall");core.items.add(Items.copper,cost);runtime.update(.1);require(runtime.pastes().pending(Vars.player)==0&&core.items.get(Items.copper)==0,"restock completes and charges existing recipe");
        runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(wall,25,23),1,LayerComponent.Mode.FREE));require(runtime.apply(Vars.player,LayerActions.Action.cancelPaste()).accepted()&&runtime.pastes().pending(Vars.player)==0,"clear queue cancels unpaid jobs without refund minting");
        core.items.add(Items.copper,100);Vars.state.rules.instantBuild=false;Vars.state.rules.buildCostMultiplier=2;
        require(runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(wall,27,23),1,LayerComponent.Mode.FREE)).accepted(),"ordinary timed build job queued");runtime.update(.001);
        require(runtime.grid().at(27,23,1,LayerComponent.Kind.BLOCK)==null&&core.items.get(Items.copper)==100,"timed queue waits for native build time without charging unfinished plans");
        for(int i=0;i<1000&&runtime.pastes().pending(Vars.player)>0;i++)runtime.update(.1);
        require(runtime.grid().at(27,23,1,LayerComponent.Kind.BLOCK)!=null&&core.items.get(Items.copper)==100-2*cost,"native builder speed and build-cost multiplier complete timed queue");Vars.state.rules.instantBuild=true;Vars.state.rules.buildCostMultiplier=1;
        Vars.state.rules.infiniteResources=true;long a=place(10,16,2,Blocks.itemBridge,0),b=place(13,16,2,Blocks.itemBridge,0);require(runtime.apply(Vars.player,LayerActions.Action.configure(a,new Point2(3,0))).accepted(),"native bridge fixture config");var bridges=LayerSchematic.capture(runtime,2,10,16,13,16);require(bridges.tiles.first().config instanceof Point2 p&&p.x==3,"internal bridge link stays relative");require(LayerSchematic.capture(runtime,2,10,16,10,16).tiles.first().config==null,"external uncopied bridge link omitted");
        require(runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(bridges,32,35),1,LayerComponent.Mode.FREE)).accepted(),"bridge copy Z2 to Z1");runtime.update(.1);var bridgeCopy=runtime.grid().at(30,35,1,LayerComponent.Kind.BLOCK);require(runtime.building(bridgeCopy.id()).config() instanceof Point2 p&&p.x==3,"bridge config applies after both new endpoints exist");
        long powerA=place(10,20,2,Blocks.powerNode,0),powerB=place(13,20,2,Blocks.powerNode,0);require(runtime.nativeBuildings().connectPower(powerA,powerB),"native power link fixture");var powers=LayerSchematic.capture(runtime,2,10,20,13,20);require(((Point2[])powers.tiles.first().config).length==1,"upper power proxy config reconstructed from actual links");require(runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(powers,42,35),3,LayerComponent.Mode.FREE)).accepted(),"power copy different floor");runtime.update(.1);var p1=runtime.grid().at(40,35,3,LayerComponent.Kind.BLOCK);require(runtime.nativeBuildings().powerLinks().stream().anyMatch(link->link.first()==p1.id()||link.second()==p1.id()),"copied new native power endpoints linked");
        runtime.apply(Vars.player,LayerActions.Action.place(10,25,2,"",true,0,LayerComponent.Mode.FREE));var slab=LayerSchematic.capture(runtime,2,10,25,10,25);require(LayerSchematic.slabs(slab).size==1&&slab.tiles.isEmpty(),"platforms standard two-dimensional msch metadata");require(runtime.apply(Vars.player,LayerActions.Action.paste(new LayerSchematic.Stamp(slab,40,25),3,LayerComponent.Mode.FREE)).accepted(),"platform queued copy");runtime.update(.1);require(runtime.grid().at(40,25,3,LayerComponent.Kind.PLATFORM)!=null,"same-floor platform paste uses ordinary paid placement");
        try{LayerSchematic.decode(new byte[]{1,2,3});throw new AssertionError("malformed accepted");}catch(IllegalArgumentException expected){checks++;}
        var overlap=new Schematic(Seq.with(new Schematic.Stile(Blocks.copperWall,0,0,null,(byte)0),new Schematic.Stile(Blocks.copperWall,0,0,null,(byte)0)),new StringMap(),1,1);try{LayerSchematic.decode(LayerSchematic.encode(overlap));throw new AssertionError("overlap accepted");}catch(IllegalArgumentException expected){checks++;}
        runtime.close();System.out.println("LayerSchematicTest: "+checks+" assertions passed.");
    }
    private static long place(int x,int y,int z,Block block,int rotation){var r=runtime.apply(Vars.player,LayerActions.Action.place(x,y,z,block.name,false,rotation,LayerComponent.Mode.FREE));require(r.accepted(),"fixture placement "+block.name+": "+r.reason());return r.componentId();}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
