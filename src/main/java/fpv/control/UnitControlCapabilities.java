package fpv.control;

import mindustry.gen.*;
import mindustry.world.blocks.ControlBlock;

/** Native capabilities, not content names. Possession itself stays in native validated RPCs. */
public final class UnitControlCapabilities {
    public enum Movement { AIRCRAFT, WALKER, MECH, NAVAL, HOVER, GROUND, STATIONARY }
    private UnitControlCapabilities(){}
    public static Movement movement(Unit unit){
        if(unit instanceof BlockUnitc)return Movement.STATIONARY;
        if(unit.type.flying)return Movement.AIRCRAFT;
        if(unit instanceof Legsc)return Movement.WALKER;
        if(unit instanceof WaterMovec||unit instanceof WaterCrawlc)return Movement.NAVAL;
        if(unit.type.hovering)return Movement.HOVER;
        if(unit instanceof Mechc)return Movement.MECH;
        return Movement.GROUND;
    }
    public static boolean stationary(Unit unit){return unit instanceof BlockUnitc;}
    public static boolean boostEligible(Unit unit){return unit!=null&&unit.type!=null&&unit.type.canBoost&&!unit.type.internal&&!unit.type.flying&&!stationary(unit);}
    public static boolean creatorEligible(Unit unit){return unit!=null&&unit.type!=null&&!unit.type.flying&&!stationary(unit);}
    public static boolean validOwner(Player player,Unit unit){
        if(player==null||unit==null||player.dead()||unit.dead()||!unit.isAdded()||player.unit()!=unit||unit.controller()!=player||unit.team!=player.team())return false;
        if(unit instanceof BlockUnitc proxy)return proxy.tile() instanceof ControlBlock block&&block.canControl();
        return unit.playerControllable();
    }
}
