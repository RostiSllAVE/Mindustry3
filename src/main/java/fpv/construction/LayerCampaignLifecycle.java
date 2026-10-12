package fpv.construction;

import java.util.*;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.campaign.LaunchPad;
import mindustry.world.meta.BlockFlag;

/** SectorInfo.prepare requires active pads in the native launch flag; never inserts upper tiles/entities. */
final class LayerCampaignLifecycle {
    private LayerBuildingAdapter owner;
    private final IdentityHashMap<Building,Seq<Building>> registered=new IdentityHashMap<>();
    void activate(LayerBuildingAdapter value){deactivate();owner=value;reconcile();}
    void reconcile(){
        if(owner==null||Vars.indexer==null)return;
        var iterator=registered.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();Building building=entry.getKey();if(building.dead||building.tile.build!=building){entry.getValue().remove(building);iterator.remove();}}
        for(var entry:owner.entries())if(entry.building() instanceof LaunchPad.LaunchPadBuild building&&!building.dead){
            Seq<Building> current;
            try{current=Vars.indexer.getFlagged(building.team,BlockFlag.launchPad);}catch(NullPointerException notReady){continue;}
            if(current==null)continue;Seq<Building> previous=registered.put(building,current);if(previous!=null&&previous!=current)previous.remove(building);
            if(!current.contains(building))current.add(building);
        }
    }
    void deactivate(){for(var entry:registered.entrySet())entry.getValue().remove(entry.getKey());registered.clear();owner=null;}
}
