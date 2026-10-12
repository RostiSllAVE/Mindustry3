package fpv.construction;

import arc.func.Prov;
import arc.math.Mathf;
import java.util.*;
import java.util.function.Predicate;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.liquid.LiquidBridge;

/** Native Z0 bridge endpoints retain their normal behavior unless an explicit server-owned 3D link exists. */
public final class CrossLayerBridgeHooks implements AutoCloseable {
    private record Factory(Block block,Prov<Building> original,Prov<Building> replacement){}
    private final List<Factory> factories=new ArrayList<>();
    private final Predicate<Building> external;
    private static final java.lang.reflect.Field bufferField=bufferField();
    public CrossLayerBridgeHooks(Predicate<Building> external){this.external=Objects.requireNonNull(external);}
    public void install(){
        if(!factories.isEmpty())return;
        for(Block block:Vars.content.blocks()){
            if(block instanceof DirectionBridge){
                Prov<Building> original=block.buildType,replacement;Class<?> family=block.newBuilding().getClass();
                if(family==DuctBridge.DuctBridgeBuild.class){var bridge=(DuctBridge)block;replacement=()->bridge.new DuctBridgeBuild(){
                    @Override public void updateTile(){if(external.test(this))lastLink=null;else super.updateTile();}
                    @Override public boolean acceptItem(Building source,Item item){return external.test(this)?acceptDirectionalItem(this,source):super.acceptItem(source,item);}
                };}else if(family==DirectionLiquidBridge.DuctBridgeBuild.class){var bridge=(DirectionLiquidBridge)block;replacement=()->bridge.new DuctBridgeBuild(){
                    @Override public void updateTile(){if(external.test(this))lastLink=null;else super.updateTile();}
                    @Override public boolean acceptLiquid(Building source,Liquid liquid){return external.test(this)?acceptDirectionalLiquid(this,source,liquid):super.acceptLiquid(source,liquid);}
                };}else continue;
                factories.add(new Factory(block,original,replacement));block.buildType=replacement;continue;
            }
            if(!(block instanceof ItemBridge type))continue;
            Prov<Building> original=block.buildType,replacement;Class<?> family=block.newBuilding().getClass();
            // Do not erase behavior installed by unrelated mods.
            if(family==BufferedItemBridge.BufferedItemBridgeBuild.class){var bridge=(BufferedItemBridge)block;replacement=()->bridge.new BufferedItemBridgeBuild(){
                @Override public void updateTile(){if(external.test(this))updateExternal(this);else super.updateTile();}
                @Override public boolean acceptItem(Building from,Item item){return external.test(this)?acceptExternalItem(this,from):super.acceptItem(from,item);}
            };}
            else if(family==LiquidBridge.LiquidBridgeBuild.class){var bridge=(LiquidBridge)block;replacement=()->bridge.new LiquidBridgeBuild(){
                @Override public void updateTile(){if(external.test(this))updateExternal(this);else super.updateTile();}
                @Override public boolean acceptLiquid(Building from,Liquid liquid){return external.test(this)?acceptExternalLiquid(this,from,liquid):super.acceptLiquid(from,liquid);}
            };}
            else if(family==ItemBridgeBuild.class){replacement=()->type.new ItemBridgeBuild(){
                @Override public void updateTile(){if(external.test(this))updateExternal(this);else super.updateTile();}
                @Override public boolean acceptItem(Building from,Item item){return external.test(this)?acceptExternalItem(this,from):super.acceptItem(from,item);}
                @Override public boolean acceptLiquid(Building from,Liquid liquid){return external.test(this)?acceptExternalLiquid(this,from,liquid):super.acceptLiquid(from,liquid);}
            };}
            else continue;
            factories.add(new Factory(block,original,replacement));block.buildType=replacement;
        }
    }
    static void updateExternal(ItemBridgeBuild bridge){
        ItemBridge type=(ItemBridge)bridge.block;
        bridge.hadValidLink=true;
        bridge.warmup=Mathf.approachDelta(bridge.warmup,bridge.efficiency,1f/30);
        if(bridge.timer(type.timerCheckMoved,30)){bridge.wasMoved=bridge.moved;bridge.moved=false;}
        bridge.timeSpeed=Mathf.approachDelta(bridge.timeSpeed,bridge.wasMoved?1:0,1f/60);bridge.time+=bridge.timeSpeed*bridge.delta();
        // A pre-existing buffered horizontal shipment returns to the real source inventory first.
        // It is neither duplicated nor discarded while switching to an explicit cross-floor link.
        if(bridge instanceof BufferedItemBridge.BufferedItemBridgeBuild&&bufferField!=null){
            try{ItemBuffer buffer=(ItemBuffer)bufferField.get(bridge);
                for(int limit=0;limit<256&&bridge.items.total()<type.itemCapacity;limit++){
                    Item item=buffer.poll(0);if(item==null)break;bridge.items.add(item,1);buffer.remove();
                }
            }catch(IllegalAccessException failure){throw new IllegalStateException("Cannot recover native bridge buffer",failure);}
        }
    }
    static boolean acceptDirectionalItem(Building build,Building source){return source!=null&&source.team==build.team&&build.enabled&&build.items.total()<build.block.itemCapacity&&build.relativeToEdge(source.tile)!=build.rotation;}
    static boolean acceptDirectionalLiquid(Building build,Building source,Liquid liquid){return source!=null&&source.team==build.team&&build.enabled&&(build.liquids.current()==liquid||build.liquids.currentAmount()<.2f)&&build.relativeToEdge(source.tile)!=build.rotation;}
    private static boolean acceptExternalItem(ItemBridgeBuild bridge,Building from){
        ItemBridge type=(ItemBridge)bridge.block;return type.hasItems&&from!=null&&from.team==bridge.team&&(!type.noAcceptDisabled||bridge.enabled)&&bridge.items.total()<type.itemCapacity;
    }
    private static boolean acceptExternalLiquid(ItemBridgeBuild bridge,Building from,Liquid liquid){
        ItemBridge type=(ItemBridge)bridge.block;return type.hasLiquids&&from!=null&&from.team==bridge.team&&(!type.noAcceptDisabled||bridge.enabled)&&(bridge.liquids.current()==liquid||bridge.liquids.currentAmount()<.2f);
    }
    private static java.lang.reflect.Field bufferField(){try{var field=BufferedItemBridge.BufferedItemBridgeBuild.class.getDeclaredField("buffer");field.setAccessible(true);return field;}catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}}
    @Override public void close(){for(var factory:factories)if(factory.block.buildType==factory.replacement)factory.block.buildType=factory.original;factories.clear();}
}
