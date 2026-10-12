package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.defense.ProjectedWallSystem;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

/** Coverage changes visibility/collision, never reinitializes the existing native wall projection pool. */
public final class LayerProjectionNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();
        Core.audio=new MockAudio();Core.graphics=new MockGraphics();Vars.headless=true;Vars.tree=new FileTree();
        Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(16,16);Vars.world.setGenerating(true);Vars.world.tiles.fill();Blocks.copperWall.init();
        var tile=Vars.world.tile(8,8);tile.updateBlockReference(Blocks.copperWall);Building anchor=Blocks.copperWall.newBuilding();
        anchor.block=Blocks.copperWall;anchor.tile=tile;anchor.team=Team.sharded;anchor.health=anchor.maxHealth=Blocks.copperWall.health;
        anchor.x=8*Vars.tilesize;anchor.y=8*Vars.tilesize;tile.build=anchor;
        TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitudes=new UnitAltitudeSystem(terrain::heightAt);
        World3DAdapter physical=new World3DAdapter(terrain,(x,y)->1.4,altitudes);LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);
        ProjectedWallSystem walls=new ProjectedWallSystem(physical,()->true);
        walls.setCoveredPredicate(build->grid.columnsCovered(build.tile.x,build.tile.y,build.block.size));
        Core.settings.put("fpv-wall-regen-delay",8);Core.settings.put("fpv-wall-regen-permille",25);Core.settings.put("fpv-wall-unit-collision",true);
        walls.update(0);var before=walls.volume(anchor);float baseHp=anchor.health;
        require(before!=null&&before.hp()>0,"native ground wall starts with independent projection health");
        var contact=walls.trace(Team.crux,6,8,1.6,10,8,1.6,0);require(contact!=null,"open column blocks enemy projectiles");
        require(walls.hit(contact,null,before.maxHp()*.4f),"existing projection accepts ordinary damage");
        float damaged=walls.volume(anchor).hp();float cooldown=walls.snapshot().get(0).cooldown();
        var chunk=walls.renderChunks().get(0);long revision=chunk.revision();require(chunk.volumes().size()==1,"open projection enters cached render geometry");
        grid.add(new LayerComponent(1,LayerComponent.Kind.PLATFORM,8,8,2,"fpv-platform",1,0,Team.sharded.id,LayerComponent.Mode.FREE,
            100,100,1,.16,LayerComponent.SupportProfile.NONE,null,null));walls.update(0);
        require(ProjectedWallSystem.eligible(anchor),"covered native foundation remains eligible and attached");
        require(walls.volume(anchor).hp()==damaged&&walls.snapshot().get(0).cooldown()==cooldown,"covering preserves existing pool and regeneration cooldown");
        require(walls.trace(Team.crux,6,8,1.6,10,8,1.6,0)==null,"covered column no longer blocks projectile passage");
        require(walls.topAt(8,8)==Double.NEGATIVE_INFINITY,"covered column no longer presents phantom unit barrier");
        require(chunk.revision()>revision&&chunk.volumes().isEmpty(),"coverage invalidates render cache and removes projected mesh");
        require(!walls.hit(contact,null,25),"stale pre-cover projectile contact cannot damage hidden projection");
        walls.update(2);require(walls.volume(anchor).hp()==damaged&&walls.snapshot().get(0).cooldown()==cooldown-2,"covered wall continues native regeneration timer without resetting health");
        var replication=walls.replication();require(replication.damaged().size()==1&&replication.damaged().get(0).hp()==damaged,"covered damaged pool stays in authoritative network state");
        try(var replica=new ProjectedWallSystem(physical,()->false)){
            replica.setCoveredPredicate(build->grid.columnsCovered(build.tile.x,build.tile.y,build.block.size));replica.applyReplication(replication);
            require(replica.volume(anchor).hp()==damaged&&replica.trace(Team.crux,6,8,1.6,10,8,1.6,0)==null,"client keeps covered health while matching server collision");
        }
        revision=chunk.revision();grid.remove(1);walls.update(0);
        require(walls.volume(anchor).hp()==damaged&&walls.snapshot().get(0).cooldown()==cooldown-2,"uncovering restores original damaged pool and elapsed cooldown");
        require(walls.trace(Team.crux,6,8,1.6,10,8,1.6,0)!=null&&walls.topAt(8,8)>1.4,"uncovered projection regains its actual damage-limited barrier");
        require(chunk.revision()>revision&&chunk.volumes().size()==1,"uncovering rebuilds cached projection mesh");
        require(anchor.health==baseHp&&Vars.world.tile(8,8).build==anchor,"coverage never mutates native building health or identity");
        walls.close();altitudes.close();System.out.println("LayerProjectionNativeTest: "+checks+" checks passed");
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
