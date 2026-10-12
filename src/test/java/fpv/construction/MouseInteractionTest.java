package fpv.construction;

import arc.Core;
import arc.math.geom.Ray;
import arc.math.geom.Vec3;
import arc.mock.*;
import arc.util.I18NBundle;
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

/** Real native objects and authoritative requests; a UI preview cannot mutate transport state. */
public final class MouseInteractionTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Groups.resize(0,0,512,512);
        for(Block b:new Block[]{Blocks.itemBridge,Blocks.bridgeConduit,Blocks.powerNode,Blocks.massDriver,Blocks.container,Blocks.sorter,Blocks.door})b.init();
        Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit pilot=UnitTypes.alpha.create(Team.sharded);pilot.set(12*8,12*8);pilot.add();Vars.player.unit(pilot);
        var terrain=new TerrainElevation();var altitude=new UnitAltitudeSystem(terrain::heightAt);var physical=new World3DAdapter(terrain,(x,y)->0,altitude);var runtime=new LayerRuntime(physical);
        physical.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));
        try{
            runtime.grid().add(component(1,12,12,1,Blocks.container));runtime.grid().add(component(2,12,12,3,Blocks.container));
            var ray=new Ray(new Vec3(12,10,-12),new Vec3(0,-1,0));
            var hit=BuildingMousePicker.pick(runtime,ray,20,null);require(hit!=null&&hit.id()==2&&hit.layer()==3,"same XY downward ray selects nearest upper floor");
            require(hit.building()==runtime.building(2)&&hit.building().rotation==0&&hit.building().team==Team.sharded,"exact instance, team and orientation preserved");
            ray.set(new Vec3(10,1.7f,-12),new Vec3(1,0,0));hit=BuildingMousePicker.pick(runtime,ray,10,null);require(hit!=null&&hit.id()==1,"side ray under upper roof selects lower building");
            runtime.grid().add(new LayerComponent(3,LayerComponent.Kind.PLATFORM,12,12,5,LayerCatalog.PLATFORM,1,0,Team.sharded.id,LayerComponent.Mode.FREE,100,100,1,.16,LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]));
            ray.set(new Vec3(12,10,-12),new Vec3(0,-1,0));hit=BuildingMousePicker.pick(runtime,ray,20,null);require(hit!=null&&hit.platform()&&hit.layer()==5,"solid elevated platform occludes building and supplies placement floor");
            Building ground=ground(20,20,Blocks.itemBridge);runtime.grid().add(component(4,21,20,2,Blocks.itemBridge));
            var source=groundHit(ground);var target=upperHit(runtime,4);int count=runtime.transfers().links().size();
            var plan=MouseConnection.preview(runtime,source,target,null);require(plan.valid()&&plan.action().op()==LayerActions.Op.LINK,"mouse bridge uses existing LINK authority");
            require(runtime.transfers().links().size()==count,"hover validation is read-only");
            require(MouseConnection.submit(runtime,source,target,null).accepted(),"Z0 to Z2 bridge submits real existing request");
            require(runtime.transfers().links().stream().anyMatch(l->l.from()==source.id()&&l.to()==4),"real transfer registry contains mouse connection");
            target.building().team=Team.crux;require(!MouseConnection.preview(runtime,source,target,null).valid(),"enemy target red preview");target.building().team=Team.sharded;
            runtime.grid().add(component(5,29,20,2,Blocks.itemBridge));require(!MouseConnection.preview(runtime,source,upperHit(runtime,5),null).valid(),"range rejection before click");
            runtime.grid().add(component(6,32,30,1,Blocks.powerNode));runtime.grid().add(component(7,32,30,3,Blocks.powerNode));
            pilot.set(30*8,30*8);
            require(MouseConnection.submit(runtime,upperHit(runtime,6),upperHit(runtime,7),null).accepted(),"existing aligned upper power riser works by mouse");
            require(runtime.transfers().links().stream().anyMatch(l->l.kind()==LayerVerticalTransfers.Kind.POWER),"power link uses shared transfer registry");
            runtime.grid().add(component(8,34,30,3,Blocks.powerNode));require(MouseConnection.preview(runtime,upperHit(runtime,6),upperHit(runtime,8),null).valid(),"diagonal cross-floor node link within XYZ range accepted");
            runtime.grid().add(component(9,35,35,1,Blocks.massDriver));runtime.grid().add(component(10,35,35,3,Blocks.massDriver));
            var unsupported=MouseConnection.preview(runtime,upperHit(runtime,9),upperHit(runtime,10),null);require(!unsupported.valid()&&unsupported.reason().contains("same floor"),"missing cross-floor Mass Driver gameplay reported explicitly");
            runtime.grid().add(component(11,38,35,1,Blocks.massDriver));require(MouseConnection.submit(runtime,upperHit(runtime,9),upperHit(runtime,11),null).accepted(),"existing same-floor Mass Driver configuration reused");
            require(((mindustry.world.blocks.distribution.MassDriver.MassDriverBuild)runtime.building(9)).link==runtime.building(11).pos(),"native driver target configured");
            runtime.grid().add(component(12,40,40,1,Blocks.container));runtime.grid().add(component(13,40,40,3,Blocks.container));
            require(MouseConnection.submit(runtime,upperHit(runtime,12),upperHit(runtime,13),LayerVerticalTransfers.Kind.ITEMS).accepted(),"Shift-click inventory riser uses existing connector");
            Building a=ground(46,46,Blocks.powerNode),b=ground(48,46,Blocks.powerNode);
            var nativePlan=MouseConnection.preview(runtime,groundHit(a),groundHit(b),null);require(nativePlan.valid()&&nativePlan.action()==null&&nativePlan.ground()==a,"Z0 power retains native configuration RPC");
            require(!MouseConnection.preview(runtime,source,source,null).valid(),"self-link cannot be submitted");
            var stale=upperHit(runtime,5);runtime.grid().remove(5);require(!BuildingMousePicker.present(runtime,stale),"removed object identity is not reused");
            require(!MouseConnection.preview(runtime,source,stale,null).valid(),"stale click rejected");
            runtime.grid().add(component(14,52,52,2,Blocks.door));runtime.grid().add(component(15,52,54,2,Blocks.container));
            var door=(mindustry.world.blocks.defense.Door.DoorBuild)runtime.building(14);door.open=true;
            ray.set(new Vec3(52,3.1f,-50),new Vec3(0,0,-1));hit=BuildingMousePicker.pick(runtime,ray,10,null);require(hit!=null&&hit.id()==15,"open door's empty center does not occlude a visible destination");
            door.open=false;hit=BuildingMousePicker.pick(runtime,ray,10,null);require(hit!=null&&hit.id()==14,"closed door's solid geometry occludes destination");
            var top=upperHit(runtime,2);var coords=runtime.grid().coordinates();
            var placement=MouseConstructionTarget.resolve(top,ray,coords,Blocks.conveyor,false,false,1);
            require(placement.resolved()&&placement.layer()==3&&placement.x()==13&&placement.y()==13,"placement uses actual hit on even-size model rather than its anchor or selected plane");
            var slab=runtime.grid().get(3);var sb=coords.bounds(slab);
            var edge=new BuildingMousePicker.Hit(3,null,5,sb.maxX(),sb.centerY(),sb.maxZ(),sb,true);
            placement=MouseConstructionTarget.resolve(edge,ray,coords,null,true,false,1);
            require(placement.layer()==5&&placement.x()==13&&placement.y()==12,"platform edge expands only toward its exposed neighbour");
            var underside=new BuildingMousePicker.Hit(3,null,5,sb.centerX(),sb.centerY(),sb.minZ(),sb,true);
            require(!MouseConstructionTarget.resolve(underside,ray,coords,null,true,false,1).resolved(),"underside does not silently relocate to an arbitrary edge");
            require(!MouseConstructionTarget.resolve(null,ray,coords,Blocks.conveyor,false,false,3).resolved(),"sky needs explicit manual lock");
            ray.set(new Vec3(15,10,-15),new Vec3(0,-1,0));placement=MouseConstructionTarget.resolve(top,ray,coords,Blocks.conveyor,false,true,1);
            require(placement.resolved()&&placement.layer()==1&&placement.x()==15,"explicit manual lock is the only plane override");
            var entry=runtime.catalog().entry("titanium-wall-large",false,coords.spacing());
            runtime.grid().add(new LayerComponent(16,LayerComponent.Kind.BLOCK,5,5,1,entry.name(),entry.size(),0,Team.sharded.id,LayerComponent.Mode.FREE,entry.health(),entry.health(),entry.mass(),entry.height(),entry.support(),null,null));
            ray.set(new Vec3(5,12,-5),new Vec3(0,-1,0));hit=BuildingMousePicker.pick(runtime,ray,20,null);
            require(hit.id()==16&&hit.layer()==1&&hit.surface()==BuildingMousePicker.Surface.TOP&&hit.nz()==1&&hit.constructionLayer()==3,"tall wall top resolves its real normal and aligned Z3 socket");
            placement=MouseConstructionTarget.resolve(hit,ray,coords,Blocks.conveyor,false,false,0);
            require(placement.layer()==3&&placement.x()==5,"ordinary block top placement uses wall surface rather than wall's occupied floor");
            ray.set(new Vec3(2,2,-5),new Vec3(1,0,0));hit=BuildingMousePicker.pick(runtime,ray,10,null);
            require(hit.id()==16&&hit.surface()==BuildingMousePicker.Surface.SIDE&&hit.nx()==-1&&hit.nz()==0&&!hit.supportsConstruction(),"side normal does not masquerade as load-bearing top");
            ray.set(new Vec3(5,.5f,-5),new Vec3(0,1,0));hit=BuildingMousePicker.pick(runtime,ray,10,null);
            require(hit.id()==16&&hit.surface()==BuildingMousePicker.Surface.BOTTOM&&hit.nz()==-1&&!MouseConstructionTarget.resolve(hit,ray,coords,Blocks.conveyor,false,false,0).resolved(),"underside selection remains exact but cannot relocate construction");
            var legacyBounds=new LayerCoordinates.Bounds(0,0,1.4,2,2,3.2);
            var legacyHit=new BuildingMousePicker.Hit(17,null,1,1,1,3.2,legacyBounds,false,Blocks.titaniumWallLarge);
            require(MouseConstructionTarget.resolve(legacyHit,ray,coords,Blocks.conveyor,false,false,0).layer()==3,"unaligned cap uses ceiling, never rounded-down floor inside the wall");
            require(!MouseConstructionTarget.resolve(legacyHit,ray,new LayerCoordinates(1.4,2),Blocks.conveyor,false,false,0).resolved(),"surface above maximum floor reports unresolved instead of Z0 fallback");
            require(new LayerCoordinates(2,16).layerAtOrAbove(6.00000001)==3&&new LayerCoordinates(2,16).layerAtOrAbove(6.1)==4,"conversion respects configured spacing and finite boundary tolerance");

        }finally{runtime.close();altitude.close();}
        System.out.println("MouseInteractionTest: "+checks+" native checks passed.");
    }
    private static LayerComponent component(long id,int x,int y,int layer,Block b){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,b.name,b.size,0,Team.sharded.id,LayerComponent.Mode.FREE,b.health,b.health,2,fpv.net.HeadlessGeometry.heightFor(b),LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]);}
    private static Building ground(int x,int y,Block block){Tile tile=Vars.world.tile(x,y);tile.updateBlockReference(block);Building b=block.newBuilding();tile.build=b;b.init(tile,Team.sharded,false,0);return b;}
    private static BuildingMousePicker.Hit upperHit(LayerRuntime runtime,long id){var c=runtime.grid().get(id);var b=runtime.grid().coordinates().bounds(c);return new BuildingMousePicker.Hit(id,runtime.building(id),c.layer(),b.centerX(),b.centerY(),b.maxZ(),b,false);}
    private static BuildingMousePicker.Hit groundHit(Building b){double x=b.x/8.0,y=b.y/8.0,half=b.block.size*.5;var bounds=new LayerCoordinates.Bounds(x-half,y-half,0,x+half,y+half,fpv.net.HeadlessGeometry.heightFor(b.block));return new BuildingMousePicker.Hit(LayerVerticalTransfers.groundId(b.tileX(),b.tileY()),b,0,x,y,bounds.maxZ(),bounds,false);}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
