package fpv.units;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.payloads.*;

/** Exercises native production, payload serialization and actual dump/add, including shore outputs. */
public final class NavalFactoryNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,768,768);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        var altitude=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->{var t=Vars.world.tile((int)Math.round(x),(int)Math.round(-y));return t!=null&&t.build!=null?1:0;},altitude);
        var safety=new FactorySpawnSafety(world,altitude);safety.install();
        try{
            for(var t:Vars.world.tiles)t.setFloor(Blocks.water.asFloor());
            scenario(safety,altitude,Blocks.navalFactory,UnitTypes.risso,null,false);
            scenario(safety,altitude,Blocks.navalFactory,UnitTypes.retusa,null,false);
            scenario(safety,altitude,Blocks.additiveReconstructor,UnitTypes.minke,UnitTypes.risso,false);
            scenario(safety,altitude,Blocks.tetrativeReconstructor,UnitTypes.omura,UnitTypes.sei,false);
            // Native navigation requires water beneath the boat center, not every artwork/hull edge.
            for(var t:Vars.world.tiles)t.setFloor(t.y==30?Blocks.water.asFloor():Blocks.sand.asFloor());
            scenario(safety,altitude,Blocks.navalFactory,UnitTypes.risso,null,true);
            invalidWater(safety);
            System.out.println("NavalFactoryNativeTest: "+checks+" native factories/reconstructors/shoreline/blocked output/costs/waterline/serialization checks passed");
        }finally{safety.close();altitude.close();}
    }
    private static void scenario(FactorySpawnSafety safety,UnitAltitudeSystem altitudes,Block block,mindustry.type.UnitType output,mindustry.type.UnitType input,boolean shore){
        var tile=Vars.world.tile(30,30);tile.setBlock(block,Team.sharded,0);var holder=(PayloadBlock.PayloadBlockBuild<?>)tile.build;
        for(var item:Vars.content.items())holder.items.add(item,10000);holder.efficiency=1;int cost=holder.items.total();
        if(holder instanceof UnitFactory.UnitFactoryBuild f){var factory=(UnitFactory)block;f.currentPlan=factory.plans.indexOf(p->p.unit==output);check(f.currentPlan>=0,"native plan");f.progress=factory.plans.get(f.currentPlan).time;f.updateTile();}
        else {var r=(Reconstructor.ReconstructorBuild)holder;r.payload=new UnitPayload(input.create(Team.sharded));r.progress=((Reconstructor)block).constructTime;r.updateTile();}
        check(holder.payload instanceof UnitPayload,"native completion payload");var unit=((UnitPayload)holder.payload).unit;check(unit.type==output&&!unit.isAdded(),"correct unspawned output");check(holder.items.total()<cost,"native cost once");
        // Native serialized payload can be wrapped again after reload without changing its identity/type.
        var memory=new java.io.ByteArrayOutputStream();Payload.write(holder.payload,new arc.util.io.Writes(new java.io.DataOutputStream(memory)));
        var restored=Payload.read(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(memory.toByteArray()))));check(restored instanceof UnitPayload&&((UnitPayload)restored).unit.type==output,"native payload read/write");
        restore(holder,(UnitPayload)restored);arc.Events.fire(new mindustry.game.EventType.WorldLoadEvent());unit=((UnitPayload)holder.payload).unit;
        int inventory=holder.items.total(),released=safety.releases();float health=unit.health;
        int bx=30+(int)Math.ceil(block.size*.5+unit.hitSize/16.0+.2);var obstacle=Vars.world.tile(bx,30);obstacle.setBlock(Blocks.copperWall,Team.sharded,0);
        for(int i=0;i<200;i++){Time.time++;holder.moveOutPayload();}
        check(holder.payload!=null&&!unit.isAdded(),"blocked output waits");check(holder.items.total()==inventory,"no repeat costs while waiting");obstacle.setBlock(Blocks.air);
        for(int i=0;i<300&&holder.payload!=null;i++){Time.time++;holder.moveOutPayload();}
        check(holder.payload==null&&unit.isAdded(),"native release "+output.name+(shore?" at shoreline":""));check(safety.releases()==released+1,"single release");
        var pose=altitudes.unitState(unit);check(Math.abs(pose.z()-pose.halfHeight())<1e-7,"water surface birth Z");check(unit.health==health&&unit.vel.isZero(),"no birth impact");check(unit.floorOn().isLiquid&&!unit.onSolid(),"native navigable water output");
        unit.remove();tile.setBlock(Blocks.air);
    }
    private static void invalidWater(FactorySpawnSafety safety){
        for(var tile:Vars.world.tiles)tile.setFloor(Blocks.sand.asFloor());
        for(int x=29;x<=31;x++)for(int y=29;y<=31;y++)Vars.world.tile(x,y).setFloor(Blocks.water.asFloor());
        Vars.world.tile(50,30).setFloor(Blocks.water.asFloor()); // A distant pool must not become a fallback teleport.
        var source=Vars.world.tile(30,30);source.setBlock(Blocks.navalFactory,Team.sharded,0);var f=(UnitFactory.UnitFactoryBuild)source.build;
        for(var item:Vars.content.items())f.items.add(item,10000);f.currentPlan=0;f.efficiency=1;f.progress=((UnitFactory)f.block).plans.get(0).time;f.updateTile();var unit=f.payload.unit;int releases=safety.releases();
        for(int i=0;i<250;i++){Time.time++;f.moveOutPayload();}
        check(f.payload!=null&&!unit.isAdded()&&safety.releases()==releases,"dry native output waits without teleporting to another pool");
        check(safety.lastObstruction().contains("requires water"),"actual dry-output reason recorded");
        check(unit.x<34*Vars.tilesize,"blocked payload stays at native factory mouth");source.setBlock(Blocks.air);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void restore(PayloadBlock.PayloadBlockBuild holder,UnitPayload payload){holder.payload=payload;}
}
