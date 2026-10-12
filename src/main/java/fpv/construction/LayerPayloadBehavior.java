package fpv.construction;

import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.PayloadDeconstructor.PayloadDeconstructorBuild;
import mindustry.world.blocks.units.UnitBlock.UnitBuild;
import mindustry.world.blocks.units.UnitAssembler;
import mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild;
import mindustry.world.blocks.units.UnitAssemblerModule.UnitAssemblerModuleBuild;
import mindustry.ai.types.AssemblerAI;
import mindustry.gen.Unit;
import mindustry.gen.BuildingTetherc;
import mindustry.gen.Groups;
import arc.struct.IntSeq;
import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.world.blocks.units.UnitCargoLoader;
import mindustry.world.blocks.units.UnitCargoLoader.UnitTransportSourceBuild;
import mindustry.world.blocks.payloads.PayloadMassDriver.PayloadDriverBuild;
import mindustry.world.blocks.payloads.PayloadMassDriver.PayloadDriverState;
import arc.math.geom.Point2;

/** Layer-owned transport boundaries; payload building types keep their native recipes and inventories. */
final class LayerPayloadBehavior {
    private LayerPayloadBehavior(){}
    static void moveOut(LayerBuildingAdapter owner,PayloadBlockBuild<?> build){
        if(build.payload==null)return;build.updatePayload();
        PayloadBlock block=(PayloadBlock)build.block;float edge=block.size*Vars.tilesize/2f;
        float dx=Geometry.d4x(build.rotation)*edge,dy=Geometry.d4y(build.rotation)*edge;
        build.payRotation=Angles.moveToward(build.payRotation,build.rotdeg(),block.payloadRotateSpeed*build.delta());
        float distance=build.payVector.dst(dx,dy),movement=block.payloadSpeed*build.delta();
        if(distance<=movement)build.payVector.set(dx,dy);else build.payVector.add((dx-build.payVector.x)*movement/distance,(dy-build.payVector.y)*movement/distance);
        if(!build.payVector.within(dx,dy,.001f))return;
        Building front=build.front();
        if(front!=null&&(front.block.outputsPayload||front.block.acceptsPayload)){
            if(front.team==build.team&&front.acceptPayload(build,build.payload)){front.handlePayload(build,build.payload);build.payload=null;}
        }else if(front==null||!front.tile.solid())dump(owner,build);
        // Native pushOutput is deliberately omitted: it pushes every grounded Z0 unit underneath.
    }
    static void dump(LayerBuildingAdapter owner,PayloadBlockBuild<?> build){
        if(!(build.payload instanceof UnitPayload payload))return;
        if(owner.releaseUnit(build,payload.unit,payload.x(),payload.y())){
            build.payload=null;if(build instanceof UnitBuild unitBuild)unitBuild.progress=0;
        }
    }
    static void conveyor(LayerBuildingAdapter owner,PayloadConveyorBuild build){
        if(owner.restoringState())return;
        if(build.item!=null){build.item.update(null,build);if(build.item.isDead())build.item=null;}
        PayloadConveyor block=(PayloadConveyor)build.block;
        if(build.enabled){build.lastInterp=build.curInterp;build.curInterp=build.fract();if(build.lastInterp>build.curInterp)build.lastInterp=0;build.progress=build.time()%block.moveTime;}
        build.updatePayload();if(!build.enabled)return;
        int current=build.curStep();if(current<=build.step)return;boolean valid=build.step!=-1;build.step=current;boolean had=build.item!=null;
        if(valid&&build.stepAccepted!=current&&build.item!=null){
            build.onProximityUpdate();
            if(build.next!=null){
                build.next.updateTile();
                if(build.next.team==build.team&&build.next.acceptPayload(build,build.item)){build.next.handlePayload(build,build.item);build.item=null;build.moved();}
            }else if(!build.blocked&&build.item instanceof UnitPayload payload&&owner.releaseUnit(build,payload.unit,payload.x(),payload.y())){
                build.item=null;build.moved();
            }
        }
        if(had&&build.item!=null)build.moveFailed();
    }
    /** Native puddles are indexed only by XY; drain waste here instead of depositing on a different floor. */
    static void drainDeconstructionLiquid(PayloadDeconstructorBuild build){
        if(build.deconstructing instanceof BuildPayload payload&&build.progress==0&&build.edelta()>0&&payload.build.liquids!=null){
            for(var requirement:payload.requirements())if(build.items.get(requirement.item)>=build.block.itemCapacity)return;
            payload.build.liquids.clear();
        }
    }
    static void reconcileAssembler(UnitAssemblerBuild build,IntSeq pending){
        // Native saved/synced drone IDs remain useful; their packed-XY building references do not.
        for(int i=0;i<pending.size;i++){Unit unit=Groups.unit.getByID(pending.get(i));if(unit!=null){build.units.addUnique(unit);pending.removeIndex(i--);}}
        build.units.removeAll(unit->!unit.isAdded()||unit.dead||!(unit.controller() instanceof AssemblerAI));
        for(Unit unit:build.units)if(unit instanceof BuildingTetherc tether)tether.building(build);
    }
    static void assembler(LayerBuildingAdapter owner,UnitAssemblerBuild build){
        if(owner.restoringState())return;UnitAssembler block=(UnitAssembler)build.block;
        if(build.lastTier!=build.currentTier){if(build.lastTier>=0)build.progress=0;build.lastTier=build.lastTier==-2?-1:build.currentTier;}
        build.modules.removeAll(module->module.dead||module.link!=build||module.tile.build!=module);build.checkTier();
        if(!build.allowUpdate()){build.progress=0;for(Unit unit:build.units)unit.kill();build.units.clear();return;}
        float power=!build.enabled?0:build.power==null?1:build.power.status;
        build.powerWarmup=Mathf.lerpDelta(build.powerWarmup,power>.0001f?1:0,.1f);
        build.droneWarmup=Mathf.lerpDelta(build.droneWarmup,build.units.size<block.dronesCreated?power:0,.1f);
        build.totalDroneProgress+=build.droneWarmup*build.delta();
        if(build.units.size<block.dronesCreated&&build.enabled&&power>0){
            build.droneProgress+=build.delta()*Vars.state.rules.unitBuildSpeed(build.team)*power/block.droneConstructTime;
            if(build.droneProgress>=1){
                Unit drone=block.droneType.create(build.team);drone.rotation=90;
                if(drone.controller() instanceof AssemblerAI&&owner.releaseUnit(build,drone,build.x,build.y)){
                    if(drone instanceof BuildingTetherc tether)tether.building(build);build.units.add(drone);build.droneProgress=0;
                }
            }
        }else if(build.units.size>=block.dronesCreated)build.droneProgress=0;
        Vec2 target=build.getUnitSpawn();float sx=target.x,sy=target.y;
        if(build.moveInPayload()&&!build.wasOccupied){build.yeetPayload(build.payload);build.payload=null;}
        int ready=0;
        for(int i=0;i<build.units.size;i++){
            var ai=(AssemblerAI)build.units.get(i).controller();ai.targetPos.trns(i*90f+45,block.areaSize/2f*Mathf.sqrt2*Vars.tilesize).add(sx,sy);ai.targetAngle=i*90f+225;
            if(ai.inPosition())ready++;
        }
        build.wasOccupied=assemblyBlocked(owner,build,target);
        build.sameTypeWarmup=Mathf.lerpDelta(build.sameTypeWarmup,1,.1f);build.invalidWarmup=Mathf.lerpDelta(build.invalidWarmup,build.wasOccupied?1:0,.1f);
        var plan=build.plan();
        if(!build.wasOccupied&&build.efficiency>0&&mindustry.entities.Units.canCreate(build.team,plan.unit)){
            build.warmup=Mathf.lerpDelta(build.warmup,build.efficiency,.1f);
            build.progress+=build.edelta()*Vars.state.rules.unitBuildSpeed(build.team)*(ready/(float)Math.max(1,block.dronesCreated))/plan.time;
            if(build.progress>=1)spawnAssembler(owner,build);
        }else build.warmup=Mathf.lerpDelta(build.warmup,0,.1f);
    }
    static boolean assemblyBlocked(LayerBuildingAdapter owner,UnitAssemblerBuild build,Vec2 target){
        double radius=build.plan().unit.hitSize*.7/Vars.tilesize,z=owner.buildingAltitude(build);
        for(var entry:owner.entries()){
            Building other=entry.building();if(other==build||entry.layer()!=((LayerTile)build.tile).layer)continue;
            if(Math.abs(other.x-target.x)/Vars.tilesize<other.block.size*.5+radius&&Math.abs(other.y-target.y)/Vars.tilesize<other.block.size*.5+radius&&!other.block.acceptsPayload&&!other.block.outputsPayload)return true;
        }
        for(Unit unit:Groups.unit){
            if(unit.dead||!unit.isAdded()||build.units.contains(unit))continue;
            if(Math.abs(owner.unitAltitude(unit)-z)<Math.max(1,unit.hitSize*.5/Vars.tilesize)&&unit.within(target,(float)((radius+unit.hitSize*.5/Vars.tilesize)*Vars.tilesize)))return true;
        }
        return false;
    }
    static void spawnAssembler(LayerBuildingAdapter owner,UnitAssemblerBuild build){
        var plan=build.plan();Vec2 target=build.getUnitSpawn();float x=target.x,y=target.y;
        Unit unit=plan.unit.create(build.team);unit.set(x,y);unit.rotation=build.rotdeg();
        if(unit.isCommandable()&&build.commandPos!=null)unit.command().commandPosition(build.commandPos);
        var payload=new UnitPayload(unit);var layer=(LayerTile)build.tile;
        var cell=owner.tile(mindustry.core.World.toTile(x),mindustry.core.World.toTile(y),layer.layer);Building receiver=cell==null?null:cell.build;
        boolean released=receiver!=null&&receiver.team==build.team&&receiver.acceptPayload(receiver,payload);
        if(released)receiver.handlePayload(receiver,payload);else released=owner.releaseUnit(build,unit,x,y);
        if(!released){build.wasOccupied=true;build.progress=Math.min(build.progress,1);return;}
        build.consume();build.progress=0;build.blocks.clear();
        arc.Events.fire(new mindustry.game.EventType.UnitCreateEvent(unit,build));
    }
    static void findAssembler(LayerBuildingAdapter owner,UnitAssemblerModuleBuild module){
        UnitAssemblerBuild found=null;
        for(var entry:owner.entries())if(entry.layer()==((LayerTile)module.tile).layer&&entry.building() instanceof UnitAssemblerBuild candidate&&candidate.team==module.team&&candidate.moduleFits(module.block,module.x,module.y,module.rotation)){found=candidate;break;}
        if(module.link!=found){if(module.link!=null)module.link.removeModule(module);module.link=found;if(found!=null)found.updateModules(module);}
    }
    static void module(LayerBuildingAdapter owner,UnitAssemblerModuleBuild module){
        // World.tileChanges does not describe upper layers. Recheck periodically and on broken links.
        int stamp=(int)(Time.time/30);if(module.lastChange!=stamp||module.link==null||module.link.dead){module.lastChange=stamp;findAssembler(owner,module);}
        if(module.moveInPayload()&&module.link!=null&&!module.link.wasOccupied&&module.link.moduleFits(module.block,module.x,module.y,module.rotation)&&module.link.acceptPayload(module,module.payload)&&module.efficiency>0){module.link.yeetPayload(module.payload);module.payload=null;}
    }
    static PayloadDriverBuild driverLink(LayerBuildingAdapter owner,PayloadDriverBuild source){
        if(source.link==-1)return null;var tile=owner.tile(Point2.x(source.link),Point2.y(source.link),((LayerTile)source.tile).layer);
        if(tile!=null&&tile.build instanceof PayloadDriverBuild other&&other!=source&&other.team==source.team&&other.block==source.block&&source.within(other,((PayloadMassDriver)source.block).range))return other;return null;
    }
    static void massDriver(LayerBuildingAdapter owner,PayloadDriverBuild build){
        if(owner.restoringState())return;PayloadMassDriver block=(PayloadMassDriver)build.block;var target=driverLink(owner,build);
        if(build.payload!=null){build.payload.update(null,build);if(build.payload.isDead())build.payload=null;}
        if(!build.charging)build.charge=Math.max(0,build.charge-Time.delta*10);build.charging=false;
        build.targetSize=build.payload==null?block.grabWidth*2:build.payload.size();build.curSize=Mathf.lerpDelta(build.curSize,build.targetSize,.05f);
        build.effectDelayTimer-=Time.delta;build.reloadCounter=Math.max(0,build.reloadCounter-build.edelta()/block.reload);
        while(!build.waitingShooters.isEmpty()){
            var shooter=build.waitingShooters.first();
            if(shooter instanceof PayloadDriverBuild from&&!from.dead&&from.tile.build==from&&from.efficiency>0&&driverLink(owner,from)==build)break;
            build.waitingShooters.removeFirst();
        }
        if(build.state==PayloadDriverState.idle){if(!build.waitingShooters.isEmpty()&&build.payload==null)build.state=PayloadDriverState.accepting;else if(target!=null)build.state=PayloadDriverState.shooting;}
        if((build.state==PayloadDriverState.idle||build.state==PayloadDriverState.accepting)&&build.payload!=null){
            if(build.loaded){build.payLength-=block.payloadSpeed*build.delta();if(build.payLength<=0){build.loaded=false;build.payVector.setZero();}}
            else if(build.effectDelayTimer<=0)moveOut(owner,build);
        }
        if(build.efficiency<=0)return;
        if(build.state==PayloadDriverState.accepting){
            Building shooter=build.currentShooter();if(shooter==null||build.payload!=null){build.state=PayloadDriverState.idle;return;}
            build.turretRotation=Angles.moveToward(build.turretRotation,build.angleTo(shooter),block.rotateSpeed*build.efficiency);return;
        }
        if(build.state!=PayloadDriverState.shooting)return;
        if(target==null||!build.waitingShooters.isEmpty()&&build.payload==null){build.state=PayloadDriverState.idle;return;}
        build.payRotation=Angles.moveToward(build.payRotation,build.turretRotation,block.payloadRotateSpeed*build.delta());
        boolean loaded=false;if(build.loaded){float length=block.length-build.reloadCounter*block.knockback;build.payLength=Math.min(length,build.payLength+block.payloadSpeed*build.delta());loaded=build.payLength>=length;}
        else if(build.moveInPayload()){build.payLength=0;build.loaded=true;}
        if(!loaded||build.payload==null||target.payload!=null)return;
        if(!target.waitingShooters.contains(build))target.waitingShooters.addLast(build);
        if(build.reloadCounter>0)return;float angle=build.angleTo(target);
        build.turretRotation=Angles.moveToward(build.turretRotation,angle,block.rotateSpeed*build.efficiency);
        if(target.currentShooter()!=build||target.state!=PayloadDriverState.accepting||target.reloadCounter>0||!Angles.within(build.turretRotation,angle,1)||!Angles.within(target.turretRotation,angle+180,1))return;
        build.charge+=build.edelta();build.charging=true;if(build.charge<block.chargeTime)return;
        Payload cargo=build.payload;target.handlePayload(build,cargo);target.lastOther=build;target.payVector.trns(build.turretRotation+180,block.length);target.payRotation=build.turretRotation;
        target.payLength=block.length;target.loaded=true;target.updatePayload();target.effectDelayTimer=block.transferEffect.lifetime;
        target.waitingShooters.removeFirst();target.state=PayloadDriverState.idle;target.reloadCounter=1;
        build.payload=null;build.payLength=0;build.loaded=false;build.state=PayloadDriverState.idle;build.reloadCounter=1;build.charge=0;
    }
    static void reconcileCargo(LayerBuildingAdapter owner,UnitTransportSourceBuild build){
        if(build.unit!=null&&(build.unit.dead||!build.unit.isAdded()))build.unit=null;
        if(build.readUnitId!=-1){Unit found=Groups.unit.getByID(build.readUnitId);if(found!=null){build.unit=found;build.readUnitId=-1;}}
        if(build.unit!=null){
            if(build.unit instanceof BuildingTetherc tether)tether.building(build);
            var controller=build.unit.controller();
            if(!build.unit.isPlayer()&&controller instanceof mindustry.ai.types.CargoAI&&(!(controller instanceof LayerCargoAI cargo)||!cargo.belongsTo(owner,build)))
                build.unit.controller(new LayerCargoAI(owner,build));
        }
    }
    static void cargoLoader(LayerBuildingAdapter owner,UnitTransportSourceBuild build){
        reconcileCargo(owner,build);if(owner.restoringState())return;UnitCargoLoader block=(UnitCargoLoader)build.block;
        build.warmup=Mathf.approachDelta(build.warmup,build.efficiency,1f/60);build.readyness=Mathf.approachDelta(build.readyness,build.unit!=null?1:0,1f/60);
        if(build.unit==null&&mindustry.entities.Units.canCreate(build.team,block.unitType)){
            build.buildProgress+=build.edelta()/block.unitBuildTime;build.totalProgress+=build.edelta();
            if(build.buildProgress>=1){Unit drone=block.unitType.create(build.team);drone.rotation=90;
                if(owner.releaseUnit(build,drone,build.x,build.y)){build.unit=drone;build.buildProgress=0;reconcileCargo(owner,build);}}
        }
    }
}
