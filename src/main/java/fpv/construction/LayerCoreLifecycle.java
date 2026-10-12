package fpv.construction;

import java.util.*;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.storage.StorageBlock.StorageBuild;
import mindustry.world.modules.ItemModule;

/** Explicit live-world activation. Constructing/restoring a candidate adapter never reaches this service. */
public final class LayerCoreLifecycle {
    private LayerBuildingAdapter owner;
    private final LayerCampaignLifecycle campaign=new LayerCampaignLifecycle();
    private final Set<CoreBuild> registered=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CoreBuild> wanted=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Team> teams=new HashSet<>();
    private final IdentityHashMap<StorageBuild,CoreBuild> storageOwners=new IdentityHashMap<>();
    private final IdentityHashMap<Team,ItemModule> modules=new IdentityHashMap<>();
    private final IdentityHashMap<mindustry.game.Teams.TeamData,Integer> capContributions=new IdentityHashMap<>();
    public void activate(LayerBuildingAdapter registry){
        deactivate();owner=registry;campaign.activate(registry);reconcile();
    }
    /** Safe on every topology refresh; shared modules are chosen once and never summed on snapshot restore. */
    public void reconcile(){
        campaign.reconcile();
        if(owner==null||Vars.state==null||Vars.state.teams==null)return;
        wanted.clear();teams.clear();
        for(var entry:owner.entries())if(entry.building() instanceof CoreBuild core&&!core.dead&&core.health>0){wanted.add(core);teams.add(core.team);}
        for(CoreBuild core:registered)teams.add(core.team);
        for(CoreBuild core:registered)if(!wanted.contains(core))Vars.state.teams.unregisterCore(core);
        registered.retainAll(wanted);
        for(var entry:owner.entries())if(entry.building() instanceof CoreBuild core&&wanted.contains(core)){Vars.state.teams.registerCore(core);registered.add(core);}
        // Removed/changed storage must stop pointing to a detached core. Do not copy a shared team's inventory.
        var storageIterator=storageOwners.entrySet().iterator();
        while(storageIterator.hasNext()){
            var tracked=storageIterator.next();StorageBuild storage=tracked.getKey();CoreBuild core=tracked.getValue();
            if(!wanted.contains(core)||storage.dead||storage.tile.build!=storage||!core.proximity.contains(storage)||storage.team!=core.team){
                if(storage.linkedCore==core){storage.linkedCore=null;storage.items=new ItemModule();}
                storageIterator.remove();
            }
        }
        for(Team team:teams)reconcileTeam(team);
        for(Team team:teams){
            var data=team.data();int next=0;for(CoreBuild core:wanted)if(core.team==team)next+=core.block.unitCapModifier;
            data.unitCap=Math.max(0,data.unitCap-capContributions.getOrDefault(data,0)+next);capContributions.put(data,next);
        }
    }
    private void reconcileTeam(Team team){
        var cores=Vars.state.teams.cores(team);ItemModule shared=null;
        // Z0 holds the authoritative native save pool; upper snapshots contain a duplicate of that pool.
        for(CoreBuild core:cores)if(!(core.tile instanceof LayerTile)&&!core.dead){shared=core.items;break;}
        if(shared==null)shared=modules.get(team);
        if(shared==null)for(CoreBuild core:cores)if(!core.dead){shared=core.items;break;}
        if(shared==null){modules.remove(team);return;}modules.put(team,shared);
        long capacity=0;
        Set<StorageBuild> counted=Collections.newSetFromMap(new IdentityHashMap<>());
        for(CoreBuild core:cores)if(!core.dead){
            core.items=shared;capacity+=core.block.itemCapacity;
            for(Building building:core.proximity)if(building instanceof StorageBuild storage&&storage.team==team&&
                ((StorageBlock)storage.block).coreMerge&&(storage.linkedCore==null||storage.linkedCore==core)&&counted.add(storage)){
                storage.items=shared;storage.linkedCore=core;capacity+=storage.block.itemCapacity;
                if(core.tile instanceof LayerTile)storageOwners.put(storage,core);
            }
        }
        int limit=(int)Math.min(Integer.MAX_VALUE,capacity);
        for(CoreBuild core:cores)core.storageCapacity=limit;
        // Match native loss above the remaining capacity, after the entire replacement registry is installed.
        if(!Vars.world.isGenerating())for(Item item:Vars.content.items())if(shared.get(item)>limit)shared.set(item,limit);
    }
    /** Unregister only this service's upper cores. Replacement activation must follow before simulation resumes. */
    public void deactivate(){
        campaign.deactivate();
        if(Vars.state!=null&&Vars.state.teams!=null)for(CoreBuild core:registered)Vars.state.teams.unregisterCore(core);
        // Native world reload may already have reset a TeamData counter; rebuild its ground baseline.
        for(var data:capContributions.keySet()){
            int nativeCap=0;if(mindustry.gen.Groups.build!=null)for(Building building:mindustry.gen.Groups.build)if(building.team==data.team&&!(building.tile instanceof LayerTile)&&!building.dead)nativeCap+=building.block.unitCapModifier;
            data.unitCap=nativeCap;
        }
        for(var entry:storageOwners.entrySet())if(entry.getKey().linkedCore==entry.getValue())entry.getKey().linkedCore=null;
        registered.clear();wanted.clear();teams.clear();storageOwners.clear();modules.clear();capContributions.clear();owner=null;
    }
    public int registeredCount(){return registered.size();}
}
