package fpv.construction;

import arc.math.Mathf;
import arc.math.geom.Point2;
import mindustry.gen.Building;
import mindustry.type.*;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;

/** Packed XY references are resolved against their owning floor, never Vars.world. */
final class LayerNativeBehavior {
    private static final java.lang.reflect.Field dumpTimer=dumpTimer();
    private static java.lang.reflect.Field dumpTimer(){try{var field=mindustry.world.Block.class.getDeclaredField("timerDump");field.setAccessible(true);return field;}catch(ReflectiveOperationException error){throw new ExceptionInInitializerError(error);}}
    private static int dumpTimer(mindustry.world.Block block){try{return dumpTimer.getInt(block);}catch(IllegalAccessException error){throw new IllegalStateException(error);}}
    static void stackProximity(mindustry.world.blocks.distribution.StackConveyor.StackConveyorBuild build){
        var type=(mindustry.world.blocks.distribution.StackConveyor)build.block;
        Building front=build.front(),back=build.back();
        // Native states are move=0/load=1/unload=2. Visual blending never consults the Z0 atlas neighbors.
        boolean next=front instanceof mindustry.world.blocks.distribution.StackConveyor.StackConveyorBuild&&front.team==build.team;
        boolean previous=back instanceof mindustry.world.blocks.distribution.StackConveyor.StackConveyorBuild&&back.team==build.team&&back.front()==build;
        build.state=!next?2:previous?0:1;build.blendprox=next?1:0;
        if(previous)build.blendprox|=4;
    }
    static void beamFacing(LayerBuildingAdapter owner,mindustry.world.blocks.production.BeamDrill.BeamDrillBuild build){
        var type=(mindustry.world.blocks.production.BeamDrill)build.block;
        build.lastItem=null;build.facingAmount=0;boolean multiple=false;
        for(int row=0;row<type.size;row++){
            Point2 start=build.lasers[row];Tile found=null;
            if(start!=null)for(int step=0;step<type.range;step++){
                Tile cell=owner.tile(start.x+arc.math.geom.Geometry.d4x(build.rotation)*step,start.y+arc.math.geom.Geometry.d4y(build.rotation)*step,((LayerTile)build.tile).layer);
                if(cell!=null&&cell.solid()){
                    Item item=cell.wallDrop();if(item!=null&&item.hardness<=type.tier&&(type.blockedItems==null||!type.blockedItems.contains(item))){
                        found=cell;build.facingAmount++;multiple|=build.lastItem!=null&&build.lastItem!=item;build.lastItem=item;
                    }break;
                }
            }build.facing[row]=found;
        }if(multiple)build.lastItem=null;
    }
    static void wallCraft(LayerBuildingAdapter owner,mindustry.world.blocks.production.WallCrafter.WallCrafterBuild build){
        var type=(mindustry.world.blocks.production.WallCrafter)build.block;float affinity=0;
        int lower=-(type.size-1)/2,upper=type.size/2;
        for(int row=lower;row<=upper;row++){
            int dx=build.rotation==0?upper+1:build.rotation==2?lower-1:row;
            int dy=build.rotation==1?upper+1:build.rotation==3?lower-1:row;
            Tile cell=build.tile.nearby(dx,dy);if(cell!=null&&cell.solid())affinity+=cell.block().attributes.get(type.attribute);
        }
        boolean item=type.itemConsumer!=null&&type.itemConsumer.efficiency(build)>0;
        float output=affinity*Mathf.lerp(1,type.liquidBoostIntensity,build.optionalEfficiency)*(item?type.itemBoostIntensity:1);
        build.warmup=Mathf.approachDelta(build.warmup,build.efficiency>0?1:0,1f/40);
        if(item&&output*build.efficiency>0&&build.timer(type.timerUse,type.boostItemUseTime/build.timeScale()))build.consume();
        build.lastEfficiency=output*build.timeScale()*build.efficiency;
        if(build.shouldConsume()&&(build.time+=build.edelta()*output)>=type.drillTime){build.offload(type.output);build.time%=type.drillTime;}
        build.totalTime+=build.edelta()*build.warmup*(output>0?1:0);if(build.timer(dumpTimer(type),type.dumpTime/build.timeScale()))build.dump(type.output);
    }
    static Tile linked(LayerBuildingAdapter owner,ItemBridgeBuild bridge,int packed){
        return packed==-1?null:owner.tile(Point2.x(packed),Point2.y(packed),((LayerTile)bridge.tile).layer);
    }
    static boolean validBridge(LayerBuildingAdapter owner,ItemBridgeBuild bridge,int packed,boolean checkDouble){
        Tile destination=linked(owner,bridge,packed);
        return destination!=null&&destination.build!=null&&destination.build!=bridge&&((ItemBridge)bridge.block).linkValid(bridge.tile,destination,checkDouble);
    }
    static void checkIncoming(LayerBuildingAdapter owner,ItemBridgeBuild bridge){
        for(int i=bridge.incoming.size-1;i>=0;i--){
            int packed=bridge.incoming.get(i);Tile other=linked(owner,bridge,packed);
            if(!validBridge(owner,bridge,packed,false)||!(other.build instanceof ItemBridgeBuild incoming)||incoming.link!=bridge.pos())bridge.incoming.removeIndex(i);
        }
    }
    static void updateBridge(LayerBuildingAdapter owner,ItemBridgeBuild bridge){
        if(owner.restoringState())return;
        if(owner.externalBridge(bridge)){CrossLayerBridgeHooks.updateExternal(bridge);return;}
        ItemBridge block=(ItemBridge)bridge.block;
        if(bridge.timer(block.timerCheckMoved,30)){bridge.wasMoved=bridge.moved;bridge.moved=false;}
        bridge.timeSpeed=Mathf.approachDelta(bridge.timeSpeed,bridge.wasMoved?1:0,1f/60);bridge.time+=bridge.timeSpeed*bridge.delta();
        checkIncoming(owner,bridge);
        bridge.hadValidLink=validBridge(owner,bridge,bridge.link,true);
        if(!bridge.hadValidLink){bridge.doDump();bridge.warmup=0;return;}
        ItemBridgeBuild target=(ItemBridgeBuild)linked(owner,bridge,bridge.link).build;
        if(!target.incoming.contains(bridge.pos()))target.incoming.add(bridge.pos());
        bridge.warmup=Mathf.approachDelta(bridge.warmup,bridge.efficiency,1f/30);bridge.updateTransport(target);
    }
    static boolean fromLink(LayerBuildingAdapter owner,ItemBridgeBuild bridge,Building source){
        return source instanceof ItemBridgeBuild incoming&&source.tile instanceof LayerTile tile&&tile.layer==((LayerTile)bridge.tile).layer&&incoming.link==bridge.pos()&&validBridge(owner,incoming,bridge.pos(),false);
    }
    static boolean canAccept(LayerBuildingAdapter owner,ItemBridgeBuild bridge,Building source){
        if(source==null||source.team!=bridge.team)return false;
        if(owner.externalBridge(bridge)||fromLink(owner,bridge,source))return true;
        if(!validBridge(owner,bridge,bridge.link,true))return false;
        Tile target=linked(owner,bridge,bridge.link);
        return bridge.relativeTo(target)!=bridge.relativeTo(source.tileX(),source.tileY());
    }
    static boolean acceptBridgeItem(LayerBuildingAdapter owner,ItemBridgeBuild bridge,Building source,Item item){
        ItemBridge block=(ItemBridge)bridge.block;return block.hasItems&&(!block.noAcceptDisabled||bridge.enabled)&&bridge.items.total()<block.itemCapacity&&canAccept(owner,bridge,source);
    }
    static boolean acceptBridgeLiquid(LayerBuildingAdapter owner,ItemBridgeBuild bridge,Building source,Liquid liquid){
        ItemBridge block=(ItemBridge)bridge.block;return block.hasLiquids&&(!block.noAcceptDisabled||bridge.enabled)&&(bridge.liquids.current()==liquid||bridge.liquids.currentAmount()<.2f)&&canAccept(owner,bridge,source);
    }
    static boolean bridgeDump(LayerBuildingAdapter owner,ItemBridgeBuild bridge,Building to){
        if(to==null||owner.externalBridge(bridge))return false;
        if(validBridge(owner,bridge,bridge.link,true))return bridge.relativeTo(linked(owner,bridge,bridge.link))!=bridge.relativeTo(to.tileX(),to.tileY());
        int direction=bridge.relativeTo(to.tileX(),to.tileY());
        for(int i=0;i<bridge.incoming.size;i++)if(bridge.relativeTo(Point2.x(bridge.incoming.get(i)),Point2.y(bridge.incoming.get(i)))==direction)return false;
        return true;
    }
}
