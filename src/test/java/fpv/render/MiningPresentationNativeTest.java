package fpv.render;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.part.RegionPart;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.draw.DrawTurret;

public final class MiningPresentationNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        var copper=Vars.world.tile(22,20);copper.setFloor((mindustry.world.blocks.environment.Floor)Blocks.sand);copper.setOverlay(Blocks.oreCopper);
        var lead=Vars.world.tile(22,21);lead.setFloor((mindustry.world.blocks.environment.Floor)Blocks.sand);lead.setOverlay(Blocks.oreLead);
        var u=UnitTypes.mono.create(Team.sharded);u.set(20*8,20*8);u.controller(new AIController(){@Override public void updateUnit(){}});u.add();
        var sample=new MiningVisualRenderer.Sample();var pose=new Unit3DState(u.id,u.type.id,u.team.id,20,20,4,0,0,0,25,15,0,4,.5,.2,true);
        require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"no mining target is silent");
        u.mineTile=copper;u.mineTimer=12;
        float hp=u.health,speed=u.type.mineSpeed;int items=u.stack.amount;
        for(int i=0;i<100;i++){
            require(MiningVisualRenderer.read(u,pose,(x,y)->1.75,sample),"active native mining state visible");
            require(sample.target==copper&&sample.item==Items.copper&&sample.bx==22&&sample.by==20&&Math.abs(sample.bz-1.778)<1e-7,"real ore and surface coordinates");
            require(sample.az>3.9&&sample.ax>20&&sample.ay>20,"emitter follows actual elevated pitched XYZ pose");
            require(sample.progress==12/65d,"native timer/hardness progress");
        }
        require(u.health==hp&&u.stack.amount==items&&u.mineTimer==12&&u.type.mineSpeed==speed,"render sampling changes no native progress/inventory/balance");
        u.mineTile=lead;u.mineTimer=35;require(MiningVisualRenderer.read(u,pose,(x,y)->0,sample)&&sample.item==Items.lead&&sample.by==21,"target changes without cached old ore");
        double low=sample.progress;u.mineTimer=60;require(MiningVisualRenderer.read(u,pose,(x,y)->0,sample)&&sample.progress>low,"actual mining cycle drives presentation");
        u.mineTile=null;require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"stop immediately");
        u.mineTile=copper;u.set(50*8,50*8);require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"out of native mining range silent");u.set(20*8,20*8);
        Vars.state.rules.unitMineSpeedMultiplier=0;require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"disabled native mining rate silent");Vars.state.rules.unitMineSpeedMultiplier=1;
        u.dead=true;require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"dead miner silent");u.dead=false;u.remove();require(!MiningVisualRenderer.read(u,pose,(x,y)->0,sample),"removed miner silent");
        var salvo=(Turret)Blocks.salvo;var drawer=(DrawTurret)salvo.drawer;RegionPart sides=null,barrel=null;
        for(var part:drawer.parts)if(part instanceof RegionPart region){if(region.suffix.equals("-side"))sides=region;if(region.suffix.equals("-barrel"))barrel=region;}
        require(sides!=null&&sides.mirror&&sides.moves.size==1&&sides.moveX==.6f&&sides.moveRot==-15,"native Salvo mirrored warmup/recoil transforms intact");
        float t=TurretModelProfile.forTurret(salvo).thickness(salvo.size),panel=TurretModelProfile.partLift(salvo,sides,t),gun=TurretModelProfile.partLift(salvo,barrel,t);
        double[] basis=new double[9];
        for(int yaw=0;yaw<360;yaw+=10)for(int pitch=-85;pitch<=85;pitch+=5){
            SpriteExtrusionGeometry.basis(yaw,pitch,0,basis);
            double normalDistance=0;for(int j=3;j<6;j++)normalDistance+=(panel-gun)*basis[j]*basis[j];
            require(normalDistance-t*.65>.0179,"Salvo panel slab clears barrel slab for all authoritative angles");
        }
        require(TurretModelProfile.partLift((Turret)Blocks.duo,sides,t)==t*.52f,"other turret parts unchanged");
        System.out.println("MiningPresentationNativeTest: "+checks+" native mining start/stop/ore/progress/XYZ/read-only and Salvo animated-slab clearance checks passed");
    }
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
