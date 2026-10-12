package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import fpv.combat.Combat3DSystem;
import fpv.effects.RadialExplosion;
import fpv.physics.RamDamageLimit;
import fpv.physics.RamDamageSettings;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;

/** Real native weapon proxies + layered volumes; isolated headless world, never a user save. */
public final class LayerCombatIntegrationTest {
    private static int checks;
    private static LayerWorld grid;
    private static LayerBuildingAdapter nativeBuildings;
    private static LayerPhysicalQueries volumes;
    private static World3DAdapter world;
    private static Combat3DSystem combat;
    private static long serial;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.set(GameState.State.playing);
        var terrain=new TerrainElevation();var altitudes=new UnitAltitudeSystem((x,y)->0);
        world=new World3DAdapter(terrain,(x,y)->0,altitudes);
        grid=new LayerWorld(LayerCoordinates.DEFAULT);
        nativeBuildings=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){
            @Override public void damaged(long id,float health){var c=grid.get(id);if(c!=null)replaceHealth(c,health);}
        });
        volumes=new LayerPhysicalQueries(()->grid,(id,amount)->{var c=grid.get(id);if(c!=null)replaceHealth(c,c.health()-amount);},nativeBuildings::building);
        world.setConstruction(volumes);combat=new Combat3DSystem(world,()->true,unit->null);
        combat.setUpperBuildings(nativeBuildings::entries);combat.setProjectedDefense(volumes);combat.install();
        nativeBuildings.setTurretAiming(combat.layerTurretAiming());
        try{
            nativeWeapon();nativeBeam();groundWeapon();wrongAltitude();physicalFloors();splash();armorAndRules();healing();ramExplosionCaps();
            require(Groups.build.size()==0&&Groups.powerGraph.size()==0,"upper combat creates no native building/power updater entities");
            for(var tile:Vars.world.tiles)require(tile.block()==Blocks.air&&tile.build==null,"upper weapons preserve every native Z0 cell");
            System.out.println("LayerCombatIntegrationTest: "+checks+" checks passed");
        }finally{combat.close();nativeBuildings.clear();altitudes.close();}
    }
    private static void nativeWeapon(){
        clear();var source=add(10,10,3,Blocks.duo,Team.sharded);var enemy=add(18,10,7,Blocks.container,Team.crux);
        var turret=(ItemTurret.ItemTurretBuild)nativeBuildings.building(source.id());turret.handleStack(Items.copper,10,null);
        var target=nativeBuildings.building(enemy.id());double hp=grid.get(enemy.id()).health();
        int budget=400;Bullet first=null;
        while(first==null&&budget-->0){tick(false);for(Bullet b:Groups.bullet)if(b.owner==turret){first=b;break;}}
        require(first!=null&&combat.managed(first),"actual native elevated Duo emits a managed 3D projectile");
        require(turret.target==target,"actual 3D turret targeting acquires an upper enemy absent from native world");
        var state=combat.projectileState(first);double targetZ=(world.buildingBase(target)+world.buildingTop(target))*.5;
        require(state.z()>grid.coordinates().plane(3)&&state.z()<world.buildingTop(turret)+2,"native turret muzzle follows its elevated world bounds");
        require(state.vz()>0,"ordinary native turret bullet climbs toward the target upper floor");
        double horizontal=Math.hypot(target.x/Vars.tilesize-state.x(),target.y/Vars.tilesize-state.y());
        double projectedZ=state.z()+state.vz()*horizontal/Math.hypot(state.vx(),state.vy());
        require(Math.abs(projectedZ-targetZ)<.1,"native turret projectile pitch uses the actual target Z");
        for(int i=0;i<70;i++)tick(true);
        require(grid.get(enemy.id()).health()<hp,"actual native projectile damages an enemy on its correct upper floor");
    }
    private static void wrongAltitude(){
        clear();var target=add(24,24,5,Blocks.container,Team.crux);double hp=target.health();
        var b=grid.coordinates().bounds(target);BasicBulletType type=bullet(80);
        Bullet low=launch(type,Team.sharded,b.minX()-4,b.centerY(),1,1,0,0);advance(low,45);
        require(grid.get(target.id()).health()==hp,"same XY below an upper building cannot deal damage");
        combat.clear();Bullet high=launch(type,Team.sharded,b.minX()-4,b.centerY(),b.maxZ()+3,1,0,0);advance(high,45);
        require(grid.get(target.id()).health()==hp,"same XY above an upper building cannot deal damage");
        combat.clear();Bullet hit=launch(type,Team.sharded,b.minX()-4,b.centerY(),b.centerZ(),1,0,0);advance(hit,45);
        require(grid.get(target.id()).health()<hp,"same XY and matching Z body receives native projectile damage");
    }
    private static void nativeBeam(){
        clear();var source=add(10,30,3,Blocks.lancer,Team.sharded);var enemy=add(20,30,7,Blocks.container,Team.crux);
        var turret=(PowerTurret.PowerTurretBuild)nativeBuildings.building(source.id());
        for(int i=0;i<90;i++)tick(true);
        require(turret.totalShots==0,"actual native upper Lancer cannot bypass its power requirement");
        add(8,30,3,Blocks.powerNode,Team.sharded);add(4,30,3,Blocks.largeSolarPanel,Team.sharded);
        int budget=2400;while(grid.get(enemy.id()).health()==enemy.health()&&budget-->0)tick(true);
        require(turret.totalShots>0&&grid.get(enemy.id()).health()<enemy.health(),"solar-powered native upper Lancer deals real XYZ beam damage to an elevated target");
        require(combat.visuals().stream().anyMatch(v->v.kind()==fpv.combat.CombatVisual.Kind.BEAM&&v.az()>grid.coordinates().plane(3)&&v.bz()>v.az()),
            "actual native upper beam presentation shares its physical upward trajectory");
    }
    private static void groundWeapon(){
        clear();var enemy=add(18,10,4,Blocks.container,Team.crux);var tile=Vars.world.tile(10,10);
        // A genuine native build factory with its normal 3D targeting subclass; init(false) keeps
        // this fixture out of the global scheduler, so the test controls exact update ordering.
        var turret=(ItemTurret.ItemTurretBuild)Blocks.duo.newBuilding();tile.updateBlockReference(Blocks.duo);tile.build=turret;
        turret.init(tile,Team.sharded,false,0);turret.handleStack(Items.copper,10,null);
        try{
            int budget=500;while(grid.get(enemy.id()).health()==enemy.health()&&budget-->0){tick(true);turret.update();}
            require(turret.target==nativeBuildings.building(enemy.id())&&turret.totalShots>0,"Z0 native turret validates and fires at an actual upper building");
            require(grid.get(enemy.id()).health()<enemy.health(),"Z0 native turret bullet crosses altitude and damages upper enemy");
        }finally{turret.dead=true;tile.build=null;tile.updateBlockReference(Blocks.air);combat.clear();}
    }
    private static void physicalFloors(){
        clear();var floor=platform(30,30,5,Team.sharded);double plane=grid.coordinates().plane(5);
        Bullet upward=launch(bullet(50),Team.sharded,30,30,plane-3,0,0,1);advance(upward,40);
        require(!upward.isAdded(),"friendly platform has a physical underside that stops upward bullets");
        require(grid.get(floor.id()).health()==floor.health(),"friendly fire stops at a floor without damaging it");
        Bullet over=launch(bullet(50),Team.crux,27,30,plane+1,1,0,0);advance(over,25);
        require(over.isAdded()&&grid.get(floor.id()).health()==floor.health(),"platform volume leaves open air above it");
        combat.clear();Bullet enemy=launch(bullet(50),Team.crux,30,30,plane+2,0,0,-1);advance(enemy,35);
        require(!enemy.isAdded()&&grid.get(floor.id()).health()<floor.health(),"enemy downward projectile hits the physical platform top");
    }
    private static void splash(){
        clear();var hit=add(35,35,6,Blocks.copperWall,Team.crux);var nearby=add(35,37,6,Blocks.copperWall,Team.crux);
        var far=add(35,37,10,Blocks.copperWall,Team.crux);var friend=add(35,33,6,Blocks.copperWall,Team.sharded);
        var b=grid.coordinates().bounds(hit);BasicBulletType shell=bullet(20);shell.splashDamage=90;shell.splashDamageRadius=3*Vars.tilesize;
        Bullet shot=launch(shell,Team.sharded,b.minX()-3,b.centerY(),b.centerZ(),1,0,0);advance(shot,40);
        require(grid.get(hit.id()).health()<hit.health()-20,"physical impact triggers an actual native splash payload on the upper surface");
        require(grid.get(nearby.id()).health()<nearby.health(),"native splash damages another nearby upper component");
        require(grid.get(far.id()).health()==far.health(),"native splash uses full XYZ separation between floors");
        require(grid.get(friend.id()).health()==friend.health(),"native splash preserves team damage rules on upper floors");
    }
    private static void armorAndRules(){
        clear();var armored=add(24,24,5,Blocks.carbideWall,Team.crux);float nativeArmor=Blocks.carbideWall.armor;
        require(nativeArmor>0,"armor regression uses an actual armored native wall family");
        double loss=directLoss(armored,bullet(60));
        require(Math.abs(loss-mindustry.entities.Damage.applyArmor(60,nativeArmor))<.01,"upper direct hit preserves native wall armor");
        BasicBulletType piercing=bullet(60);piercing.pierceArmor=true;
        require(Math.abs(directLoss(grid.get(armored.id()),piercing)-60)<.01,"upper armor-piercing bullet honors native pierceArmor");
        BasicBulletType category=bullet(60);category.blockArmorMultiplier=2;category.armorMultiplier=.5f;category.buildingDamageMultiplier=.5f;
        require(Math.abs(directLoss(grid.get(armored.id()),category)-mindustry.entities.Damage.applyArmor(30,nativeArmor))<.01,
            "upper direct hit retains native building, armor and block-armor multipliers");
        Vars.state.rules.blockHealthMultiplier=2;
        try{
            require(Math.abs(directLoss(grid.get(armored.id()),bullet(60))-mindustry.entities.Damage.applyArmor(60,nativeArmor)/2)<.01,
                "descriptor-backed walls obey native block health rules");
            var storage=add(34,24,5,Blocks.container,Team.crux);
            require(Math.abs(directLoss(storage,bullet(60))-30)<.01,"native upper building receives block health multiplier exactly once");
        }finally{Vars.state.rules.blockHealthMultiplier=1;}
    }
    private static void healing(){
        clear();var storage=add(24,24,5,Blocks.container,Team.sharded);replaceHealth(storage,storage.health()-100);
        BasicBulletType heal=bullet(1);heal.collidesTeam=true;heal.healAmount=25;heal.healPercent=0;
        var b=grid.coordinates().bounds(storage);float before=nativeBuildings.building(storage.id()).health;
        advance(launch(heal,Team.sharded,b.minX()-3,b.centerY(),b.centerZ(),1,0,0),40);
        require(Math.abs(nativeBuildings.building(storage.id()).health-before-25)<.01,"actual healing bullet restores native upper building health");
        combat.clear();var wall=add(34,24,5,Blocks.copperWall,Team.sharded);replaceHealth(wall,wall.health()-100);
        b=grid.coordinates().bounds(wall);double hp=grid.get(wall.id()).health();
        advance(launch(heal,Team.sharded,b.minX()-3,b.centerY(),b.centerZ(),1,0,0),40);
        require(Math.abs(grid.get(wall.id()).health()-hp-25)<.01,"healing projectile restores descriptor-backed upper wall health");
        combat.clear();var trigger=add(34,34,5,Blocks.copperWall,Team.crux);var friend=add(34,36,5,Blocks.copperWall,Team.sharded);
        var far=add(34,36,9,Blocks.copperWall,Team.sharded);replaceHealth(friend,friend.health()-100);replaceHealth(far,far.health()-100);
        heal.splashDamageRadius=3*Vars.tilesize;heal.splashDamage=1; // Native area payload carries repair metadata.
        b=grid.coordinates().bounds(trigger);hp=grid.get(friend.id()).health();
        advance(launch(heal,Team.sharded,b.minX()-3,b.centerY(),b.centerZ(),1,0,0),40);
        require(Math.abs(grid.get(friend.id()).health()-hp-25)<.01,"native splash repair reaches a nearby friendly upper wall");
        require(grid.get(far.id()).health()==far.health()-100,"repair splash cannot jump to a distant floor at identical XY");
    }
    private static void ramExplosionCaps(){
        clear();var enemy=add(24,24,5,Blocks.container,Team.crux);var friend=add(26,24,5,Blocks.copperWall,Team.sharded);
        var b=grid.coordinates().bounds(enemy);var blast=new RadialExplosion(world);
        var spec=new RadialExplosion.Spec(3,120,3,.6,RadialExplosion.Style.sparks,false);
        double hp=grid.get(enemy.id()).health();var alpha=UnitTypes.alpha.create(Team.sharded);double cap=RamDamageSettings.cap(alpha);
        require(cap==0,"actual Alpha default ram cap remains zero");
        blast.detonateXYZ(Team.sharded,b.centerX(),b.centerY(),b.centerZ(),spec,limit(new RamDamageLimit(cap)));
        require(grid.get(enemy.id()).health()==hp,"zero core-drone cap cannot leak damage through elevated blast integration");
        var shared=new RamDamageLimit(30);
        blast.detonateXYZ(Team.sharded,b.centerX(),b.centerY(),b.centerZ(),spec,limit(shared));
        blast.detonateXYZ(Team.sharded,b.centerX(),b.centerY(),b.centerZ(),spec,limit(shared));
        require(Math.abs(hp-grid.get(enemy.id()).health()-30)<.01,"same-collision repeated upper blast respects one stable component-ID budget");
        require(grid.get(friend.id()).health()==friend.health(),"ram explosion never damages a friendly upper structure");
    }
    private static RadialExplosion.DamageLimiter limit(RamDamageLimit cap){return new RadialExplosion.DamageLimiter(){
        public double unit(Unit unit,double proposed){return cap.reserve(unit,proposed,RamDamageSettings.healthScale(unit));}
        public double building(Building building,double proposed){return cap.reserve(building,proposed,RamDamageSettings.healthScale(building));}
        public double elevated(LayerComponent component,double proposed){return cap.reserve(Long.valueOf(component.id()),proposed,Vars.state.rules.blockHealth(Team.get(component.team())));}
    };}
    private static double directLoss(LayerComponent target,BasicBulletType type){
        combat.clear();double hp=grid.get(target.id()).health();var b=grid.coordinates().bounds(target);
        advance(launch(type,Team.sharded,b.minX()-2,b.centerY(),b.centerZ(),1,0,0),30);
        return hp-grid.get(target.id()).health();
    }
    private static LayerComponent add(int x,int y,int layer,Block block,Team team){
        var entry=new LayerCatalog().entry(block.name,false,grid.coordinates().spacing());
        if(entry==null)throw new AssertionError("Test content not supported: "+block.name);
        var c=new LayerComponent(++serial,LayerComponent.Kind.BLOCK,x,y,layer,block.name,block.size,0,team.id,LayerComponent.Mode.FREE,
            block.health,block.health,entry.mass(),entry.height(),entry.support(),null,null);grid.add(c);
        if(LayerBuildingAdapter.supports(block))nativeBuildings.create(c.id(),x,y,layer,block,team,0);
        return c;
    }
    private static LayerComponent platform(int x,int y,int layer,Team team){
        var c=new LayerComponent(++serial,LayerComponent.Kind.PLATFORM,x,y,layer,LayerCatalog.PLATFORM,1,0,team.id,LayerComponent.Mode.FREE,
            250,250,1,LayerCoordinates.PLATFORM_THICKNESS,LayerComponent.SupportProfile.NONE,null,null);grid.add(c);return c;
    }
    private static void replaceHealth(LayerComponent c,double health){
        if(health<=0){grid.remove(c.id());nativeBuildings.remove(c.id());}
        else {grid.replace(c.withHealth(Math.min(c.maxHealth(),health)));var b=nativeBuildings.building(c.id());if(b!=null)b.health=(float)health;}
    }
    private static BasicBulletType bullet(float damage){var type=new BasicBulletType(4,damage);type.keepVelocity=false;type.lifetime=120;type.hitSize=1;type.init();return type;}
    private static Bullet launch(BasicBulletType type,Team team,double x,double y,double z,double dx,double dy,double dz){
        return combat.launchPhysical(type,null,team,new WorldCoordinates.Point(x,y,z),new WorldCoordinates.Point(dx,dy,dz),-1);
    }
    private static void clear(){combat.clear();nativeBuildings.clear();for(var c:new ArrayList<>(grid.components()))grid.remove(c.id());}
    private static void tick(boolean move){Time.update();Vars.state.updateId++;combat.update(1d/60);nativeBuildings.tick();if(move)for(var b:Groups.bullet.copy())if(b.isAdded())b.update();}
    private static void advance(Bullet bullet,int ticks){for(int i=0;i<ticks&&bullet.isAdded();i++){Time.update();Vars.state.updateId++;combat.update(1d/60);bullet.update();}}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
