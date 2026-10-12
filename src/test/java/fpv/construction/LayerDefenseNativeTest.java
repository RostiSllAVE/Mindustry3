package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.defense.ForceProjectorSystem;
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
import mindustry.world.blocks.defense.*;

/** The same XY address on two floors must never alias shields, health, door state or physics. */
public final class LayerDefenseNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);Vars.indexer=new mindustry.ai.BlockIndexer();
        Blocks.forceProjector.init();Blocks.door.init();
        var terrain=new TerrainElevation();var altitude=new UnitAltitudeSystem((x,z)->0);var world=new World3DAdapter(terrain,(x,z)->0,altitude);
        var grid=new LayerWorld(LayerCoordinates.DEFAULT);double[] unitZ={0};
        var owner=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){@Override public double unitAltitude(Unit unit){return unitZ[0];}});
        var physical=new LayerPhysicalQueries(()->grid,(id,damage)->{},owner::building);world.setConstruction(physical);
        var lower=(ForceProjector.ForceBuild)ground(20,20,Blocks.forceProjector);lower.broken=false;lower.radscl=1;lower.buildup=0;
        var upper=(ForceProjector.ForceBuild)owner.create(1,20,20,12,Blocks.forceProjector,Team.sharded,0);component(grid,1,upper);upper.broken=false;upper.radscl=1;upper.buildup=0;
        var fields=new ForceProjectorSystem(world,()->true);fields.setUpperBuildings(owner::entries,grid::geometryRevision);
        require(fields.volumes().size()==2,"two projector shields sharing XY retain independent actual volumes");
        var high=fields.trace(Team.crux,0,20,17,20,20,17,0);require(high!=null&&fields.hit(high,null,37),"enemy laser/shot hits upper shield at upper altitude");
        require(upper.buildup==37&&lower.buildup==0,"upper hit cannot damage same-XY ground projector shield");
        var low=fields.trace(Team.crux,0,20,1,20,20,1,0);require(low!=null&&fields.hit(low,null,19),"ground shield still defends its own altitude");
        require(lower.buildup==19&&upper.buildup==37,"ground hit cannot modify upper projector shield");
        require(fields.trace(Team.sharded,0,20,17,20,20,17,0)==null,"own-team projectiles pass both shield volumes");
        byte[] saved=owner.writeState(1);upper.buildup=99;owner.readState(1,saved);require(upper.buildup==37&&lower.buildup==19,"upper native shield state survives save without XY aliasing");
        require(fields.snapshot().size()==1,"legacy projector network stream only carries Z0; upper state uses layer identity");
        owner.remove(1);grid.remove(1);require(fields.volumes().size()==1&&fields.trace(Team.crux,0,20,17,20,20,17,0)==null,"removed upper projector cannot retain a stale shield");
        var groundDoor=(Door.DoorBuild)ground(10,10,Blocks.door);var door=(Door.DoorBuild)owner.create(2,10,10,10,Blocks.door,Team.sharded,0);component(grid,2,door);
        require(physical.sweep(8,10,14.5,12,10,14.5,.15,.2)!=null&&physical.trace(Team.crux,8,10,14.5,12,10,14.5,0)!=null,"closed upper door blocks both actual body and projectile in XYZ");
        require(owner.configureNative(2,true)&&door.open&&!groundDoor.open,"upper door config cannot change same-XY ground door");
        require(physical.sweep(8,10,14.5,12,10,14.5,.15,.2)==null&&physical.trace(Team.crux,8,10,14.5,12,10,14.5,0)==null,"open upper door clears real physical/projectile volume");
        Unit occupant=UnitTypes.dagger.create(Team.crux);occupant.set(10*Vars.tilesize,10*Vars.tilesize);occupant.add();unitZ[0]=14.5;
        require(!owner.configureNative(2,false)&&door.open,"enemy body in upper doorway prevents clipping by closing door");
        unitZ[0]=0;require(owner.configureNative(2,false)&&!door.open,"same XY unit on ground does not prevent upper door closure");
        require(Vars.world.tile(10,10).build==groundDoor&&Vars.world.tile(20,20).build==lower,"upper defenses preserve original ground building identities");
        occupant.remove();fields.close();owner.clear();altitude.close();Groups.clear();System.out.println("LayerDefenseNative: "+checks+" assertions passed.");
    }
    private static Building ground(int x,int y,Block block){Tile tile=Vars.world.tile(x,y);tile.updateBlockReference(block);Building b=block.newBuilding();tile.build=b;b.init(tile,Team.sharded,false,0);return b;}
    private static void component(LayerWorld grid,long id,Building build){grid.add(new LayerComponent(id,LayerComponent.Kind.BLOCK,build.tile.x,build.tile.y,((LayerTile)build.tile).layer,build.block.name,build.block.size,0,build.team.id,LayerComponent.Mode.FREE,build.health,build.maxHealth,1,1.4,LayerComponent.SupportProfile.NONE,null,null));}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
