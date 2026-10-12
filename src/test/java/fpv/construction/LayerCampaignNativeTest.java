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
import mindustry.world.blocks.campaign.*;
import mindustry.world.meta.BlockFlag;

/** In-memory campaign accounting only: no real user save, planet transition or disk-backed settings. */
public final class LayerCampaignNativeTest {
    private static int checks;
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(80,80);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,640,640);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.set(GameState.State.playing);Vars.state.rules.infiniteResources=true;
        LayerBuildingAdapter owner=new LayerBuildingAdapter(null);LayerCoreLifecycle lifecycle=new LayerCoreLifecycle();
        for(Block block:new Block[]{Blocks.launchPad,Blocks.advancedLaunchPad,Blocks.landingPad,Blocks.interplanetaryAccelerator})require(LayerBuildingAdapter.supports(block),"campaign block supported: "+block.name);
        var launch=(LaunchPad.LaunchPadBuild)owner.create(1,10,10,2,Blocks.launchPad,Team.sharded,0);
        var land=(LandingPad.LandingPadBuild)owner.create(2,25,10,2,Blocks.landingPad,Team.sharded,0);
        var accelerator=(Accelerator.AcceleratorBuild)owner.create(3,45,25,2,Blocks.interplanetaryAccelerator,Team.sharded,0);
        require(Vars.indexer.getFlagged(Team.sharded,BlockFlag.launchPad).isEmpty(),"candidate campaign registry is inert");
        lifecycle.activate(owner);require(Vars.indexer.getFlagged(Team.sharded,BlockFlag.launchPad).contains(launch),"live upper launchpad participates in native export-stat preservation");
        launch.items.add(Items.copper,launch.block.itemCapacity);launch.launchCounter=((LaunchPad)launch.block).launchTime;launch.efficiency=1;Time.update();launch.updateTile();
        LaunchPayload payload=null;for(var entity:Groups.all)if(entity instanceof LaunchPayload p){payload=p;break;}
        require(payload!=null&&payload.stacks.sum(s->s.amount)==launch.block.itemCapacity&&launch.items.empty(),"native export pod contains exact real removed resources");
        require(LayerCampaignFamilies.launchPodTimer(launch)==120,"upper launch animation begins at source layer");
        byte[] launchSave=owner.writeState(1);owner.readState(1,launchSave);require(LayerCampaignFamilies.launchPodTimer(launch)==120,"launch visual state survives snapshot");
        var sector=Planets.serpulo.sectors.first();Vars.state.rules.sector=sector;sector.info.destination=Planets.serpulo.sectors.get(1);Planets.serpulo.campaignRules.legacyLaunchPads=false;
        payload.remove();require(sector.info.export.get(Items.copper).counter==launch.block.itemCapacity,"native campaign export ledger receives actual shipped amount");
        Vars.state.rules.sector=null;require(owner.configureNative(2,Items.copper),"landing item configuration follows native permissions");
        var landType=(LandingPad)land.block;land.liquids.add(landType.consumeLiquid,landType.consumeLiquidAmount);land.efficiency=1;land.updateTile();require(land.arriving==Items.copper,"sandbox landing invokes upper receiver directly");
        byte[] landSave=owner.writeState(2);owner.readState(2,landSave);
        for(int i=0;i<landType.arrivalDuration+2;i++){Time.update();land.efficiency=1;land.updateTile();}
        require(land.items.get(Items.copper)==land.block.itemCapacity,"landing fills exact native item capacity");
        require(Math.abs(land.liquids.get(landType.consumeLiquid))<.01,"landing consumes exact native coolant amount");
        require(Vars.world.build(25,10)==null,"landing never targets ground tile at matching XY");
        // One actual campaign import token cannot start both a native queued pad and an upper pad.
        Vars.state.rules.sector=sector;sector.info.getImportRates(Planets.serpulo)[Items.copper.id]=60;
        land.items.clear();land.cooldown=0;land.priority=-100;land.liquids.add(landType.consumeLiquid,landType.consumeLiquidAmount);land.updateTimers();
        sector.info.importCooldownTimers.put(Items.copper,1.5f);
        Vars.world.tile(25,10).setBlock(Blocks.landingPad,Team.sharded,0);var nativeLand=(LandingPad.LandingPadBuild)Vars.world.build(25,10);nativeLand.config=Items.copper;nativeLand.priority=100;
        var field=LandingPad.class.getDeclaredField("waiting");field.setAccessible(true);var queue=(arc.struct.ObjectMap<mindustry.type.Item,arc.struct.Seq<LandingPad.LandingPadBuild>>)field.get(null);
        queue.get(Items.copper,()->new arc.struct.Seq<>()).add(nativeLand);land.updateTile();
        require(land.arriving==Items.copper&&nativeLand.arriving==null&&queue.get(Items.copper).isEmpty(),"upper arrival consumes queued import reservation without packed-XY delivery");
        require(sector.info.importCooldownTimers.get(Items.copper,0f)==0,"one shared campaign shipment token consumed once");
        Vars.state.rules.sector=null;accelerator.efficiency=1;Time.update();accelerator.updateTile();require(accelerator.progress>0,"native accelerator progress updates upstairs");
        var accelType=(Accelerator)accelerator.block;for(var stack:accelType.launchBlock.requirements)accelerator.items.add(stack.item,stack.amount);
        accelerator.consume();require(accelerator.items.empty(),"native core launch recipe consumes exact resources");
        accelerator.beginLaunch(true);require(accelerator.launching&&Groups.bullet.isEmpty(),"layer launch animation avoids unsafe XY lightning damage");accelerator.endLaunch();
        lifecycle.deactivate();require(Vars.indexer.getFlagged(Team.sharded,BlockFlag.launchPad).isEmpty(),"launch export flags removed with upper runtime");
        System.out.println("LayerCampaignNativeTest: "+checks+" checks passed; planet transition UI deliberately not invoked");
    }
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
