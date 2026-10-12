package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import fpv.control.*;
import fpv.net.NetworkControls;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import java.io.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.TypeIO;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/** Real native control proxies, unchanged XY tile ownership, manual lifecycle and resource exhaustion. */
public final class LayerTurretControlNativeTest {
    private static int checks;
    private static final class Keys extends MockInput {boolean release;@Override public boolean keyTap(arc.input.KeyBind key){return release&&key==mindustry.input.Binding.respawn;}}
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();Core.input=new MockInput();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(60,60);Vars.world.setGenerating(true);Vars.world.tiles.fill();Groups.resize(0,0,480,480);Time.setDeltaProvider(()->1);
        for(Block block:new Block[]{Blocks.duo,Blocks.salvo,Blocks.scatter,Blocks.lancer,Blocks.hail,Blocks.wave,Blocks.meltdown,Blocks.sublimate})block.init();for(var b:Vars.content.bullets())b.init();
        Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);Vars.player=Player.create();Vars.player.team(Team.sharded);Vars.player.add();
        Unit previous=UnitTypes.alpha.create(Team.sharded);previous.set(4*8,4*8);previous.add();Vars.player.unit(previous);
        var terrain=new TerrainElevation();var heights=new UnitAltitudeSystem(terrain::heightAt);var world=new World3DAdapter(terrain,(x,z)->0,heights);var runtime=new LayerRuntime(world);world.setConstruction(new LayerPhysicalQueries(runtime::grid,runtime::damage,runtime::building));Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        runtime.setTurretAiming(new LayerBuildingAdapter.TurretAiming(){public Posc enemy(Turret.TurretBuild b,float range){return null;}public boolean valid(Turret.TurretBuild b,Posc target){return false;}public boolean aim(Turret.TurretBuild b,Posc target){return false;}});
        long id=place(runtime,12,12,3,Blocks.duo);var duo=(ItemTurret.ItemTurretBuild)runtime.building(id);var proxy=duo.unit();
        require(proxy instanceof LayerTurretUnit&&proxy.isAI()&&proxy.isAdded()&&proxy.playerControllable()&&duo.canControl(),"live native upper turret proxy has initialized control capabilities");
        var keys=new Keys();var input=new fpv.input.FpvDesktopInput(keys,fpv.generated.InputProfiles.create());input.setFpvEnabled(true);input.setBuildMode(true);
        input.setControlAction(u->runtime.requestPossess(id),()->{if(runtime.turretControls().owns(Vars.player)){runtime.requestReleaseTurret();return true;}return false;});input.setControlPick(proxy);
        require(input.applyControlSelection(true,true)&&Vars.player.unit()==proxy&&duo.controlled(),"existing FPV Ctrl-click boundary possesses exact upper identity");
        require(UnitControlCapabilities.validOwner(Vars.player,proxy)&&UnitControlCapabilities.stationary(proxy),"shared control authority recognizes valid stationary upper owner");
        var pose=world.unitState(proxy);require(pose!=null&&Math.abs(pose.z()-world.buildingTop(duo)-.12)<1e-7&&pose.vz()==0&&!pose.flying(),"fixed player pose follows actual Z3 turret top");
        require(!runtime.requestPossess(id).accepted(),"already controlled turret cannot be possessed twice");
        Player other=Player.create();other.team(Team.crux);Unit foe=UnitTypes.alpha.create(Team.crux);foe.add();other.unit(foe);
        require(!runtime.apply(other,LayerActions.Action.possessTurret(id)).accepted(),"foreign-team possession rejected");
        var pilot=new GroundedPilot();var controls=new NetworkControls(true,1,1,1,1,1,1,1,1,1,1,.8,0,.6,true);
        float x=proxy.x,y=proxy.y;pilot.step(Vars.player,proxy,controls,heights,.1);require(proxy.x==x&&proxy.y==y&&proxy.vel.isZero()&&runtime.grid().get(id).layer()==3,"movement/climb input cannot move or fly a turret");
        duo.handleStack(Items.copper,5,null);int ammo=duo.totalAmmo;for(int i=0;i<300;i++){Time.update();runtime.update(1d/60);}
        require(duo.totalShots>0&&duo.totalAmmo<ammo&&duo.target==null,"manual firing works without an automatic enemy target and consumes real ammo");int count=duo.totalShots;for(int i=0;i<300;i++){Time.update();runtime.update(1d/60);}require(duo.totalAmmo==0&&duo.totalShots>=count,"ammo exhaustion respected");count=duo.totalShots;for(int i=0;i<100;i++){Time.update();runtime.update(1d/60);}require(duo.totalShots==count,"empty manually controlled turret stops firing");
        byte[] save=runtime.replication();require(runtime.requestReleaseTurret().accepted()&&Vars.player.unit()==previous&&!duo.controlled(),"release returns previous live unit and restores native turret AI");
        Vars.state.rules.possessionAllowed=false;require(!runtime.requestPossess(id).accepted(),"native possession rule enforced");Vars.state.rules.possessionAllowed=true;
        // Native unit serialization must not select the ground turret beneath an elevated one.
        Vars.world.tile(12,12).setBlock(Blocks.duo,Team.crux,0);var ground=Vars.world.tile(12,12).build;
        var out=new ByteArrayOutputStream();TypeIO.writeUnit(new Writes(new DataOutputStream(out)),proxy);var decoded=TypeIO.readUnit(new Reads(new DataInputStream(new ByteArrayInputStream(out.toByteArray()))));
        require(decoded==null&&ground.team==Team.crux&&ground.isValid(),"upper wire view safely decodes to null, never an overlapping Z0 turret");
        require(duo.pos()==duo.tile.pos()&&duo.pos()==arc.math.geom.Point2.pack(12,12),"real building/logistics packed position stays unchanged");
        require(runtime.requestPossess(id).accepted(),"re-possess live turret");duo.kill();runtime.update(.01);runtime.turretControls().update();require(Vars.player.unit()==previous&&!runtime.turretControls().owns(Vars.player),"destroyed upper turret releases safely to prior unit");
        runtime.restore(save,false);var restored=(ItemTurret.ItemTurretBuild)runtime.building(id);require(restored!=null&&restored.totalAmmo==0&&!restored.controlled()&&restored.unit() instanceof LayerTurretUnit,"save/load preserves functional native ammo and creates unpossessed safe proxy");
        input.setControlPick(restored.unit());require(input.applyControlSelection(true,true),"FPV can reselect loaded upper turret");keys.release=true;input.update();keys.release=false;require(Vars.player.unit()==previous&&!restored.controlled(),"existing respawn/release binding restores pilot without duplicate native unitClear");
        require(runtime.requestPossess(id).accepted(),"team-change fixture possession");Vars.player.team(Team.crux);runtime.turretControls().update();require(restored.team==Team.sharded&&!runtime.turretControls().owns(Vars.player),"team change safely releases control without changing real turret ownership");Vars.player.team(Team.sharded);Vars.player.unit(previous);
        for(Block block:new Block[]{Blocks.salvo,Blocks.scatter,Blocks.lancer,Blocks.hail,Blocks.wave,Blocks.meltdown,Blocks.sublimate}){
            long n=place(runtime,30,20,2,block);var t=(Turret.TurretBuild)runtime.building(n);require(t.canControl()&&runtime.requestPossess(n).accepted(),"native control family "+block.name);require(runtime.requestReleaseTurret().accepted(),"release family "+block.name);runtime.requestRemove(n);
        }
        var bytes=new ByteArrayOutputStream();LayerActions.write(new DataOutputStream(bytes),LayerActions.Action.possessTurret(id));require(LayerActions.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).equals(LayerActions.Action.possessTurret(id)),"existing authority-channel possession action roundtrip");
        runtime.close();System.out.println("LayerTurretControlNative: "+checks+" assertions passed.");
    }
    private static long place(LayerRuntime runtime,int x,int y,int z,Block block){var r=runtime.requestPlace(x,y,z,block.name,false,0,LayerComponent.Mode.FREE);require(r.accepted(),"fixture "+block.name+": "+r.reason());return r.componentId();}
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
