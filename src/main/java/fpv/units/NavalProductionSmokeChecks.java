package fpv.units;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.Block;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.payloads.*;
import mindustry.type.UnitType;

/** Actual native production/dump and sailing in the disposable SDL lab, never the player's save. */
public final class NavalProductionSmokeChecks {
    private static int checks;
    public static String run(FactorySpawnSafety safety,UnitAltitudeSystem altitudes){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active()||Vars.world.width()<128)throw new IllegalStateException("Isolated naval production lab required");
        checks=0;var floors=new mindustry.world.blocks.environment.Floor[70*70];int i=0;
        for(int x=35;x<105;x++)for(int y=35;y<105;y++){var t=Vars.world.tile(x,y);floors[i++]=t.floor();t.setFloor(Blocks.water.asFloor());}
        float oldDelta=Time.delta,oldTime=Time.time;int oldCap=Vars.state.rules.unitCap;Vars.state.rules.unitCap=1000;Time.delta=1;
        try{
            Block[] producer={Blocks.navalFactory,Blocks.navalFactory,Blocks.additiveReconstructor,Blocks.additiveReconstructor,Blocks.multiplicativeReconstructor,Blocks.multiplicativeReconstructor,Blocks.exponentialReconstructor,Blocks.exponentialReconstructor,Blocks.tetrativeReconstructor,Blocks.tetrativeReconstructor};
            UnitType[] output={UnitTypes.risso,UnitTypes.retusa,UnitTypes.minke,UnitTypes.oxynoe,UnitTypes.bryde,UnitTypes.cyerce,UnitTypes.sei,UnitTypes.aegires,UnitTypes.omura,UnitTypes.navanax};
            UnitType[] input={null,null,UnitTypes.risso,UnitTypes.retusa,UnitTypes.minke,UnitTypes.oxynoe,UnitTypes.bryde,UnitTypes.cyerce,UnitTypes.sei,UnitTypes.aegires};
            for(int n=0;n<output.length;n++)scenario(safety,altitudes,producer[n],output[n],input[n],n%4);
            // A valid native center-water channel surrounded by flat dry shore must still release.
            for(int x=35;x<105;x++)for(int y=35;y<105;y++)Vars.world.tile(x,y).setFloor(y==64?Blocks.water.asFloor():Blocks.sand.asFloor());
            scenario(safety,altitudes,Blocks.navalFactory,UnitTypes.risso,null,0);
            return "PASS: "+checks+" native SDL production checks; both ten-ship lines, native recipes/costs, four exit directions, blocked/unblocked output, full-body clearance, water Z/health, single release, native sailing and shoreline";
        }finally{Vars.world.tile(64,64).setBlock(Blocks.air);i=0;for(int x=35;x<105;x++)for(int y=35;y<105;y++)Vars.world.tile(x,y).setFloor(floors[i++]);Time.delta=oldDelta;Time.time=oldTime;Vars.state.rules.unitCap=oldCap;}
    }
    private static void scenario(FactorySpawnSafety safety,UnitAltitudeSystem altitudes,Block block,UnitType expected,UnitType input,int rotation){
        var source=Vars.world.tile(64,64);source.setBlock(block,Vars.player.team(),rotation);var holder=(PayloadBlock.PayloadBlockBuild<?>)source.build;
        Unit unit=null;mindustry.world.Tile obstacle=null;
        try{
            for(var item:Vars.content.items())holder.items.add(item,10000);int before=holder.items.total();holder.efficiency=1;
            if(holder instanceof UnitFactory.UnitFactoryBuild f){var factory=(UnitFactory)block;f.currentPlan=factory.plans.indexOf(p->p.unit==expected);check(f.currentPlan>=0,"native plan");f.progress=factory.plans.get(f.currentPlan).time;f.updateTile();}
            else{var r=(Reconstructor.ReconstructorBuild)holder;r.payload=new UnitPayload(input.create(holder.team));r.progress=((Reconstructor)block).constructTime;r.updateTile();}
            check(holder.payload!=null,"completed native payload");unit=((UnitPayload)holder.payload).unit;check(unit.type==expected&&!unit.isAdded(),"native output identity");check(holder.items.total()<before,"native resources consumed");
            int after=holder.items.total(),releases=safety.releases();float health=unit.health;
            int distance=(int)Math.ceil(block.size*.5+unit.hitSize/(2*Vars.tilesize)+.2),dx=arc.math.geom.Geometry.d4x(rotation),dy=arc.math.geom.Geometry.d4y(rotation);
            obstacle=Vars.world.tile(64+dx*distance,64+dy*distance);obstacle.setBlock(Blocks.copperWall,holder.team,0);
            for(int tick=0;tick<200;tick++){Time.time++;holder.moveOutPayload();}
            check(holder.payload!=null&&!unit.isAdded(),"blocked full hull waits "+expected.name);check(holder.items.total()==after,"blocked output has no repeat cost");obstacle.setBlock(Blocks.air);
            for(int tick=0;tick<300&&holder.payload!=null;tick++){Time.time++;holder.moveOutPayload();}
            check(holder.payload==null&&unit.isAdded()&&!unit.dead,"native release "+expected.name);check(safety.releases()==releases+1,"exactly one boat");
            var state=altitudes.unitState(unit);check(Math.abs(state.z()-state.halfHeight())<.02,"physical waterline "+expected.name);check(unit.floorOn().isLiquid&&!unit.onSolid(),"navigable water center");check(unit.health==health,"healthy birth");
            double start=unit.x*dx+unit.y*dy;
            unit.controller(new mindustry.entities.units.AIController(){@Override public void updateUnit(){}});
            for(int tick=0;tick<90;tick++){Time.time++;unit.vel.set(dx*unit.type.speed,dy*unit.type.speed);unit.update();altitudes.update(1d/60);}
            check(unit.x*dx+unit.y*dy>start+Vars.tilesize*.2,"boat leaves output using native movement");check(!unit.dead&&unit.health==health,"sailing retains health");
        }finally{if(unit!=null&&unit.isAdded())unit.remove();if(obstacle!=null)obstacle.setBlock(Blocks.air);source.setBlock(Blocks.air);}
    }
    private static void check(boolean ok,String message){checks++;if(!ok)throw new IllegalStateException("Naval production: "+message);}
}
