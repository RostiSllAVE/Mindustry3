package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import java.util.IdentityHashMap;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.BaseShield;

/** Campaign/sandbox upper base shield defends a finite XYZ volume without ground aliases. */
public final class LayerBaseShieldNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.set(GameState.State.playing);Vars.state.rules.unitCap=1000;Vars.indexer=new mindustry.ai.BlockIndexer();
        Blocks.shieldProjector.init();var type=(BaseShield)Blocks.shieldProjector;
        var altitude=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitude);var owner=new LayerBuildingAdapter(null);var grid=new LayerWorld(LayerCoordinates.DEFAULT);
        world.setConstruction(new LayerPhysicalQueries(()->grid,(id,damage)->{},owner::building));
        var upper=(BaseShield.BaseShieldBuild)owner.create(1,20,20,10,type,Team.sharded,0);grid.add(new LayerComponent(1,LayerComponent.Kind.BLOCK,20,20,10,type.name,type.size,0,Team.sharded.id,LayerComponent.Mode.FREE,upper.health,upper.maxHealth,1,1,LayerComponent.SupportProfile.NONE,null,null));
        Tile groundTile=Vars.world.tile(20,20);groundTile.updateBlockReference(type);var lower=(BaseShield.BaseShieldBuild)type.newBuilding();groundTile.build=lower;lower.init(groundTile,Team.sharded,false,0);
        var shields=new LayerBaseShieldSystem(world,owner::entries,grid::geometryRevision);upper.smoothRadius=40;upper.efficiency=40/type.radius;
        var volume=shields.volumes().get(0);require(volume.bottom()==14&&volume.top()==24&&volume.radius()==5,"upper shield native radius maps to finite actual floor height");
        var contact=shields.trace(Team.crux,0,20,16,20,20,16,0);require(contact!=null,"enemy trajectory crossing actual upper shield volume is intercepted");
        require(shields.trace(Team.crux,0,20,0,20,20,0,0)==null&&shields.trace(Team.crux,0,20,25,20,20,25,0)==null,"bullets below/above upper shield do not collide through XY projection");
        require(shields.trace(Team.sharded,0,20,16,20,20,16,0)==null,"own team can shoot through upper base shield");
        Bullet bullet=Bullet.create();bullet.team=Team.crux;require(shields.hit(contact,bullet,200)&&upper.hit==1&&lower.hit==0,"actual enemy bullet impact flashes upper shield only");
        bullet.team=Team.sharded;require(!shields.hit(contact,bullet,200),"friendly projectile cannot register enemy shield contact");
        var heights=new IdentityHashMap<Unit,Double>();altitude.setExternalStateProvider(unit->{Double height=heights.get(unit);return height==null?null:new Unit3DState(unit.id,unit.type.id,unit.team.id,unit.x/(double)Vars.tilesize,unit.y/(double)Vars.tilesize,height,0,0,0,0,0,0,height,.25,.25,true);});
        Unit beneath=unit(20,20,Team.crux),above=unit(20,20,Team.crux),inside=unit(20,20,Team.crux),friendly=unit(20,20,Team.sharded),edge=unit(25.1,20,Team.crux);
        heights.put(beneath,0d);heights.put(above,25d);heights.put(inside,16d);heights.put(friendly,16d);heights.put(edge,16d);shields.update(upper);
        require(!beneath.dead&&!above.dead&&!friendly.dead,"units on another altitude and own team remain untouched");require(inside.dead,"deep enemy penetration into real shield volume is rejected");require(!edge.dead&&edge.vel.x>0,"near-boundary enemy unit receives an outward impulse");
        upper.broken=true;require(shields.volumes().isEmpty()&&shields.trace(Team.crux,0,20,16,20,20,16,0)==null,"broken native base shield has no active volume");upper.broken=false;
        owner.remove(1);grid.remove(1);require(shields.volumes().isEmpty()&&Vars.world.tile(20,20).build==lower,"removed upper shield invalidates cache without modifying Z0");
        owner.clear();altitude.close();Groups.clear();System.out.println("LayerBaseShieldNative: "+checks+" assertions passed.");
    }
    private static Unit unit(double x,double y,Team team){Unit unit=UnitTypes.flare.create(team);unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.add();return unit;}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
