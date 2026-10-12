package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;

/** Fresh JVM/native content: no user profile, no GL, no live save changes. */
public final class LayerRuntimeNativeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(40,40);Vars.world.setGenerating(true);Vars.world.tiles.fill();
        for(Block block:new Block[]{Blocks.copperWall,Blocks.copperWallLarge,Blocks.titaniumWall,Blocks.surgeWall,Blocks.phaseWall,Blocks.plastaniumWall,Blocks.scrapWall,Blocks.thoriumWall,Blocks.graphitePress,Blocks.container,Blocks.conveyor,Blocks.conduit,Blocks.battery,Blocks.solarPanel,Blocks.powerNode})block.init();
        Vars.state.rules.infiniteResources=true;Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);
        Vars.player=Player.create();Vars.player.team(Team.sharded);Unit builder=UnitTypes.alpha.create(Team.sharded);builder.set(4*Vars.tilesize,4*Vars.tilesize);builder.add();Vars.player.unit(builder);
        TerrainElevation terrain=new TerrainElevation();UnitAltitudeSystem altitudes=new UnitAltitudeSystem(terrain::heightAt);
        World3DAdapter physical=new World3DAdapter(terrain,(x,z)->{Tile tile=Vars.world.tile((int)Math.round(x),(int)Math.round(-z));return tile!=null&&tile.build!=null?1.4:0;},altitudes);
        LayerRuntime runtime=new LayerRuntime(physical);LayerCatalog catalog=runtime.catalog();
        require(catalog.profile(Blocks.titaniumWall).span()==7&&catalog.profile(Blocks.surgeWall).span()==6,"native materials retain required span ordering");
        require(catalog.profile(Blocks.surgeWall).capacity()>catalog.profile(Blocks.titaniumWall).capacity(),"surge has greatest load strength");
        require(catalog.profile(Blocks.copperWallLarge).capacity()==2*catalog.profile(Blocks.copperWall).capacity(),"large wall capacity doubled");
        require(catalog.profile(Blocks.copperWallLarge).span()==catalog.profile(Blocks.copperWall).span(),"large wall span not doubled");
        var free=LayerActions.Action.place(10,10,2,"copper-wall",false,0,LayerComponent.Mode.FREE);
        require(!runtime.apply(Vars.player,free).accepted(),"free placement denied by default");Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        var result=runtime.apply(Vars.player,free);require(result.accepted(),"host explicitly permits free placement: "+result.reason());
        require(Vars.world.tile(10,10).block()==Blocks.air,"upper placement cannot overwrite native tile");
        Core.settings.put(LayerRuntime.FREE_PERMISSION,false);runtime.update(.1);require(runtime.grid().get(result.componentId())!=null,"revoking permission does not change existing free flags");
        var onFree=LayerActions.Action.place(10,10,3,"copper-wall",false,0,LayerComponent.Mode.STRUCTURAL);
        require(!runtime.apply(Vars.player,onFree).accepted(),"floating free wall cannot support structural wall");
        require(!runtime.apply(Vars.player,LayerActions.Action.place(16,16,1,"core-shard",false,0,LayerComponent.Mode.STRUCTURAL)).accepted(),"unsupported category fails closed");
        byte[] encoded=runtime.replication();require(runtime.replication()==encoded,"unchanged grid reuses encoded snapshot bytes");
        runtime.grid().remove(result.componentId());runtime.restore(encoded,false);
        require(runtime.grid().get(result.componentId())!=null&&runtime.grid().get(result.componentId()).mode()==LayerComponent.Mode.FREE,"saved placement mode survives actual runtime decoder");
        byte[] invalid=Arrays.copyOf(encoded,encoded.length+1);try{runtime.restore(invalid,false);throw new AssertionError("bad snapshot accepted");}catch(java.io.IOException expected){checks++;}
        require(runtime.grid().get(result.componentId())!=null,"malformed snapshot cannot partially replace a live grid");
        var foreign=Player.create();foreign.team(Team.crux);Unit other=UnitTypes.alpha.create(Team.crux);other.add();foreign.unit(other);
        require(!runtime.apply(foreign,LayerActions.Action.remove(result.componentId())).accepted(),"enemy team cannot remove upper building");
        require(runtime.apply(Vars.player,LayerActions.Action.remove(result.componentId())).accepted(),"owner removes upper building");
        require(runtime.grid().components().isEmpty(),"remove clears grid occupancy");
        // A synthetic native wall remains a genuine native Building fact for the structural solver.
        Tile foundationTile=Vars.world.tile(12,12);Building foundation=Building.create();foundation.block=Blocks.copperWall;foundation.team=Team.sharded;foundation.health=Blocks.copperWall.health;foundation.tile=foundationTile;foundationTile.build=foundation;
        var structural=runtime.apply(Vars.player,LayerActions.Action.place(12,12,1,"copper-wall",false,0,LayerComponent.Mode.STRUCTURAL));
        require(structural.accepted(),"native ground wall supports upper wall: "+structural.reason());
        var slab=runtime.apply(Vars.player,LayerActions.Action.place(12,12,2,"",true,0,LayerComponent.Mode.STRUCTURAL));
        require(slab.accepted(),"upper column supports real next-floor platform: "+slab.reason());
        require(foundationTile.build==foundation,"construction never replaces native foundation");
        foundationTile.build=null;runtime.solver().invalidateColumn(12,12);runtime.update(.1);
        require(runtime.warning(structural.componentId()),"support loss enters warning grace before collapse");
        boolean sawFalling=false;for(int i=0;i<60;i++){runtime.update(.1);if(!sawFalling&&!runtime.falling().isEmpty()){
            Core.settings.put("fpv-layer-max-components",2);Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
            var duringFall=runtime.apply(Vars.player,LayerActions.Action.place(16,16,4,"copper-wall",false,0,LayerComponent.Mode.FREE));
            require(!duringFall.accepted()&&duringFall.reason().contains("including falling"),"falling components still consume total save/network component capacity");
            Core.settings.put("fpv-layer-max-components",16384);Core.settings.put(LayerRuntime.FREE_PERMISSION,false);
        }sawFalling|=!runtime.falling().isEmpty();}
        require(sawFalling,"detached structural section becomes an aggregate falling chunk");
        require(runtime.grid().components().isEmpty()&&runtime.falling().isEmpty(),"falling aggregate resolves impact without duplicate buildings");
        // Native core inventory pays the same requirements as the ground recipe.
        var core=(mindustry.world.blocks.storage.CoreBlock.CoreBuild)Blocks.coreShard.newBuilding();core.block=Blocks.coreShard;core.team=Team.sharded;core.items=new mindustry.world.modules.ItemModule();core.storageCapacity=4000;core.set(4*Vars.tilesize,4*Vars.tilesize);Team.sharded.data().cores.add(core);
        Vars.state.rules.infiniteResources=false;Core.settings.put(LayerRuntime.FREE_PERMISSION,true);core.items.add(Items.copper,100);
        int price=Blocks.copperWall.requirements[0].amount;var paid=runtime.apply(Vars.player,LayerActions.Action.place(8,8,2,"copper-wall",false,0,LayerComponent.Mode.FREE));
        require(paid.accepted(),"paid upper construction accepts native core inventory: "+paid.reason());
        require(core.items.get(Items.copper)==100-price,"upper wall charges original recipe without height multiplier");
        runtime.apply(Vars.player,LayerActions.Action.remove(paid.componentId()));
        require(core.items.get(Items.copper)==100-price+Math.round(price*Vars.state.rules.deconstructRefundMultiplier),"deconstruction uses native refund multiplier");
        int beforeFailure=core.items.get(Items.copper);LayerWorld.ChangeListener rejectedPlacement=(before,after)->{if(before==null&&after!=null){Vars.state.rules.buildCostMultiplier=5;throw new IllegalStateException("Fixture placement listener rejects");}};
        runtime.grid().addListener(rejectedPlacement);var failedPlacement=runtime.apply(Vars.player,LayerActions.Action.place(8,8,2,"copper-wall",false,0,LayerComponent.Mode.FREE));runtime.grid().removeListener(rejectedPlacement);Vars.state.rules.buildCostMultiplier=1;
        require(!failedPlacement.accepted()&&runtime.grid().at(8,8,2,LayerComponent.Kind.BLOCK)==null&&core.items.get(Items.copper)==beforeFailure,"failed placement refunds exact paid receipt even if a callback changes prices");
        Core.settings.put("fpv-platform-copper",2);Core.settings.put("fpv-platform-lead",0);int beforePlatform=core.items.get(Items.copper);
        var purchasedPlatform=runtime.apply(Vars.player,LayerActions.Action.place(8,8,3,"",true,0,LayerComponent.Mode.FREE));
        require(purchasedPlatform.accepted(),"platform purchase succeeds before recipe change");
        byte[] paidSave=runtime.replication();runtime.restore(paidSave,false);Core.settings.put("fpv-platform-copper",100);
        runtime.apply(Vars.player,LayerActions.Action.remove(purchasedPlatform.componentId()));
        require(core.items.get(Items.copper)==beforePlatform-2+Math.round(2*Vars.state.rules.deconstructRefundMultiplier),"saved receipt prevents resource minting after recipe change");
        Vars.state.rules.infiniteResources=true;var gifted=runtime.apply(Vars.player,LayerActions.Action.place(8,8,3,"copper-wall",false,0,LayerComponent.Mode.FREE));
        Vars.state.rules.infiniteResources=false;int beforeGift=core.items.get(Items.copper);runtime.apply(Vars.player,LayerActions.Action.remove(gifted.componentId()));
        require(core.items.get(Items.copper)==beforeGift,"infinite-resource placement cannot mint resources when rules return to survival");
        LayerWorld beforeSettings=runtime.grid();Core.settings.put("fpv-layer-max",128);runtime.applyHostSettings();
        require(runtime.grid()==beforeSettings&&runtime.grid().coordinates().maxLayers()==128,"host floor limit applies without replacing grid identity");
        Vars.state.rules.infiniteResources=true;
        var factory=runtime.apply(Vars.player,LayerActions.Action.place(20,20,5,"graphite-press",false,0,LayerComponent.Mode.FREE));
        var output=runtime.apply(Vars.player,LayerActions.Action.place(22,20,5,"container",false,0,LayerComponent.Mode.FREE));
        var upstairs=runtime.apply(Vars.player,LayerActions.Action.place(22,20,7,"container",false,0,LayerComponent.Mode.FREE));
        require(factory.accepted()&&output.accepted()&&upstairs.accepted(),"functional elevated factory/storage placement: "+factory.reason()+" / "+output.reason()+" / "+upstairs.reason());
        builder.addItem(Items.coal,8);require(runtime.apply(Vars.player,LayerActions.Action.deposit(factory.componentId(),Items.coal.id,8)).accepted(),"manual cargo deposit reaches elevated native crafter");
        require(builder.stack.amount==0&&runtime.building(factory.componentId()).items.get(Items.coal)==8,"manual deposit conserves item count");
        arc.util.Time.delta=1;for(int i=0;i<440;i++)runtime.update(1d/60);
        require(runtime.building(output.componentId()).items.get(Items.graphite)==4,"native upper factory recipe feeds native neighbour");
        require(runtime.building(upstairs.componentId()).items.total()==0,"unlinked floors do not exchange items");
        var link=runtime.apply(Vars.player,LayerActions.Action.link(output.componentId(),upstairs.componentId(),LayerVerticalTransfers.Kind.ITEMS,false));
        require(link.accepted(),"explicit vertical item connection accepted: "+link.reason());
        for(int i=0;i<60;i++)runtime.update(1d/60);
        require(runtime.building(upstairs.componentId()).items.get(Items.graphite)==4&&runtime.building(output.componentId()).items.total()==0,"intentional vertical connector transfers without duplication");
        Building repaired=runtime.building(factory.componentId());float repairedHealth=repaired.health-13;repaired.damage(20);repaired.heal(7);runtime.update(.01);
        require(Math.abs(repaired.health-repairedHealth)<.001&&Math.abs(runtime.grid().get(factory.componentId()).health()-repairedHealth)<.001,"same-frame damage then repair retains newest native health");
        byte[] production=runtime.replication();LayerWorld sameGrid=runtime.grid();Building sameFactory=runtime.building(factory.componentId());runtime.restore(production,true);
        require(runtime.grid()==sameGrid&&runtime.building(factory.componentId())==sameFactory,"remote state reconciliation preserves grid and native instance identity");
        require(runtime.transfers().links().size()==1&&runtime.building(upstairs.componentId()).items.get(Items.graphite)==4,"native inventories and explicit link survive network/save snapshot");
        require(runtime.apply(Vars.player,LayerActions.Action.withdraw(upstairs.componentId(),Items.graphite.id,3)).accepted(),"authorized withdrawal to pilot cargo");
        require(builder.stack.amount==3&&builder.stack.item==Items.graphite&&runtime.building(upstairs.componentId()).items.get(Items.graphite)==1,"withdrawal conserves native cargo/inventory");
        Core.settings.put("fpv-layer-max",1);runtime.applyHostSettings();require(runtime.grid().coordinates().maxLayers()>=7&&runtime.grid().get(upstairs.componentId())!=null,"lowering floor limit never destroys higher occupied levels");
        Unit friendly=UnitTypes.dagger.create(Team.sharded),enemy=UnitTypes.dagger.create(Team.crux);friendly.set(3*Vars.tilesize,25*Vars.tilesize);enemy.set(friendly);friendly.add();enemy.add();friendly.health=enemy.health=10000;
        friendly.healthMultiplier=enemy.healthMultiplier=.1f;Vars.state.rules.unitHealthMultiplier=.5f;
        Core.settings.put("fpv-collapse-grace-ms",0);Core.settings.put("fpv-collapse-max-damage",100);
        runtime.grid().add(new LayerComponent(runtime.grid().nextId(),LayerComponent.Kind.BLOCK,3,25,4,"copper-wall",1,0,Team.sharded.id,LayerComponent.Mode.STRUCTURAL,320,320,100,1.4,LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]));
        for(int i=0;i<100&&friendly.health==10000;i++)runtime.update(.05);
        require(friendly.health<10000&&enemy.health<10000,"falling chunk damages both friendly and enemy native units");
        float friendlyAfter=friendly.health,enemyAfter=enemy.health;
        require(10000-friendlyAfter<=100.001f&&10000-enemyAfter<=100.001f,"falling section respects per-target HP cap");
        runtime.restore(runtime.replication(),false);for(int i=0;i<100;i++)runtime.update(.05);
        require(friendly.health==friendlyAfter&&enemy.health==enemyAfter,"impact ledger survives save and never damages a target twice");
        Vars.state.rules.unitHealthMultiplier=1;Vars.state.rules.blockHealthMultiplier=.1f;
        Building fragile=nativeTarget(28,28);addFalling(runtime,28,28,LayerComponent.Kind.BLOCK,4);
        for(int i=0;i<100;i++)runtime.update(.05);
        require(fragile.health<10000&&10000-fragile.health<=100.001f,"native low-health building rule cannot amplify collapse beyond HP cap");
        Vars.state.rules.blockHealthMultiplier=0;Core.settings.put("fpv-collapse-max-damage",0);Building harmless=nativeTarget(30,30);
        Unit zeroScale=UnitTypes.dagger.create(Team.sharded);zeroScale.set(30*Vars.tilesize,30*Vars.tilesize);zeroScale.add();zeroScale.health=10000;zeroScale.healthMultiplier=0;
        addFalling(runtime,30,30,LayerComponent.Kind.BLOCK,4);for(int i=0;i<100;i++)runtime.update(.05);
        require(harmless.health==10000&&!harmless.dead&&zeroScale.health==10000&&!zeroScale.dead,"zero collapse cap never calls native lethal zero-scale damage");
        Vars.state.rules.blockHealthMultiplier=1;Core.settings.put("fpv-collapse-max-damage",100);Building intersected=nativeTarget(32,32);
        addFalling(runtime,32,32,LayerComponent.Kind.PLATFORM,1);runtime.update(.05);
        require(runtime.falling().isEmpty()&&intersected.health<10000,"section already intersecting raised surface resolves immediately instead of falling through terrain");
        var largeStateIds=new java.util.ArrayList<Long>();byte[] largeState=new byte[LayerComponent.MAX_STATE_BYTES];
        for(int i=0;i<220;i++){long id=runtime.grid().nextId();largeStateIds.add(id);runtime.grid().add(new LayerComponent(id,LayerComponent.Kind.BLOCK,i%40,30+i/40,5,"copper-wall",1,0,Team.sharded.id,LayerComponent.Mode.FREE,320,320,2,1.4,LayerComponent.SupportProfile.NONE,new byte[0],largeState));}
        var budget=runtime.preview(Vars.player,LayerActions.Action.place(10,10,5,"copper-wall",false,0,LayerComponent.Mode.FREE));
        require(!budget.accepted()&&budget.reason().contains("budget"),"large opaque saved states stop construction before exhausting total payload budget");
        for(long id:largeStateIds)runtime.grid().remove(id);
        runtime.replicationFailed("fixture encoding failure");var temporarilyBlocked=runtime.preview(Vars.player,LayerActions.Action.place(10,10,5,"copper-wall",false,0,LayerComponent.Mode.FREE));
        require(!temporarilyBlocked.accepted()&&temporarilyBlocked.reason().contains("paused"),"encoding failure visibly blocks further construction without removing existing data");
        runtime.replication();require(runtime.preview(Vars.player,LayerActions.Action.place(10,10,5,"copper-wall",false,0,LayerComponent.Mode.FREE)).accepted(),"successful bounded snapshot restores construction after synchronization failure");
        runtime.close();altitudes.close();
        System.out.println("LayerRuntimeNativeTest PASS "+checks+" checks");
    }
    private static Building nativeTarget(int x,int y){
        Building target=new Building(){@Override public void healthChanged(){}};target.block=Blocks.copperWall;target.team=Team.sharded;target.health=10000;target.tile=Vars.world.tile(x,y);target.tile.build=target;target.set(x*Vars.tilesize,y*Vars.tilesize);return target;
    }
    private static void addFalling(LayerRuntime runtime,int x,int y,LayerComponent.Kind kind,int layer){runtime.grid().add(new LayerComponent(runtime.grid().nextId(),kind,x,y,layer,kind==LayerComponent.Kind.PLATFORM?"fpv-platform":"copper-wall",1,0,Team.sharded.id,LayerComponent.Mode.STRUCTURAL,320,320,100,kind==LayerComponent.Kind.PLATFORM?.16:1.4,LayerComponent.SupportProfile.NONE,new byte[0],new byte[0]));}
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
