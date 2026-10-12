package fpv.input;

import arc.Core;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/** Public native unit predicates plus InputHandler's package-private player-unmineable policy. */
public final class FpvMiningRules {
    private FpvMiningRules(){}
    public static boolean allowed(Unit unit,Tile tile){
        if(unit==null||unit.dead||!unit.canMine()||unit.activelyBuilding()||!unit.validMine(tile)||!unit.acceptsItem(unit.getMineResult(tile)))return false;
        boolean doubleTap=Core.settings.getBool("doubletapmine");
        return !(!doubleTap&&tile.floor().playerUnmineable&&tile.overlay().itemDrop==null)
            &&!(!doubleTap&&tile.overlay().playerUnmineable&&tile.overlay().itemDrop!=null);
    }
}
