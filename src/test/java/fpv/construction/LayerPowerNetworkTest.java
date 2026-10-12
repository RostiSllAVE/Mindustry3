package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import java.util.*;

/** Real native graphs and modules: no injected satisfaction or alternate energy solver. */
public final class LayerPowerNetworkTest {
    private static int checks;
    private static LayerBuildingAdapter owner;
    private static LayerVerticalTransfers transfers;
    private static final ArrayList<Building> groundBuildings=new ArrayList<>();
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Groups.resize(0,0,768,768);
        for(Block b:new Block[]{Blocks.powerNode,Blocks.powerNodeLarge,Blocks.surgeTower,Blocks.battery,Blocks.solarPanel,Blocks.powerVoid,Blocks.lancer,Blocks.conveyor,Blocks.plastaniumWall})b.init();
        Vars.state.rules.infiniteResources=false;Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);Time.delta=1;
        owner=new LayerBuildingAdapter(null);transfers=new LayerVerticalTransfers(owner);
        Building solar=ground(20,20,Blocks.solarPanel),node=ground(21,20,Blocks.powerNode);
        Building upper=owner.create(1,21,20,2,Blocks.powerNode,Team.sharded,0),battery=owner.create(2,22,20,2,Blocks.battery,Team.sharded,0);
        long id=LayerVerticalTransfers.groundId(21,20);solar.updateProximity();node.updateProximity();Building groundBattery=ground(24,20,Blocks.battery);node.configureAny(groundBattery.pos());
        check(transfers.configureLink(id,1,LayerVerticalTransfers.Kind.POWER),"Z0 to Z2 power link");
        tick(1);check(groundBattery.power.status*groundBattery.block.consPower.capacity+battery.power.status*battery.block.consPower.capacity<=solar.getPowerProduction()*solar.delta()+1e-5,"first attached tick cannot charge native production twice");tick(39);
        check(solar.power.graph==upper.power.graph&&battery.power.graph==node.power.graph,"native and upper graph really unified");
        check(solar.power.graph.producers.size==1&&solar.power.graph.batteries.size==2,"no duplicated producer/battery membership");
        check(battery.power.status>0,"real Z0 generation charges elevated battery");
        float production=solar.power.graph.getLastPowerProduced();check(Math.abs(production-solar.getPowerProduction()*solar.delta())<1e-6,"energy counted exactly once");
        long rebuilt=owner.powerRebuilds();tick(30);check(owner.powerRebuilds()==rebuilt,"unchanged topology cached");
        check(transfers.configureLink(1,id,LayerVerticalTransfers.Kind.POWER)&&transfers.links().size()==1,"reverse request is same undirected electrical link");
        transfers.removeLink(1,id,LayerVerticalTransfers.Kind.POWER);tick(1);
        check(solar.power.graph!=upper.power.graph,"disconnect splits native/upper");float stored=battery.power.status;tick(10);check(battery.power.status==stored,"isolated battery does not gain phantom energy");
        check(transfers.configureLink(id,1,LayerVerticalTransfers.Kind.POWER),"reconnect");tick(3);check(solar.power.graph==upper.power.graph,"reconnect restores graph");
        Vars.world.tile(21,20).setAir();transfers.update(.1);tick(2);check(transfers.links().isEmpty()&&solar.power.graph!=upper.power.graph,"destroy ground node splits network");
        owner.clear();
        Building g=ground(40,40,Blocks.powerNode),z5=owner.create(10,40,40,5,Blocks.powerNodeLarge,Team.sharded,0),z2=owner.create(11,42,40,2,Blocks.powerNode,Team.sharded,0);
        long gid=LayerVerticalTransfers.groundId(40,40);
        check(owner.connectPower(gid,10),"Z0 to Z5 within range including true Z");check(owner.connectPower(11,10),"diagonal Z2 to Z5");
        owner.create(12,40,40,20,Blocks.powerNode,Team.sharded,0);check(!owner.connectPower(gid,12),"same XY cannot bypass height range");
        owner.create(13,80,40,1,Blocks.powerNode,Team.sharded,0);check(!owner.connectPower(gid,13),"horizontal range remains finite");
        owner.create(14,40,41,1,Blocks.powerNode,Team.crux,0);check(!owner.connectPower(gid,14),"foreign team rejected");
        owner.create(15,41,40,1,Blocks.conveyor,Team.sharded,0);check(!owner.connectPower(gid,15),"non-power block rejected");
        owner.create(16,41,41,1,Blocks.surgeTower,Team.sharded,0);check(!owner.connectPower(16,14),"same-block/team restrictions preserved");
        int max=((PowerNode)g.block).maxNodes;
        for(int i=2;i<max;i++){owner.create(100+i,37,40,i,Blocks.powerNodeLarge,Team.sharded,0);check(owner.connectPower(gid,100+i),"link slot "+i);}
        owner.create(200,39,40,1,Blocks.powerNode,Team.sharded,0);check(owner.connectPower(gid,200),"last slot");
        owner.create(201,39,41,1,Blocks.powerNode,Team.sharded,0);check(!owner.connectPower(gid,201),"native connection-count limit");
        owner.clear();check(g.power.graph.all.contains(g),"registry teardown restores ground graph membership");
        System.out.println("LayerPowerNetworkTest: "+checks+" checks passed");
    }
    private static Building ground(int x,int y,Block block){Vars.world.tile(x,y).setBlock(block,Team.sharded,0);Building b=Vars.world.tile(x,y).build;groundBuildings.add(b);return b;}
    private static void tick(int frames){for(int i=0;i<frames;i++){Time.time++;for(Building b:groundBuildings)if(Vars.world.build(b.pos())==b)b.update();for(var graph:Groups.powerGraph.copy())graph.update();owner.tick();}}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
