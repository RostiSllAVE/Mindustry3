package fpv.net;

import arc.Events;
import arc.util.Log;
import arc.util.Time;
import fpv.combat.Combat3DSystem;
import fpv.effects.RadialExplosion;
import fpv.terrain.TerrainElevation;
import fpv.units.*;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType.*;

/** Dedicated server bootstrap. Construct from Mod.init after the native content catalog exists. */
public final class HeadlessFpvRuntime implements AutoCloseable {
    public final TerrainElevation terrain=new TerrainElevation();
    public final UnitAltitudeSystem altitudes=new UnitAltitudeSystem(terrain::heightAt);
    public final World3DAdapter world=new World3DAdapter(terrain,(x,z)->HeadlessGeometry.surface(terrain,x,z),altitudes);
    public final fpv.units.FactorySpawnSafety factorySpawns=new fpv.units.FactorySpawnSafety(world,altitudes);
    public final RadialExplosion explosions=new RadialExplosion(world);
    public final Combat3DSystem combat=new Combat3DSystem(world,()->Vars.state.isGame(),unit->networkAim(unit));
    public final fpv.defense.ProjectedWallSystem projectedWalls=new fpv.defense.ProjectedWallSystem(world,()->!Vars.net.client());
    public final fpv.defense.ForceProjectorSystem forceProjectors=new fpv.defense.ForceProjectorSystem(world,()->!Vars.net.client());
    public final FpvNetwork network=new FpvNetwork(world,altitudes,combat,explosions,(x,z)->HeadlessGeometry.surface(terrain,x,z));
    public final AltitudeSeparation separation=new AltitudeSeparation(Vars.unitPhysics,world::unitState,()->Vars.net.server());
    public final fpv.construction.LayerRuntime construction=new fpv.construction.LayerRuntime(world);
    public final fpv.construction.LayerPhysicalQueries layerQueries=new fpv.construction.LayerPhysicalQueries(construction::grid,construction::damage,construction::building);
    private boolean closed;
    private fpv.world.WorldCoordinates.Point networkAim(mindustry.gen.Unit unit){return network==null?null:network.aim(unit);}
    public void install(){
        if(!Vars.headless||!fpv.platform.HostCompatibility.supportsCurrent())throw new IllegalStateException("Dedicated FPV runtime requires native Mindustry "+fpv.platform.HostCompatibility.supportedVersions()+"; found "+fpv.platform.HostCompatibility.currentVersion());
        combat.setAuthority(()->!Vars.net.client());combat.setProjectedDefense(new fpv.combat.CompositeDefense(projectedWalls,forceProjectors));
        construction.install(network);world.setConstruction(layerQueries);
        construction.setTurretAiming(combat.layerTurretAiming());combat.setUpperBuildings(()->construction.nativeBuildings().entries());
        construction.setUnitRelease(factorySpawns::releaseUpper);construction.setDefenseUpdate(combat::updateUpperDefense);
        construction.setDestructionEffect(blast->explosions.detonateXYZ(mindustry.game.Team.derelict,blast.x(),blast.y(),blast.z(),blast.spec()));
        forceProjectors.setUpperBuildings(()->construction.nativeBuildings().entries(),()->construction.grid().geometryRevision());
        projectedWalls.setCoveredPredicate(b->construction.grid().columnsCovered(b.tile.x,b.tile.y,b.block.size));
        combat.setProjectedDefense(new fpv.combat.CompositeDefense(projectedWalls,forceProjectors,construction.baseShields(),layerQueries));
        world.setUnitBarrier(forceProjectors.combine(projectedWalls::sweepUnit));altitudes.setAircraftBarrier(world::traceUnitBarrier);altitudes.setGroundSupport(new fpv.defense.LegTraversal(world,()->projectedWalls.settings().legStepMultiplier()));
        altitudes.setAircraftStructures(construction::grid,world::surfaceHeight);
        var ground=new fpv.units.ground.GroundFamilyLocomotion(world);
        ground.setProjectedObstacle(projectedWalls::topAt);
        fpv.units.ground.GroundUnitProfiles.setNativeStepMultiplierSupplier(()->projectedWalls.settings().legStepMultiplier());
        altitudes.setGroundFamily(ground);
        factorySpawns.install();
        network.registerProjection(projectedWalls);network.registerForceProjectors(forceProjectors);combat.install();separation.install();
        Events.run(Trigger.update,()->{if(!closed){fpv.combat.CombatSettings.apply(combat);combat.syncActive();separation.syncActive();}});
        Events.run(Trigger.afterGameUpdate,()->{if(!closed){double dt=Math.max(0,Math.min(.1,Time.delta/60));construction.update(dt);combat.update(dt);explosions.update(dt);projectedWalls.update(dt);forceProjectors.update(dt);}});
        Events.on(WorldLoadEvent.class,e->{terrain.invalidate();altitudes.clear();combat.clear();explosions.clear();projectedWalls.clear();forceProjectors.clear();});
        Events.on(ResetEvent.class,e->{altitudes.clear();combat.clear();explosions.clear();projectedWalls.clear();forceProjectors.clear();});
        Events.on(TileChangeEvent.class,e->{terrain.invalidate(e.tile);projectedWalls.invalidate(e.tile);forceProjectors.invalidate(e.tile);});
        Events.on(DisposeEvent.class,e->close());
        network.install();new NetworkSmokeHarness(network).install();Log.info("[FPV-NET] Dedicated CPU runtime ready");
    }
    @Override public void close(){if(closed)return;closed=true;construction.close();factorySpawns.close();network.close();separation.close();combat.close();projectedWalls.close();forceProjectors.close();altitudes.close();explosions.clear();}
}
