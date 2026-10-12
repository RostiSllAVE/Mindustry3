package fpv.flight;

import arc.Core;
import fpv.generated.PerformanceConfig;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.gen.Unit;

/** Small native adapter: read settings/metadata, cache one resolved profile, never mutate UnitType. */
public final class FlightSettings {
    private final FlightController.Profile base;
    private final int[] sampled = new int[13];
    private final int[] previous = new int[13];
    private Unit tracked;
    private FlightController target;
    private FlightProfile current;
    private double previousSpeed, previousSize, previousHealth;
    private boolean previousFlying;
    private String previousTypeName;

    public FlightSettings(FlightController.Profile base) {
        if (base == null) throw new IllegalArgumentException("base profile required");
        this.base = base;
    }

    /** Call on the owning game thread; only settings/metadata changes allocate a profile. */
    public FlightProfile configure(FlightController controller, Unit unit) {
        if (controller == null || unit == null || unit.type == null)
            throw new IllegalArgumentException("controller and typed unit required");
        readSettings();
        double speed = speed(unit), size = size(unit), health = health(unit);
        boolean flying = unit.type.flying;
        String typeName = typeName(unit);
        if (current != null && tracked == unit && target == controller
                && speed == previousSpeed && size == previousSize && health == previousHealth
                && flying == previousFlying && typeName.equals(previousTypeName)
                && Arrays.equals(sampled, previous)) return current;

        FlightProfile.Modifiers modifiers = sampled[0] == (int)PerformanceConfig.CUSTOM_PRESET
                ? new FlightProfile.Modifiers(sampled[1] / 100.0, sampled[2], sampled[3] / 100.0,
                    sampled[4] / 100.0, sampled[5] / 100.0, sampled[6], sampled[7] / 100.0)
                : FlightProfile.preset(sampled[0]);
        FlightController.Profile userRates = new FlightController.Profile(base.metersPerTile, base.massKg,
                base.inertiaXKgM2, base.inertiaYKgM2, base.inertiaZKgM2, base.maxThrustNewtons,
                base.gravityMps2, base.linearDragPerSecond, base.quadraticDragPerMeter, base.angularDragPerSecond,
                base.motorTimeConstantSeconds, base.rateResponseSeconds, base.maxTorqueNewtonMeters,
                sampled[8], sampled[9], sampled[10], sampled[11] / 100.0,
                base.fixedStepSeconds, base.maxFrameSeconds);
        FlightProfile resolved = FlightProfile.resolve(userRates,
                new FlightProfile.UnitMetadata(speed, size, health, flying, typeName), modifiers, sampled[12]/100.0);
        controller.configureProfile(resolved.profile());
        current = resolved;
        tracked = unit;
        target = controller;
        previousSpeed = speed;
        previousSize = size;
        previousHealth = health;
        previousFlying = flying;
        previousTypeName = typeName;
        System.arraycopy(sampled, 0, previous, 0, sampled.length);
        return current;
    }

    public FlightProfile current() {
        return current;
    }

    /** Native reference metadata for collisions; ignores transient remaining health and user presets. */
    public static FlightProfile.UnitMetadata metadata(Unit unit) {
        if (unit == null || unit.type == null) throw new IllegalArgumentException("typed unit required");
        return new FlightProfile.UnitMetadata(speed(unit), size(unit), health(unit), unit.type.flying, typeName(unit));
    }

    private static String typeName(Unit unit) {
        return unit.type.name == null ? "" : unit.type.name;
    }

    private void readSettings() {
        sampled[0] = integer("fpv-performance-preset", PerformanceConfig.DEFAULT_PRESET, 0, PerformanceConfig.CUSTOM_PRESET);
        sampled[1] = integer("fpv-power", PerformanceConfig.FREESTYLE_THRUST * 100,
                PerformanceConfig.MIN_POWER_PERCENT, PerformanceConfig.MAX_POWER_PERCENT);
        sampled[2] = integer("fpv-speed-reference", PerformanceConfig.FREESTYLE_SPEED,
                PerformanceConfig.MIN_SPEED_REFERENCE, PerformanceConfig.MAX_PRACTICAL_SPEED_MPS);
        sampled[3] = integer("fpv-mass", PerformanceConfig.FREESTYLE_MASS * 100,
                PerformanceConfig.MIN_MASS_PERCENT, PerformanceConfig.MAX_MASS_PERCENT);
        sampled[4] = integer("fpv-drag", PerformanceConfig.FREESTYLE_DRAG * 100,
                PerformanceConfig.MIN_DRAG_PERCENT, PerformanceConfig.MAX_DRAG_PERCENT);
        sampled[5] = integer("fpv-response", PerformanceConfig.FREESTYLE_RESPONSE * 100,
                PerformanceConfig.MIN_RESPONSE_PERCENT, PerformanceConfig.MAX_RESPONSE_PERCENT);
        sampled[6] = integer("fpv-acceleration", PerformanceConfig.FREESTYLE_ACCELERATION,
                PerformanceConfig.MIN_ACCELERATION_REFERENCE, PerformanceConfig.MAX_ACCELERATION_MPS2);
        sampled[7] = integer("fpv-rate-scale", PerformanceConfig.FREESTYLE_RATES * 100,
                PerformanceConfig.MIN_RATE_PERCENT, PerformanceConfig.MAX_RATE_PERCENT);
        sampled[8] = integer("fpv-pitch-rate", base.pitchRateDegreesPerSecond, 20, 1000);
        sampled[9] = integer("fpv-roll-rate", base.rollRateDegreesPerSecond, 20, 1000);
        sampled[10] = integer("fpv-yaw-rate", base.yawRateDegreesPerSecond, 20, 1000);
        sampled[11] = integer("fpv-expo", base.rateExpo * 100, 0, 100);
        sampled[12] = integer("fpv-flight-speed", 100, 25, 300);
    }

    private static int integer(String key, double fallback, double min, double max) {
        int value = (int)fallback;
        try {
            if (Core.settings != null) value = Core.settings.getInt(key, value);
        } catch (RuntimeException invalidSavedType) {
            // A stale or hand-edited value cannot crash physical gameplay.
        }
        return Math.max((int)min, Math.min((int)max, value));
    }

    private static double speed(Unit unit) {
        double speed = unit.type.speed;
        return Double.isFinite(speed) && speed >= 0 ? speed * 60.0 / Vars.tilesize
                : PerformanceConfig.REFERENCE_SPEED_TILES_PER_SECOND;
    }

    private static double size(Unit unit) {
        double value = unit.hitSize;
        if (!Double.isFinite(value) || value <= 0) value = unit.type.hitSize;
        return Double.isFinite(value) && value > 0 ? value / Vars.tilesize
                : PerformanceConfig.REFERENCE_HIT_SIZE_TILES;
    }

    private static double health(Unit unit) {
        double value = unit.maxHealth;
        if (!Double.isFinite(value) || value <= 0) value = unit.type.health;
        return Double.isFinite(value) && value > 0 ? value : PerformanceConfig.REFERENCE_HEALTH;
    }
}
