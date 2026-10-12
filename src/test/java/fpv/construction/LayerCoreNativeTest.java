package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.StorageBlock.StorageBuild;

/** Candidate decode isolation and duplicate-safe native/upper core inventory sharing. */
public final class LayerCoreNativeTest {
    private static int checks,spawns;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.set(GameState.State.playing);
        Vars.world.tile(10,10).setBlock(Blocks.coreShard,Team.sharded,0);
        CoreBuild ground=(CoreBuild)Vars.world.build(10,10);Vars.state.teams.registerCore(ground);ground.items.add(Items.copper,123);
        var groundPool=ground.items;int groundCapacity=Blocks.coreShard.itemCapacity;
        LayerBuildingAdapter owner=registry();CoreBuild first=(CoreBuild)owner.create(1,10,10,2,Blocks.coreShard,Team.sharded,0);
        CoreBuild second=(CoreBuild)owner.create(2,20,10,2,Blocks.coreShard,Team.sharded,0);
        StorageBuild storage=(StorageBuild)owner.create(3,13,10,2,Blocks.vault,Team.sharded,0);
        first.items.add(Items.copper,999);second.items.add(Items.copper,999);
        require(Team.sharded.cores().size==1&&ground.items.get(Items.copper)==123,"candidate core creation does not register or touch live inventory");
        byte[] candidate=owner.writeState(1);owner.readState(1,candidate);
        require(Team.sharded.cores().size==1&&ground.items==groundPool,"candidate decode is inert");
        LayerCoreLifecycle lifecycle=new LayerCoreLifecycle();lifecycle.activate(owner);
        require(lifecycle.registeredCount()==2&&Team.sharded.cores().size==3,"explicit live activation registers upper cores");
        require(Team.sharded.data().unitCap>=2*Blocks.coreShard.unitCapModifier,"upper cores contribute real native unit capacity");
        require(first.items==groundPool&&second.items==groundPool&&first.items.get(Items.copper)==123,"never sums duplicate saved pools or overwrites native inventory");
        require(storage.linkedCore==first&&storage.items==groundPool,"upper storage merges with adjacent same-floor core");
        require(first.storageCapacity==groundCapacity*3+Blocks.vault.itemCapacity&&ground.storageCapacity==first.storageCapacity,"all native and upper core/storage capacity counted");
        first.items.add(Items.lead,7);require(ground.items.get(Items.lead)==7,"shared inventory works through native item methods");
        first.requestSpawn(Player.create());require(spawns==1,"spawn travels through explicit layer callback");
        byte[] restoredFirst=owner.writeState(1),restoredSecond=owner.writeState(2);
        LayerBuildingAdapter replacement=registry();CoreBuild nextFirst=(CoreBuild)replacement.create(1,10,10,2,Blocks.coreShard,Team.sharded,0);
        replacement.create(2,20,10,2,Blocks.coreShard,Team.sharded,0);replacement.readState(1,restoredFirst);replacement.readState(2,restoredSecond);
        require(Team.sharded.cores().size==3,"prepared replacement never leaks cores into global registry");
        lifecycle.activate(replacement);
        require(Team.sharded.cores().size==3&&!Team.sharded.cores().contains(first)&&nextFirst.items==groundPool,"atomic replacement removes old references and preserves native item module");
        require(ground.items.get(Items.copper)==123&&ground.items.get(Items.lead)==7,"restore never duplicates or drops team inventory");
        replacement.remove(2);lifecycle.reconcile();require(Team.sharded.cores().size==2&&ground.storageCapacity==groundCapacity*2,"demolition updates native core count and capacity");
        lifecycle.deactivate();require(Team.sharded.cores().size==1&&ground.items==groundPool&&ground.items.get(Items.copper)==123,"deactivation leaves native resources unchanged");
        LayerBuildingAdapter upperOnly=registry();CoreBuild green=(CoreBuild)upperOnly.create(4,30,30,2,Blocks.coreShard,Team.green,0);green.items.add(Items.silicon,55);
        CoreBuild greenSecond=(CoreBuild)upperOnly.create(5,40,30,2,Blocks.coreShard,Team.green,0);greenSecond.items.add(Items.silicon,55);
        lifecycle.activate(upperOnly);require(green.items==greenSecond.items&&green.items.get(Items.silicon)==55,"upper-only restored team chooses one pool without duplication");
        lifecycle.deactivate();require(Team.green.cores().isEmpty(),"upper-only team registry cleans up");
        require(Vars.world.build(10,10)==ground&&ground.health==ground.maxHealth,"original ground core survives unchanged");
        System.out.println("LayerCoreNativeTest: "+checks+" checks passed");
    }
    private static LayerBuildingAdapter registry(){return new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){@Override public void requestCoreSpawn(CoreBuild core,Player player){spawns++;}});}
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
