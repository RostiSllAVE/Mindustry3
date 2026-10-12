package fpv.control;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import fpv.construction.*;
import fpv.effects.RadialExplosion;
import fpv.flight.*;
import fpv.generated.FlightProfiles;
import fpv.net.*;
import fpv.render.BoostLegPose;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import java.io.*;
import java.util.*;

/** Installed native content, real control/physics/ownership/restrictions; no atlas or GPU required. */
public final class NativeBoostTest {
    private static int checks;
    private static void yes(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,768,768);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);System.setProperty("fpv.smoke","true");
        for(Tile t:Vars.world.tiles)t.setFloor((mindustry.world.blocks.environment.Floor)Blocks.stone);
        var alt=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,alt);
        alt.setGroundFamily(new fpv.units.ground.GroundFamilyLocomotion(world));
        var boost=new NativeBoostSystem(world,alt,new RadialExplosion(world));alt.setRuntimeStateProvider(boost::state);alt.setRuntimeVerticalImpulse(boost::addVerticalVelocity);
        var player=Player.create();player.team(Team.sharded);player.add();Vars.player=player;
        var eligible=new ArrayList<String>();
        for(var type:Vars.content.units()){
            var unit=type.create(Team.sharded);
            yes(UnitControlCapabilities.boostEligible(unit)==(type.canBoost&&!type.internal&&!type.flying&&!(unit instanceof BlockUnitc)),"native capability "+type.name);
            if(UnitControlCapabilities.boostEligible(unit))eligible.add(type.name);
        }
        System.out.println("Native ground boost types: "+eligible);
        for(var type:new mindustry.type.UnitType[]{UnitTypes.nova,UnitTypes.quasar,UnitTypes.vela}){
            var unit=type.create(Team.sharded);unit.set(48*8,48*8);unit.add();player.unit(unit);
            float speed=type.speed,reload=unit.mounts[0].weapon.reload,health=unit.health;double start=world.unitState(unit).z();
            yes(unit.canShoot(),"ground native shooting "+type.name);
            for(int i=0;i<90;i++)tick(boost,alt,player,unit,control(true,1,.3));
            yes(boost.active(unit)&&boost.state(unit).z()>start+1,"continuous native takeoff "+type.name);
            yes(unit.isFlying()&&!unit.canShoot(),"installed native boost fire restriction "+type.name);
            var snapshot=boost.snapshot();var saved=snapshot.get(0);boost.restoreSaved(snapshot);
            yes(boost.landing(unit)&&Math.abs(boost.state(unit).z()-saved.y())<1e-9,"saved boost resumes landing at exact height");
            tick(boost,alt,player,unit,control(false,1,0));
            yes(boost.active(unit)&&boost.landing(unit)&&boost.state(unit).z()>start+.3,"ground request does not snap");
            for(int i=0;i<1800&&boost.active(unit);i++)tick(boost,alt,player,unit,control(false,1,0));
            yes(!boost.active(unit)&&!unit.isFlying()&&unit.canShoot(),"physical grounded handoff "+type.name+" "+boost.describeForSmoke(unit));
            yes(unit.health==health&&type.speed==speed&&unit.mounts[0].weapon.reload==reload,"no false crash/stat/reload mutation");
            for(int i=0;i<45;i++)tick(boost,alt,player,unit,control(true,1,.3));
            var ai=new mindustry.ai.types.CommandAI();unit.controller(ai);ai.setStance(mindustry.ai.UnitStance.boost,true);
            double inherited=boost.state(unit).z();boost.release(unit);
            yes(!boost.active(unit)&&unit.controller()==ai&&unit.isFlying()&&Math.abs(world.unitState(unit).z()-inherited)<1e-6,"native boosted AI handoff "+type.name);
            alt.update(.1);yes(unit.controller()==ai&&world.unitState(unit).flying()&&Math.abs(world.unitState(unit).z()-inherited)<3,"AI continues native smooth flight "+type.name);
            unit.remove();boost.after();
        }
        var dagger=UnitTypes.dagger.create(Team.sharded);dagger.set(48*8,48*8);dagger.add();player.unit(dagger);boost.control(player,dagger,control(true,1,1));
        yes(!boost.active(dagger)&&!dagger.type.canBoost,"Dagger cannot request natural flight even with Creator permission");Core.settings.put(CreatorFlightSystem.SETTING,true);boost.control(player,dagger,control(true,1,1));yes(!boost.active(dagger),"Creator cheat remains separate");dagger.remove();
        var flyer=UnitTypes.flare.create(Team.sharded);flyer.add();player.unit(flyer);boost.control(player,flyer,control(true,1,1));yes(!boost.active(flyer)&&flyer.type.flying&&flyer.canShoot(),"dedicated aircraft native path unchanged");flyer.remove();
        var p=FlightProfiles.create();double small=NativeBoostProfile.speed(UnitTypes.nova.speed,UnitTypes.nova.boostMultiplier,p.metersPerTile,1),large=NativeBoostProfile.speed(UnitTypes.vela.speed,UnitTypes.vela.boostMultiplier,p.metersPerTile,1);
        yes(small!=large,"native speed/multiplier distinguish unit performances");var resolved=NativeBoostProfile.resolve(p,large);yes(resolved.massKg==p.massKg&&resolved.inertiaXKgM2==p.inertiaXKgM2,"reuse resolved mass/inertia");
        var grid=new LayerWorld(new LayerCoordinates(2,8));
        // A physical upper platform, not a projected defense.
        grid.add(new LayerComponent(1,LayerComponent.Kind.PLATFORM,48,48,2,"fpv-platform",8,0,Team.sharded.id,LayerComponent.Mode.FREE,1000,1000,1,.16,LayerComponent.SupportProfile.NONE,null,null));
        world.setConstruction(new LayerPhysicalQueries(()->grid,(id,damage)->{}));
        var nova=UnitTypes.nova.create(Team.sharded);nova.set(48*8,48*8);nova.add();player.unit(nova);
        double upper=NativeBoostLanding.height(world,alt,nova,48,48,12,.4,.2);
        yes(upper>4,"elevated landing support selected");yes(Double.isNaN(NativeBoostLanding.height(world,alt,nova,48,48,4.1,.4,.2)),"occupied upper volume rejected");
        world.setUnitBarrier((u,ax,ay,az,bx,by,bz,r,h)->new fpv.physics.SweptBodies.Hit(0,1,0,0));
        yes(Double.isNaN(NativeBoostLanding.height(world,alt,nova,48,48,12,.4,.2)),"blocked/insufficient physical clearance rejected");world.setUnitBarrier(null);
        boost.restoreSaved(List.of(new CreatorFlightSystem.Saved(nova.id,nova.type.name,nova.team.id,48,48,0,.5,.12,48,12,-48,0,0,0,0,0,0,1,0,0,0)));
        for(int i=0;i<2400&&boost.active(nova);i++)tick(boost,alt,player,nova,control(false,1,0));
        yes(!boost.active(nova)&&!nova.isFlying()&&world.unitState(nova).z()>4,"actual upper-floor boost touchdown");
        for(int i=0;i<120;i++){alt.captureGroundIntent(1d/60);nova.update();alt.update(1d/60);}
        yes(world.unitState(nova).z()>4&&!world.unitState(nova).flying(),"ordinary mech remains on upper platform after ground handoff");
        var groundSave=alt.boostGroundSnapshot();var groundBytes=new ByteArrayOutputStream();NativeBoostGroundSave.write(new DataOutputStream(groundBytes),groundSave);
        yes(NativeBoostGroundSave.read(new DataInputStream(new ByteArrayInputStream(groundBytes.toByteArray()))).equals(groundSave),"bounded upper support height save roundtrip");
        for(int i=0;i<90;i++)tick(boost,alt,player,nova,control(true,1,.3));
        yes(boost.active(nova)&&boost.state(nova).z()>upper+1,"takeoff from elevated platform");
        // Release to an existing native CommandAI that explicitly wants boost. No forced landing
        // or controller replacement; native altitude solver receives the exact flight height.
        var command=new mindustry.ai.types.CommandAI();nova.controller(command);command.setStance(mindustry.ai.UnitStance.boost,true);
        double air=boost.state(nova).z();boost.release(nova);
        yes(!boost.active(nova)&&nova.controller()==command&&nova.isFlying()&&Math.abs(world.unitState(nova).z()-air)<1e-6,"native boosted AI handoff retains controller and XYZ");
        nova.remove();
        var crashing=UnitTypes.nova.create(Team.sharded);crashing.set(20*8,20*8);crashing.add();player.unit(crashing);float crashHp=crashing.health;
        boost.restoreSaved(List.of(new CreatorFlightSystem.Saved(crashing.id,crashing.type.name,crashing.team.id,20,20,0,.5,.12,20,.35,-20,0,-80/p.metersPerTile,0,0,0,0,1,0,0,0)));
        for(int i=0;i<10&&boost.active(crashing)&&!crashing.dead;i++)tick(boost,alt,player,crashing,control(false,1,0));
        yes(crashing.dead||crashing.health<crashHp,"real unrecoverable landing impact retains normal crash damage");if(crashing.isAdded())crashing.remove();boost.after();
        var c=control(true,0,.5);var copy=NetProtocol.read(NetProtocol.bytes(out->NetProtocol.controls(out,c)),NetProtocol::controls);yes(copy.equals(c)&&copy.boostFlight()&&!copy.creatorFlight(),"boost bit independently encoded");
        var fold=new BoostLegPose(false);for(int i=0;i<30;i++)fold.advance(true,1d/60);yes(fold.amount()==1,"all tripod transforms fully fold");for(int i=0;i<30;i++)fold.advance(false,1d/60);yes(fold.amount()==0,"deploy after landing");
        boost.clear();alt.close();System.out.println("NativeBoost: "+checks+" assertions passed.");
    }
    private static NetworkControls control(boolean boosted,int mode,double climb){return new NetworkControls(true,mode,.6,0,0,0,0,0,climb,0,0,0,1,0,false,false,boosted);}
    private static void tick(NativeBoostSystem boost,UnitAltitudeSystem alt,Player p,Unit u,NetworkControls c){Time.delta=1;boost.control(p,u,c);boost.before(1d/60);u.update();boost.after();alt.update(1d/60);}
}
