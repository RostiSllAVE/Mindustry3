package fpv.construction;

import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.types.CargoAI;
import mindustry.gen.Building;
import mindustry.world.blocks.units.UnitCargoLoader.UnitTransportSourceBuild;
import mindustry.world.blocks.units.UnitCargoUnloadPoint.UnitCargoUnloadPointBuild;

/** Native cargo flight/controller with layer-local target discovery and authoritative inventory moves. */
final class LayerCargoAI extends CargoAI {
    private final LayerBuildingAdapter owner;
    private final UnitTransportSourceBuild source;
    private float pickupClock,dropClock;
    LayerCargoAI(LayerBuildingAdapter owner,UnitTransportSourceBuild source){this.owner=owner;this.source=source;}
    boolean belongsTo(LayerBuildingAdapter owner,UnitTransportSourceBuild source){return this.owner==owner&&this.source==source;}
    @Override public void updateMovement(){
        if(Vars.net.client()||source.dead||source.tile.build!=source||unit==null)return;
        pickupClock+=Time.delta;dropClock+=Time.delta;
        if(unloadTarget!=null&&(unloadTarget.dead||unloadTarget.tile.build!=unloadTarget||unloadTarget.item!=itemTarget||unloadTarget.team!=unit.team))unloadTarget=null;
        if(!unit.hasItem()){
            moveTo(source,moveRange,moveSmoothing);
            if(source.items.any()&&docked(source)&&pickupClock>=30){
                pickupClock=0;findAnyTarget(source);
                if(unloadTarget!=null){int amount=Math.min(unit.maxAccepted(itemTarget),source.items.get(itemTarget));
                    if(amount>0){source.items.remove(itemTarget,amount);unit.addItem(itemTarget,amount);}}
            }
        }else{
            if(unloadTarget==null)findDropTarget(unit.item(),targetIndex,null);
            if(unloadTarget==null){moveTo(source,moveRange,moveSmoothing);return;}
            moveTo(unloadTarget,moveRange,moveSmoothing);
            if(docked(unloadTarget)&&dropClock>=dropSpacing){dropClock=0;int amount=unloadTarget.acceptStack(unit.item(),unit.stack.amount,unit);
                if(amount>0){unloadTarget.handleStack(unit.item(),amount,unit);unit.stack.amount-=amount;}
                if(!unit.hasItem()||(noDestTimer+=dropSpacing)>=emptyWaitTime){targetIndex=findDropTarget(unit.item(),targetIndex,unloadTarget);noDestTimer=0;}
            }
        }
    }
    private boolean docked(Building building){
        // Transfers occur at the same selected construction floor and nearby XY. The small vertical
        // docking corridor includes the aircraft's automatic roof clearance, never another storey.
        double height=owner.unitAltitude(unit)-owner.buildingAltitude(building);
        var profile=fpv.units.UnitAltitudeSystem.profile();double size=unit.hitSize/Vars.tilesize;
        double cruise=size<=profile.smallMaxSize()?profile.smallCruiseMax():size<=profile.mediumMaxSize()?profile.mediumCruiseMax():profile.largeCruiseMax();
        double dockingTop=fpv.net.HeadlessGeometry.heightFor(building.block)+cruise+profile.minClearance()+Math.max(profile.minBodyHalfDepth(),size*profile.bodyHalfDepthPerSize())+.5;
        return unit.within(building,transferRange)&&height>=-.25&&height<=dockingTop;
    }
    @Override public void findAnyTarget(Building building){
        unloadTarget=null;itemTarget=null;int maximum=-1;
        for(var entry:owner.entries())if(candidate(entry)&&entry.building() instanceof UnitCargoUnloadPointBuild target&&target.item!=null){
            int available=building.items.get(target.item);if(available>maximum&&available>0&&(!target.stale||unloadTarget==null)){maximum=available;unloadTarget=target;itemTarget=target.item;}
        }
    }
    @Override public int findDropTarget(mindustry.type.Item item,int offset,UnitCargoUnloadPointBuild ignore){
        unloadTarget=null;itemTarget=item;int index=0,chosen=0;
        for(var entry:owner.entries())if(candidate(entry)&&entry.building() instanceof UnitCargoUnloadPointBuild target&&target.item==item&&target!=ignore){
            if(unloadTarget==null||unloadTarget.stale&&!target.stale){unloadTarget=target;chosen=index;}index++;
        }
        if(unloadTarget==null&&ignore!=null&&!ignore.dead&&ignore.item==item)unloadTarget=ignore;
        return chosen;
    }
    private boolean candidate(LayerBuildingAdapter.Entry entry){return entry.layer()==((LayerTile)source.tile).layer&&entry.building().team==unit.team&&!entry.building().dead;}
}
