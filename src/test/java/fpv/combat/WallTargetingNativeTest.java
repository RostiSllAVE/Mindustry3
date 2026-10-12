package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import fpv.construction.*;
import fpv.units.ground.*;
import mindustry.Vars;
import mindustry.ai.types.GroundAI;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import java.util.*;

/** Native static buildings are valid tiles, even when absent from the update group. */
public final class WallTargetingNativeTest {
    private static int checks;
    private static final Map<Unit,Unit3DState> poses=new IdentityHashMap<>();
    private static final class GroundFixture extends GroundAI {
        boolean fire=true;
        void seed(Building b){target=b;for(var m:unit.mounts)m.target=b;}
        Teamc objective(){return target;}
        @Override public boolean retarget(){return false;}
        @Override public boolean shouldFire(){return fire;}
    }
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.set(GameState.State.playing);
        var altitudes=new UnitAltitudeSystem((x,y)->0);altitudes.setExternalStateProvider(poses::get);
        var world=new World3DAdapter(new TerrainElevation(),(x,y)->{var t=Vars.world.tile((int)Math.round(x),(int)Math.round(-y));return t!=null&&t.build!=null?fpv.render.FpvWorldRenderer.heightFor(t.block()):0;},altitudes);
        boolean[] enabled={false};var combat=new Combat3DSystem(world,()->enabled[0],u->null);combat.install();
        try{
            Vars.world.tile(50,24).setBlock(Blocks.coreShard,Team.sharded,0);
            var tile=Vars.world.tile(24,24);tile.setBlock(Blocks.copperWall,Team.sharded,0);var wall=tile.build;
            var enemy=UnitTypes.dagger.create(Team.crux);enemy.set(18*8,24*8);enemy.rotation=0;enemy.add();pose(enemy,.12);
            var ai=new GroundFixture();enemy.controller(ai);ai.seed(wall);
            check(wall.isValid()&&!wall.isAdded(),"real native Copper Wall exists without update membership");
            ai.updateWeapons();check(enemy.mounts[0].target==wall&&enemy.mounts[0].shoot,"vanilla GroundAI accepts the actual wall target");
            enemy.mounts[0].weapon.update(enemy,enemy.mounts[0]);check(enemy.mounts[0].target==wall&&enemy.mounts[0].shoot,"2D weapon preserves native wall intent");
            enabled[0]=true;combat.syncActive();ai.seed(wall);ai.updateWeapons();enemy.mounts[0].weapon.update(enemy,enemy.mounts[0]);
            System.out.println("Wall repro: valid="+wall.isValid()+", added="+wall.isAdded()+", 3D target="+enemy.mounts[0].target+", shoot="+enemy.mounts[0].shoot);
            check(enemy.mounts[0].target==wall&&enemy.mounts[0].shoot,"3D weapon must retain the native valid static wall target");
            check(Combat3DSystem.usableTarget(wall),"3D pitch/launch target validity matches native tile identity");
            for(boolean xyz:new boolean[]{false,true}){
                enabled[0]=xyz;combat.syncActive();
                for(var block:new Block[]{Blocks.copperWall,Blocks.copperWallLarge,Blocks.titaniumWall,Blocks.mechanicalDrill,Blocks.duo,Blocks.graphitePress}){
                    tile.setBlock(block,Team.sharded,0);var target=tile.build;ai.seed(target);
                    float originalDamage=combat.originalType(enemy.mounts[0].weapon.bullet).damage;
                    ai.updateWeapons();for(var m:enemy.mounts)m.weapon.update(enemy,m);
                    check(enemy.mounts[0].target==target&&enemy.mounts[0].shoot,"native target retained: "+block.name+" xyz="+xyz);
                    float hp=target.health;fire(enemy,ai,combat,xyz,240);
                    check(target.health<hp,"real native Dagger shots damage "+block.name+" xyz="+xyz);
                    check(combat.originalType(enemy.mounts[0].weapon.bullet).damage==originalDamage,"native damage preserved");
                    combat.clear();for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                }
            }
            enabled[0]=true;combat.syncActive();tile.setBlock(Blocks.copperWall,Team.sharded,0);wall=tile.build;
            ai.seed(wall);fire(enemy,ai,combat,true,1800);
            check(wall.dead&&!wall.isValid()&&tile.build==null,"native weapon destroys and removes the static wall");
            check(!Combat3DSystem.usableTarget(wall),"destroyed wall cannot remain a launch or pitch target");
            ai.seed(Vars.world.tile(50,24).build);ai.updateWeapons();
            check(ai.objective()==Vars.world.tile(50,24).build,"original core objective remains accessible after wall removal");
            tile.setBlock(Blocks.copperWall,Team.sharded,0);Building replaced=tile.build;tile.setBlock(Blocks.titaniumWall,Team.sharded,0);
            check(!Combat3DSystem.usableTarget(replaced)&&Combat3DSystem.usableTarget(tile.build),"same-tile replacement rejects stale wall identity");
            tile.setBlock(Blocks.copperWall,Team.crux,0);ai.seed(tile.build);ai.updateWeapons();enemy.mounts[0].weapon.update(enemy,enemy.mounts[0]);
            check(enemy.mounts[0].target!=tile.build&&!enemy.mounts[0].shoot,"friendly wall cannot become an enemy attack target");
            tile.setBlock(Blocks.copperWall,Team.sharded,0);ai.seed(tile.build);ai.fire=false;ai.updateWeapons();enemy.mounts[0].weapon.update(enemy,enemy.mounts[0]);
            check(!enemy.mounts[0].shoot,"native hold-fire remains effective against walls");ai.fire=true;
            var source=combat.originalType(enemy.mounts[0].weapon.bullet);boolean ground=source.collidesGround;
            try{source.collidesGround=false;ai.seed(tile.build);ai.updateWeapons();enemy.mounts[0].weapon.update(enemy,enemy.mounts[0]);check(!enemy.mounts[0].shoot,"air-only mount cannot attack walls");}finally{source.collidesGround=ground;}
            var miner=UnitTypes.mono.create(Team.crux);check(miner.mounts.length==0,"non-attacking native miner receives no demolition weapon");
            tile.setBlock(Blocks.copperWall,Team.sharded,0);var shared=tile.build;ai.seed(shared);
            var other=UnitTypes.dagger.create(Team.crux);other.set(17*8,24*8);other.rotation=0;other.add();pose(other,.12);var second=new GroundFixture();other.controller(second);second.seed(shared);
            float sharedHP=shared.health;for(int i=0;i<180;i++){second.updateWeapons();for(var m:other.mounts)m.weapon.update(other,m);fire(enemy,ai,combat,true,1);}
            check(shared.health<sharedHP&&enemy.mounts[0].totalShots>0&&other.mounts[0].totalShots>0,"multiple native enemies can attack the same wall using independent weapons: HP="+shared.health+", shots="+enemy.mounts[0].totalShots+"/"+other.mounts[0].totalShots);other.remove();poses.remove(other);
            combat.clear();tile.setBlock(Blocks.copperWall,Team.sharded,0);var protectedWall=tile.build;
            Core.settings.put("fpv-wall-regen-delay",60);var shields=new fpv.defense.ProjectedWallSystem(world,()->true);shields.update(0);combat.setProjectedDefense(shields);pose(enemy,5);
            float hp=protectedWall.health,shieldHP=shields.volume(protectedWall).hp();ai.seed(protectedWall);fire(enemy,ai,combat,true,100);
            check(shields.volume(protectedWall).hp()<shieldHP&&protectedWall.health==hp,"real native AI shots hit active upper projection before protected physical wall: HP="+protectedWall.health+"/"+hp+", projection="+shields.volume(protectedWall).hp()+"/"+shieldHP+", pose="+world.unitState(enemy));
            shields.close();combat.setProjectedDefense(null);combat.clear();pose(enemy,.12);
            upperWalls(enemy,ai,world,combat);
            System.out.println("WallTargetingNativeTest: "+checks+" checks passed");
        }finally{combat.close();altitudes.close();}
    }
    private static void pose(Unit u,double z){poses.put(u,new Unit3DState(u.id,u.type.id,u.team.id,u.x/8,u.y/8,z,0,0,0,u.rotation,0,0,z,u.hitSize/16,.12,false));}
    private static void fire(Unit u,GroundFixture ai,Combat3DSystem combat,boolean xyz,int ticks){
        for(int i=0;i<ticks;i++){
            Time.delta=1;Time.update();Vars.state.updateId++;ai.updateWeapons();for(var m:u.mounts)m.weapon.update(u,m);
            if(xyz)combat.update(1d/60);
            for(var b:Groups.bullet.copy())if(b.isAdded())b.update();
        }
    }
    private static void upperWalls(Unit enemy,GroundFixture ai,World3DAdapter world,Combat3DSystem combat){
        combat.clear();Vars.world.tile(24,24).setBlock(Blocks.copperWall,Team.sharded,0);float baseHp=Vars.world.tile(24,24).build.health;
        enemy=UnitTypes.atrax.create(Team.crux);enemy.set(22*8,24*8);enemy.rotation=0;enemy.add();ai=new GroundFixture();enemy.controller(ai);
        var grid=new LayerWorld(LayerCoordinates.DEFAULT);
        for(int y=0;y<64;y++)grid.add(new LayerComponent(y+1,LayerComponent.Kind.BLOCK,24,y,2,"copper-wall",1,0,Team.sharded.id,LayerComponent.Mode.FREE,320,320,1,1.4,LayerComponent.SupportProfile.NONE,null,null));
        // An overhead platform must not become the wall's target or its lower-floor alias.
        grid.add(new LayerComponent(100,LayerComponent.Kind.PLATFORM,24,24,4,LayerCatalog.PLATFORM,1,0,Team.sharded.id,LayerComponent.Mode.FREE,250,250,1,.16,LayerComponent.SupportProfile.NONE,null,null));
        long[] damaged={0};var volumes=new LayerPhysicalQueries(()->grid,(id,amount)->{damaged[0]=id;var c=grid.get(id);if(c!=null){if(amount>=c.health())grid.remove(id);else grid.replace(c.withHealth(c.health()-amount));}});
        world.setConstruction(volumes);combat.setProjectedDefense(volumes);pose(enemy,3.1);
        var model=new GroundLocomotionModel(3,3,.2,.2);var floor=new GroundLocomotionModel.Surface(){public double support(double x,double y){return 0;}public double obstruction(double x,double y){return 0;}};
        var profile=GroundUnitProfiles.profile(enemy);var state=model.create(profile,22,24,0,.3,.12,floor);model.seedHeight(state,profile,3.1,.12);
        var nav=new LayerNavigationAdapter(world);
        try{
            check(nav.next(enemy,state,22.1,24,.3,.12,1d/60)==null,"closed upper wall barrier has no physical detour");
            var obstacle=LayerNavigationAdapter.obstacle(enemy);check(obstacle!=null&&obstacle.componentId()==25&&Math.abs(obstacle.bounds().minZ()-2.8)<1e-6,"existing navigation resolves actual upper wall and layer: "+obstacle+", stateZ="+state.z()+", flying="+enemy.isFlying());
            ai.seed(Vars.world.tile(50,24).build);ai.updateWeapons();for(var m:enemy.mounts)m.weapon.update(enemy,m);
            check(LayerNavigationAdapter.aim(enemy,combat.originalType(enemy.mounts[0].weapon.bullet))!=null,"native weapon receives the existing upper obstruction aim");
            for(int i=0;i<300&&grid.get(25)!=null;i++){
                Vars.state.updateId++;Time.delta=1;Time.update();nav.next(enemy,state,22.1,24,.3,.12,1d/60);ai.updateWeapons();for(var m:enemy.mounts)m.weapon.update(enemy,m);combat.update(1d/60);for(var b:Groups.bullet.copy())if(b.isAdded())b.update();
            }
            check(damaged[0]==25&&(grid.get(25)==null||grid.get(25).health()<320),"native Atrax shots damage only the intended elevated wall");
            check(Vars.world.tile(24,24).build.health==baseHp&&grid.get(100).health()==250,"lower wall and overhead platform retain HP");
            for(int y=21;y<=27;y++)grid.remove(y+1);nav.clear();
            check(nav.next(enemy,state,22.1,24,.3,.12,1d/60)==null&&LayerNavigationAdapter.obstacle(enemy)==null,"removed upper opening restores native forward intent");
            // The existing bounded planner, not a per-frame global pathfinder, owns detours.
            var detour=LayerNavigationAdapter.plan(22,24,3.1,28,24,.3,.12,24,1536,(ax,ay,az,bx,by,bz,r,h)->volumes.sweep(ax,ay,az,bx,by,bz,r,h)==null);
            check(detour.found(),"alternate upper route remains usable after an opening");
        }finally{nav.clear();combat.clear();world.setConstruction(null);combat.setProjectedDefense(null);enemy.remove();poses.remove(enemy);}
    }
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
