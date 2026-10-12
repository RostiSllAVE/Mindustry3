package fpv.construction;

import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.power.PowerNode;

/** Mouse gestures resolve to existing native configure or existing layer LINK requests only. */
public final class MouseConnection {
    private MouseConnection(){}
    public record Plan(boolean valid,String reason,LayerActions.Action action,Building ground,Object configuration){
        static Plan no(String reason){return new Plan(false,reason,null,null,null);}
    }
    public static boolean ordinarySource(Building b){return b!=null&&(LayerVerticalTransfers.isBridge(b)||b.block instanceof MassDriver||b.block instanceof PowerNode);}
    public static Plan preview(LayerRuntime runtime,BuildingMousePicker.Hit source,BuildingMousePicker.Hit target,LayerVerticalTransfers.Kind riser){
        if(!BuildingMousePicker.present(runtime,source)||!BuildingMousePicker.present(runtime,target)||source.building()==null||target.building()==null)return Plan.no("Point at a live building");
        Building a=source.building(),b=target.building();
        if(source.id()==target.id())return Plan.no("Choose another building");
        if(Vars.player==null||a.team!=Vars.player.team()||b.team!=Vars.player.team())return Plan.no("Both buildings must belong to your team");
        // A single physical target/pair drives preview, server validation and disconnection.
        if(a.block instanceof PowerNode&&(source.layer()>0||target.layer()>0)){
            boolean linked=runtime.nativeBuildings().linkedPower(source.id(),target.id());
            var action=LayerActions.Action.link(source.id(),target.id(),LayerVerticalTransfers.Kind.POWER,linked);
            var result=runtime.preview(Vars.player,action);
            return new Plan(result.accepted(),result.accepted()?(linked?"Click to disconnect power link":"Click to connect power link"):result.reason(),action,null,null);
        }
        if(source.layer()!=target.layer()){
            if(a.block instanceof MassDriver)return Plan.no("Mass Drivers currently link only on the same floor");
            var kind=riser;
            if(LayerVerticalTransfers.isBridge(a))kind=a.block.hasLiquids?LayerVerticalTransfers.Kind.BRIDGE_LIQUIDS:LayerVerticalTransfers.Kind.BRIDGE_ITEMS;
            else if(a.block instanceof PowerNode)kind=LayerVerticalTransfers.Kind.POWER;
            if(kind==null)return Plan.no("Shift-click a source to start an aligned vertical connector");
            var action=LayerActions.Action.link(source.id(),target.id(),kind,false);
            var result=runtime.preview(Vars.player,action);
            return new Plan(result.accepted(),result.accepted()?"Click to connect":result.reason(),action,null,null);
        }
        if(riser!=null)return Plan.no("Vertical connectors need different floors and aligned buildings");
        if(a.block instanceof MassDriver driver){
            if(a.block!=b.block||!a.within(b,driver.range))return Plan.no("Choose the same Mass Driver within its native range");
        }else if(a.block instanceof ItemBridge bridge){
            if(!(b instanceof ItemBridge.ItemBridgeBuild)||!bridge.linkValid(a.tile,b.tile,false))return Plan.no("Choose a compatible bridge aligned within its native range");
        }else if(a.block instanceof PowerNode node){
            if(!node.linkValid(a,b)||PowerNode.insulated(a,b)||a.power.links.size>=node.maxNodes&&!a.power.links.contains(b.pos()))return Plan.no("Power range, insulation or connection limit prevents this link");
        }else return Plan.no("This type has no manual same-floor connection");
        if(source.layer()==0)return new Plan(true,"Click to configure native connection",null,a,b.pos());
        var action=LayerActions.Action.configure(source.id(),b.pos());var result=runtime.preview(Vars.player,action);
        return new Plan(result.accepted(),result.accepted()?"Click to connect":result.reason(),action,null,null);
    }
    public static LayerActions.Result submit(LayerRuntime runtime,BuildingMousePicker.Hit source,BuildingMousePicker.Hit target,LayerVerticalTransfers.Kind riser){
        var plan=preview(runtime,source,target,riser);
        if(!plan.valid())return LayerActions.Result.rejected(plan.reason());
        if(plan.action()!=null)return runtime.submit(plan.action());
        // Ordinary Z0 configuration stays on Mindustry's existing server RPC and action filter.
        plan.ground().configure(plan.configuration());
        return new LayerActions.Result(!Vars.net.client(),Vars.net.client(),Vars.net.client()?"Waiting for native server":"Configured",0);
    }
}
