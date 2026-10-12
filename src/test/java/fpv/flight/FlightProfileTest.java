package fpv.flight;

import fpv.generated.FlightProfiles;
import fpv.generated.PerformanceConfig;

/** Deterministic performance tests; native input/collision integration is tested separately. */
public final class FlightProfileTest {
    private static int checks;
    private static final FlightController.Profile base = FlightProfiles.create();
    private static final FlightProfile.UnitMetadata reference = new FlightProfile.UnitMetadata(
            PerformanceConfig.REFERENCE_SPEED_TILES_PER_SECOND,
            PerformanceConfig.REFERENCE_HIT_SIZE_TILES, PerformanceConfig.REFERENCE_HEALTH, true);

    public static void main(String[] args) {
        presetSpeeds();
        unitCharacteristics();
        flareIdentitySpeed();
        heavyMobility();
        customControls();
        preserveIntegration();
        frameDelivery();
        validationAndExtremes();
        System.out.println("FlightProfile: " + checks + " assertions passed (pure original performance model).");
    }

    private static void presetSpeeds() {
        double previous = 0;
        double legacy = sustainedSpeed(base);
        for (int preset = 0; preset < 4; preset++) {
            FlightProfile resolved = FlightProfile.resolve(base, reference, FlightProfile.preset(preset));
            double actual = sustainedSpeed(resolved.profile());
            check(actual > previous, "preset " + preset + " is faster than previous preset");
            close(actual, resolved.practicalSpeedMps(), resolved.practicalSpeedMps() * .02,
                    "drag produces the practical-speed reference for preset " + preset);
            check(resolved.practicalSpeedMps() <= PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                    "reference speed is bounded for preset " + preset);
            if (preset == 1) check(actual > legacy * 1.7, "Freestyle is materially faster than legacy flight");
            if (preset == 3) check(actual > legacy * 8, "Insane permits substantially higher sustained speed");
            previous = actual;
        }
    }

    private static void unitCharacteristics() {
        // Pinned native Flare/Zenith/Eclipse metadata, converted world units/tick to tiles/s.
        FlightProfile.UnitMetadata[] units = {
                new FlightProfile.UnitMetadata(20.25, 1.125, 70, true),
                new FlightProfile.UnitMetadata(12.75, 2.5, 700, true),
                new FlightProfile.UnitMetadata(4.05, 7.25, 22000, true)
        };
        FlightProfile previous = null;
        for (FlightProfile.UnitMetadata unit : units) {
            FlightProfile resolved = FlightProfile.resolve(base, unit, FlightProfile.preset(1));
            close(resolved.massKg(), FlightProfile.baseMassKg(unit), 1e-12,
                    "default mass agrees with stable collision-target mass");
            check(resolved.profile().hoverThrottle() < 1, "native size class retains enough thrust to hover");
            if (previous != null) {
                check(resolved.massKg() > previous.massKg(), "larger tougher unit is heavier");
                check(resolved.profile().inertiaXKgM2 > previous.profile().inertiaXKgM2,
                        "larger unit has higher rotational inertia");
                check(resolved.fullThrustAccelerationMps2() < previous.fullThrustAccelerationMps2(),
                        "larger slower unit accelerates less strongly");
                check(resolved.practicalSpeedMps() < previous.practicalSpeedMps(), "larger slower unit has lower practical speed");
                check(resolved.profile().motorTimeConstantSeconds > previous.profile().motorTimeConstantSeconds,
                        "larger unit has slower motor response");
                check(resolved.profile().pitchRateDegreesPerSecond < previous.profile().pitchRateDegreesPerSecond,
                        "larger unit has lower angular rate authority");
            }
            previous = resolved;
        }
        FlightProfile small = FlightProfile.resolve(base, units[0], FlightProfile.preset(2));
        FlightProfile large = FlightProfile.resolve(base, units[2], FlightProfile.preset(2));
        check(large.massKg() > small.massKg() * 100, "huge and tiny collision masses remain meaningfully distinct");
        check(large.profile().inertiaXKgM2 / large.profile().maxTorqueNewtonMeters
                        > small.profile().inertiaXKgM2 / small.profile().maxTorqueNewtonMeters,
                "heavy unit is harder to angularly accelerate even with its larger motors");
        FlightProfile.UnitMetadata ground = new FlightProfile.UnitMetadata(12.75, 2.5, 700, false);
        close(FlightProfile.baseMassKg(ground), FlightProfile.baseMassKg(units[1]), 0,
                "ground collision target uses mass without falsely changing native flight classification");
    }

    private static void flareIdentitySpeed() {
        var generic = new FlightProfile.UnitMetadata(20.25, 1.125, 70, true);
        var flare = new FlightProfile.UnitMetadata(20.25, 1.125, 70, true, "flare");
        for (int preset = 0; preset < 4; preset++) {
            var previous = FlightProfile.resolve(base, generic, FlightProfile.preset(preset));
            var current = FlightProfile.resolve(base, flare, FlightProfile.preset(preset));
            close(current.massKg(), previous.massKg(), 0, "Flare identity retains native-derived mass");
            close(current.profile().inertiaXKgM2, previous.profile().inertiaXKgM2, 0,
                    "Flare identity retains native-derived inertia");
            close(current.profile().maxThrustNewtons, previous.profile().maxThrustNewtons, 0,
                    "Flare speed tuning does not fabricate additional motor force");
            close(current.profile().pitchRateDegreesPerSecond, previous.profile().pitchRateDegreesPerSecond, 0,
                    "Flare speed tuning retains angular control");
            close(current.practicalSpeedMps(), Math.min(PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                    previous.practicalSpeedMps() * PerformanceConfig.FLARE_SPEED_MULTIPLIER), 1e-12,
                    "Flare applies explicit speed multiplier before cap");
            double ratio = sustainedSpeed(current.profile()) / sustainedSpeed(previous.profile());
            if (preset < 3) close(ratio, 1.5, .025, "measured Flare speed gains about 1.5x in preset " + preset);
            else check(ratio >= 1 && current.practicalSpeedMps() == PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                    "Insane Flare respects existing aerodynamic reference cap");
        }
        var modUnit = new FlightProfile.UnitMetadata(20.25, 1.125, 70, true, "othermod-flare");
        close(FlightProfile.resolve(base, modUnit, FlightProfile.preset(1)).practicalSpeedMps(),
                FlightProfile.resolve(base, generic, FlightProfile.preset(1)).practicalSpeedMps(), 0,
                "similar modded names do not accidentally inherit Flare tuning");
        close(FlightProfile.baseMassKg(flare), FlightProfile.baseMassKg(generic), 0,
                "ram target mass remains independent of native name speed bonus");
    }

    private static void heavyMobility() {
        var medium = new FlightProfile.UnitMetadata(12.75, 2.5, 700, true, "zenith");
        var antumbra = new FlightProfile.UnitMetadata(6.0, 5.75, 7200, true, "antumbra");
        var eclipse = new FlightProfile.UnitMetadata(4.05, 7.25, 22000, true, "eclipse");
        var preset = FlightProfile.preset(1);
        close(FlightProfile.resolve(base, medium, preset).practicalSpeedMps(), legacySpeed(medium, preset), 1e-12,
                "medium unit below heavy thresholds retains previous practical speed");
        double previousRatio = 1.0;
        for (var unit : new FlightProfile.UnitMetadata[]{antumbra, eclipse}) {
            var resolved = FlightProfile.resolve(base, unit, preset);
            double ratio = resolved.practicalSpeedMps() / legacySpeed(unit, preset);
            check(ratio < previousRatio && ratio < .5, "heavy mobility progressively lowers speed below half of 0.4");
            previousRatio = ratio;
            close(sustainedSpeed(resolved.profile()), resolved.practicalSpeedMps(), resolved.practicalSpeedMps() * .02,
                    "integrated heavy sustained speed matches reduced reference");
            double oldAgility = Math.max(PerformanceConfig.MIN_AGILITY, Math.min(PerformanceConfig.MAX_AGILITY,
                    Math.pow(unit.nativeSpeedTilesPerSecond() / PerformanceConfig.REFERENCE_SPEED_TILES_PER_SECOND,
                            PerformanceConfig.NATIVE_SPEED_EXPONENT)
                    / Math.pow(resolved.massProxy(), PerformanceConfig.AGILITY_MASS_EXPONENT)));
            check(resolved.agility() < oldAgility, "heavy modifier also reduces agility and acceleration authority");
            double oldAcceleration = Math.min((base.gravityMps2
                    + (base.maxThrustNewtons / base.massKg - base.gravityMps2) * oldAgility)
                    * preset.thrustMultiplier() / preset.massMultiplier(),
                    Math.max(base.gravityMps2 * PerformanceConfig.MIN_THRUST_WEIGHT,
                            preset.accelerationLimitMps2() * oldAgility));
            check(resolved.fullThrustAccelerationMps2() < oldAcceleration * .8,
                    "heavy preset provides substantially less acceleration than 0.4");
            close(resolved.profile().inertiaXKgM2,
                    base.inertiaXKgM2 * resolved.massProxy() * unit.hitSizeTiles() * unit.hitSizeTiles(), 1e-10,
                    "heavier tuning preserves original physical inertia instead of reducing mass");
            check(resolved.profile().rateResponseSeconds > base.rateResponseSeconds / oldAgility,
                    "heavy angular response slows with reduced agility");
            check(resolved.profile().hoverThrottle() < 1, "heavy craft retains hover reserve");
        }
    }

    private static double legacySpeed(FlightProfile.UnitMetadata unit, FlightProfile.Modifiers modifiers) {
        double unitMass = FlightProfile.baseMassKg(base, unit) / base.massKg;
        double speedScale = Math.max(PerformanceConfig.MIN_SPEED_SCALE, Math.min(PerformanceConfig.MAX_SPEED_SCALE,
                Math.pow(unit.nativeSpeedTilesPerSecond() / PerformanceConfig.REFERENCE_SPEED_TILES_PER_SECOND,
                        PerformanceConfig.NATIVE_SPEED_EXPONENT)
                / Math.pow(unitMass, PerformanceConfig.SPEED_MASS_EXPONENT)));
        return Math.min(PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                modifiers.practicalSpeedMps() * speedScale / Math.sqrt(modifiers.dragMultiplier()));
    }

    private static void customControls() {
        FlightProfile.Modifiers m = FlightProfile.preset(1);
        FlightProfile normal = FlightProfile.resolve(base, reference, m);
        FlightProfile mass = resolve(m.thrustMultiplier(), m.practicalSpeedMps(), 2,
                m.dragMultiplier(), m.responseMultiplier(), m.accelerationLimitMps2(), m.rateMultiplier());
        close(mass.massKg(), normal.massKg() * 2, 1e-12, "custom mass changes gameplay kilograms");
        close(mass.profile().maxThrustNewtons, normal.profile().maxThrustNewtons, 1e-10,
                "extra custom mass does not conjure more motor force");
        check(mass.fullThrustAccelerationMps2() < normal.fullThrustAccelerationMps2(), "extra mass reduces acceleration");
        FlightProfile power = resolve(m.thrustMultiplier() * 2, m.practicalSpeedMps(), m.massMultiplier(),
                m.dragMultiplier(), m.responseMultiplier(), m.accelerationLimitMps2(), m.rateMultiplier());
        check(power.profile().maxThrustNewtons > normal.profile().maxThrustNewtons, "power increases available force");
        FlightProfile drag = resolve(m.thrustMultiplier(), m.practicalSpeedMps(), m.massMultiplier(),
                4, m.responseMultiplier(), m.accelerationLimitMps2(), m.rateMultiplier());
        check(sustainedSpeed(drag.profile()) < sustainedSpeed(normal.profile()) * .6, "drag reduces measured speed");
        FlightProfile speed = resolve(m.thrustMultiplier(), 150, m.massMultiplier(), m.dragMultiplier(),
                m.responseMultiplier(), m.accelerationLimitMps2(), m.rateMultiplier());
        close(speed.profile().maxThrustNewtons, normal.profile().maxThrustNewtons, 0,
                "speed reference changes drag, not thrust");
        check(speed.profile().quadraticDragPerMeter < normal.profile().quadraticDragPerMeter,
                "higher practical speed lowers aerodynamic resistance");
        FlightProfile cap = resolve(m.thrustMultiplier(), m.practicalSpeedMps(), m.massMultiplier(),
                m.dragMultiplier(), m.responseMultiplier(), 20, m.rateMultiplier());
        close(cap.fullThrustAccelerationMps2(), 20, 1e-12, "reference acceleration governor limits available force");
        FlightProfile response = resolve(m.thrustMultiplier(), m.practicalSpeedMps(), m.massMultiplier(),
                m.dragMultiplier(), 2, m.accelerationLimitMps2(), m.rateMultiplier());
        check(response.profile().motorTimeConstantSeconds > normal.profile().motorTimeConstantSeconds,
                "response modifier changes throttle spool time");
        FlightController quick = new FlightController(normal.profile()), slow = new FlightController(response.profile());
        quick.step(.1, 1, 0, 0, 0); slow.step(.1, 1, 0, 0, 0);
        check(quick.state().vy > slow.state().vy, "response difference affects actual short-term acceleration");
        FlightProfile rates = resolve(m.thrustMultiplier(), m.practicalSpeedMps(), m.massMultiplier(),
                m.dragMultiplier(), m.responseMultiplier(), m.accelerationLimitMps2(), 2);
        close(rates.profile().yawRateDegreesPerSecond, normal.profile().yawRateDegreesPerSecond * 2,
                1e-12, "rate modifier affects real profile authority");
        FlightProfile weak = resolve(.25, m.practicalSpeedMps(), 10, m.dragMultiplier(),
                m.responseMultiplier(), m.accelerationLimitMps2(), m.rateMultiplier());
        close(weak.practicalSpeedMps(), 0, 0, "underpowered custom profile makes no level-flight speed promise");
        check(weak.profile().hoverThrottle() > 1, "underpowered custom mass cannot hover");
    }

    private static void preserveIntegration() {
        FlightController changed = new FlightController(base), untouched = new FlightController(base);
        for (int i = 0; i < 125; i++) {
            changed.step(base.fixedStepSeconds, .6, .1, -.2, .15);
            untouched.step(base.fixedStepSeconds, .6, .1, -.2, .15);
        }
        changed.step(base.fixedStepSeconds / 2, .5, 0, 0, 0);
        untouched.step(base.fixedStepSeconds / 2, .5, 0, 0, 0);
        changed.configureProfile(base);
        sameState(changed.state(), untouched.state(), "same profile preserves visible state");
        changed.step(base.fixedStepSeconds / 2, .4, 0, 0, 0);
        untouched.step(base.fixedStepSeconds / 2, .4, 0, 0, 0);
        sameState(changed.state(), untouched.state(), "same profile preserves spool and fractional timestep");
        FlightProfile race = FlightProfile.resolve(base, reference, FlightProfile.preset(2));
        changed.configureProfile(race.profile());
        sameState(changed.state(), untouched.state(), "different physical profile does not teleport or reset momentum");
        changed.state().vx = 1000;
        changed.configureProfile(FlightProfile.resolve(base, reference, FlightProfile.preset(0)).profile());
        close(changed.state().vx, 1000, 0, "slow preset never instantaneously clips high existing velocity");
        changed.step(base.fixedStepSeconds, 0, 0, 0, 0);
        check(changed.state().vx > 100 && changed.state().vx < 1000,
                "aerodynamic drag slows excess momentum continuously");
        rejects(() -> changed.configureProfile(null), "null live profile");
        FlightController.Profile otherScale = new FlightController.Profile(base.metersPerTile * 2, base.massKg,
                base.inertiaXKgM2, base.inertiaYKgM2, base.inertiaZKgM2, base.maxThrustNewtons, base.gravityMps2,
                base.linearDragPerSecond, base.quadraticDragPerMeter, base.angularDragPerSecond,
                base.motorTimeConstantSeconds, base.rateResponseSeconds, base.maxTorqueNewtonMeters,
                base.pitchRateDegreesPerSecond, base.rollRateDegreesPerSecond, base.yawRateDegreesPerSecond,
                base.rateExpo, base.fixedStepSeconds, base.maxFrameSeconds);
        rejects(() -> changed.configureProfile(otherScale), "live coordinate scale change");
    }

    private static void frameDelivery() {
        FlightController.Profile profile = FlightProfile.resolve(base, reference, FlightProfile.preset(3)).profile();
        FlightController fixed = new FlightController(profile), frames = new FlightController(profile);
        for (int i = 0; i < 2500; i++) fixed.step(.004, .65, .07, -.08, .06);
        for (int i = 0; i < 600; i++) frames.step(1.0 / 60, .65, .07, -.08, .06);
        double[] a = values(fixed.state()), b = values(frames.state());
        for (int i = 0; i < a.length; i++) close(a[i], b[i], 1e-8, "Insane frame consistency " + i);
    }

    private static void validationAndExtremes() {
        rejects(() -> new FlightProfile.UnitMetadata(Double.NaN, 1, 100, true), "nonfinite unit speed");
        rejects(() -> new FlightProfile.UnitMetadata(1, 0, 100, true), "zero hit size");
        rejects(() -> new FlightProfile.UnitMetadata(1, 1, -1, true), "negative max HP");
        rejects(() -> resolve(1, 401, 1, 1, 1, 100, 1), "out-of-budget speed reference");
        rejects(() -> resolve(0, 100, 1, 1, 1, 100, 1), "zero power");
        rejects(() -> resolve(1, 100, Double.NaN, 1, 1, 100, 1), "nonfinite mass modifier");
        rejects(() -> resolve(1, 100, 1, 1, -1, 100, 1), "negative response");
        rejects(() -> FlightProfile.preset(4), "Custom is not an implicit fixed preset");
        FlightProfile.UnitMetadata exotic = new FlightProfile.UnitMetadata(Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, true);
        FlightProfile resolved = FlightProfile.resolve(base, exotic, new FlightProfile.Modifiers(20, 400, 10, .1, .25, 1200, 2.5));
        check(Double.isFinite(resolved.massKg()) && resolved.massProxy() <= PerformanceConfig.MAX_MASS_SCALE,
                "extreme finite content mass remains bounded");
        check(resolved.practicalSpeedMps() <= 400, "extreme content speed reference remains bounded");
        FlightController controller = new FlightController(resolved.profile());
        for (int i = 0; i < 2500; i++) controller.step(.004, .7, .3, -.2, .4);
        for (double value : values(controller.state())) check(Double.isFinite(value), "extreme profile remains finite");
        FlightProfile stopped = FlightProfile.resolve(base, new FlightProfile.UnitMetadata(0, .01, .01, false), FlightProfile.preset(0));
        check(stopped.massProxy() >= PerformanceConfig.MIN_MASS_SCALE && stopped.agility() >= PerformanceConfig.MIN_AGILITY,
                "stationary tiny content retains finite positive physical parameters");
    }

    private static FlightProfile resolve(double power, double speed, double mass, double drag,
                                         double response, double acceleration, double rates) {
        return FlightProfile.resolve(base, reference,
                new FlightProfile.Modifiers(power, speed, mass, drag, response, acceleration, rates));
    }

    private static double sustainedSpeed(FlightController.Profile profile) {
        FlightController controller = new FlightController(profile);
        double tilt = -Math.acos(profile.gravityMps2 / (profile.maxThrustNewtons / profile.massKg));
        controller.state().qx = Math.sin(tilt / 2);
        controller.state().qw = Math.cos(tilt / 2);
        for (int i = 0; i < 6000; i++) controller.step(profile.fixedStepSeconds, 1, 0, 0, 0);
        return Math.hypot(controller.state().vx, controller.state().vz) * profile.metersPerTile;
    }

    private static void sameState(FlightController.State a, FlightController.State b, String label) {
        double[] left = values(a), right = values(b);
        for (int i = 0; i < left.length; i++) close(left[i], right[i], 0, label + " component " + i);
    }

    private static double[] values(FlightController.State s) {
        return new double[]{s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz};
    }

    private static void rejects(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label + " accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void close(double actual, double expected, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + ": expected " + expected + ", actual " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
