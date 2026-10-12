package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import fpv.terrain.TerrainElevation;
import fpv.units.*;
import fpv.world.World3DAdapter;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.units.*;

/** Real native payload production, costs, transfer and XYZ release with no profile or graphics. */
public final class LayerPayloadNativeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        for(Block block:new Block[]{Blocks.airFactory,Blocks.groundFactory,Blocks.additiveReconstructor,Blocks.constructor,Blocks.payloadConveyor,Blocks.payloadRouter,Blocks.payloadLoader,Blocks.payloadUnloader,Blocks.deconstructor,Blocks.payloadSource,Blocks.payloadVoid,Blocks.copperWall,Blocks.container,Blocks.tankAssembler,Blocks.basicAssemblerModule,Blocks.payloadMassDriver,Blocks.unitCargoLoader,Blocks.unitCargoUnloadPoint})block.init();
        LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitude=new UnitAltitudeSystem((x,z)->0);
        World3DAdapter world=new World3DAdapter(terrain,(x,z)->0,altitude);FactorySpawnSafety safety=new FactorySpawnSafety(world,altitude);
        LayerBuildingAdapter registry=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){
            @Override public boolean releaseUnit(Building source,Unit unit,double x,double y){return safety.releaseUpper(source,unit,x,y);}
            @Override public double unitAltitude(Unit unit){return altitude.unitState(unit).z();}
            @Override public double buildingAltitude(Building building){return world.buildingBase(building);}
        });
        world.setConstruction(new LayerPhysicalQueries(()->grid,(id,damage)->{},registry::building));
        Block[] ground=new Block[4096];for(int i=0;i<ground.length;i++)ground[i]=Vars.world.tiles.geti(i).block();
        for(Block block:new Block[]{Blocks.airFactory,Blocks.groundFactory,Blocks.additiveReconstructor,Blocks.constructor,Blocks.payloadConveyor,Blocks.payloadRouter,Blocks.payloadLoader,Blocks.payloadUnloader,Blocks.deconstructor,Blocks.payloadSource,Blocks.payloadVoid})
            require(LayerBuildingAdapter.supports(block),"real native payload family supported: "+block.name);
        var factory=(UnitFactory.UnitFactoryBuild)registry.create(1,10,10,10,Blocks.airFactory,Team.sharded,0);component(grid,1,factory,3.7);
        require(registry.configureNative(1,0),"native plan index config accepted");var plan=((UnitFactory)factory.block).plans.get(0);
        for(var stack:plan.requirements)factory.items.add(stack.item,stack.amount);
        factory.progress=plan.time;factory.efficiency=1;Time.delta=1;factory.updateTile();
        require(factory.payload!=null&&factory.payload.unit.type==plan.unit,"native air factory creates original planned payload");
        for(var stack:plan.requirements)require(factory.items.get(stack.item)==0,"factory consumes exact native requirements");
        require(!factory.payload.unit.isAdded(),"factory output remains a payload until safely released");
        byte[] saved=registry.writeState(1);var second=(UnitFactory.UnitFactoryBuild)registry.create(2,20,10,10,Blocks.airFactory,Team.sharded,0);registry.readState(2,saved);
        require(second.payload!=null&&second.payload.unit.type==plan.unit&&!second.payload.unit.isAdded(),"payload inventory survives native state roundtrip without spawning");second.payload=null;
        Unit flyer=factory.payload.unit;for(int i=0;i<100&&factory.payload!=null;i++){Time.time++;factory.moveOutPayload();}
        require(factory.payload==null&&flyer.isAdded(),"factory releases flyer through upper XYZ boundary");
        require(altitude.unitState(flyer).z()>14,"factory release seeds real altitude above source floor before packets");
        flyer.remove();
        var groundFactory=(UnitFactory.UnitFactoryBuild)registry.create(3,10,20,10,Blocks.groundFactory,Team.sharded,0);component(grid,3,groundFactory,3.7);
        Unit dagger=UnitTypes.dagger.create(Team.sharded);groundFactory.payload=new UnitPayload(dagger);groundFactory.payVector.set(12,0);groundFactory.updatePayload();
        grid.add(new LayerComponent(4,LayerComponent.Kind.BLOCK,12,20,10,"copper-wall",1,0,1,LayerComponent.Mode.FREE,100,100,1,1.4,LayerComponent.SupportProfile.NONE,null,null));
        groundFactory.dumpPayload();require(groundFactory.payload!=null&&!dagger.isAdded(),"solid same-floor wall blocks entire produced ground unit");
        grid.remove(4);groundFactory.dumpPayload();require(groundFactory.payload==null&&dagger.isAdded(),"blocked output retries after obstruction removed");
        require(altitude.unitState(dagger).z()>14&&!altitude.unitState(dagger).flying(),"upper ground unit release stays grounded at actual floor height");dagger.remove();
        Unit below=UnitTypes.dagger.create(Team.sharded);below.set(12*Vars.tilesize,20*Vars.tilesize);below.add();
        Unit above=UnitTypes.dagger.create(Team.sharded);require(safety.releaseUpper(groundFactory,above,12*Vars.tilesize,20*Vars.tilesize),"Z0 unit below upper exit cannot block release");
        Unit overlap=UnitTypes.dagger.create(Team.sharded);require(!safety.releaseUpper(groundFactory,overlap,12*Vars.tilesize,20*Vars.tilesize),"same-tick unit at same XYZ blocks duplicated release");below.remove();above.remove();
        int priorCap=Vars.state.rules.unitCap;Vars.state.rules.unitCap=0;Unit coreRespawn=UnitTypes.alpha.create(Team.sharded);coreRespawn.spawnedByCore=true;
        require(safety.releaseUpper(groundFactory,coreRespawn,12*Vars.tilesize,20*Vars.tilesize),"authenticated core respawn preserves native unit-cap exemption");coreRespawn.remove();Vars.state.rules.unitCap=priorCap;
        var conveyor=(PayloadConveyor.PayloadConveyorBuild)registry.create(5,30,30,4,Blocks.payloadConveyor,Team.sharded,0);
        var next=(PayloadConveyor.PayloadConveyorBuild)registry.create(6,33,30,4,Blocks.payloadConveyor,Team.sharded,0);
        BuildPayload cargo=new BuildPayload(Blocks.copperWall,Team.sharded);conveyor.handlePayload(conveyor,cargo);
        boolean transferred=false;for(int i=0;i<400;i++){Time.time++;conveyor.updateTile();if(next.item==cargo){transferred=true;break;}}
        require(transferred,"native payload conveyor transfers whole build payload to same-floor neighbor");
        var assembler=(UnitAssembler.UnitAssemblerBuild)registry.create(7,30,10,10,Blocks.tankAssembler,Team.sharded,0);component(grid,7,assembler,5);
        var assemblyPlan=assembler.plan();for(var requirement:assemblyPlan.requirements)assembler.blocks.add(requirement.item,requirement.amount);
        assembler.power.status=1;assembler.droneProgress=1;assembler.efficiency=0;Time.time++;assembler.updateTile();
        require(assembler.units.size==1,"upper assembler produces actual native assembly drone");
        Unit assemblyDrone=assembler.units.first();require(assemblyDrone instanceof BuildingTetherc&&((BuildingTetherc)assemblyDrone).building()==assembler,"actual helper drone bound to upper building identity");
        require(altitude.unitState(assemblyDrone).z()>14,"helper drone spawns at upper assembly floor");
        assembler.progress=1;assembler.efficiency=1;Time.time++;assembler.updateTile();
        require(assembler.progress==0&&assembler.blocks.total()==0,"assembler consumes original payload recipe on successful creation");
        Unit output=Groups.unit.find(u->u.type==assemblyPlan.unit);require(output!=null&&altitude.unitState(output).z()>14,"native high-tier assembler output uses true upper altitude");
        byte[] assemblerSave=registry.writeState(7);((BuildingTetherc)assemblyDrone).building(null);registry.readState(7,assemblerSave);LayerPayloadFamilies.reconcileReferences(registry);
        require(((BuildingTetherc)assemblyDrone).building()==assembler,"saved/network drone IDs restore upper tether instead of packed-XY ground tile");
        var driver=(PayloadMassDriver.PayloadDriverBuild)registry.create(8,12,42,5,Blocks.payloadMassDriver,Team.sharded,0);
        var receiver=(PayloadMassDriver.PayloadDriverBuild)registry.create(9,24,42,5,Blocks.payloadMassDriver,Team.sharded,0);
        var wrongFloor=(PayloadMassDriver.PayloadDriverBuild)registry.create(10,24,42,6,Blocks.payloadMassDriver,Team.sharded,0);
        require(registry.configureNative(8,new arc.math.geom.Point2(12,0)),"payload driver native relative link configuration");
        BuildPayload driverCargo=new BuildPayload(Blocks.copperWall,Team.sharded);driver.payload=driverCargo;driver.payVector.setZero();driver.efficiency=receiver.efficiency=1;driver.turretRotation=0;receiver.turretRotation=180;
        boolean shot=false;for(int i=0;i<1200;i++){Time.time++;driver.updateTile();receiver.updateTile();if(receiver.payload==driverCargo){shot=true;break;}}
        require(shot&&driver.payload==null&&wrongFloor.payload==null,"payload driver charges and transfers native cargo only to intended upper floor");
        require(driver.reloadCounter>0&&receiver.reloadCounter>0,"payload driver keeps native reload handshake after transfer");
        var loader=(UnitCargoLoader.UnitTransportSourceBuild)registry.create(11,42,42,10,Blocks.unitCargoLoader,Team.sharded,0);component(grid,11,loader,3);
        var unload=(UnitCargoUnloadPoint.UnitCargoUnloadPointBuild)registry.create(12,48,42,10,Blocks.unitCargoUnloadPoint,Team.sharded,0);component(grid,12,unload,2);
        var wrongUnload=(UnitCargoUnloadPoint.UnitCargoUnloadPointBuild)registry.create(13,48,42,11,Blocks.unitCargoUnloadPoint,Team.sharded,0);
        registry.configureNative(12,Items.copper);registry.configureNative(13,Items.copper);loader.items.add(Items.copper,80);loader.efficiency=1;loader.buildProgress=1;loader.updateTile();
        require(loader.unit!=null&&loader.unit.isAdded()&&loader.unit.controller() instanceof LayerCargoAI,"cargo loader creates actual native drone with upper-aware inventory controller");
        Unit carrier=loader.unit;carrier.set(loader.x,loader.y);altitude.initializePayloadRelease(carrier,17);
        for(int i=0;i<40;i++){Time.time++;carrier.controller().updateUnit();}
        require(carrier.hasItem()&&loader.items.get(Items.copper)<80,"cargo drone picks native capacity from selected upper source");
        carrier.set(unload.x,unload.y);for(int i=0;i<100;i++){Time.time++;carrier.controller().updateUnit();}
        require(unload.items.get(Items.copper)>0&&wrongUnload.items.total()==0,"cargo drone transfers resources only to configured same-floor unload point");
        require(Groups.build.size()==0,"upper producers and conveyors remain outside global native building groups");
        for(int i=0;i<ground.length;i++)require(Vars.world.tiles.geti(i).block()==ground[i]&&Vars.world.tiles.geti(i).build==null,"payload production/transfer/release cannot modify Z0 tile "+i);
        safety.close();altitude.close();registry.clear();Groups.clear();
        System.out.println("LayerPayloadNative: "+checks+" assertions passed.");
    }
    private static void component(LayerWorld grid,long id,Building build,double height){grid.add(new LayerComponent(id,LayerComponent.Kind.BLOCK,build.tile.x,build.tile.y,((LayerTile)build.tile).layer,build.block.name,build.block.size,build.rotation,build.team.id,LayerComponent.Mode.FREE,build.health,build.maxHealth,10,height,LayerComponent.SupportProfile.NONE,null,null));}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
