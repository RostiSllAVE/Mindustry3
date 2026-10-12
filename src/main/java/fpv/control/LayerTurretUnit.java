package fpv.control;

import mindustry.gen.*;
import mindustry.world.blocks.ControlBlock;
import mindustry.world.blocks.defense.turrets.Turret;

/** Native fixed-building proxy. Its wire view cannot alias an unrelated Z0 building in TypeIO. */
public final class LayerTurretUnit extends BlockUnitUnit {
    private final WireView wire=new WireView();
    public LayerTurretUnit(Turret.TurretBuild owner){super();setType(mindustry.content.UnitTypes.block);team=owner.team;tile(owner);}
    @Override public void team(mindustry.game.Team requested){team=tile==null?requested:tile.team;}
    @Override public Building tile(){
        // Inherited BlockUnit behavior uses its real tile field for validity, damage and position.
        // Only the public reference used by native TypeIO needs an unresolvable ground-grid position.
        wire.owner=tile;wire.tile=tile.tile;wire.block=tile.block;wire.team=tile.team;wire.x=tile.x;wire.y=tile.y;wire.health=tile.health;
        return wire;
    }
    public static Building actual(Unit unit){return unit instanceof LayerTurretUnit upper?upper.tile:unit instanceof BlockUnitc block?block.tile():null;}
    private final class WireView extends Building implements ControlBlock {
        Building owner;
        @Override public int pos(){return -1;}
        @Override public boolean isValid(){return owner!=null&&owner.isValid();}
        @Override public boolean isAdded(){return isValid();}
        @Override public boolean canControl(){return owner instanceof ControlBlock c&&c.canControl();}
        @Override public Unit unit(){return LayerTurretUnit.this;}
    }
}
