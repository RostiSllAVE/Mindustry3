package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.net.NetProtocol;
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

/** Cross-floor transfers and cargo use real native modules with exact resource accounting. */
public final class LayerInteractionTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Groups.resize(0,0,512,512);
        for(Block b:new Block[]{Blocks.container,Blocks.sorter,Blocks.switchBlock,Blocks.itemBridge,Blocks.bridgeConduit,Blocks.ductBridge,Blocks.reinforcedBridgeConduit,Blocks.powerNode,Blocks.battery,Blocks.solarPanel})b.init();
        Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit pilot=UnitTypes.flare.create(Team.sharded);pilot.set(10*8,10*8);pilot.add();Vars.player.unit(pilot);
        var terrain=new TerrainElevation();var altitude=new UnitAltitudeSystem(terrain::heightAt);var world=new World3DAdapter(terrain,(x,y)->0,altitude);var runtime=new LayerRuntime(world);
        world.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));
        // Non-builder flyers can still carry cargo from a ground inventory to an upstairs inventory.
        Building ground=ground(10,12,Blocks.container);ground.items.add(Items.copper,40);long groundId=LayerVerticalTransfers.groundId(10,12);
        require(!pilot.canBuild(),"fixture is a non-builder flyer");
        int capacity=pilot.type.itemCapacity;
        var take=runtime.apply(Vars.player,LayerActions.Action.withdraw(groundId,Items.copper.id,100));require(take.accepted(),"ground cargo withdrawal by non-builder: "+take.reason());
        int taken=Math.min(40,capacity);require(pilot.stack.amount==taken&&ground.items.get(Items.copper)==40-taken,"bounded unit capacity exact withdrawal");
        long upper=1;runtime.grid().add(component(upper,10,12,2,Blocks.container));
        var deposit=runtime.apply(Vars.player,LayerActions.Action.deposit(upper,Items.copper.id,100));require(deposit.accepted(),"deposit on another floor: "+deposit.reason());
        require(pilot.stack.amount==0&&runtime.building(upper).items.get(Items.copper)==taken,"no duplication across floors");
        ground.team=Team.crux;require(!runtime.apply(Vars.player,LayerActions.Action.withdraw(groundId,Items.copper.id,1)).accepted(),"enemy cargo rejected");ground.team=Team.sharded;
        pilot.set(63*8,63*8);require(!runtime.apply(Vars.player,LayerActions.Action.withdraw(groundId,Items.copper.id,1)).accepted(),"cargo range enforced even in infinite resources");pilot.set(10*8,10*8);
        runtime.grid().add(component(2,13,12,2,Blocks.sorter));
        require(runtime.apply(Vars.player,LayerActions.Action.configure(2,Items.lead)).accepted(),"native sorter configuration through authority");
        require(runtime.building(2).config()==Items.lead,"native sorter actually changes filter");
        require(!runtime.apply(Vars.player,LayerActions.Action.configure(2,"not an item")).accepted(),"wrong configuration schema rejected");
        for(Object value:new Object[]{null,true,123,"text",Items.copper,Liquids.water,Blocks.container,UnitTypes.flare,mindustry.ai.UnitCommand.moveCommand,new arc.math.geom.Point2(3,-2),new byte[]{1,2,3}}){
            var action=LayerActions.Action.configure(2,value);var decoded=NetProtocol.read(NetProtocol.bytes(out->LayerActions.write(out,action)),LayerActions::read);
            require(action.equals(decoded),"bounded configuration packet roundtrip");Object result=LayerConfiguration.decode(decoded.configuration());require(value instanceof byte[]?java.util.Arrays.equals((byte[])value,(byte[])result):java.util.Objects.equals(value,result),"exact native value roundtrip");
        }
        reject(()->LayerConfiguration.encode(new byte[12289]));reject(()->LayerConfiguration.decode(new byte[]{7,127,-1}));
        // Item bridges at different XY/Z use the native XYZ range and one output budget.
        var registry=new LayerBuildingAdapter(null);var transfer=new LayerVerticalTransfers(registry);var hooks=new CrossLayerBridgeHooks(transfer::externalSource);hooks.install();
        Building source=ground(24,24,Blocks.itemBridge),target=registry.create(10,25,24,2,Blocks.itemBridge,Team.sharded,0);source.efficiency=1;target.efficiency=1;
        source.items.add(Items.copper,9);long sourceId=LayerVerticalTransfers.groundId(24,24);
        require(transfer.configureLink(sourceId,10,LayerVerticalTransfers.Kind.BRIDGE_ITEMS),"Z0 to Z2 diagonal bridge range accepted");
        require(source.acceptItem(ground,Items.copper),"linked ground bridge accepts conveyor input without native XY link");
        source.items.add(Items.copper,1);require(!source.acceptItem(ground,Items.copper),"cross-floor link retains native item capacity");
        Building horizontal=ground(27,24,Blocks.itemBridge);((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)source).link=horizontal.pos();
        int total=source.items.total()+target.items.total();for(int i=0;i<30;i++){Time.time++;source.updateTile();transfer.update(1.0/60);}
        require(target.items.total()>0&&source.items.total()+target.items.total()==total,"actual cross-floor item transfer conserves resources");
        require(horizontal.items.empty(),"explicit cross-floor output suppresses pre-existing native horizontal output");
        source.items.clear();target.items.clear();transfer.update(1);source.items.add(Items.copper,4);source.efficiency=0;
        transfer.update(.1);require(source.items.get(Items.copper)==4&&target.items.empty(),"stored idle item credit cannot bypass bridge power loss");
        source.efficiency=1;transfer.update(.1);require(source.items.total()+target.items.total()==4,"restored power transfers without duplication");
        Building far=registry.create(11,35,24,2,Blocks.itemBridge,Team.sharded,0);require(!transfer.canLink(sourceId,11,LayerVerticalTransfers.Kind.BRIDGE_ITEMS),"beyond native bridge radius rejected");
        Building conflict=registry.create(12,24,25,1,Blocks.itemBridge,Team.sharded,0);require(!transfer.canLink(sourceId,12,LayerVerticalTransfers.Kind.BRIDGE_ITEMS),"duplicate source output does not multiply throughput");
        Building aligned=registry.create(13,24,24,1,Blocks.itemBridge,Team.sharded,0);require(!transfer.canLink(sourceId,13,LayerVerticalTransfers.Kind.ITEMS),"vertical riser cannot duplicate active bridge item output");
        transfer.removeLink(sourceId,10,LayerVerticalTransfers.Kind.BRIDGE_ITEMS);require(!transfer.externalSource(source),"unlinked ground bridge resumes native behavior");
        Building water=ground(34,34,Blocks.bridgeConduit),waterTarget=registry.create(20,35,34,2,Blocks.bridgeConduit,Team.sharded,0);water.efficiency=1;water.liquids.add(Liquids.water,8);
        require(transfer.configureLink(LayerVerticalTransfers.groundId(34,34),20,LayerVerticalTransfers.Kind.BRIDGE_LIQUIDS),"liquid bridge cross-floor accepted");
        transfer.update(.1);require(waterTarget.liquids.get(Liquids.water)>0&&Math.abs(water.liquids.get(Liquids.water)+waterTarget.liquids.get(Liquids.water)-8)<1e-5,"liquid preserved exactly");
        water.efficiency=0;float before=waterTarget.liquids.get(Liquids.water);transfer.update(.1);require(waterTarget.liquids.get(Liquids.water)==before,"unpowered bridge cannot transfer");
        water.efficiency=1;waterTarget.liquids.clear();waterTarget.liquids.add(Liquids.oil,1);float sourceWater=water.liquids.get(Liquids.water);transfer.update(.1);
        require(water.liquids.get(Liquids.water)==sourceWater&&waterTarget.liquids.get(Liquids.water)==0,"incompatible stored liquid rejects transfer without loss");
        waterTarget.team=Team.crux;transfer.update(.1);require(!transfer.externalSource(water),"captured target removes cross-team bridge authority");
        Building lowerPower=registry.create(30,45,45,1,Blocks.powerNode,Team.sharded,0),upperPower=registry.create(31,45,45,3,Blocks.powerNode,Team.sharded,0);
        require(transfer.configureLink(30,31,LayerVerticalTransfers.Kind.POWER),"explicit upper power riser joins actual native graphs");
        Building groundPower=ground(45,45,Blocks.powerNode);require(transfer.canLink(LayerVerticalTransfers.groundId(45,45),30,LayerVerticalTransfers.Kind.POWER),"ground node links by explicit XYZ identities without packed coordinate alias");
        registry.remove(31);transfer.update(.1);require(transfer.links().stream().noneMatch(link->link.from()==30),"removing power endpoint tears down saved link");
        hooks.close();runtime.close();altitude.close();registry.clear();System.out.println("LayerInteractionTest PASS "+checks+" checks");
    }
    private static LayerComponent component(long id,int x,int y,int layer,Block b){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,b.name,b.size,0,Team.sharded.id,LayerComponent.Mode.FREE,b.health,b.health,2,fpv.net.HeadlessGeometry.heightFor(b),LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]);}
    private static Building ground(int x,int y,Block block){Tile tile=Vars.world.tile(x,y);tile.updateBlockReference(block);Building b=block.newBuilding();tile.build=b;b.init(tile,Team.sharded,false,0);return b;}
    private static void require(boolean condition,String text){checks++;if(!condition)throw new AssertionError(text);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid config accepted");}catch(IllegalArgumentException expected){checks++;}}
}
