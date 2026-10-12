package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.flight.FlightController;
import fpv.physics.CrashSystem;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.units.ground.GroundLocomotionModel;
import fpv.units.ground.GroundUnit3DModelProfile;
import fpv.world.Unit3DState;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

/** Production CrashSystem and ground solver both consume actual indexed upper-volume queries. */
public final class LayerMotionIntegrationTest {
    private static int checks;
    private static LayerWorld grid;
    private static LayerPhysicalQueries volumes;
    private static final GroundLocomotionModel model=new GroundLocomotionModel(12,9,.15,.1);
    private static final GroundUnit3DModelProfile low=new GroundUnit3DModelProfile("low-test-walker",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,4,1,.1,0,.25,1);
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.state.rules.unitCap=1000;Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(24,24);Vars.world.setGenerating(true);Vars.world.tiles.fill();Groups.resize(0,0,192,192);
        TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitudes=new UnitAltitudeSystem(terrain::heightAt);World3DAdapter world=new World3DAdapter(terrain,(x,y)->0,altitudes);
        grid=new LayerWorld(LayerCoordinates.DEFAULT);volumes=new LayerPhysicalQueries(()->grid,(id,damage)->{throw new AssertionError("collision-only fixture must not damage structures");});world.setConstruction(volumes);
        Unit drone=UnitTypes.flare.create(Team.sharded);drone.set(8*8,8*8);drone.add();
        altitudes.setRuntimeStateProvider(unit->new Unit3DState(unit.id,unit.type.id,unit.team.id,unit.x/8,unit.y/8,1,0,0,0,0,0,0,1,.3,.2,true));
        CrashSystem crash=new CrashSystem((x,y)->0,null,2);crash.setWorld(world);
        grid.add(component(1,LayerComponent.Kind.PLATFORM,8,8,2,5));
        FlightController.State up=flight(8,4,8,0,30,0);var hit=crash.resolve(drone,up,8,1,-8,.1,false);
        require(hit.hit()&&hit.ny()<0,"real FPV crash adapter finds underside of upper slab");
        require(up.y<=2.64-.2+1e-5&&up.y>2.4&&up.vy==0,"drone is clamped below ceiling with upward velocity stopped");
        FlightController.State down=flight(8,1,8,0,-30,0);hit=crash.resolve(drone,down,8,4,-8,.1,false);
        require(hit.hit()&&hit.ny()>0,"real FPV crash adapter finds slab top during descent");
        require(down.y>=2.8+.2-1e-5&&down.y<3.02&&down.vy==0,"drone settles above floor rather than tunnelling through it");
        FlightController.State gap=flight(10,1,8,40,0,0);hit=crash.resolve(drone,gap,6,1,-8,.1,false);
        require(!hit.hit()&&gap.x==10&&gap.y==1&&gap.vx==40,"real drone flies through air beneath roof without a fictitious solid column");
        require(crash.impactDeaths()==0&&crash.lastFeedback()==null,"collision-only verification has no damage or death side effects");
        drone.remove();

        grid.clear();grid.add(component(2,LayerComponent.Kind.PLATFORM,8,8,1,3));
        Walker shortBody=new Walker(low,5,8,.2,.2,0);shortBody.walk(10,8,240);
        require(shortBody.state.x()>9.5,"short walker passes under an actual indexed slab");
        Walker tallBody=new Walker(low,5,8,.2,.7,0);tallBody.walk(10,8,240);
        require(tallBody.state.x()<6.5&&tallBody.state.blocked(),"tall body cannot enter same roof opening");
        var extendable=new GroundUnit3DModelProfile("extendable-test-walker",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,4,1,.1,6,.25,1);
        Walker raising=new Walker(extendable,8,8,.2,.2,0);
        for(int i=0;i<120;i++)model.step(raising.state,extendable,8,8,0,.2,.2,1d/60,raising.surface,extendable.maximumClearance());
        require(raising.state.z()+.2<=1.24+1e-5&&raising.state.z()>.3,"upward stance extension is stopped at real roof underside");
        require(raising.state.blocked(),"ceiling contact remains physically blocked despite ample leg extension");

        grid.clear();grid.add(component(3,LayerComponent.Kind.PLATFORM,8,8,1,7));
        grid.add(component(4,LayerComponent.Kind.BLOCK,8,7,1,1));grid.add(component(5,LayerComponent.Kind.BLOCK,8,9,1,1));
        Walker narrow=new Walker(low,5,8,.2,.2,1.4);narrow.walk(10,8,240);
        Walker wide=new Walker(low,5,8,.6,.2,1.4);wide.walk(10,8,240);
        require(narrow.state.x()>9.5,"narrow walker passes physical upper-floor doorway");
        require(wide.state.x()<7.5&&wide.state.blocked(),"wide walker cannot pass same doorway by center-only collision");
        require(narrow.state.z()>=1.4+.2&&wide.state.z()>=1.4+.2,"both walkers remain supported by actual floor slab");
        require(Vars.world.tile(8,8).block()==mindustry.content.Blocks.air,"all movement leaves original native grid unchanged");
        altitudes.close();System.out.println("LayerMotionIntegrationTest: "+checks+" checks passed");
    }
    private static FlightController.State flight(double x,double height,double y,double vx,double vertical,double vy){var state=new FlightController.State();state.x=x;state.y=height;state.z=-y;state.vx=vx;state.vy=vertical;state.vz=-vy;return state;}
    private static LayerComponent component(long id,LayerComponent.Kind kind,int x,int y,int layer,int size){return new LayerComponent(id,kind,x,y,layer,kind==LayerComponent.Kind.PLATFORM?"fpv-platform":"copper-wall",size,0,Team.sharded.id,LayerComponent.Mode.FREE,100,100,1,kind==LayerComponent.Kind.PLATFORM?.16:1.4,LayerComponent.SupportProfile.NONE,null,null);}
    private static final class Walker {
        final GroundUnit3DModelProfile profile;final double radius,half,initialFloor;final GroundLocomotionModel.State state;final GroundLocomotionModel.Surface surface;
        Walker(GroundUnit3DModelProfile p,double x,double y,double r,double h,double floor){
            profile=p;radius=r;half=h;initialFloor=floor;
            surface=new GroundLocomotionModel.Surface(){
                public double support(double px,double py){return volumes.supportBelow(px,py,state==null?initialFloor+.04:state.z()-half+.04,0);}
                public double obstruction(double px,double py){return volumes.obstructionTop(px,py,state==null?initialFloor+p.normalStance()+2*half:state.z()+half,support(px,py));}
                public fpv.physics.SweptBodies.Hit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){return volumes.sweepUnit(null,ax,ay,az,bx,by,bz,radius,halfHeight);}
            };
            state=model.create(p,x,y,0,r,h,surface);
        }
        void walk(double targetX,double targetY,int ticks){for(int i=0;i<ticks;i++)model.step(state,profile,Math.min(targetX,state.x()+.04),targetY,0,radius,half,1d/60,surface);}
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
