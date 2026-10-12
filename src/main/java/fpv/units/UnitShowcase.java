package fpv.units;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/**
 * Explicit isolated-lab fixture, never a production movement controller. Spawns a near-core
 * visual group and commands one separate probe across existing natural terrain through native
 * velocity integration. Call update before native entity updates; inspect/verify after >=3 seconds.
 */
public final class UnitShowcase implements AutoCloseable {
    public record Metrics(int fixtures, double simulationSeconds, double groupAltitudeSpread,
                          double minimumClearance, double probeClimb, double maximumPitch,
                          double maximumRoll, double yawTravel, double peakTerrain,
                          boolean nativeElevationPreserved) {}

    private final UnitAltitudeSystem altitudes;
    private final DoubleBinaryOperator terrain;
    private final List<Unit> owned = new ArrayList<>();
    private final List<Float> originalElevations = new ArrayList<>();
    private final List<Integer> originalIds = new ArrayList<>();
    private Unit probe;
    private RouteController probeController;
    private double elapsed, coreX, coreZ, startX, startZ, peakX, peakZ, peakTerrain;
    private double initialProbeHeight = Double.NaN, highestProbe, minClearance = Double.POSITIVE_INFINITY;
    private double maximumPitch, maximumRoll, yawTravel, previousYaw;
    private Object tiles;
    private boolean started;

    public UnitShowcase(UnitAltitudeSystem altitudes, DoubleBinaryOperator terrainHeight) {
        if (!Boolean.getBoolean("fpv.smoke")) throw new IllegalStateException("Unit showcase requires isolated smoke mode");
        if (altitudes == null || terrainHeight == null) throw new IllegalArgumentException("Altitude and terrain required");
        this.altitudes = altitudes;
        terrain = terrainHeight;
    }

    public void spawn() {
        if (started) throw new IllegalStateException("Showcase already started");
        if (Vars.player.core() == null) throw new IllegalStateException("Showcase needs a native core");
        started = true;
        tiles = Vars.world.tiles;
        coreX = Vars.player.core().x / Vars.tilesize;
        coreZ = -Vars.player.core().y / Vars.tilesize;
        spawn(UnitTypes.flare, coreX - 5, coreZ + 1, 90);
        spawn(UnitTypes.horizon, coreX - 2, coreZ - 1, 65);
        spawn(UnitTypes.zenith, coreX + 3, coreZ - 1, 110);
        spawn(UnitTypes.antumbra, coreX + 9, coreZ - 5, 80);
        spawn(UnitTypes.dagger, coreX - 3, coreZ + 4, 120);
        findMountainRoute();
        probe = spawn(UnitTypes.flare, startX, startZ, 200);
        probeController = new RouteController();
        probe.controller(probeController);
        altitudes.pose(probe); // Establish the start before the first scripted displacement.
    }

    private Unit spawn(UnitType type, double x, double z, float rotation) {
        Unit unit = type.create(Vars.player.team());
        unit.set((float)(x * Vars.tilesize), (float)(-z * Vars.tilesize));
        unit.rotation = rotation;
        unit.controller(new InactiveController());
        unit.add();
        owned.add(unit);
        originalElevations.add(unit.elevation);
        originalIds.add(unit.id);
        return unit;
    }

    /** Scripted route commands are confined to owned lab fixtures; no map or direct per-frame XY writes. */
    public void update(double seconds) {
        if (!started || tiles != Vars.world.tiles) return;
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Simulation seconds must be nonnegative");
        observe();
        if (seconds == 0) return;
        elapsed += seconds;
        double dx = peakX - startX, dz = peakZ - startZ;
        double length = Math.max(0.001, Math.hypot(dx, dz));
        double progress = Math.min(1, elapsed * 4 / length);
        // A small curved approach produces movement-derived yaw/bank while climbing the ridge.
        double bend = Math.sin(progress * Math.PI) * Math.sin(elapsed * 1.2) * 0.6;
        double x = startX + dx * progress - dz / length * bend;
        double z = startZ + dz * progress + dx / length * bend;
        if (probe != null && probe.isAdded() && !probe.dead) {
            probeController.command(x * Vars.tilesize,-z * Vars.tilesize,seconds * 60);
        }
    }

    private void observe() {
        for (Unit unit : owned) {
            UnitAltitudeModel.Pose pose = altitudes.pose(unit);
            if (pose == null) continue;
            if (unit.isFlying()) {
                double floor = terrain.applyAsDouble(unit.x / Vars.tilesize, -unit.y / Vars.tilesize);
                minClearance = Math.min(minClearance, pose.height() - floor);
            }
            if (unit == probe) {
                if (Double.isNaN(initialProbeHeight)) {
                    initialProbeHeight = pose.height();
                    highestProbe = pose.height();
                    previousYaw = pose.yaw();
                }
                highestProbe = Math.max(highestProbe, pose.height());
                maximumPitch = Math.max(maximumPitch, Math.abs(pose.pitch()));
                maximumRoll = Math.max(maximumRoll, Math.abs(pose.roll()));
                yawTravel += Math.abs(((pose.yaw() - previousYaw + 540) % 360) - 180);
                previousYaw = pose.yaw();
            }
        }
    }

    public Metrics metrics() {
        observe();
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        boolean preserved = true;
        for (int i = 0; i < owned.size(); i++) {
            Unit unit = owned.get(i);
            preserved &= unit.elevation == originalElevations.get(i);
            if (unit == probe || !unit.isFlying()) continue;
            UnitAltitudeModel.Pose pose = altitudes.pose(unit);
            if (pose != null) { low = Math.min(low, pose.height()); high = Math.max(high, pose.height()); }
        }
        double spread = Double.isFinite(high - low) ? high - low : 0;
        return new Metrics(owned.size(), elapsed, spread, minClearance,
            Double.isNaN(initialProbeHeight) ? 0 : highestProbe - initialProbeHeight,
            maximumPitch, maximumRoll, yawTravel, peakTerrain, preserved);
    }

    public Metrics verify() {
        Metrics m = metrics();
        if (m.fixtures != 6 || m.simulationSeconds < 3 || m.groupAltitudeSpread < 0.25
            || m.minimumClearance <= 0 || m.probeClimb < 0.15 || m.maximumPitch < 0.1
            || m.maximumRoll < 0.1 || m.yawTravel < 0.5 || !m.nativeElevationPreserved) {
            throw new IllegalStateException("Scripted unit showcase failed: " + m);
        }
        if(probeController==null||probeController.completedSteps==0||probeController.maximumPositionError>.002) {
            throw new IllegalStateException("Showcase route must be integrated once by native VelComp");
        }
        return m;
    }

    public double cameraX() { return coreX; }
    public double cameraY() { return 10; }
    public double cameraZ() { return coreZ + 20; }
    public double probeX() { return probe == null ? startX : probe.x / Vars.tilesize; }
    public double probeZ() { return probe == null ? startZ : -probe.y / Vars.tilesize; }

    private void findMountainRoute() {
        double best = -1;
        int width = Vars.world.width(), height = Vars.world.height();
        for (int y = 3; y < height - 3; y += 2) {
            for (int x = 3; x < width - 3; x += 2) {
                double elevation = terrain.applyAsDouble(x, -y);
                double score = elevation - Math.hypot(x - coreX, -y - coreZ) * 0.002;
                if (score > best) { best = score; peakX = x; peakZ = -y; peakTerrain = elevation; }
            }
        }
        if (peakTerrain < 2) throw new IllegalStateException("Showcase map needs a natural mountain above two tiles");
        double nearest = Double.POSITIVE_INFINITY;
        startX = peakX; startZ = peakZ;
        for (int radius = 1; radius <= Math.max(width, height); radius++) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * Math.PI * 2 / 16;
                double x = peakX + Math.cos(angle) * radius, z = peakZ + Math.sin(angle) * radius;
                if (x < 2 || x > width - 3 || z > -2 || z < -height + 3) continue;
                if (terrain.applyAsDouble(x, z) < 0.01 && radius < nearest) {
                    nearest = radius; startX = x; startZ = z;
                }
            }
            if (Double.isFinite(nearest)) break;
        }
        if (!Double.isFinite(nearest)) throw new IllegalStateException("Showcase map has no open approach to mountain");
    }

    /** Removes only this fixture's exact unit references, never all units or player units. */
    public void dispose() {
        for (int i = 0; i < owned.size(); i++) {
            Unit unit = owned.get(i);
            if (tiles == Vars.world.tiles && unit.id == originalIds.get(i) && unit.isAdded()) unit.remove();
        }
        owned.clear();
        originalElevations.clear();
        originalIds.clear();
        probe = null;
        probeController = null;
    }
    @Override public void close() { dispose(); }

    private static final class InactiveController implements UnitController {
        private Unit unit;
        @Override public void unit(Unit value) { unit = value; }
        @Override public Unit unit() { return unit; }
        @Override public void updateUnit() {
            unit.vel.setZero();
            unit.controlWeapons(false, false);
        }
    }

    /** Native VelComp moves first; this later controller publishes the actual command, never a second move. */
    private static final class RouteController implements UnitController {
        private Unit unit;
        private float vx,vy;
        private double expectedX,expectedY,maximumPositionError;
        private int completedSteps;
        private boolean pending;
        @Override public void unit(Unit value) { unit=value; }
        @Override public Unit unit() { return unit; }
        void command(double x,double y,double nativeDelta) {
            if(!(nativeDelta>0))throw new IllegalArgumentException("Positive native route timestep required");
            expectedX=x;expectedY=y;
            vx=(float)((x-unit.x)/nativeDelta);vy=(float)((y-unit.y)/nativeDelta);
            unit.vel.set(vx,vy);pending=true;
        }
        @Override public void updateUnit() {
            if(pending) {
                double error=Math.hypot(unit.x-expectedX,unit.y-expectedY);
                maximumPositionError=Math.max(maximumPositionError,error);
                if(error>.002)throw new IllegalStateException("Native showcase route moved incorrectly: error="+error);
                completedSteps++;pending=false;
            }
            // Preserve the route's direction after native drag for the physical altitude observer.
            // The next smoke update supplies the next command before native velocity integration.
            unit.vel.set(vx,vy);
            unit.controlWeapons(false,false);
        }
    }
}
