package fpv.units;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.construction.*;
import fpv.flight.FlightController;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

/** Native aircraft altitude integration against real layered volumes, with no renderer or user saves. */
public final class AircraftStructureClearanceTest {
    private static int checks;
    public static void main(String[] args){
        geometryAndCache();nativeOverflight();nativeCeiling();terrainRegression();
        System.out.println("AircraftStructureClearance: "+checks+" assertions passed.");
    }
    private static LayerComponent block(long id,int x,int y,int layer,int size,double height){
        return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,"copper-wall",size,0,1,LayerComponent.Mode.FREE,
            100,100,1,height,LayerComponent.SupportProfile.NONE,null,null);
    }
    private static LayerComponent slab(long id,int x,int y,int layer,int size){
        return new LayerComponent(id,LayerComponent.Kind.PLATFORM,x,y,layer,"fpv-platform",size,0,1,LayerComponent.Mode.FREE,
            100,100,1,.16,LayerComponent.SupportProfile.NONE,null,null);
    }
    private static void geometryAndCache(){
        LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);grid.add(block(1,13,7,5,1,4));
        AircraftStructureClearance probe=new AircraftStructureClearance(()->grid);
        probe.sample(0,7,30,7,3,.5,.2);close(probe.ahead(),11,1e-9,"continuous corridor catches thin wall between old sample positions");
        require(probe.support()==Double.NEGATIVE_INFINITY,"distant tower is not support underneath unit");
        probe.sample(0,8,30,8,3,.7,.2);close(probe.ahead(),11,1e-9,"full body wing footprint clears obstacle off center line");
        probe.sample(0,10,30,10,3,.5,.2);require(probe.ahead()==Double.NEGATIVE_INFINITY,"off-route buildings do not raise cruise");
        probe.sample(13,7,14,7,12,.5,.2);close(probe.support(),11,1e-9,"roof supports aircraft after actual overflight");
        grid.add(slab(2,24,7,10,5));
        probe.sample(23,7,28,7,3,.5,.2);require(probe.ahead()==Double.NEGATIVE_INFINITY,"aircraft already underneath canopy may leave through air gap");
        close(probe.ceiling(),13.84,1e-9,"real canopy underside retained");
        probe.sample(18,7,28,7,3,.5,.2);close(probe.ahead(),14,1e-9,"aircraft approaching canopy plans over roof");
        long builds=probe.rebuilds();for(int i=0;i<100;i++)probe.sample(18,7,28,7,3,.5,.2);
        require(probe.rebuilds()==builds,"idle aircraft queries reuse geometry index");
        grid.replace(grid.get(1).withHealth(50));probe.sample(0,7,30,7,3,.5,.2);
        require(probe.rebuilds()==builds,"HP-only damage does not rebuild spatial clearance index");
        grid.remove(2);probe.sample(18,7,28,7,3,.5,.2);
        require(probe.ahead()==Double.NEGATIVE_INFINITY&&probe.rebuilds()==builds+1,"removal invalidates cached roof immediately");
        LayerWorld other=new LayerWorld(LayerCoordinates.DEFAULT);LayerWorld[] source={grid};probe=new AircraftStructureClearance(()->source[0]);
        probe.sample(0,7,30,7,3,.5,.2);source[0]=other;probe.sample(0,7,30,7,3,.5,.2);
        require(probe.ahead()==Double.NEGATIVE_INFINITY,"world replacement cannot retain old towers");
    }
    private static void init(){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(80,80);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
    }
    private static Unit flyer(double x,double y){Unit unit=UnitTypes.flare.create(Team.sharded);unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));unit.add();return unit;}
    private static void nativeOverflight(){
        init();LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);grid.add(block(1,20,20,1,1,30));
        LayerPhysicalQueries physical=new LayerPhysicalQueries(()->grid,(id,damage)->{throw new AssertionError("AI clearance cannot damage structure");});
        Unit unit=flyer(10,20),controlled=flyer(10,22),replica=flyer(10,24);Unit ground=UnitTypes.dagger.create(Team.sharded);ground.set(20*Vars.tilesize,20*Vars.tilesize);ground.add();
        Object originalController=unit.controller();FlightController.State flight=new FlightController.State();flight.y=2;flight.x=10;flight.z=-22;
        UnitAltitudeSystem system=new UnitAltitudeSystem((x,z)->0,()->controlled);
        system.setControlledStateProvider(()->flight);system.setAircraftStructures(()->grid,(x,y)->0);system.setAircraftBarrier(physical::sweepUnit);
        var external=new fpv.world.Unit3DState(replica.id,replica.type.id,replica.team.id,10,24,2,0,0,0,0,0,0,2,.5,.12,true);
        system.setExternalStateProvider(u->u==replica?external:null);system.update(1.0/60);
        double initial=system.pose(unit).height(),previous=initial;boolean blocked=false,slid=false,over=false;
        for(int i=0;i<900;i++){
            double beforeX=unit.x/(double)Vars.tilesize;
            if(beforeX<45){unit.vel.set(8*Vars.tilesize/60f,0);unit.set(unit.x+8*Vars.tilesize/60f,unit.y);}else unit.vel.setZero();
            system.update(1.0/60);double currentX=unit.x/(double)Vars.tilesize,z=system.pose(unit).height();
            require(z-previous<=UnitAltitudeSystem.profile().maxClimbSpeed()/60+1e-6,"upper structure climb is rate bounded, no teleport through roof");
            if(currentX<20&&currentX-beforeX<.01){blocked=true;if(z>previous+.01)slid=true;}
            if(currentX>19.5&&currentX<20.5){over=true;require(z-system.unitState(unit).halfHeight()>31.4,"full tilted aircraft passes above tall upper wall: x="+currentX+" z="+z+" frame="+i);}
            require(Double.isFinite(z)&&z<38,"tower cruise remains bounded by roof plus cruise envelope");previous=z;
        }
        require(blocked&&slid,"close tall tower blocks forward motion while aircraft climbs along wall");
        require(over&&unit.x/Vars.tilesize>40,"aircraft clears tower and resumes original horizontal route");
        require(system.pose(unit).height()<initial+.6,"aircraft descends smoothly to normal cruise after obstruction");
        require(system.unitState(controlled).z()==2&&system.unitState(replica)==external,"FPV and authoritative network pose providers are not auto-climbed");
        require(system.pose(ground).height()<1,"ground unit stays in existing air gap and is not lifted to highest floor");
        require(unit.controller()==originalController&&grid.size()==1&&grid.get(1).health()==100,"native AI ownership and map structures remain unchanged");
        double z=system.pose(unit).height();system.update(0);close(system.pose(unit).height(),z,0,"paused building clearance does not advance altitude");
        grid.remove(1);system.update(.1);require(system.pose(unit).desiredHeight()<6,"removed structure cannot leave stale climb target");
        system.close();Groups.clear();
    }
    private static void nativeCeiling(){
        init();LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);grid.add(slab(1,20,20,6,8));grid.add(block(2,20,20,1,1,5));
        LayerPhysicalQueries physical=new LayerPhysicalQueries(()->grid,(id,damage)->{});Unit unit=flyer(17,20);
        UnitAltitudeSystem system=new UnitAltitudeSystem((x,z)->0);system.setAircraftStructures(()->grid,(x,y)->0);system.setAircraftBarrier(physical::sweepUnit);system.update(1.0/60);
        for(int i=0;i<600;i++){
            unit.vel.set(5*Vars.tilesize/60f,0);unit.set(unit.x+5*Vars.tilesize/60f,unit.y);system.update(1.0/60);
            if(unit.x/Vars.tilesize<24.5)require(system.pose(unit).height()<8.25,"overflight preference never moves aircraft through an existing overhead ceiling");
        }
        system.close();Groups.clear();
    }
    private static void terrainRegression(){
        init();Unit unit=flyer(10,10);LayerWorld empty=new LayerWorld(LayerCoordinates.DEFAULT);
        double[] terrain={0};UnitAltitudeSystem original=new UnitAltitudeSystem((x,y)->terrain[0]);
        UnitAltitudeSystem extended=new UnitAltitudeSystem((x,y)->terrain[0]);extended.setAircraftStructures(()->empty,(x,y)->terrain[0]);
        for(int i=0;i<300;i++){
            terrain[0]=i<100?i*.05:i<200?5:0;unit.vel.set(i<200?.5f:0,0);
            original.update(1.0/60);extended.update(1.0/60);
            close(extended.pose(unit).height(),original.pose(unit).height(),0,"empty upper grid retains existing terrain altitude");
            close(extended.pose(unit).verticalVelocity(),original.pose(unit).verticalVelocity(),0,"empty upper grid retains original vertical response");
        }
        original.close();extended.close();Groups.clear();
    }
    private static void close(double value,double expected,double tolerance,String why){require(Double.isFinite(value)&&Math.abs(value-expected)<=tolerance,why+": "+value+" expected "+expected);}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
