package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/** Native weapon lifecycles in a synthetic world; XYZ target selection itself is supplied by the combat module. */
public final class LayerTurretNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        for(Block block:new Block[]{Blocks.duo,Blocks.salvo,Blocks.wave,Blocks.lancer,Blocks.meltdown,Blocks.solarPanel,Blocks.largeSolarPanel,Blocks.powerNode,Blocks.battery})block.init();
        for(var bullet:Vars.content.bullets())bullet.init();
        Vars.state.rules.fog=true; // The synthetic world intentionally has no native fog indexer.
        Block[] ground=new Block[4096];for(int i=0;i<4096;i++)ground[i]=Vars.world.tiles.geti(i).block();
        LayerBuildingAdapter registry=new LayerBuildingAdapter(null);
        require(LayerBuildingAdapter.supports(Blocks.duo)&&LayerBuildingAdapter.supports(Blocks.wave)&&LayerBuildingAdapter.supports(Blocks.lancer),"audited native turret families supported");
        require(LayerBuildingAdapter.supports(Blocks.meltdown),"continuous specialised turret has a layer-local adapter");
        // Existing Combat3D targeting changes buildType to an anonymous subclass; it must not hide this family.
        ItemTurret duoType=(ItemTurret)Blocks.duo;var originalFactory=duoType.buildType;duoType.buildType=()->duoType.new ItemTurretBuild(){};
        require(LayerBuildingAdapter.supports(Blocks.duo),"existing 3D targeting build factory remains compatible");duoType.buildType=originalFactory;
        Building target=Building.create();target.x=16*8;target.y=10*8;target.team=Team.crux;
        target.tile=new Tile(16,10);target.tile.updateBlockReference(Blocks.copperWall);target.block=Blocks.copperWall;
        registry.setTurretAiming(new LayerBuildingAdapter.TurretAiming(){
            public Posc enemy(Turret.TurretBuild build,float range){return target;}
            public boolean valid(Turret.TurretBuild build,Posc candidate){return candidate==target;}
            public boolean aim(Turret.TurretBuild build,Posc candidate){build.targetPos.set(candidate.x(),candidate.y());return true;}
        });
        ItemTurret.ItemTurretBuild duo=(ItemTurret.ItemTurretBuild)registry.create(1,10,10,2,Blocks.duo,Team.sharded,0);
        duo.handleItem(duo,Items.copper);int ammo=duo.totalAmmo;tick(registry,400);
        require(duo.totalShots==ammo/duoType.ammoPerShot&&duo.totalAmmo==0,"native item ammo is consumed once per shot and exhaustion stops fire");
        int owned=0;for(Bullet bullet:Groups.bullet)if(bullet.owner==duo){owned++;require(bullet.team==duo.team,"native bullets preserve team");}
        require(owned==duo.totalShots&&owned>0,"live native bullets retain the actual elevated building owner");
        byte[] emptyAmmo=registry.writeState(1);registry.readState(1,emptyAmmo);
        require(Arrays.equals(emptyAmmo,registry.writeState(1)),"native turret reload, heading and ammo roundtrip");
        registry.remove(1);int count=Groups.bullet.size();tick(registry,60);require(Groups.bullet.size()==count,"removed empty turret emits no further bullets");

        ItemTurret.ItemTurretBuild salvo=(ItemTurret.ItemTurretBuild)registry.create(2,10,10,2,Blocks.salvo,Team.sharded,0);
        salvo.handleStack(Items.copper,10,null);
        int budget=500;while(salvo.queuedBullets==0&&budget-->0)tick(registry,1);
        require(salvo.queuedBullets>0,"original native burst pattern queues delayed shots");
        count=Groups.bullet.size();registry.remove(2);tick(registry,120);
        require(Groups.bullet.size()==count&&salvo.queuedBullets==0,"queued native shots are cancelled after upper building removal");

        LiquidTurret.LiquidTurretBuild wave=(LiquidTurret.LiquidTurretBuild)registry.create(3,10,10,2,Blocks.wave,Team.sharded,0);
        wave.handleLiquid(wave,Liquids.water,10);tick(registry,160);
        require(wave.totalShots>0&&wave.liquids.get(Liquids.water)<10,"native liquid turret fires and consumes real water ammunition");
        registry.remove(3);

        // Isolated power-turret fixture keeps native PowerTurret mechanics and real PowerGraph supply,
        // but uses a physical bullet: native laser collision belongs to Combat3D's separate integration tests.
        PowerTurret powerType=new PowerTurret("layer-test-power-turret");powerType.range=120;powerType.reload=10;powerType.rotateSpeed=20;
        powerType.shootType=new BasicBulletType(3,5);powerType.consumePower(.2f);powerType.init();powerType.shootType.init();
        PowerTurret.PowerTurretBuild powered=(PowerTurret.PowerTurretBuild)registry.create(4,10,10,2,powerType,Team.sharded,0);
        tick(registry,120);require(powered.totalShots==0,"native power turret cannot shoot without energy");
        registry.create(5,8,10,2,Blocks.powerNode,Team.sharded,0);registry.create(6,4,10,2,Blocks.largeSolarPanel,Team.sharded,0);
        tick(registry,240);require(powered.totalShots>0&&powered.power.graph.getLastPowerNeeded()>0,"native power turret reload consumes actual graph energy");
        require(Groups.build.size()==0&&Groups.powerGraph.size()==0,"upper turrets never enter global building or power updater groups");
        for(int i=0;i<4096;i++)require(ground[i]==Vars.world.tiles.geti(i).block()&&Vars.world.tiles.geti(i).build==null,"all Z0 cells survive firing unchanged");
        registry.clear();count=Groups.bullet.size();tick(registry,100);require(Groups.bullet.size()==count,"clearing registry cancels all surviving delayed owners");
        System.out.println("LayerTurretNativeTest: "+checks+" checks passed");
    }
    private static void tick(LayerBuildingAdapter registry,int count){for(int i=0;i<count;i++){Time.update();registry.tick();}}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
