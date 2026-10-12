package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import fpv.combat.Combat3DSystem;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.units.ground.GroundLocomotionModel;
import fpv.units.ground.GroundUnitProfiles;
import fpv.world.Unit3DState;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

/** Real native walker weapons against an unreachable elevated enemy barrier; fresh JVM, no profile/GL. */
public final class LayerNavigationNativeTest {
    private static int checks;
    private static boolean fire=true;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        Vars.state=new GameState();Vars.net=new Net(null);Vars.indexer=new mindustry.ai.BlockIndexer();Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.state.rules.fog=true;Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        UnitTypes.atrax.init();for(var bullet:Vars.content.bullets())bullet.init();
        TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitudes=new UnitAltitudeSystem(terrain::heightAt);
        World3DAdapter world=new World3DAdapter(terrain,(x,y)->0,altitudes);
        LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);
        for(int y=0;y<64;y++)grid.add(new LayerComponent(y+1,LayerComponent.Kind.BLOCK,11,y,1,"copper-wall",1,0,Team.crux.id,
            LayerComponent.Mode.FREE,1000,1000,1,1.4,LayerComponent.SupportProfile.NONE,null,null));
        double[] acceptedDamage={0};LayerPhysicalQueries physical=new LayerPhysicalQueries(()->grid,(id,amount)->acceptedDamage[0]+=amount);world.setConstruction(physical);
        Combat3DSystem combat=new Combat3DSystem(world,()->true,unit->null);combat.setProjectedDefense(physical);combat.install();
        Unit walker=UnitTypes.atrax.create(Team.sharded);walker.set(9*8,32*8);walker.rotation=0;walker.add();
        AIController controller=new AIController(){@Override public boolean shouldFire(){return fire;}};walker.controller(controller);
        double height=2.2,radius=.3,half=.2;
        altitudes.setRuntimeStateProvider(unit->new Unit3DState(unit.id,unit.type.id,unit.team.id,unit.x/8,unit.y/8,height,0,0,0,unit.rotation,0,0,height,radius,half,false));
        GroundLocomotionModel model=new GroundLocomotionModel(3,3,.2,.2);
        GroundLocomotionModel.Surface floor=new GroundLocomotionModel.Surface(){public double support(double x,double y){return 0;}public double obstruction(double x,double y){return 0;}};
        var profile=GroundUnitProfiles.profile(walker);require(profile!=null,"real Atrax uses grounded navigation family");
        var state=model.create(profile,9,32,0,radius,half,floor);model.seedHeight(state,profile,height,half);
        LayerNavigationAdapter navigation=new LayerNavigationAdapter(world);
        require(navigation.next(walker,state,9.1,32,radius,half,1d/60)==null,"unbroken full-map barrier has no local detour");
        var obstacle=LayerNavigationAdapter.obstacle(walker);require(obstacle!=null&&obstacle.team()==Team.crux.id,"unreachable enemy volume is selected from actual physical sweep");
        require(obstacle.z()>1.4&&obstacle.z()<2.8,"attack target lies within actual upper floor rather than native Z0");
        require(walker.controller()==controller,"native high-level AI controller retained");
        var source=combat.originalType(walker.mounts[0].weapon.bullet);
        require(LayerNavigationAdapter.aim(walker,source)==null,"navigation cannot shoot or damage without native weapon update");
        fire=false;walker.mounts[0].weapon.update(walker,walker.mounts[0]);
        require(LayerNavigationAdapter.aim(walker,source)==null&&!walker.mounts[0].shoot,"native hold-fire prevents obstruction attack");
        fire=true;
        for(int i=0;i<160;i++){
            Vars.state.updateId++;Time.update();navigation.next(walker,state,9.1,32,radius,half,1d/60);
            for(var mount:walker.mounts)mount.weapon.update(walker,mount);
        }
        int bullets=0;for(Bullet bullet:Groups.bullet)if(bullet.owner==walker){bullets++;require(combat.managed(bullet),"obstruction fire uses existing native-to-XYZ bullet adapter");var shot=combat.projectileState(bullet);require(shot!=null&&shot.z()>1.4,"native weapon muzzle retains upper XYZ height");}
        require(bullets>0,"original Atrax weapon lifecycle emits real owned projectiles");
        require(acceptedDamage[0]==0,"aim/weapon selection never inflicts free damage before actual projectile impact");
        require(walker.mounts[0].totalShots>0&&walker.mounts[0].totalShots<160,"native weapon reload constrains fire frequency");
        require(LayerNavigationAdapter.aim(walker,source)!=null,"only actual weapon source carries canonical XYZ attack target");
        require(LayerNavigationAdapter.aim(walker,new mindustry.entities.bullet.BasicBulletType())==null,"unrelated mounts cannot inherit another weapon target");
        var emitted=new java.util.ArrayList<Bullet>();for(Bullet bullet:Groups.bullet)if(bullet.owner==walker)emitted.add(bullet);
        for(int i=0;i<40;i++){Time.update();for(Bullet bullet:emitted)if(bullet.isAdded())bullet.type.update(bullet);}
        require(acceptedDamage[0]>0,"only actual XYZ projectile impact applies enemy obstruction damage");
        var target=grid.get(obstacle.componentId());grid.remove(target.id());
        require(LayerNavigationAdapter.obstacle(walker)==null&&LayerNavigationAdapter.aim(walker,source)==null,"removing obstruction clears stale aim without replacing native AI");
        navigation.clear();grid.clear();
        for(int y=0;y<64;y++)grid.add(new LayerComponent(y+1,LayerComponent.Kind.BLOCK,11,y,1,"copper-wall",1,0,Team.sharded.id,
            LayerComponent.Mode.FREE,1000,1000,1,1.4,LayerComponent.SupportProfile.NONE,null,null));
        require(navigation.next(walker,state,9.1,32,radius,half,1d/60)==null&&LayerNavigationAdapter.obstacle(walker)==null,"friendly obstruction is never offered as a damage target");
        navigation.clear();for(int y=30;y<=34;y++)grid.remove(y+1);
        require(navigation.next(walker,state,9.1,32,radius,half,1d/60)==null,"clear opening leaves native forward intent unchanged");
        navigation.clear();combat.close();altitudes.close();
        System.out.println("LayerNavigationNativeTest: "+checks+" checks passed");
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
