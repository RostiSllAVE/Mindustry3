package fpv.render;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.distribution.DirectionBridge;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.payloads.PayloadMassDriver;

/** Read-only native connection metadata; no activity/updater-group filters and no map-specific rules. */
public final class NativeBridgeLinks {
    private NativeBridgeLinks() {}
    public enum Kind { item, liquid, massDriver, payloadDriver }
    /** Caller-owned reusable output. Texture/color references remain owned by Mindustry. */
    public static final class Link {
        public Building source,target;
        public Kind kind;
        public TextureRegion region,arrow;
        public Color liquidColor;
        public float width,phase;
        public boolean physicalSpan(){return kind==Kind.item||kind==Kind.liquid;}
    }

    public static boolean resolve(Building source,Link output){
        if(!attached(source))return false;
        Building target;
        output.region=output.arrow=null;output.liquidColor=null;output.phase=0;
        if(source instanceof ItemBridge.ItemBridgeBuild bridge && source.block instanceof ItemBridge type){
            var tile=Vars.world.tile(bridge.link);
            if(tile==null||!attached(tile.build)||!type.linkValid(source.tile,tile))return false;
            target=tile.build;
            output.kind=type.hasLiquids?Kind.liquid:Kind.item;
            output.region=type.bridgeRegion;output.arrow=type.arrowRegion;
            output.width=type.bridgeWidth/Vars.tilesize;
            if(source.enabled&&source.efficiency>0)output.phase=bridge.time/Math.max(.001f,type.arrowTimeScl)/Vars.tilesize;
        }else if(source instanceof DirectionBridge.DirectionBridgeBuild bridge && source.block instanceof DirectionBridge type){
            target=bridge.findLink();
            if(!attached(target))return false;
            output.kind=type.hasLiquids?Kind.liquid:Kind.item;
            output.region=type.bridgeRegion;output.arrow=type.arrowRegion;
            output.width=NativeAtlasMesh.valid(type.bridgeRegion)?type.bridgeRegion.height*type.bridgeRegion.scl()/Vars.tilesize:1;
        }else if(source instanceof MassDriver.MassDriverBuild driver && source.block instanceof MassDriver type){
            target=Vars.world.build(driver.link);
            if(!(target instanceof MassDriver.MassDriverBuild)||!attached(target)||target.block!=source.block||
                target.team!=source.team||!source.within(target,type.range))return false;
            output.kind=Kind.massDriver;output.width=0;
        }else if(source instanceof PayloadMassDriver.PayloadDriverBuild driver && source.block instanceof PayloadMassDriver type){
            target=Vars.world.build(driver.link);
            if(!(target instanceof PayloadMassDriver.PayloadDriverBuild)||!attached(target)||target.block!=source.block||
                target.team!=source.team||!source.within(target,type.range))return false;
            output.kind=Kind.payloadDriver;output.width=0;
        }else return false;
        if(target==source)return false;
        output.source=source;output.target=target;
        if(output.kind==Kind.liquid&&source.liquids!=null&&source.liquids.currentAmount()>.001f)
            output.liquidColor=source.liquids.current().color;
        return true;
    }

    private static boolean attached(Building building){
        return building!=null&&!building.dead&&building.tile!=null&&building.tile.build==building&&
            Vars.world!=null&&Vars.world.tile(building.tile.x,building.tile.y)==building.tile;
    }
}
