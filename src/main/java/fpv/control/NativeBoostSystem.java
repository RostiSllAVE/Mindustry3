package fpv.control;

import fpv.effects.RadialExplosion;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;

/** Ordinary native capability, isolated from Creator permission, sharing the existing physical pilot. */
public final class NativeBoostSystem extends CreatorFlightSystem {
    public NativeBoostSystem(World3DAdapter world,UnitAltitudeSystem altitudes,RadialExplosion explosions){super(world,altitudes,explosions,true);altitudes.setBoostGroundWorld(world);NativeBoostGroundSave.install(altitudes);}
}
