package fpv.construction;

import arc.math.geom.Point2;
import java.util.*;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;

/** Explicit directed vertical resource links. Construction/network owner validates cost and player permissions. */
public final class LayerVerticalTransfers {
    @FunctionalInterface public interface ShaftClearance {boolean clear(long from,long to);}
    public enum Kind { ITEMS, LIQUIDS, POWER, BRIDGE_ITEMS, BRIDGE_LIQUIDS;
        public boolean bridge(){return this==BRIDGE_ITEMS||this==BRIDGE_LIQUIDS;}
        public boolean items(){return this==ITEMS||this==BRIDGE_ITEMS;}
    }
    public record Link(long from,long to,Kind kind) {
        public Link {if(kind==Kind.POWER&&from>to){long swap=from;from=to;to=swap;}}
    }
    private record Endpoint(Building building,int layer) {}
    private static final int MAX_LINKS=8192,MAX_ITEM_MOVES_PER_UPDATE=64;
    private static final class Progress {
        final Endpoint source,target;double credit;int cursor;
        Progress(Endpoint source,Endpoint target){this.source=source;this.target=target;}
    }
    private final LayerBuildingAdapter registry;
    private final LinkedHashMap<Link,Progress> links=new LinkedHashMap<>();
    private final double itemsPerSecond,liquidPerSecond;
    private int maxLayerGap=16;
    private double layerSpacing=LayerCoordinates.DEFAULT.spacing();
    public void setLayerSpacing(double value){if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("Layer spacing");layerSpacing=value;registry.setPowerLayerSpacing(value);}
    private ShaftClearance clearance=(from,to)->true;

    public LayerVerticalTransfers(LayerBuildingAdapter registry){this(registry,12,60);}
    public LayerVerticalTransfers(LayerBuildingAdapter registry,double itemsPerSecond,double liquidPerSecond){
        if(registry==null||!Double.isFinite(itemsPerSecond)||!Double.isFinite(liquidPerSecond)||itemsPerSecond<=0||liquidPerSecond<=0)throw new IllegalArgumentException("Registry and finite positive throughput required");
        this.registry=registry;this.itemsPerSecond=itemsPerSecond;this.liquidPerSecond=liquidPerSecond;
        registry.setMaxVerticalLayerGap(maxLayerGap);
        registry.setExternalBridge(this::externalSource);
    }
    public void setMaxLayerGap(int layers){if(layers<1||layers>64)throw new IllegalArgumentException("Vertical layer gap must be 1–64");maxLayerGap=layers;registry.setMaxVerticalLayerGap(layers);}
    public int maxLayerGap(){return maxLayerGap;}
    public void setShaftClearance(ShaftClearance value){clearance=value==null?(from,to)->true:value;}
    /** Negative endpoint IDs are explicit vanilla Z0 anchor tiles; positive IDs are upper components. */
    public static long groundId(int x,int y){
        if(x<0||y<0||x>32767||y>32767)throw new IllegalArgumentException("Native tile coordinates required");
        return -(Integer.toUnsignedLong(Point2.pack(x,y))+1L);
    }
    public Collection<Link> links(){return Collections.unmodifiableCollection(links.keySet());}
    public boolean canLink(long from,long to,Kind kind){
        if(kind==null||from==to)return false;
        Link link=new Link(from,to,kind);
        if(links.containsKey(link))return true;
        Progress progress=new Progress(endpoint(link.from),endpoint(link.to));
        if(links.size()>=MAX_LINKS||!valid(link,progress))return false;
        // One physical output per resource category, rather than multiplying throughput by duplicate endpoints.
        for(Link other:links.keySet())if(kind!=Kind.POWER&&other.from==from&&(other.kind==kind||other.kind.items()==kind.items()&&other.kind!=Kind.POWER&&kind!=Kind.POWER))return false;
        return true;
    }
    public boolean configureLink(long from,long to,Kind kind){
        if(!canLink(from,to,kind))return false;
        Link link=new Link(from,to,kind);if(links.containsKey(link))return true;
        if(kind==Kind.POWER&&!registry.connectVerticalPower(link.from,link.to))return false;
        links.put(link,new Progress(endpoint(link.from),endpoint(link.to)));return true;
    }
    public void removeLink(long from,long to,Kind kind){
        Link link=new Link(from,to,kind);
        if(links.remove(link)!=null&&kind==Kind.POWER)registry.disconnectPower(from,to);
    }
    public void clear(){for(Link link:links.keySet())if(link.kind==Kind.POWER)registry.disconnectPower(link.from,link.to);links.clear();}
    public boolean externalSource(Building building){for(var pair:links.entrySet())if(pair.getKey().kind.bridge()&&pair.getValue().source.building==building)return true;return false;}
    public static boolean isBridge(Building b){return b!=null&&(b.block instanceof mindustry.world.blocks.distribution.ItemBridge||b.block instanceof mindustry.world.blocks.distribution.DirectionBridge);}
    private static double bridgeRange(Building b){return b.block instanceof mindustry.world.blocks.distribution.ItemBridge bridge?bridge.range:b.block instanceof mindustry.world.blocks.distribution.DirectionBridge bridge?bridge.range:0;}
    public void prunePowerLinks(){
        for(var iterator=links.entrySet().iterator();iterator.hasNext();){var item=iterator.next();if(item.getKey().kind==Kind.POWER&&!valid(item.getKey(),item.getValue())){registry.disconnectPower(item.getKey().from,item.getKey().to);iterator.remove();}}
    }
    public void update(double seconds){
        if(!Double.isFinite(seconds)||seconds<=0)return;
        // Budgeted throughput is based on elapsed simulation time, not display framerate.
        seconds=Math.min(seconds,1);
        for(Iterator<Map.Entry<Link,Progress>> iterator=links.entrySet().iterator();iterator.hasNext();){
            Map.Entry<Link,Progress> item=iterator.next();Link link=item.getKey();
            if(!valid(link,item.getValue())){if(link.kind==Kind.POWER)registry.disconnectPower(link.from,link.to);iterator.remove();continue;}
            if(link.kind==Kind.POWER)continue;
            Progress progress=item.getValue();Endpoint source=progress.source,target=progress.target;
            double efficiency=link.kind.bridge()?Math.max(0,Math.min(1,source.building.efficiency)):1;
            if(link.kind.bridge()&&(!source.building.enabled||!target.building.enabled||efficiency<=0))continue;
            if(link.kind.items()){
                double rate=itemsPerSecond;
                if(link.kind.bridge()&&source.building.block instanceof mindustry.world.blocks.distribution.ItemBridge bridge)rate=60/Math.max(1,bridge.transportTime);
                progress.credit=Math.min(rate,progress.credit+rate*seconds*efficiency);
                int moves=Math.min(MAX_ITEM_MOVES_PER_UPDATE,(int)progress.credit),moved=0;
                for(int move=0;move<moves;move++){
                    Item type=nextItem(source.building,target.building,progress,link.kind.bridge());
                    if(type==null)break;
                    if(source.building.removeStack(type,1)!=1)break;
                    target.building.handleItem(source.building,type);moved++;
                }
                progress.credit-=moved;
            }else{
                Liquid liquid=source.building.liquids.current();
                float amount=(float)Math.min(liquidPerSecond*seconds*efficiency,Math.min(source.building.liquids.get(liquid),Math.max(0,target.building.block.liquidCapacity-target.building.liquids.get(liquid))));
                boolean accepts=link.kind.bridge()?(target.building.liquids.current()==liquid||target.building.liquids.currentAmount()<.2f):target.building.acceptLiquid(source.building,liquid);
                if(amount>0&&accepts){
                    source.building.liquids.remove(liquid,amount);target.building.handleLiquid(source.building,liquid,amount);
                }
            }
        }
    }
    private Item nextItem(Building source,Building target,Progress progress,boolean bridge){
        int count=Vars.content.items().size;
        for(int i=0;i<count;i++){
            int index=Math.floorMod(progress.cursor++,count);Item item=Vars.content.items().get(index);
            if(source.items.get(item)>0&&(bridge?target.items.total()<target.block.itemCapacity:target.acceptItem(source,item)))return item;
        }
        return null;
    }
    private boolean valid(Link link,Progress progress){
        if(link.kind==Kind.POWER){
            Endpoint a=progress.source,b=progress.target;
            return a!=null&&b!=null&&!a.building.dead&&!b.building.dead&&a.building.health>0&&b.building.health>0&&a.building.team==b.building.team&&present(link.from,a)&&present(link.to,b)&&(links.containsKey(link)?registry.linkedPower(link.from,link.to):registry.powerLinkReason(link.from,link.to)==null)&&
                (a.building.block instanceof mindustry.world.blocks.power.PowerNode||b.building.block instanceof mindustry.world.blocks.power.PowerNode||clearance.clear(link.from,link.to));
        }
        Endpoint a=progress.source,b=progress.target;
        if(a==null||b==null||!present(link.from,a)||!present(link.to,b)||a.building==b.building||a.building.dead||b.building.dead||a.building.health<=0||b.building.health<=0||a.building.team!=b.building.team||Math.abs(a.layer-b.layer)<1||Math.abs(a.layer-b.layer)>maxLayerGap)return false;
        if(link.kind.bridge()){
            if(!isBridge(a.building)||!isBridge(b.building)||a.building.block!=b.building.block)return false;
            double dx=(a.building.x-b.building.x)/(double)Vars.tilesize,dy=(a.building.y-b.building.y)/(double)Vars.tilesize,dz=(a.layer-b.layer)*layerSpacing;
            double range=Math.min(bridgeRange(a.building),bridgeRange(b.building));if(dx*dx+dy*dy+dz*dz>range*range+1e-6)return false;
        }else if(a.building.x!=b.building.x||a.building.y!=b.building.y||!clearance.clear(link.from,link.to))return false;
        return switch(link.kind){
            case ITEMS,BRIDGE_ITEMS -> a.building.items!=null&&b.building.items!=null;
            case LIQUIDS,BRIDGE_LIQUIDS -> a.building.liquids!=null&&b.building.liquids!=null;
            // Electrical edges are validated by the identity-aware native graph adapter above.
            case POWER -> false;
        };
    }
    private boolean present(long id,Endpoint endpoint){return id>0?registry.building(id)==endpoint.building:Vars.world!=null&&Vars.world.build(endpoint.building.pos())==endpoint.building;}
    private Endpoint endpoint(long id){
        if(id>0){LayerBuildingAdapter.Entry entry=registry.entry(id);return entry==null?null:new Endpoint(entry.building(),entry.layer());}
        if(id==0||Vars.world==null)return null;
        long packed=-id-1;if(packed<0||packed>0xffffffffL)return null;
        Building building=Vars.world.build((int)packed);
        // Alias footprint cells must not create duplicate links to the same multiblock.
        return building==null||Integer.toUnsignedLong(building.pos())!=packed?null:new Endpoint(building,0);
    }
}
