package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.logic.LUnitControl;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.power.NuclearReactor.NuclearReactorBuild;

/** Real native payload identity, server boundaries, XYZ transfer limits and destruction lifecycle. */
public final class LayerPayloadLogicNativeTest {
    private static int checks;private static long id;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Vars.content.init();Vars.logicVars=new mindustry.logic.GlobalVars();Vars.logicVars.init();Vars.state.rules.editor=true;Vars.state.set(GameState.State.playing);
        var owner=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){
            @Override public double unitAltitude(Unit unit){return unit.flag;}
            @Override public boolean releaseUnit(Building source,Unit unit,double x,double y){unit.set((float)x,(float)y);unit.flag=((LayerTile)source.tile).layer*LayerCoordinates.DEFAULT.spacing();unit.add();return unit.isAdded();}
        });
        var processor=(LogicBuild)create(owner,Blocks.microProcessor,5,5,2);
        var source=(PayloadConveyorBuild)create(owner,Blocks.payloadConveyor,10,10,2);
        var destination=(PayloadConveyorBuild)create(owner,Blocks.payloadConveyor,16,10,2);
        var other=(PayloadConveyorBuild)create(owner,Blocks.payloadConveyor,16,10,3);
        Unit unit=UnitTypes.mega.create(Team.sharded);unit.flag=2.8;unit.set(source);unit.add();Payloadc carrier=(Payloadc)unit;
        BuildPayload block=new BuildPayload(Blocks.copperWall,Team.sharded);source.item=block;
        require(LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payTake,false)&&source.item==null&&carrier.payloads().peek()==block,"takes actual produced building payload from selected floor");
        unit.set(destination);require(!LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payDrop,false),"native90-frame payload cooldown retained");Time.time+=91;
        unit.flag=100;require(!LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payDrop,false)&&destination.item==null,"payload drop requires real altitude proximity");unit.flag=2.8;
        require(LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payDrop,false)&&!carrier.hasPayload()&&destination.item==block&&other.item==null,"drop reaches only correct XYZ building and consumes carried payload once");
        byte[] state=owner.writeState(3);owner.readState(3,state);require(destination.item instanceof BuildPayload p&&p.block()==Blocks.copperWall,"transferred native payload survives upper save state");
        unit.set(30*Vars.tilesize,30*Vars.tilesize);Unit target=UnitTypes.dagger.create(Team.sharded);target.set(unit);target.flag=102.8;target.add();Time.time+=91;
        require(!LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payTake,true),"unit payload pickup cannot grab same-XY unit on another altitude");target.flag=2.8;
        require(LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payTake,true)&&!target.isAdded()&&carrier.payloads().peek() instanceof UnitPayload p&&p.unit==target,"valid XYZ unit pickup preserves original unit");Time.time+=91;
        require(LayerLogicPayloadBehavior.run(owner,processor,unit,LUnitControl.payDrop,false)&&target.isAdded()&&!carrier.hasPayload()&&target.flag==2.8,"unit payload drop uses upper release callback");
        var enter=(PayloadConveyorBuild)create(owner,Blocks.payloadConveyor,24,10,2);target.set(enter);Time.time+=91;int originalId=target.id;
        require(LayerLogicPayloadBehavior.run(owner,processor,target,LUnitControl.payEnter,false)&&!target.isAdded()&&enter.item instanceof UnitPayload p&&p.unit==target,"payEnter moves live unit into real upper payload receiver");
        require(target.id!=originalId&&!target.dead,"payload entry resets network removal ID without killing the unit");
        unit.remove();owner.clear();destruction();System.out.println("LayerPayloadLogicNativeTest: "+checks+" checks passed");
    }
    private static void destruction(){
        java.util.ArrayList<LayerDestruction.Blast> blasts=new java.util.ArrayList<>();
        var owner=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){@Override public void destroyed(Building value){var blast=LayerDestruction.generator(value,((LayerTile)value.tile).layer*LayerCoordinates.DEFAULT.spacing());if(blast!=null)blasts.add(blast);}});
        var reactor=(NuclearReactorBuild)create(owner,Blocks.thoriumReactor,10,10,4);long reactorId=id;Vars.state.rules.reactorExplosions=true;
        require(LayerDestruction.generator(reactor,5.6)==null,"cold empty reactor does not gain an invented explosion");reactor.heat=.7f;reactor.productionEfficiency=1;
        Vars.state.rules.reactorExplosions=false;require(LayerDestruction.generator(reactor,5.6)==null,"native reactor explosion rule respected");Vars.state.rules.reactorExplosions=true;
        byte[] saved=owner.writeState(reactorId);owner.readState(reactorId,saved);require(blasts.isEmpty(),"save restore never emits destruction");
        reactor.kill();reactor.kill();require(blasts.size()==1&&blasts.get(0).z()==5.6&&blasts.get(0).spec().damage()>0,"hot reactor destruction emits one bounded actual-height blast");
        owner.remove(reactorId);require(blasts.size()==1,"dead object cleanup never duplicates blast");var second=(NuclearReactorBuild)create(owner,Blocks.thoriumReactor,15,15,4);second.heat=.7f;second.productionEfficiency=1;owner.clear();require(blasts.size()==1,"manual removal or registry replacement never detonates reactor");
    }
    private static Building create(LayerBuildingAdapter owner,Block block,int x,int y,int layer){return owner.create(++id,x,y,layer,block,Team.sharded,0);}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
