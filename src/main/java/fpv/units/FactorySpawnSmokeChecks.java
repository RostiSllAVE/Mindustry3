package fpv.units;

import arc.util.Time;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.Reconstructor;

/** Runs actual native production/reconstruction and moveOutPayload -> dump -> add, not synthetic unit.add spawns. */
public final class FactorySpawnSmokeChecks {
    private int assertions;
    private FactorySpawnSmokeChecks(){}
    public static String run(FactorySpawnSafety safety,World3DAdapter world,UnitAltitudeSystem altitudes){return new FactorySpawnSmokeChecks().verify(safety,world,altitudes);}
    private String verify(FactorySpawnSafety safety,World3DAdapter world,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active()&&Vars.world.width()>=100,"isolated empty factory lab required");
        float delta=Time.delta,time=Time.time;int cap=Vars.state.rules.unitCap;Vars.state.rules.unitCap=1000;Time.delta=1;
        Unit active=null;Tile source=Vars.world.tile(34,34),obstacle=Vars.world.tile(37,34),exitFloor=Vars.world.tile(36,34);
        try{
            Block[] factories={Blocks.groundFactory,Blocks.multiplicativeReconstructor,Blocks.tetrativeReconstructor,Blocks.airFactory,Blocks.tetrativeReconstructor};
            UnitType[] outputs={UnitTypes.crawler,UnitTypes.spiroct,UnitTypes.toxopid,UnitTypes.flare,UnitTypes.eclipse};
            UnitType[] inputs={null,UnitTypes.atrax,UnitTypes.arkyid,null,UnitTypes.antumbra};
            for(int scenario=0;scenario<outputs.length;scenario++){
                Block block=factories[scenario];UnitType expected=outputs[scenario];
                source.setBlock(block,Vars.player.team(),scenario%4);var holder=(PayloadBlockBuild<?>)source.build;
                for(var item:Vars.content.items())holder.items.add(item,10000);
                int inventory=holder.items.total();holder.efficiency=1;
                if(block instanceof UnitFactory factory){
                    var build=(UnitFactory.UnitFactoryBuild)holder;build.currentPlan=factory.plans.indexOf(plan->plan.unit==expected);
                    require(build.currentPlan>=0,"requested native factory plan exists");build.progress=factory.plans.get(build.currentPlan).time;build.updateTile();
                }else{
                    var factory=(Reconstructor)block;var build=(Reconstructor.ReconstructorBuild)holder;
                    build.payload=new UnitPayload(inputs[scenario].create(Vars.player.team()));build.payVector.setZero();build.progress=factory.constructTime;build.updateTile();
                }
                require(holder.payload instanceof UnitPayload,"native producer created a unit payload");active=((UnitPayload)holder.payload).unit;
                require(active.type==expected&&!active.isAdded(),"factory keeps correct newly constructed unit as payload");
                require(holder.items.total()<inventory,"native production resources are still consumed");
                float health=active.health;
                if(scenario==0){
                    obstacle.setBlock(Blocks.copperWall,Vars.player.team(),0);int blocked=safety.blockedAttempts();
                    for(int tick=0;tick<160;tick++){Time.time++;holder.moveOutPayload();}
                    require(holder.payload!=null&&!active.isAdded()&&safety.blockedAttempts()>blocked,"full-body blocked exit waits as payload instead of spawning inside next wall");
                    require(active.health==health,"blocked payload has no fictitious collision damage");obstacle.setBlock(Blocks.air);
                    exitFloor.setBlock(Blocks.conveyor,Vars.player.team(),0);obstacle.setBlock(Blocks.conveyor,Vars.player.team(),0);
                }
                int released=safety.releases();
                for(int tick=0;tick<240&&holder.payload!=null;tick++){Time.time++;holder.moveOutPayload();}
                require(holder.payload==null&&active.isAdded()&&!active.dead,"native "+block.name+" safely releases "+expected.name);
                require(safety.releases()==released+1,"one native release, no duplicate actor");
                var body=world.unitState(active);
                require(body!=null&&Double.isFinite(body.z()),"canonical altitude exists on release frame");
                if(scenario==0)require(body.z()-body.halfHeight()>=world.surfaceHeight(body.x(),body.y())-.02,"passable conveyor exit supplies real physical ground support at birth");
                double direction=Math.toRadians(holder.rotdeg()),dx=Math.cos(direction),dy=Math.sin(direction);
                require((body.x()-holder.x/(double)Vars.tilesize)*dx+(body.y()-holder.y/(double)Vars.tilesize)*dy>block.size*.5+body.radius(),"entire body clears its producing factory in native output direction");
                require(active.health==health&&active.vel.len()<.001,"release preserves health and adds no random inward crash velocity");
                double start=active.x*dx+active.y*dy;
                for(int tick=0;tick<80;tick++){
                    active.vel.set((float)(dx*Math.max(.1f,active.type.speed)),(float)(dy*Math.max(.1f,active.type.speed)));altitudes.captureGroundIntent(1d/60);active.update();altitudes.update(1d/60);Time.time++;
                }
                require(!active.dead&&active.health==health,"first physical/native frames do not kill newborn "+expected.name);
                require(active.x*dx+active.y*dy>start+Vars.tilesize*.25,"new "+expected.name+" can leave the spawn area");
                float before=active.health;active.damagePierce(7);require(active.health<before,"newborn retains legitimate damage; no global grace/immunity");
                active.remove();active=null;source.setBlock(Blocks.air);exitFloor.setBlock(Blocks.air);obstacle.setBlock(Blocks.air);
            }
            return "PASS: "+assertions+" assertions; real small ground factory, medium reconstructor, T5 reconstructor and small/large air outputs in all four output directions, native costs, blocked full-body exit waits, passable conveyor output support, safe canonical XYZ at add, no random birth impact, movement away and normal damage";
        }finally{if(active!=null&&active.isAdded())active.remove();obstacle.setBlock(Blocks.air);exitFloor.setBlock(Blocks.air);source.setBlock(Blocks.air);Time.delta=delta;Time.time=time;Vars.state.rules.unitCap=cap;}
    }
    private void require(boolean value,String label){assertions++;if(!value)throw new AssertionError("Factory spawn: "+label);}
}
