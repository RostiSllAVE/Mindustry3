package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.type.Liquid;
import mindustry.world.*;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.production.GenericCrafter;

/** Native content, consumers and recipes on synthetic stacked grids, without a live profile or network. */
public final class LayerBuildingAdapterNativeTest {
    private static int checks;
    private static float spill,damageHealth=-1;
    private static long configurationRequest;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        for(Block block:new Block[]{Blocks.conveyor,Blocks.titaniumConveyor,Blocks.armoredConveyor,Blocks.container,Blocks.vault,Blocks.coreShard,Blocks.graphitePress,Blocks.siliconSmelter,Blocks.conduit,Blocks.pulseConduit,Blocks.siliconCrucible,Blocks.solarPanel,Blocks.largeSolarPanel,Blocks.powerNode,Blocks.battery})block.init();
        Block[] ground=new Block[1600];Building[] groundBuilds=new Building[1600];
        for(int i=0;i<ground.length;i++){ground[i]=Vars.world.tiles.geti(i).block();groundBuilds[i]=Vars.world.tiles.geti(i).build;}
        LayerBuildingAdapter registry=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){
            @Override public void spilled(long id,Liquid liquid,float amount){require(liquid==Liquids.water,"water spills preserve liquid identity");spill+=amount;}
            @Override public void damaged(long id,float health){damageHealth=health;}
            @Override public void configureRequested(long id,Object value){configurationRequest=id;}
        });
        require(LayerBuildingAdapter.supports(Blocks.conveyor),"vanilla conveyor supported");
        require(LayerBuildingAdapter.supports(Blocks.container),"vanilla storage supported");
        require(LayerBuildingAdapter.supports(Blocks.graphitePress),"plain native crafter supported");
        require(LayerBuildingAdapter.supports(Blocks.armoredConveyor),"armored conveyor has its own native subtype adapter");
        require(LayerBuildingAdapter.supports(Blocks.coreShard),"guarded core family supported with explicit live lifecycle");
        require(LayerBuildingAdapter.supports(Blocks.siliconCrucible),"attribute crafter has its own native subtype adapter");

        Building first=registry.create(1,4,4,1,Blocks.conveyor,Team.sharded,0);
        registry.create(2,5,4,1,Blocks.conveyor,Team.sharded,0);registry.create(3,6,4,1,Blocks.conveyor,Team.sharded,0);
        Building storage=registry.create(4,7,4,1,Blocks.container,Team.sharded,0);
        Building stacked=registry.create(5,7,4,2,Blocks.container,Team.sharded,0);
        first.handleItem(first,Items.copper);tick(registry,130);
        require(storage.items.get(Items.copper)==1,"native conveyor moves item to same-floor native storage");
        require(stacked.items.total()==0,"same XY upstairs does not receive item");
        require(storage.tile.nearby(-1,0).build==registry.building(3),"native tile view resolves local output footprint");

        Building crafter=registry.create(6,12,4,1,Blocks.graphitePress,Team.sharded,0);
        Building output=registry.create(7,14,4,1,Blocks.container,Team.sharded,0);
        crafter.items.add(Items.coal,8);tick(registry,440);
        require(output.items.get(Items.graphite)==4,"native graphite recipe produces four from eight coal");
        require(crafter.items.get(Items.coal)==0,"native crafter consumes exact original recipe");
        require(crafter.block==Blocks.graphitePress,"crafter retains original block and requirements");
        Building powered=registry.create(8,12,12,2,Blocks.siliconSmelter,Team.sharded,0);
        powered.items.add(Items.coal,4);powered.items.add(Items.sand,8);tick(registry,240);
        require(powered.items.get(Items.silicon)==0&&powered.items.get(Items.coal)==4,"powered factory cannot invent power");
        registry.create(12,10,12,2,Blocks.powerNode,Team.sharded,0);
        registry.create(13,6,12,2,Blocks.largeSolarPanel,Team.sharded,0);
        tick(registry,240);
        require(powered.items.get(Items.silicon)==4&&powered.items.get(Items.coal)==0,"native power consumer gates original silicon recipe");
        Building battery=registry.create(14,10,13,2,Blocks.battery,Team.sharded,0);tick(registry,60);
        require(battery.power.status>0,"native power surplus charges the real battery capacity");
        Building isolatedPower=registry.create(15,12,12,3,Blocks.siliconSmelter,Team.sharded,0);
        isolatedPower.items.add(Items.coal,2);isolatedPower.items.add(Items.sand,4);tick(registry,120);
        require(isolatedPower.items.get(Items.silicon)==0,"stacked factories do not accidentally share power");
        LayerVerticalTransfers vertical=new LayerVerticalTransfers(registry);
        require(vertical.configureLink(8,15,LayerVerticalTransfers.Kind.POWER),"explicit adjacent-floor power riser is accepted");
        tick(registry,120);
        require(isolatedPower.items.get(Items.silicon)==2,"explicit vertical power link supplies actual producer energy");
        vertical.removeLink(8,15,LayerVerticalTransfers.Kind.POWER);
        isolatedPower.items.add(Items.coal,1);isolatedPower.items.add(Items.sand,2);tick(registry,120);
        require(isolatedPower.items.get(Items.silicon)==2,"removing power riser separates the upper circuit");

        Building pipe=registry.create(9,20,10,1,Blocks.conduit,Team.sharded,0);
        Building pipeNext=registry.create(10,21,10,1,Blocks.conduit,Team.sharded,0);
        Building pipeOtherLayer=registry.create(11,21,10,2,Blocks.conduit,Team.sharded,0);
        pipe.liquids.add(Liquids.water,10);tick(registry,2);
        require(pipeNext.liquids.get(Liquids.water)>0||spill>0,"native liquid flow reaches same-floor neighbour");
        require(pipeOtherLayer.liquids.get(Liquids.water)==0,"conduits never exchange water between floors");
        require(spill>0,"open conduit reports a layer-owned spill instead of a native Z0 puddle");
        require(vertical.configureLink(4,5,LayerVerticalTransfers.Kind.ITEMS),"explicit vertical item output accepted");
        storage.items.add(Items.lead,5);vertical.update(0.5);
        require(stacked.items.get(Items.lead)==5&&stacked.items.get(Items.copper)==1,"vertical transfer obeys native acceptance and direction");
        require(storage.items.total()==0,"vertical transfer moves inventory rather than duplicating it");
        require(!vertical.configureLink(4,7,LayerVerticalTransfers.Kind.ITEMS),"arbitrary XY resource teleport is rejected");
        Building wrongTeam=registry.create(16,7,4,3,Blocks.container,Team.crux,0);
        require(!vertical.configureLink(5,16,LayerVerticalTransfers.Kind.ITEMS),"cross-team vertical resource transfer rejected");
        pipeNext.liquids.clear();pipeOtherLayer.liquids.clear();pipeNext.liquids.add(Liquids.water,8);
        require(vertical.configureLink(10,11,LayerVerticalTransfers.Kind.LIQUIDS),"explicit liquid riser accepted");
        vertical.update(0.1);
        require(Math.abs(pipeOtherLayer.liquids.get(Liquids.water)-6)<0.001&&Math.abs(pipeNext.liquids.get(Liquids.water)-2)<0.001,"liquid riser respects configured real throughput and conserves liquid");

        byte[] state=registry.writeState(8);int silicon=powered.items.get(Items.silicon);
        powered.items.clear();registry.readState(8,state);
        require(powered.items.get(Items.silicon)==silicon,"native inventory survives state roundtrip");
        require(Arrays.equals(state,registry.writeState(8)),"native crafter save roundtrip is byte stable");
        byte[] corrupted=state.clone();java.nio.ByteBuffer.wrap(corrupted).putFloat(2,Float.NaN);
        try{registry.readState(8,corrupted);throw new AssertionError("NaN native health accepted");}catch(java.io.UncheckedIOException expected){checks++;}
        registry.readState(8,state);
        corrupted=state.clone();corrupted[10]|=1<<5;
        try{registry.readState(8,corrupted);throw new AssertionError("XY disabler link accepted");}catch(IllegalArgumentException expected){checks++;}
        require(Arrays.equals(state,registry.writeState(8)),"rejected XY link cannot alter the building state");
        first.handleItem(first,Items.lead);tick(registry,4);byte[] beltState=registry.writeState(1);
        int beltItems=first.items.total();registry.readState(1,beltState);
        require(first.items.total()==beltItems,"belt decoding does not transfer or duplicate items");
        require(Arrays.equals(beltState,registry.writeState(1)),"native belt load does not advance an extra tick");
        require(storage.items.get(Items.lead)==0,"loading belt does not replay neighbour transfer");
        float health=first.health;first.damage(3);
        require(first.health==health-3&&damageHealth==first.health,"damage stays local and reports to construction owner");
        try{first.add();throw new AssertionError("global registration accepted");}catch(IllegalStateException expected){checks++;}
        first.configure(null);require(configurationRequest==1,"configuration routes to layer authority, never native XY RPC");
        registry.remove(2);require(first.front()==null,"removing a belt refreshes neighbouring native output cache");
        require(registry.building(2)==null,"removed upper adapter is absent");
        require(Groups.build.size()==0&&Groups.powerGraph.size()==0&&Groups.all.size()==0,"adapters never register global native entities");
        for(int i=0;i<ground.length;i++)require(ground[i]==Vars.world.tiles.geti(i).block()&&groundBuilds[i]==Vars.world.tiles.geti(i).build,"native Z0 stays unchanged");
        // Explicit Z0 endpoint transfers are ordinary native item changes, never map/adjacency rewriting.
        Tile groundAnchor=Vars.world.tile(27,25);groundAnchor.updateBlockReference(Blocks.container);
        Building groundStore=Blocks.container.newBuilding();groundAnchor.build=groundStore;groundStore.init(groundAnchor,Team.sharded,false,0);
        Building upperStore=registry.create(30,27,25,1,Blocks.container,Team.sharded,0);
        groundStore.items.add(Items.copper,10);
        require(vertical.configureLink(LayerVerticalTransfers.groundId(27,25),30,LayerVerticalTransfers.Kind.ITEMS),"explicit Z0 to Z1 item connector accepted");
        vertical.update(0.5);
        require(groundStore.items.get(Items.copper)==4&&upperStore.items.get(Items.copper)==6,"Z0 connector consumes real native items with exact throughput");
        require(Vars.world.tile(27,25).build==groundStore,"Z0 transfer preserves building identity");
        require(!vertical.configureLink(LayerVerticalTransfers.groundId(27,25),30,LayerVerticalTransfers.Kind.POWER),"native Z0 power graph is never unsafely merged");
        registry.remove(30);vertical.update(0.1);
        require(vertical.links().stream().noneMatch(link->link.to()==30),"destroyed endpoint removes its connector");
        Building highStore=registry.create(31,27,25,4,Blocks.container,Team.sharded,0);
        require(vertical.canLink(LayerVerticalTransfers.groundId(27,25),31,LayerVerticalTransfers.Kind.ITEMS),"explicit riser can span empty intermediate floors");
        vertical.setShaftClearance((from,to)->false);
        require(!vertical.canLink(LayerVerticalTransfers.groundId(27,25),31,LayerVerticalTransfers.Kind.ITEMS),"blocked shaft prevents preview and placement");
        vertical.setShaftClearance((from,to)->true);vertical.setMaxLayerGap(2);
        require(!vertical.canLink(LayerVerticalTransfers.groundId(27,25),31,LayerVerticalTransfers.Kind.ITEMS),"configured riser length limit is enforced");
        vertical.setMaxLayerGap(16);
        require(vertical.configureLink(LayerVerticalTransfers.groundId(27,25),31,LayerVerticalTransfers.Kind.ITEMS),"clear bounded long riser is configured explicitly");
        vertical.setShaftClearance((from,to)->false);vertical.update(.1);
        require(vertical.links().isEmpty(),"new shaft obstruction stops existing transfers");
        System.out.println("LayerBuildingAdapterNativeTest: "+checks+" checks passed");
    }
    private static void tick(LayerBuildingAdapter registry,int ticks){for(int i=0;i<ticks;i++){Time.delta=1;Time.time++;registry.tick();}}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
