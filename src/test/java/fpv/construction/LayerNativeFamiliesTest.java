package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.math.geom.Point2;
import arc.util.*;
import java.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.heat.*;

/** Functional native family coverage and Z0/global-entity isolation on actual installed content. */
public final class LayerNativeFamiliesTest {
    private static int checks;private static long id;
    private static LayerBuildingAdapter owner;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.logicVars=new mindustry.logic.GlobalVars();Vars.logicVars.init();Vars.state.set(GameState.State.playing);owner=new LayerBuildingAdapter(null);
        allFamilies();router();sorter();bridges();ductBridge();generator();heat();oreIsolation();logic();bridgeHooks();door();beamNode();radar();configBounds();specialWalls();sandbox();
        require(Groups.build.size()==0&&Groups.powerGraph.size()==0,"upper adapters never enter native entity groups");
        for(Tile tile:Vars.world.tiles)require(tile.block()==Blocks.air&&tile.build==null,"original Z0 remains untouched");
        System.out.println("LayerNativeFamiliesTest: "+checks+" checks passed");
    }
    private static void allFamilies(){
        int supported=0;
        for(Block block:Vars.content.blocks())if(LayerBuildingAdapter.supports(block)){
            owner.clear();Building build=create(block,20,20,2,0);
            require(build.block==block,"preserves original content "+block.name);
            try{tick(3);byte[] saved=owner.writeState(id);owner.readState(id,saved);tick(2);}
            catch(Throwable failure){throw new AssertionError("native family fails: "+block.name+" "+build.getClass().getSuperclass().getName(),failure);}
            require(Float.isFinite(build.health)&&build.health>0,"valid native state "+block.name);supported++;
        }
        require(supported>110,"broad native content coverage: "+supported);System.out.println("Native upper family coverage: "+supported+" blocks");owner.clear();
        LayerCatalog catalog=new LayerCatalog();java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        for(Block block:Vars.content.blocks())if(block.buildVisibility!=mindustry.world.meta.BuildVisibility.hidden&&catalog.entry(block.name,false,LayerCoordinates.DEFAULT.spacing())==null)missing.add(block.name+" ("+block.getClass().getName()+" / "+block.newBuilding().getClass().getName()+")");
        System.out.println("Missing visible upper block families: "+missing);
    }
    private static void router(){
        Building source=create(Blocks.conveyor,5,5,2,0),router=create(Blocks.router,6,5,2,0),destination=create(Blocks.container,7,5,2,0);
        Building otherFloor=create(Blocks.container,7,5,3,0);source.handleItem(source,Items.copper);tick(150);
        require(destination.items.get(Items.copper)==1&&otherFloor.items.empty(),"router transfers only on its own floor");owner.clear();
    }
    private static void sorter(){
        Building source=create(Blocks.conveyor,5,5,2,0),sorter=create(Blocks.sorter,6,5,2,0);long sorterId=id;
        Building front=create(Blocks.container,7,5,2,0),side=create(Blocks.container,6,7,2,0);
        require(owner.configureNative(sorterId,Items.copper),"native sorter config accepted");
        source.handleItem(source,Items.copper);tick(150);require(front.items.get(Items.copper)==1,"configured sorter routes selected item straight");
        require(!owner.configureNative(sorterId,new Object()),"invalid native configuration rejected");owner.clear();
    }
    private static void bridges(){
        Building first=create(Blocks.itemBridge,5,5,2,0);long firstId=id;
        Building second=create(Blocks.itemBridge,8,5,2,0),destination=create(Blocks.container,9,5,2,0);
        require(owner.configureNative(firstId,new Point2(3,0)),"layer-local bridge configuration");
        require(!owner.configureNative(firstId,new Point2(40,0)),"out-of-range bridge configuration rejected");
        first.items.add(Items.copper,3);tick(300);require(destination.items.get(Items.copper)==3,"native buffered bridge conveys real items");
        owner.setExternalBridge(b->b==first);first.items.add(Items.copper,2);tick(120);require(first.items.get(Items.copper)==2,"explicit external bridge owner suppresses native horizontal transfer");
        owner.setExternalBridge(null);owner.clear();
        Building liquid=create(Blocks.bridgeConduit,5,5,2,0);long liquidId=id;
        create(Blocks.bridgeConduit,8,5,2,0);Building tank=create(Blocks.liquidContainer,9,5,2,0);
        require(owner.configureNative(liquidId,new Point2(3,0)),"layer-local liquid bridge config");liquid.liquids.add(Liquids.water,5);tick(300);require(tank.liquids.get(Liquids.water)>0,"liquid bridge delivers real liquid");owner.clear();
    }
    private static void ductBridge(){
        Building first=create(Blocks.ductBridge,5,5,2,0);create(Blocks.ductBridge,8,5,2,0);Building destination=create(Blocks.container,9,5,2,0);
        first.handleItem(first,Items.beryllium);tick(200);require(destination.items.get(Items.beryllium)==1,"native directional duct bridge finds local floor receiver");owner.clear();
    }
    private static void generator(){
        Building generator=create(Blocks.combustionGenerator,10,10,2,0);Building battery=create(Blocks.battery,11,10,2,0);
        generator.items.add(Items.coal,3);tick(100);require(generator.getPowerProduction()>0&&battery.power.status>0,"fuel generator consumes coal and charges native battery");owner.clear();
    }
    private static void heat(){
        Building heater=create(Blocks.electricHeater,10,10,2,0);Building producer=create(Blocks.largeSolarPanel,8,10,2,0);create(Blocks.powerNode,9,12,2,0);
        Building redirect=create(Blocks.heatRedirector,13,10,2,0);tick(200);
        require(heater instanceof HeatProducer.HeatProducerBuild&&((HeatProducer.HeatProducerBuild)heater).heat()>0,"electric heater native production uses actual power");
        require(redirect instanceof HeatConductor.HeatConductorBuild&&((HeatConductor.HeatConductorBuild)redirect).heat()>0,"heat propagates through layer-local native proximity");owner.clear();
    }
    private static void oreIsolation(){
        Vars.world.tile(20,20).setOverlay(Blocks.oreCopper);
        Building drill=create(Blocks.mechanicalDrill,20,20,3,0);tick(800);
        require(drill.items.empty()&&((Drill.DrillBuild)drill).dominantItem==null,"upper drill does not mine ore three floors below");
        Vars.world.tile(20,20).setOverlay(Blocks.air);owner.clear();
    }
    private static void logic(){
        var processor=(mindustry.world.blocks.logic.LogicBlock.LogicBuild)create(Blocks.microProcessor,10,10,2,0);long processorId=id;
        var memory=(mindustry.world.blocks.logic.MemoryBlock.MemoryBuild)create(Blocks.memoryCell,11,10,2,0);
        var other=(mindustry.world.blocks.logic.MemoryBlock.MemoryBuild)create(Blocks.memoryCell,11,10,3,0);
        require(owner.configureNative(processorId,Point2.pack(11,10)),"processor link resolves its actual floor");
        String code="set result 41\nop add result result 1\nwrite result cell1 0\nend";
        byte[] compressed=mindustry.world.blocks.logic.LogicBlock.compress(code,processor.relativeConnections());
        require(owner.configureNative(processorId,compressed),"bounded original compressed logic configuration accepted");tick(20);
        mindustry.logic.LVar position=new mindustry.logic.LVar("position"),result=new mindustry.logic.LVar("result");position.setnum(0);memory.read(position,result);
        require(result.num()==42,"native ASM writes real upper memory");other.read(position,result);require(result.num()==0,"logic never writes same-XY memory on other floor");
        processor.executor.optionalVar("result").setnum(99);byte[] state=owner.writeState(processorId);owner.readState(processorId,state);
        require(processor.executor.optionalVar("result").num()==99,"logic numeric variables survive upper native state");
        position.setnum(1);result.setobj(other);memory.write(position,result);long memoryId=owner.entries().stream().filter(entry->entry.building()==memory).findFirst().orElseThrow().id();
        byte[] savedMemory=owner.writeState(memoryId);owner.readState(memoryId,savedMemory);tick(1);memory.read(position,result);
        require(result.obj()==other,"memory object reference retains correct XYZ floor after restore");
        require(!owner.configureNative(processorId,new byte[12289]),"oversize compressed logic rejected");owner.clear();
    }
    private static void bridgeHooks(){
        java.util.Set<Building> external=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        var hooks=new CrossLayerBridgeHooks(external::contains);var original=Blocks.itemBridge.buildType;hooks.install();
        Building source=Blocks.itemBridge.newBuilding();Tile tile=Vars.world.tile(4,4);tile.updateBlockReference(Blocks.itemBridge);tile.build=source;source.init(tile,Team.sharded,false,0);
        Building input=owner.create(++id,3,4,1,Blocks.conveyor,Team.sharded,0);external.add(source);
        require(source.acceptItem(input,Items.copper),"native Z0 external bridge accepts normal input even without XY link");source.handleItem(input,Items.copper);
        for(int i=0;i<20;i++){Time.delta=1;Time.time++;source.update();}require(source.items.get(Items.copper)==1,"Z0 external bridge never leaks into native horizontal path");
        external.clear();require(!source.acceptItem(input,Items.copper),"unlinked Z0 bridge restores normal native acceptance");hooks.close();require(Blocks.itemBridge.buildType==original,"bridge hook restores original factory");
        tile.build=null;tile.updateBlockReference(Blocks.air);owner.clear();
    }
    private static void door(){
        var door=(mindustry.world.blocks.defense.Door.DoorBuild)create(Blocks.door,10,10,2,0);long doorId=id;
        require(owner.configureNative(doorId,true)&&door.open&&!door.checkSolid(),"upper door opens with native solid state");require(owner.configureNative(doorId,false)&&!door.open,"upper door closes without native XY pathfinder mutation");owner.clear();
    }
    private static void beamNode(){
        Building solar=create(Blocks.largeSolarPanel,10,10,2,0),beam=create(Blocks.beamNode,13,10,2,0),battery=create(Blocks.battery,16,10,2,0);owner.invalidateTopology();tick(30);
        require(battery.power.status>0,"beam node joins native battery and generator on local floor");Building above=create(Blocks.battery,16,10,3,0);tick(20);require(above.power.status==0,"beam node does not connect another floor by XY");owner.clear();
    }
    private static Building create(Block block,int x,int y,int layer,int rotation){return owner.create(++id,x,y,layer,block,Team.sharded,rotation);}
    private static void radar(){
        Building radar=create(Blocks.radar,10,10,2,0);tick(2);
        require(Vars.indexer.getFlagged(Team.sharded,mindustry.world.meta.BlockFlag.hasFogRadius).contains(radar,true),"upper radar participates in actual native fog visibility");
        owner.clear();require(!Vars.indexer.getFlagged(Team.sharded,mindustry.world.meta.BlockFlag.hasFogRadius).contains(radar,true),"removed radar cannot reveal through stale fog source");
    }
    private static void configBounds(){
        var factory=(mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild)create(Blocks.airFactory,10,10,2,0);long factoryId=id;
        int previous=factory.currentPlan;require(!owner.configureNative(factoryId,Integer.MAX_VALUE)&&factory.currentPlan==previous,"out-of-range unit recipe is rejected without changing factory");
        require(owner.configureNative(factoryId,0)&&factory.currentPlan==0,"valid native unit plan configuration retained");
        require(!owner.configureNative(factoryId,UnitTypes.eclipse),"unavailable factory unit rejected");owner.clear();
    }
    private static void specialWalls(){
        double[] altitude={LayerCoordinates.DEFAULT.plane(2)};owner=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){@Override public double unitAltitude(Unit unit){return altitude[0];}});
        var door=(mindustry.world.blocks.defense.AutoDoor.AutoDoorBuild)create(Blocks.blastDoor,10,10,2,0);
        Unit unit=UnitTypes.dagger.create(Team.sharded);unit.spawnedByCore(true);unit.elevation=0;unit.set(door);unit.add();tick(60);require(door.open,"blast door opens for friendly unit on its actual floor");
        altitude[0]+=100;tick(60);require(!door.open,"blast door ignores same-XY unit far above");
        altitude[0]-=100;door.setOpen(true);unit.team=Team.crux;tick(60);require(door.open,"open blast door cannot close onto an enemy body");unit.remove();tick(60);require(!door.open,"empty blast door closes");owner.clear();
        var wall=(mindustry.world.blocks.defense.ShieldWall.ShieldWallBuild)create(Blocks.shieldedWall,10,10,2,0);wall.power.status=1;wall.efficiency=wall.potentialEfficiency=1;wall.shield=100;wall.breakTimer=0;float health=wall.health;
        wall.damage(60);require(wall.shield==40&&wall.health==health,"shield wall absorbs a real damage call before health");wall.damage(60);require(wall.shield==0&&wall.health==health-20&&wall.breakTimer>0,"shield break spills only excess damage to health and starts cooldown");owner.clear();owner=new LayerBuildingAdapter(null);
    }
    private static void sandbox(){
        Building source=create(Blocks.itemSource,10,10,2,0);long sourceId=id;Building sink=create(Blocks.container,11,10,2,0);
        require(owner.configureNative(sourceId,Items.copper),"sandbox source retains item configuration");tick(40);require(sink.items.get(Items.copper)>0,"sandbox item source feeds actual same-floor inventory");owner.clear();
        Building solar=create(Blocks.solarPanel,10,10,2,0),battery=create(Blocks.battery,11,10,2,0);tick(30);require(battery.power.status>0,"native power fixture is charged");
        create(Blocks.powerVoid,12,10,2,0);tick(30);require(battery.power.status==0,"sandbox power void drains only its real layer-local graph");owner.clear();
    }
    private static void tick(int count){for(int n=0;n<count;n++){Time.delta=1;Time.time++;Vars.state.updateId++;owner.tick();}}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
