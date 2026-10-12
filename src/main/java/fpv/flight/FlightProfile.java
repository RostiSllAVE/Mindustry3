package fpv.flight;

import fpv.generated.FlightProfiles;
import fpv.generated.PerformanceConfig;

/** Original gameplay performance mapping. No Mindustry or Liftoff runtime dependencies. */
public record FlightProfile(FlightController.Profile profile, double massKg, double massProxy,
                            double agility, double practicalSpeedMps,
                            double fullThrustAccelerationMps2, double rateScale) {
    private static final FlightController.Profile reference = FlightProfiles.create();

    /** Caller converts native speed and hit size to tiles; HP is not a physical mass. */
    public record UnitMetadata(double nativeSpeedTilesPerSecond, double hitSizeTiles,
                               double maxHealth, boolean flying, String nativeTypeName) {
        /** Unknown native identity retains generic unit scaling for existing callers. */
        public UnitMetadata(double nativeSpeedTilesPerSecond, double hitSizeTiles,
                            double maxHealth, boolean flying) {
            this(nativeSpeedTilesPerSecond, hitSizeTiles, maxHealth, flying, "");
        }

        public UnitMetadata {
            nonnegative("native speed", nativeSpeedTilesPerSecond);
            positive("hit size", hitSizeTiles);
            positive("max health", maxHealth);
            nativeTypeName = nativeTypeName == null ? "" : nativeTypeName;
        }
    }

    /** Percent controls arrive as ratios. Practical speed and acceleration are SI references. */
    public record Modifiers(double thrustMultiplier, double practicalSpeedMps,
                            double massMultiplier, double dragMultiplier,
                            double responseMultiplier, double accelerationLimitMps2,
                            double rateMultiplier) {
        public Modifiers {
            range("thrust", thrustMultiplier, PerformanceConfig.MIN_POWER_PERCENT / 100,
                    PerformanceConfig.MAX_POWER_PERCENT / 100);
            range("speed reference", practicalSpeedMps, PerformanceConfig.MIN_SPEED_REFERENCE,
                    PerformanceConfig.MAX_PRACTICAL_SPEED_MPS);
            range("mass", massMultiplier, PerformanceConfig.MIN_MASS_PERCENT / 100,
                    PerformanceConfig.MAX_MASS_PERCENT / 100);
            range("drag", dragMultiplier, PerformanceConfig.MIN_DRAG_PERCENT / 100,
                    PerformanceConfig.MAX_DRAG_PERCENT / 100);
            range("response", responseMultiplier, PerformanceConfig.MIN_RESPONSE_PERCENT / 100,
                    PerformanceConfig.MAX_RESPONSE_PERCENT / 100);
            range("acceleration reference", accelerationLimitMps2,
                    PerformanceConfig.MIN_ACCELERATION_REFERENCE, PerformanceConfig.MAX_ACCELERATION_MPS2);
            range("rate multiplier", rateMultiplier, PerformanceConfig.MIN_RATE_PERCENT / 100,
                    PerformanceConfig.MAX_RATE_PERCENT / 100);
        }
    }

    private static final Modifiers[] presets = {
            new Modifiers(PerformanceConfig.CINEMATIC_THRUST, PerformanceConfig.CINEMATIC_SPEED,
                    PerformanceConfig.CINEMATIC_MASS, PerformanceConfig.CINEMATIC_DRAG,
                    PerformanceConfig.CINEMATIC_RESPONSE, PerformanceConfig.CINEMATIC_ACCELERATION,
                    PerformanceConfig.CINEMATIC_RATES),
            new Modifiers(PerformanceConfig.FREESTYLE_THRUST, PerformanceConfig.FREESTYLE_SPEED,
                    PerformanceConfig.FREESTYLE_MASS, PerformanceConfig.FREESTYLE_DRAG,
                    PerformanceConfig.FREESTYLE_RESPONSE, PerformanceConfig.FREESTYLE_ACCELERATION,
                    PerformanceConfig.FREESTYLE_RATES),
            new Modifiers(PerformanceConfig.RACE_THRUST, PerformanceConfig.RACE_SPEED,
                    PerformanceConfig.RACE_MASS, PerformanceConfig.RACE_DRAG,
                    PerformanceConfig.RACE_RESPONSE, PerformanceConfig.RACE_ACCELERATION,
                    PerformanceConfig.RACE_RATES),
            new Modifiers(PerformanceConfig.INSANE_THRUST, PerformanceConfig.INSANE_SPEED,
                    PerformanceConfig.INSANE_MASS, PerformanceConfig.INSANE_DRAG,
                    PerformanceConfig.INSANE_RESPONSE, PerformanceConfig.INSANE_ACCELERATION,
                    PerformanceConfig.INSANE_RATES)
    };

    /** Indices 0..3 match Cinematic/Freestyle/Race/Insane; Custom supplies its own Modifiers. */
    public static Modifiers preset(int index) {
        if (index < 0 || index >= presets.length) throw new IllegalArgumentException("Unknown flight preset");
        return presets[index];
    }

    /** Stable unit mass for uncontrolled collision participants; ignores player modifiers. */
    public static double baseMassKg(UnitMetadata unit) {
        return baseMassKg(reference, unit);
    }

    public static double baseMassKg(FlightController.Profile base, UnitMetadata unit) {
        require(base, unit);
        return base.massKg * massScale(unit);
    }

    public static FlightProfile resolve(FlightController.Profile base, UnitMetadata unit, Modifiers modifiers) {
        return resolve(base, unit, modifiers, 1.0);
    }

    /** Live flight speed scales equilibrium drag, without teleporting or changing mass/thrust. */
    public static FlightProfile resolve(FlightController.Profile base, UnitMetadata unit, Modifiers modifiers,
                                        double flightSpeedMultiplier) {
        require(base, unit);
        range("flight speed multiplier", flightSpeedMultiplier, .25, 3.0);
        if (modifiers == null) throw new IllegalArgumentException("modifiers required");
        double size = sizeScale(unit);
        double unitMass = massScale(unit);
        double burden = Math.min(PerformanceConfig.HEAVY_MAX_BURDEN,
                Math.pow(Math.max(1.0, unitMass / PerformanceConfig.HEAVY_MASS_THRESHOLD),
                        PerformanceConfig.HEAVY_MASS_EXPONENT)
                * Math.pow(Math.max(1.0, size / PerformanceConfig.HEAVY_SIZE_THRESHOLD),
                        PerformanceConfig.HEAVY_SIZE_EXPONENT));
        double speedRatio = unit.nativeSpeedTilesPerSecond / PerformanceConfig.REFERENCE_SPEED_TILES_PER_SECOND;
        // The final finite bounds protect unusual content without equating HP to kilograms.
        double nativeSpeedFactor = Math.pow(speedRatio, PerformanceConfig.NATIVE_SPEED_EXPONENT);
        double agility = clamp(nativeSpeedFactor / Math.pow(unitMass, PerformanceConfig.AGILITY_MASS_EXPONENT)
                        / Math.pow(burden, PerformanceConfig.HEAVY_AGILITY_EXPONENT),
                PerformanceConfig.MIN_AGILITY, PerformanceConfig.MAX_AGILITY);
        double speedScale = clamp(nativeSpeedFactor / Math.pow(unitMass, PerformanceConfig.SPEED_MASS_EXPONENT),
                PerformanceConfig.MIN_SPEED_SCALE, PerformanceConfig.MAX_SPEED_SCALE) / burden;
        double mass = base.massKg * unitMass * modifiers.massMultiplier;
        double nominalAcceleration = Math.max(base.gravityMps2 * PerformanceConfig.MIN_THRUST_WEIGHT,
                base.gravityMps2 + (base.maxThrustNewtons / base.massKg - base.gravityMps2) * agility);
        // Larger units gain larger motors but less thrust per mass; extra custom mass
        // does not grant extra motor power. The governor only removes available force.
        double availableAcceleration = nominalAcceleration * modifiers.thrustMultiplier / modifiers.massMultiplier;
        double accelerationLimit = Math.max(base.gravityMps2 * PerformanceConfig.MIN_THRUST_WEIGHT,
                modifiers.accelerationLimitMps2 * agility);
        double acceleration = Math.min(availableAcceleration,
                Math.min(accelerationLimit, PerformanceConfig.MAX_ACCELERATION_MPS2));
        double thrust = mass * acceleration;
        // Explicit user-requested base-game Flare speed tuning. Real unit metadata,
        // mass, inertia and available force remain the same as the generic model.
        double identitySpeed = "flare".equals(unit.nativeTypeName)
                ? PerformanceConfig.FLARE_SPEED_MULTIPLIER : 1.0;
        double practicalSpeed = Math.min(PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                modifiers.practicalSpeedMps * speedScale * identitySpeed * flightSpeedMultiplier / Math.sqrt(modifiers.dragMultiplier));
        double linearDrag = base.linearDragPerSecond * modifiers.dragMultiplier
                / Math.pow(unitMass, PerformanceConfig.DRAG_MASS_EXPONENT);
        double quadraticDrag;
        if (acceleration > base.gravityMps2) {
            double horizontalAcceleration = Math.sqrt(acceleration * acceleration
                    - base.gravityMps2 * base.gravityMps2);
            // Calibrate drag to a full-throttle level-flight equilibrium. This is
            // a continuous-force reference, not an instantaneous speed clamp.
            linearDrag = Math.min(linearDrag, horizontalAcceleration / practicalSpeed * 0.5);
            quadraticDrag = (horizontalAcceleration - linearDrag * practicalSpeed)
                    / (practicalSpeed * practicalSpeed);
        } else {
            // Weak custom motors cannot promise sustained level flight.
            practicalSpeed = 0;
            quadraticDrag = base.quadraticDragPerMeter * modifiers.dragMultiplier / (speedScale * speedScale);
        }
        double inertiaScale = unitMass * modifiers.massMultiplier * size * size;
        double motorResponse = clamp(base.motorTimeConstantSeconds * modifiers.responseMultiplier / Math.sqrt(agility),
                PerformanceConfig.MIN_RESPONSE_SECONDS, PerformanceConfig.MAX_RESPONSE_SECONDS);
        double rateResponse = clamp(base.rateResponseSeconds * modifiers.responseMultiplier / agility,
                Math.max(base.fixedStepSeconds, PerformanceConfig.MIN_RESPONSE_SECONDS),
                PerformanceConfig.MAX_RESPONSE_SECONDS);
        double rateScale = agility * modifiers.rateMultiplier;
        FlightController.Profile resolved = new FlightController.Profile(base.metersPerTile, mass,
                base.inertiaXKgM2 * inertiaScale, base.inertiaYKgM2 * inertiaScale, base.inertiaZKgM2 * inertiaScale,
                thrust, base.gravityMps2, linearDrag, quadraticDrag, base.angularDragPerSecond,
                motorResponse, rateResponse,
                base.maxTorqueNewtonMeters * unitMass * size * agility * modifiers.thrustMultiplier,
                boundedRate(base.pitchRateDegreesPerSecond * rateScale),
                boundedRate(base.rollRateDegreesPerSecond * rateScale),
                boundedRate(base.yawRateDegreesPerSecond * rateScale),
                base.rateExpo, base.fixedStepSeconds, base.maxFrameSeconds);
        return new FlightProfile(resolved, mass, unitMass, agility, practicalSpeed, acceleration, rateScale);
    }

    public static double boundedRate(double value) {
        positive("rate", value);
        return Math.min(value, PerformanceConfig.MAX_RATE_DEGREES_PER_SECOND);
    }

    private static double sizeScale(UnitMetadata unit) {
        return clamp(unit.hitSizeTiles / PerformanceConfig.REFERENCE_HIT_SIZE_TILES,
                PerformanceConfig.MIN_SIZE_SCALE, PerformanceConfig.MAX_SIZE_SCALE);
    }

    private static double massScale(UnitMetadata unit) {
        double size = sizeScale(unit);
        return clamp(size * size * Math.pow(unit.maxHealth / PerformanceConfig.REFERENCE_HEALTH,
                        PerformanceConfig.HEALTH_MASS_EXPONENT),
                PerformanceConfig.MIN_MASS_SCALE, PerformanceConfig.MAX_MASS_SCALE);
    }

    private static void require(FlightController.Profile base, UnitMetadata unit) {
        if (base == null || unit == null) throw new IllegalArgumentException("base profile and unit metadata required");
    }

    private static void positive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be finite and positive");
    }

    private static void nonnegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }

    private static void range(String name, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " outside supported range " + min + ".." + max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
