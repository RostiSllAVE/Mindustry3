package fpv.construction;

import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.type.Item;
import mindustry.world.blocks.campaign.LandingPad;
import mindustry.world.blocks.campaign.LandingPad.LandingPadBuild;

/** Campaign import accounting stays native; only upper landing dispatch avoids a packed-XY RPC. */
final class LayerCampaignBehavior {
    private static final java.lang.reflect.Field waiting=waitingField();
    private static java.lang.reflect.Field waitingField(){try{var field=LandingPad.class.getDeclaredField("waiting");field.setAccessible(true);return field;}catch(ReflectiveOperationException failure){return null;}}
    static boolean landingSupported(){return waiting!=null;}
    @SuppressWarnings("unchecked") private static Seq<LandingPadBuild> pending(Item item){
        try{return ((ObjectMap<Item,Seq<LandingPadBuild>>)waiting.get(null)).get(item);}catch(ReflectiveOperationException failure){throw new IllegalStateException("Native landing queue changed",failure);}
    }
    static void update(LayerBuildingAdapter owner,LandingPadBuild build){
        if(owner.restoringState())return;LandingPad type=(LandingPad)build.block;
        build.updateTimers(); // Advances native shared imports once per state.updateId; queue contains only native Z0 pads.
        if(build.arriving!=null){
            build.arrivingTimer+=Time.delta/type.arrivalDuration;
            float use=Math.min(type.consumeLiquidAmount/type.arrivalDuration*Time.delta,type.consumeLiquidAmount-build.liquidRemoved);
            build.liquidRemoved+=use;build.liquids.remove(type.consumeLiquid,use);
            if(build.arrivingTimer>=1){
                build.liquids.remove(type.consumeLiquid,type.consumeLiquidAmount-build.liquidRemoved);
                build.items.set(build.arriving,type.itemCapacity);
                if(!build.isFake()){build.produced(build.arriving,type.itemCapacity);Vars.state.getSector().info.handleItemImport(build.arriving,type.itemCapacity);}
                build.arriving=null;build.arrivingTimer=0;
            }
        }
        if(build.items.total()>0)build.dumpAccumulate(build.config==null||build.items.get(build.config)!=build.items.total()?null:build.config);
        if(build.arriving==null)build.cooldown=Mathf.clamp(build.cooldown-build.delta()/type.cooldownTime);
        if(!ready(build))return;
        if(build.isFake()){build.handleLanding();return;}
        if(!Vars.state.isCampaign()||Vars.state.getPlanet().campaignRules.legacyLaunchPads)return;
        var info=Vars.state.getSector().info;
        if(info.getImportRate(Vars.state.getPlanet(),build.config)<=0||info.importCooldownTimers.get(build.config,0f)<1)return;
        Seq<LandingPadBuild> nativeQueue=pending(build.config);int last=build.priority;
        if(nativeQueue!=null)for(var pad:nativeQueue)if(pad.config==build.config){last=Math.max(last,pad.priority);if(pad.priority<build.priority)return;}
        for(var entry:owner.entries())if(entry.building() instanceof LandingPadBuild other&&other!=build&&other.config==build.config&&!other.isFake()&&ready(other)){
            last=Math.max(last,other.priority);if(other.priority<build.priority||(other.priority==build.priority&&other.id<build.id))return;
        }
        // Claim one native import token and invalidate pending Z0 reservations for the same shipment.
        if(nativeQueue!=null)nativeQueue.clear();build.handleLanding();build.priority=last==Integer.MAX_VALUE?Integer.MAX_VALUE:last+1;
    }
    private static boolean ready(LandingPadBuild build){return !build.dead&&build.config!=null&&build.arriving==null&&build.cooldown<=0&&build.efficiency>0&&build.items.empty();}
}
