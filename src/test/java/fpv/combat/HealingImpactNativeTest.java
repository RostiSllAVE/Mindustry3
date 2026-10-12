package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

public final class HealingImpactNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.set(GameState.State.playing);
        var world=new World3DAdapter(new TerrainElevation(),(x,y)->Vars.world.tile((int)x,(int)-y).block().solid?1:0,new UnitAltitudeSystem((x,y)->0));
        var combat=new Combat3DSystem(world,()->true,u->null);combat.install();
        var tile=Vars.world.tile(24,24);tile.setBlock(Blocks.copperWall,Team.sharded,0);var block=tile.build;block.health=100;
        var source=new BasicBulletType(8,0);source.healAmount=20;source.healPercent=0;source.collidesTeam=true;source.lifetime=60;source.init();
        try{
            var shot=combat.launchPhysical(source,null,Team.sharded,new WorldCoordinates.Point(22,24,.5),new WorldCoordinates.Point(1,0,0),0);
            for(int i=0;i<10&&shot.isAdded();i++){Time.delta=1;combat.update(1/60d);shot.update();}
            require(Math.abs(block.health-120)<.001,"native healing amount applied exactly once");
            var effect=combat.visuals().stream().filter(v->v.kind()==CombatVisual.Kind.HEAL).findFirst().orElseThrow();
            require(effect.ax()==24&&effect.ay()==24&&effect.az()==0&&effect.bz()==1&&effect.radius()==.5,"flash describes actual healed block bounds");
            require(effect.g()>effect.r()&&effect.g()>effect.b(),"green authoritative visual event");
            combat.update(.3);require(combat.visuals().stream().noneMatch(v->v.kind()==CombatVisual.Kind.HEAL),"flash expires without health or permanent color change");require(block.health==120,"effect aging changes no HP");
            block.health=block.maxHealth;int[] notifications={0};var payload=new CombatPayloads(world,new CombatTargetIndex(world),b->notifications[0]++);payload.healing(block,source);
            require(notifications[0]==0&&block.health==block.maxHealth,"no false healing flash for zero HP gain");
            block.health=100;payload.healing(block,source);require(notifications[0]==1&&block.health==120,"notification only follows successful native heal");
            require(source.healAmount==20&&source.damage==0&&source.lifetime==60,"original source stats retained");
            System.out.println("HealingImpactNativeTest: "+checks+" actual projectile/health/green bounds/expiration/read-only visual checks passed");
        }finally{combat.close();}
    }
    private static void require(boolean v,String text){checks++;if(!v)throw new AssertionError(text);}
}
