package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.math.geom.Point2;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.distribution.MassDriver.MassDriverBuild;

/** Real resources, queue/reload, saved in-flight cargo and separation from the native XY world. */
public final class LayerMassDriverNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.set(GameState.State.playing);LayerBuildingAdapter owner=new LayerBuildingAdapter(null);
        require(LayerBuildingAdapter.supports(Blocks.massDriver),"native Mass Driver supported");
        var source=(MassDriverBuild)owner.create(1,10,10,2,Blocks.massDriver,Team.sharded,0);
        var target=(MassDriverBuild)owner.create(2,30,10,2,Blocks.massDriver,Team.sharded,0);
        var differentFloor=(MassDriverBuild)owner.create(3,30,10,3,Blocks.massDriver,Team.sharded,0);
        require(owner.configureNative(1,new Point2(20,0)),"relative same-floor config accepted");
        require(!owner.configureNative(1,new Point2(50,40)),"out-of-range config rejected");
        source.items.add(Items.copper,60);source.items.add(Items.lead,15);
        for(int i=0;i<1000&&!LayerMassDriverBehavior.pod(source).active();i++)tick(source,target);
        var flight=LayerMassDriverBehavior.pod(source);require(flight.active()&&flight.target==2,"actual aimed queued launch");
        require(source.items.total()==0&&target.items.total()==0,"cargo removed at launch but not prematurely delivered");
        require(Math.abs(flight.z0-2*LayerCoordinates.DEFAULT.spacing())<1e-5&&flight.z1==flight.z0,"pod follows true layer altitude");
        byte[] saved=owner.writeState(1);float remaining=flight.remaining;
        owner.readState(1,saved);require(LayerMassDriverBehavior.pod(source).remaining==remaining,"in-flight timer persisted");
        for(int i=0;i<1000&&target.items.total()==0;i++)tick(source,target);
        require(target.items.get(Items.copper)==60&&target.items.get(Items.lead)==15,"exact mixed real item delivery after reload/aim/travel");
        require(differentFloor.items.empty(),"same XY on different floor remains empty");
        for(int i=0;i<300;i++)tick(source,target);
        require(target.items.total()==75&&source.items.empty(),"no duplicate transfer on subsequent ticks");
        source.items.add(Items.copper,20);for(int i=0;i<1000&&!flight.active();i++)tick(source,target);
        require(flight.active(),"second cargo launches");owner.remove(2);
        for(int i=0;i<500;i++){Time.update();source.efficiency=1;source.updateTile();}
        require(!flight.active()&&source.items.empty()&&differentFloor.items.empty(),"destroyed target loses cargo without redirect to another layer");
        require(Groups.bullet.size()==0&&Groups.build.size()==0,"no unsafe native XY transport entities");
        for(Tile tile:Vars.world.tiles)require(tile.block()==Blocks.air&&tile.build==null,"ground map unchanged");
        System.out.println("LayerMassDriverNativeTest: "+checks+" checks passed");
    }
    private static void tick(MassDriverBuild source,MassDriverBuild target){Time.update();source.efficiency=target.efficiency=1;source.updateTile();target.updateTile();}
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
