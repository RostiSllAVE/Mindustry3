package fpv.construction;

import arc.util.Time;
import java.util.Map;
import java.util.WeakHashMap;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.entities.EntityGroup;
import mindustry.gen.*;
import mindustry.logic.LUnitControl;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.payloads.*;

/** Authoritative payload transfers; no upper building identity is ever sent through a native XY RPC. */
final class LayerLogicPayloadBehavior {
    private static final Map<Unit,Float> lastTransfer=new WeakHashMap<>();
    static boolean run(LayerBuildingAdapter owner,LogicBuild processor,Unit unit,LUnitControl command,boolean takeUnit){
        if(Vars.net.client()||!Vars.state.rules.logicUnitControl||unit.dead||!unit.isAdded()||unit.team!=processor.team)return false;
        if(Time.time-lastTransfer.getOrDefault(unit,-1e9f)<90)return false;
        if(command==LUnitControl.payTake&&takeUnit&&unit instanceof Payloadc carrier){
            Unit found=null;double nearest=Double.POSITIVE_INFINITY;
            for(Unit candidate:Groups.unit)if(candidate!=unit&&candidate.team==unit.team&&!candidate.dead&&candidate.isAdded()&&!candidate.isFlying()&&carrier.canPickup(candidate)){
                double dz=(owner.unitAltitude(unit)-owner.unitAltitude(candidate))*Vars.tilesize,distance=unit.dst2(candidate)+dz*dz;
                if(distance<=unit.hitSize*unit.hitSize*4&&distance<nearest){found=candidate;nearest=distance;}
            }
            if(found==null)return false;
            // This packet carries only unit IDs; its authority checks are now three-dimensional.
            Call.pickedUnitPayload(unit,found);lastTransfer.put(unit,Time.time);return true;
        }
        int layer=((LayerTile)processor.tile).layer;
        if(Math.abs(owner.unitAltitude(unit)-owner.buildingAltitude(processor))>Math.max(1.5,unit.hitSize*.5/Vars.tilesize))return false;
        var tile=owner.tile(World.toTile(unit.x),World.toTile(unit.y),layer);Building target=tile==null?null:tile.build;
        if(target!=null&&(target.team!=unit.team||target.dead))return false;
        if(command==LUnitControl.payTake&&unit instanceof Payloadc carrier){
            if(target==null)return false;Payload payload=target.getPayload();
            if(payload==null||!carrier.canPickupPayload(payload))return false;
            Payload removed=target.takePayload();if(removed==null)return false;
            carrier.addPayload(removed);lastTransfer.put(unit,Time.time);return true;
        }
        if(command==LUnitControl.payDrop&&unit instanceof Payloadc carrier&&carrier.hasPayload()){
            Payload payload=carrier.payloads().peek();
            if(target!=null){
                if(!target.acceptPayload(target,payload))return false;
                target.handlePayload(target,payload);carrier.payloads().pop();
            }else if(payload instanceof UnitPayload output){
                if(!owner.releaseUnit(processor,output.unit,unit.x,unit.y))return false;carrier.payloads().pop();
            }else return false; // A built block needs a structural/receipt transaction, never BuildPayload.place on Z0.
            lastTransfer.put(unit,Time.time);return true;
        }
        if(command==LUnitControl.payEnter&&target!=null&&!unit.spawnedByCore&&unit.type.allowedInPayloads){
            UnitPayload payload=new UnitPayload(unit);if(!target.acceptPayload(target,payload))return false;
            if(unit.isPlayer())unit.getPlayer().clearUnit();
            // Native unit-ID despawn is safe here; the destination travels in the mod's layer state.
            Call.unitDespawn(unit);unit.id=EntityGroup.nextId();target.handlePayload(target,payload);
            lastTransfer.put(unit,Time.time);return true;
        }
        return false;
    }
}
