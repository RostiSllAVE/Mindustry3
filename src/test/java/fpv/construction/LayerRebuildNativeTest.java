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
import mindustry.world.blocks.defense.BuildTurret;
import mindustry.world.blocks.storage.CoreBlock;

/** Native build tower uses host construction/payment and persisted XYZ destruction jobs. */
public final class LayerRebuildNativeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.set(GameState.State.playing);Vars.state.rules.editor=true;
        for(Block block:new Block[]{Blocks.buildTower,Blocks.coreShard,Blocks.container,Blocks.copperWall})block.init();Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        Tile tile=Vars.world.tile(50,50);tile.updateBlockReference(Blocks.coreShard);var core=(CoreBlock.CoreBuild)Blocks.coreShard.newBuilding();tile.build=core;core.init(tile,Team.sharded,false,0);Vars.state.teams.registerCore(core);
        for(var item:Vars.content.items())core.items.add(item,3000);
        var altitude=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitude);var runtime=new LayerRuntime(world);world.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));
        runtime.grid().add(component(1,10,10,10,Blocks.buildTower,Team.sharded));runtime.grid().add(component(2,15,10,10,Blocks.container,Team.sharded));
        var tower=(BuildTurret.BuildTurretBuild)runtime.building(1);Building doomed=runtime.building(2);doomed.items.add(Items.copper,400);
        runtime.damage(2,doomed.health);require(runtime.building(2)==null&&runtime.rebuilds().size()==1,"actual destruction queues upper building after removing its native inventory");
        require(tower.shouldConsume(),"native build tower requests power for a reachable rebuild");tower.efficiency=0;Time.delta=1;tower.updateTile();require(runtime.rebuilds().snapshot().get(0).progress()==0,"native unpowered tower cannot construct");
        int[] before=new int[Blocks.container.requirements.length];for(int i=0;i<before.length;i++)before[i]=core.items.get(Blocks.container.requirements[i].item);
        tower.efficiency=1;for(int i=0;i<5;i++){Time.time++;tower.updateTile();}
        double progress=runtime.rebuilds().snapshot().get(0).progress();require(progress>0&&progress<1,"powered native tower advances according to original build time");
        for(int i=0;i<before.length;i++)require(core.items.get(Blocks.container.requirements[i].item)==before[i],"no recipe is charged before completion");
        byte[] saved=runtime.replication();runtime.restore(saved,false);tower=(BuildTurret.BuildTurretBuild)runtime.building(1);tower.efficiency=1;
        require(runtime.rebuilds().size()==1&&runtime.rebuilds().snapshot().get(0).progress()==progress,"FORMAT4 runtime save retains in-flight rebuild work");
        for(int i=0;i<20000&&runtime.rebuilds().size()>0;i++){Time.time++;tower.updateTile();}
        var rebuilt=runtime.grid().at(15,10,10,LayerComponent.Kind.BLOCK);require(rebuilt!=null&&rebuilt.id()!=2&&runtime.rebuilds().size()==0,"tower creates fresh upper component identity once complete");
        require(runtime.building(rebuilt.id()).items.empty(),"rebuild cannot resurrect destroyed container items");
        for(int i=0;i<before.length;i++)require(core.items.get(Blocks.container.requirements[i].item)==before[i]-Blocks.container.requirements[i].amount,"native recipe is deducted exactly once");
        for(int i=0;i<20;i++)tower.updateTile();require(runtime.grid().at(15,10,10,LayerComponent.Kind.BLOCK).id()==rebuilt.id(),"completed job cannot create a second building");
        runtime.grid().add(component(30,15,15,10,Blocks.copperWall,Team.crux));runtime.damage(30,100000);require(!tower.shouldConsume(),"tower does not rebuild enemy plans");runtime.rebuilds().clear();
        runtime.grid().add(component(31,10,10,60,Blocks.copperWall,Team.sharded));runtime.damage(31,100000);require(!tower.shouldConsume(),"same XY plan beyond actual vertical build range is unavailable");runtime.rebuilds().clear();
        var unsupported=component(32,15,15,10,Blocks.copperWall,Team.sharded).withMode(LayerComponent.Mode.STRUCTURAL);runtime.rebuilds().remember(unsupported);require(!tower.shouldConsume(),"missing structural support blocks native tower reconstruction");runtime.rebuilds().clear();
        runtime.grid().add(component(33,15,15,10,Blocks.copperWall,Team.sharded));runtime.grid().remove(33);require(runtime.rebuilds().size()==0,"voluntary topology removal never generates a destroyed plan");
        require(Vars.world.tile(15,10).build==null&&Vars.world.tile(50,50).build==core,"rebuilding never writes to ground tiles or replaces the paying core");
        runtime.close();altitude.close();Groups.clear();System.out.println("LayerRebuildNative: "+checks+" assertions passed.");
    }
    private static LayerComponent component(long id,int x,int y,int layer,Block block,Team team){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,block.name,block.size,0,team.id,LayerComponent.Mode.FREE,block.health,block.health,1,fpv.net.HeadlessGeometry.heightFor(block),LayerComponent.SupportProfile.NONE,null,null);}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
