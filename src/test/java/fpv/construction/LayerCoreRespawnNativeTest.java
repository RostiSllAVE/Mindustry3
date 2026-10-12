package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.terrain.TerrainElevation;
import fpv.units.*;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;

/** Actual authoritative respawn path, including upper collision and native cap exemption. */
public final class LayerCoreRespawnNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.set(GameState.State.playing);Vars.indexer=new mindustry.ai.BlockIndexer();
        Blocks.coreShard.init();Blocks.copperWall.init();
        var altitude=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitude);var runtime=new LayerRuntime(world);var safety=new FactorySpawnSafety(world,altitude);
        world.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));runtime.setUnitRelease(safety::releaseUpper);
        Tile nativeTile=Vars.world.tile(20,20);nativeTile.updateBlockReference(Blocks.coreShard);Building lower=Blocks.coreShard.newBuilding();nativeTile.build=lower;lower.init(nativeTile,Team.sharded,false,0);
        runtime.grid().add(component(1,20,20,10,Blocks.coreShard,4));var upper=(CoreBlock.CoreBuild)runtime.building(1);
        require(upper!=lower&&Vars.state.teams.cores(Team.sharded).contains(upper),"upper core is a real independently registered respawn source");
        Player player=Player.create();Vars.player=player;player.team(Team.sharded);require(player.dead(),"new player fixture awaits respawn");
        player.deathTimer=Player.deathDelay-1;require(!runtime.apply(player,LayerActions.Action.respawn(1)).accepted(),"respawn cannot bypass native death delay");
        player.deathTimer=Player.deathDelay;player.team(Team.crux);require(!runtime.apply(player,LayerActions.Action.respawn(1)).accepted(),"enemy core cannot be requested for respawn");player.team(Team.sharded);
        upper.dead=true;require(!runtime.apply(player,LayerActions.Action.respawn(1)).accepted(),"destroyed core rejects delayed spawn action");upper.dead=false;
        require(!runtime.apply(player,LayerActions.Action.respawn(999)).accepted(),"removed core identity cannot respawn a unit");
        runtime.grid().add(component(2,22,20,10,Blocks.copperWall,30));int before=Groups.unit.size();
        var blocked=runtime.apply(player,LayerActions.Action.respawn(1));require(!blocked.accepted()&&player.dead()&&Groups.unit.size()==before,"blocked XYZ exit leaves no orphan unit or assigned player");
        require(player.deathTimer==Player.deathDelay,"blocked spawn remains retryable without resetting respawn timer");runtime.grid().remove(2);
        Vars.state.rules.unitCap=0;Team.sharded.data().unitCap=0;require(!mindustry.entities.Units.canCreate(Team.sharded,((CoreBlock)upper.block).unitType),"fixture has exhausted ordinary unit cap");
        var result=runtime.apply(player,LayerActions.Action.respawn(1));require(result.accepted(),"core respawn succeeds with exhausted cap: "+result.reason());
        Unit unit=player.unit();require(unit.isAdded()&&unit.spawnedByCore&&unit.team==Team.sharded&&unit.controller()==player,"spawn is a live native player-controlled core unit");
        require(altitude.unitState(unit).z()>runtime.planeHeight(10),"spawn altitude initialized above actual core floor");
        require(player.deathTimer==0&&Vars.world.tile(20,20).build==lower,"respawn resets timer without touching same-XY Z0 core");
        int count=Groups.unit.size();player.deathTimer=Player.deathDelay;require(!runtime.apply(player,LayerActions.Action.respawn(1)).accepted()&&Groups.unit.size()==count,"live player cannot request extra core units");
        unit.remove();runtime.close();safety.close();altitude.close();Groups.clear();System.out.println("LayerCoreRespawnNative: "+checks+" assertions passed.");
    }
    private static LayerComponent component(long id,int x,int y,int layer,Block block,double height){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,block.name,block.size,0,Team.sharded.id,LayerComponent.Mode.FREE,block.health,block.health,1,height,LayerComponent.SupportProfile.NONE,null,null);}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
