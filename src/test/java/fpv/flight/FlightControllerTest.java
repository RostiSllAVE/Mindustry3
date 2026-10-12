package fpv.flight;

import fpv.generated.FlightProfiles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Standalone deterministic checks; throws on failure, without test libraries. */
public final class FlightControllerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        FlightController.Profile profile = FlightProfiles.create();
        gravityAndUnits(profile);
        hoverAndMotorLag(profile);
        acroDoesNotLevel(profile);
        axesAndReset(profile);
        timestepConsistency(profile);
        boundedFrameTime(profile);
        inertiaAndTorqueLimit(profile);
        finiteAndInputValidation(profile);
        profileValidation(profile);
        configurableRateAuthority(profile);
        configurableExpo(profile);
        reconfigurationPreservesState(profile);
        configurableRateValidation(profile);
        substepObserverPath(profile);
        substepObserverTiming(profile);
        substepObserverCorrection(profile);
        System.out.println("FlightController: " + checks + " assertions passed (pure model; no game/runtime flight-feel claim).");
    }

    private static void gravityAndUnits(FlightController.Profile profile) {
        FlightController.Profile vacuum = environment(profile, profile.gravityMps2, 0.0, 0.0, 0.0);
        FlightController controller = new FlightController(vacuum);
        controller.reset(3.0, 100.0, -4.0, 0.0);
        int count = 250;
        double h = profile.fixedStepSeconds;
        for (int i = 0; i < count; i++) controller.step(h, 0.0, 0.0, 0.0, 0.0);
        double gravityTiles = profile.gravityMps2 / profile.metersPerTile;
        close(controller.state().vy, -gravityTiles * h * count, 1e-11, "freefall velocity in tiles/second");
        close(controller.state().y, 100.0 - gravityTiles * h * h * count * (count + 1) * 0.5,
                1e-10, "semi-implicit freefall position");
        close(controller.state().x, 3.0, 0.0, "gravity does not move X");
        close(controller.state().z, -4.0, 0.0, "gravity does not move Z");
    }

    private static void hoverAndMotorLag(FlightController.Profile profile) {
        check(profile.hoverThrottle() > 0.0 && profile.hoverThrottle() < 1.0, "profile can hover");
        FlightController controller = new FlightController(profile);
        controller.reset(0.0, 50.0, 0.0, 0.0);
        controller.step(profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.0, 0.0);
        check(controller.state().vy < 0.0, "reset starts with motor lag rather than instant hover thrust");
        run(controller, 3.0, profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.0, 0.0);
        // Remove initial spin-up drift without resetting motors, then test equilibrium.
        controller.state().y = 50.0;
        controller.state().vy = 0.0;
        run(controller, 3.0, profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.0, 0.0);
        close(controller.state().y, 50.0, 1e-9, "balanced thrust holds altitude after spin-up");
        close(controller.state().vy, 0.0, 1e-9, "hover has no persistent vertical acceleration");
    }

    private static void acroDoesNotLevel(FlightController.Profile profile) {
        FlightController controller = new FlightController(profile);
        controller.reset(0.0, 100.0, 0.0, 0.0);
        run(controller, 0.4, profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.35, 0.0);
        run(controller, 2.0, profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.0, 0.0);
        double[] up = new double[3];
        controller.getUp(up);
        check(up[0] > 0.1 && up[1] < 0.99, "positive roll remains a right bank after sticks center");
        check(Math.abs(controller.state().wz) < 1e-8, "centered roll stops angular rotation");
        double[] attitude = {controller.state().qx, controller.state().qy, controller.state().qz, controller.state().qw};
        run(controller, 1.0, profile.fixedStepSeconds, profile.hoverThrottle(), 0.0, 0.0, 0.0);
        close(controller.state().qx, attitude[0], 1e-8, "neutral does not level qx");
        close(controller.state().qy, attitude[1], 1e-8, "neutral does not level qy");
        close(controller.state().qz, attitude[2], 1e-8, "neutral does not level qz");
        close(controller.state().qw, attitude[3], 1e-8, "neutral does not level qw");
        check(controller.state().vx > 0.0, "banked collective thrust accelerates laterally");
    }

    private static void axesAndReset(FlightController.Profile profile) {
        FlightController controller = new FlightController(profile);
        double[] forward = new double[3];
        double[] up = new double[3];
        controller.reset(0.0, 0.0, 0.0, 90.0);
        controller.getForward(forward);
        controller.getUp(up);
        close(forward[0], 1.0, 1e-12, "reset yaw +90 points right");
        close(forward[2], 0.0, 1e-12, "reset yaw +90 removes forward Z");
        close(up[1], 1.0, 1e-12, "yaw retains up");
        controller.reset(0.0, 0.0, 0.0, 0.0);
        run(controller, 1.0, profile.fixedStepSeconds, 0.0, 0.0, 0.0, 0.25);
        controller.getForward(forward);
        controller.getUp(up);
        check(forward[0] > 0.1 && forward[2] < 0.0, "positive yaw turns right from -Z");
        close(up[1], 1.0, 1e-12, "yaw command retains up");
        controller.reset(0.0, 0.0, 0.0, 0.0);
        run(controller, 0.4, profile.fixedStepSeconds, 0.0, 0.25, 0.0, 0.0);
        controller.getForward(forward);
        check(forward[1] > 0.1, "positive pitch raises the nose");
        controller.reset(4.0, 5.0, 6.0, 0.0);
        close(controller.state().vx + controller.state().vy + controller.state().vz, 0.0, 0.0, "reset clears velocity");
        close(controller.state().wx + controller.state().wy + controller.state().wz, 0.0, 0.0, "reset clears rotation");
    }

    private static void timestepConsistency(FlightController.Profile profile) {
        FlightController reference = new FlightController(profile);
        FlightController frames125 = new FlightController(profile);
        FlightController frames60 = new FlightController(profile);
        reference.reset(2.0, 20.0, -3.0, 15.0);
        frames125.reset(2.0, 20.0, -3.0, 15.0);
        frames60.reset(2.0, 20.0, -3.0, 15.0);
        run(reference, 5.0, 1.0 / 250.0, 0.42, 0.1, -0.15, 0.2);
        run(frames125, 5.0, 1.0 / 125.0, 0.42, 0.1, -0.15, 0.2);
        run(frames60, 5.0, 1.0 / 60.0, 0.42, 0.1, -0.15, 0.2);
        sameState(reference.state(), frames125.state(), 1e-10, "125 Hz frame delivery");
        sameState(reference.state(), frames60.state(), 1e-10, "60 Hz frame delivery");
    }

    private static void boundedFrameTime(FlightController.Profile profile) {
        FlightController stalled = new FlightController(profile);
        FlightController bounded = new FlightController(profile);
        stalled.step(1000.0, 0.3, 0.2, 0.1, -0.2);
        bounded.step(profile.maxFrameSeconds, 0.3, 0.2, 0.1, -0.2);
        sameState(stalled.state(), bounded.state(), 0.0, "stall discards excess elapsed time");
        FlightController zero = new FlightController(profile);
        zero.step(0.0, 1.0, 1.0, 1.0, 1.0);
        sameState(zero.state(), new FlightController(profile).state(), 0.0, "zero dt does not advance");
    }

    private static void inertiaAndTorqueLimit(FlightController.Profile p) {
        FlightController.Profile heavierPitch = new FlightController.Profile(p.metersPerTile, p.massKg,
                p.inertiaXKgM2 * 1.5, p.inertiaYKgM2, p.inertiaZKgM2, p.maxThrustNewtons,
                p.gravityMps2, p.linearDragPerSecond, p.quadraticDragPerMeter, p.angularDragPerSecond,
                p.motorTimeConstantSeconds, p.rateResponseSeconds, p.maxTorqueNewtonMeters,
                p.pitchRateDegreesPerSecond, p.rollRateDegreesPerSecond, p.yawRateDegreesPerSecond,
                p.rateExpo, p.fixedStepSeconds, p.maxFrameSeconds);
        FlightController original = new FlightController(p);
        FlightController heavier = new FlightController(heavierPitch);
        original.step(p.fixedStepSeconds, 0.0, 1.0, 0.0, 0.0);
        heavier.step(p.fixedStepSeconds, 0.0, 1.0, 0.0, 0.0);
        check(heavier.state().wx < original.state().wx, "higher inertia slows torque-limited pitch acceleration");
        check(original.state().wx <= p.maxTorqueNewtonMeters / p.inertiaXKgM2 * p.fixedStepSeconds,
                "angular acceleration respects torque bound");
    }

    private static void finiteAndInputValidation(FlightController.Profile p) {
        FlightController controller = new FlightController(p);
        for (int i = 0; i < 15000; i++) {
            controller.step(p.fixedStepSeconds, 0.5 + 0.5 * Math.sin(i * 0.013),
                    Math.sin(i * 0.017), Math.cos(i * 0.019), Math.sin(i * 0.023));
        }
        for (double value : values(controller.state())) check(Double.isFinite(value), "long acro sequence stays finite");
        FlightController.State s = controller.state();
        close(s.qx*s.qx + s.qy*s.qy + s.qz*s.qz + s.qw*s.qw, 1.0, 1e-12, "attitude remains normalized");
        FlightController clamped = new FlightController(p);
        FlightController limited = new FlightController(p);
        clamped.step(0.1, 2.0, -2.0, 2.0, -2.0);
        limited.step(0.1, 1.0, -1.0, 1.0, -1.0);
        sameState(clamped.state(), limited.state(), 0.0, "finite out-of-range commands clamp");
        rejects(() -> controller.step(-1.0, 0.0, 0.0, 0.0, 0.0), "negative dt");
        rejects(() -> controller.step(Double.NaN, 0.0, 0.0, 0.0, 0.0), "NaN dt");
        rejects(() -> controller.step(0.01, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0), "infinite throttle");
        rejects(() -> controller.step(0.01, 0.0, Double.NaN, 0.0, 0.0), "NaN pitch");
        rejects(() -> controller.reset(0.0, 0.0, Double.NaN, 0.0), "NaN reset position");
    }

    private static void profileValidation(FlightController.Profile p) throws Exception {
        // Test each public scalar independently without duplicating tuning defaults.
        String[] names = {"metersPerTile", "massKg", "inertiaXKgM2", "inertiaYKgM2", "inertiaZKgM2",
                "maxThrustNewtons", "gravityMps2", "linearDragPerSecond", "quadraticDragPerMeter",
                "angularDragPerSecond", "motorTimeConstantSeconds", "rateResponseSeconds", "maxTorqueNewtonMeters",
                "pitchRateDegreesPerSecond", "rollRateDegreesPerSecond", "yawRateDegreesPerSecond", "rateExpo",
                "fixedStepSeconds", "maxFrameSeconds"};
        Class<?>[] types = new Class<?>[names.length];
        Arrays.fill(types, double.class);
        Constructor<FlightController.Profile> constructor = FlightController.Profile.class.getConstructor(types);
        Object[] parameters = new Object[names.length];
        for (int i = 0; i < names.length; i++) parameters[i] = FlightController.Profile.class.getField(names[i]).getDouble(p);
        for (int i = 0; i < parameters.length; i++) {
            Object old = parameters[i];
            parameters[i] = Double.NaN;
            rejectedProfile(constructor, parameters, names[i] + " NaN");
            parameters[i] = old;
        }
        parameters[1] = 0.0;
        rejectedProfile(constructor, parameters, "zero mass");
        parameters[1] = p.massKg;
        parameters[16] = 1.1;
        rejectedProfile(constructor, parameters, "expo outside unit interval");
        parameters[16] = p.rateExpo;
        parameters[18] = p.fixedStepSeconds * 1001.0;
        rejectedProfile(constructor, parameters, "excessive step budget");
    }

    private static void configurableRateAuthority(FlightController.Profile profile) {
        // Remove angular drag so convergence can be checked against physical units.
        FlightController.Profile p = environment(profile, 0.0, 0.0, 0.0, 0.0);
        for (int axis = 0; axis < 3; axis++) {
            FlightController slow = new FlightController(p);
            FlightController fast = new FlightController(p);
            slow.configureRates(20.0, 20.0, 20.0, 0.0);
            fast.configureRates(400.0, 400.0, 400.0, 0.0);
            commandAxis(slow, p, axis, 1.0);
            commandAxis(fast, p, axis, 1.0);
            close(commandedRate(slow, axis), 20.0, 1e-7, "20 deg/s authority on axis " + axis);
            close(commandedRate(fast, axis), 400.0, 1e-7, "400 deg/s authority on axis " + axis);
            check(commandedRate(fast, axis) > commandedRate(slow, axis) * 19.0,
                    "configured rate materially changes axis " + axis);
        }
        double[] distinctRates = {20.0, 80.0, 400.0};
        for (int axis = 0; axis < 3; axis++) {
            FlightController controller = new FlightController(p);
            controller.configureRates(distinctRates[0], distinctRates[1], distinctRates[2], 0.0);
            controller.reset(0.0, 20.0, 0.0, 0.0);
            commandAxis(controller, p, axis, 1.0);
            close(commandedRate(controller, axis), distinctRates[axis], 1e-7,
                    "each axis uses its own configured rate, retained through reset: " + axis);
        }
    }

    private static void configurableExpo(FlightController.Profile profile) {
        FlightController.Profile p = environment(profile, 0.0, 0.0, 0.0, 0.0);
        for (int axis = 0; axis < 3; axis++) {
            FlightController linear = new FlightController(p);
            FlightController cubic = new FlightController(p);
            linear.configureRates(400.0, 400.0, 400.0, 0.0);
            cubic.configureRates(400.0, 400.0, 400.0, 1.0);
            commandAxis(linear, p, axis, 0.5);
            commandAxis(cubic, p, axis, 0.5);
            close(commandedRate(linear, axis), 200.0, 1e-7, "linear half stick axis " + axis);
            close(commandedRate(cubic, axis), 50.0, 1e-7, "cubic half stick axis " + axis);
            linear.reset(0.0, 0.0, 0.0, 0.0);
            cubic.reset(0.0, 0.0, 0.0, 0.0);
            commandAxis(linear, p, axis, -1.0);
            commandAxis(cubic, p, axis, -1.0);
            close(commandedRate(cubic, axis), -400.0, 1e-7, "negative full-stick authority axis " + axis);
            sameState(linear.state(), cubic.state(), 0.0, "expo leaves full-stick response unchanged axis " + axis);
        }
    }

    private static void reconfigurationPreservesState(FlightController.Profile p) {
        FlightController reference = new FlightController(p);
        FlightController configured = new FlightController(p);
        run(reference, 0.5, p.fixedStepSeconds, 0.6, 0.2, -0.3, 0.1);
        run(configured, 0.5, p.fixedStepSeconds, 0.6, 0.2, -0.3, 0.1);
        // Preserve hidden integration memory too: the next half-step completes
        // one pending step while motors are already running.
        reference.step(p.fixedStepSeconds * 0.5, 0.6, 0.2, -0.3, 0.1);
        configured.step(p.fixedStepSeconds * 0.5, 0.6, 0.2, -0.3, 0.1);
        configured.configureRates(20.0, 80.0, 400.0, 1.0);
        sameState(reference.state(), configured.state(), 0.0, "changing rates retains current pose and momentum");
        configured.configureRates(p.pitchRateDegreesPerSecond, p.rollRateDegreesPerSecond,
                p.yawRateDegreesPerSecond, p.rateExpo);
        reference.step(p.fixedStepSeconds * 0.5, 0.4, -0.1, 0.2, -0.3);
        configured.step(p.fixedStepSeconds * 0.5, 0.4, -0.1, 0.2, -0.3);
        sameState(reference.state(), configured.state(), 0.0,
                "reconfiguration retains motor lag, fractional time and original profile defaults");
    }

    private static void configurableRateValidation(FlightController.Profile p) {
        FlightController configured = new FlightController(p);
        FlightController reference = new FlightController(p);
        configured.configureRates(20.0, 80.0, 400.0, 0.5);
        reference.configureRates(20.0, 80.0, 400.0, 0.5);
        double[][] invalid = {
                {0.0, 80.0, 400.0, 0.5}, {20.0, -1.0, 400.0, 0.5}, {20.0, 80.0, 0.0, 0.5},
                {Double.NaN, 80.0, 400.0, 0.5}, {20.0, Double.NaN, 400.0, 0.5},
                {20.0, 80.0, Double.NaN, 0.5}, {Double.POSITIVE_INFINITY, 80.0, 400.0, 0.5},
                {20.0, Double.NEGATIVE_INFINITY, 400.0, 0.5}, {20.0, 80.0, Double.POSITIVE_INFINITY, 0.5},
                {200.0, 300.0, 600.0, -0.1}, {200.0, 300.0, 600.0, 1.1},
                {200.0, 300.0, 600.0, Double.NaN}, {200.0, 300.0, 600.0, Double.POSITIVE_INFINITY}
        };
        for (double[] values : invalid) {
            rejects(() -> configured.configureRates(values[0], values[1], values[2], values[3]),
                    "invalid rate update " + Arrays.toString(values));
        }
        run(configured, 0.5, p.fixedStepSeconds, 0.4, 0.2, 0.3, 0.4);
        run(reference, 0.5, p.fixedStepSeconds, 0.4, 0.2, 0.3, 0.4);
        sameState(reference.state(), configured.state(), 0.0, "invalid updates preserve all previous rates");
    }

    private static void substepObserverPath(FlightController.Profile p) {
        FlightController plain = new FlightController(p);
        FlightController observed = new FlightController(p);
        plain.reset(3.0, 40.0, -2.0, 12.0);
        observed.reset(3.0, 40.0, -2.0, 12.0);
        plain.state().vx = observed.state().vx = 4.0;
        double[] previous = {3.0, 40.0, -2.0};
        double[] middle = new double[3];
        int[] count = {0};
        int expectedSteps = (int)Math.round(p.maxFrameSeconds / p.fixedStepSeconds);
        int middleStep = expectedSteps / 2;
        plain.step(p.maxFrameSeconds, 1.0, 1.0, 0.3, 0.2);
        observed.step(p.maxFrameSeconds, 1.0, 1.0, 0.3, 0.2,
                (state, oldX, oldY, oldZ, stepSeconds, elapsedWithinFrame) -> {
                    check(state == observed.state(), "observer reuses public state without a snapshot allocation");
                    close(oldX, previous[0], 0.0, "segment begins at previous X");
                    close(oldY, previous[1], 0.0, "segment begins at previous Y");
                    close(oldZ, previous[2], 0.0, "segment begins at previous Z");
                    close(stepSeconds, p.fixedStepSeconds, 0.0, "observer fixed duration");
                    close(elapsedWithinFrame, count[0] * p.fixedStepSeconds, 0.0, "segment start time");
                    previous[0] = state.x;
                    previous[1] = state.y;
                    previous[2] = state.z;
                    if (++count[0] == middleStep) {
                        middle[0] = state.x;
                        middle[1] = state.y;
                        middle[2] = state.z;
                    }
                });
        check(count[0] == expectedSteps, "one observer call per bounded fixed step");
        sameState(plain.state(), observed.state(), 0.0, "no-op observer retains exact old behavior");
        double mx = middle[0] - 3.0, my = middle[1] - 40.0, mz = middle[2] + 2.0;
        double ex = previous[0] - 3.0, ey = previous[1] - 40.0, ez = previous[2] + 2.0;
        double cx = my * ez - mz * ey, cy = mz * ex - mx * ez, cz = mx * ey - my * ex;
        check(cx * cx + cy * cy + cz * cz > 1e-12,
                "observer exposes curved intermediate positions that differ from the whole-frame chord");
    }

    private static void substepObserverTiming(FlightController.Profile p) {
        FlightController c = new FlightController(p);
        int[] count = {0};
        double[] starts = new double[2];
        FlightController.StepObserver observer = (state, oldX, oldY, oldZ, h, elapsed) -> {
            if (count[0] < starts.length) starts[count[0]] = elapsed;
            count[0]++;
        };
        c.step(0.0, 0.0, 0.0, 0.0, 0.0, observer);
        c.step(p.fixedStepSeconds * 0.5, 0.0, 0.0, 0.0, 0.0, observer);
        check(count[0] == 0, "zero/fractional frames do not notify a nonexistent segment");
        c.step(p.fixedStepSeconds * 2.0, 0.0, 0.0, 0.0, 0.0, observer);
        check(count[0] == 2, "carried half step gives two complete callbacks");
        close(starts[0], 0.0, 0.0, "straddling first segment starts at current-frame zero");
        close(starts[1], p.fixedStepSeconds * 0.5, 1e-16, "elapsed excludes previous-frame remainder");
        c.step(p.fixedStepSeconds * 0.5, 0.0, 0.0, 0.0, 0.0, observer);
        check(count[0] == 3, "observer retains fractional integration memory");
        c.reset(0.0, 0.0, 0.0, 0.0);
        count[0] = 0;
        c.step(p.maxFrameSeconds * 100.0, 0.0, 0.0, 0.0, 0.0, observer);
        check(count[0] == (int)Math.round(p.maxFrameSeconds / p.fixedStepSeconds),
                "observer does not bypass stalled-frame catch-up bound");
    }

    private static void substepObserverCorrection(FlightController.Profile p) {
        FlightController c = new FlightController(environment(p, 0.0, 0.0, 0.0, 0.0));
        c.state().vx = 10.0;
        int[] count = {0};
        c.step(p.fixedStepSeconds * 3.0, 0.0, 0.0, 0.0, 0.0,
                (state, oldX, oldY, oldZ, h, elapsed) -> {
                    if (count[0]++ == 0) {
                        close(oldX, 0.0, 0.0, "first segment retains original start");
                        close(state.x, 10.0 * h, 0.0, "first segment integrates before collision callback");
                        state.x = 5.0;
                        state.vx = 0.0;
                    } else {
                        close(oldX, 5.0, 0.0, "next segment starts at corrected contact position");
                        close(state.x, 5.0, 0.0, "next integration respects corrected velocity");
                    }
                });
        check(count[0] == 3, "correction does not drop remaining fixed steps");
        close(c.state().x, 5.0, 0.0, "final state retains observer contact correction");
        close(c.state().vx, 0.0, 0.0, "final state retains observer velocity correction");
        try {
            c.step(p.fixedStepSeconds * 2.0, 0.0, 0.0, 0.0, 0.0,
                    (state, oldX, oldY, oldZ, h, elapsed) -> state.vx = Double.NaN);
            throw new AssertionError("nonfinite callback state accepted");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void commandAxis(FlightController controller, FlightController.Profile p,
                                    int axis, double input) {
        run(controller, 2.0, p.fixedStepSeconds, 0.0,
                axis == 0 ? input : 0.0, axis == 1 ? input : 0.0, axis == 2 ? input : 0.0);
    }

    private static double commandedRate(FlightController controller, int axis) {
        FlightController.State state = controller.state();
        return Math.toDegrees(axis == 0 ? state.wx : axis == 1 ? -state.wz : -state.wy);
    }

    private static void rejectedProfile(Constructor<FlightController.Profile> constructor, Object[] parameters,
                                        String label) throws Exception {
        try {
            constructor.newInstance(parameters);
            throw new AssertionError(label + " accepted");
        } catch (InvocationTargetException ex) {
            check(ex.getCause() instanceof IllegalArgumentException, label + " rejected explicitly");
        }
    }

    private static FlightController.Profile environment(FlightController.Profile p, double gravity,
                                                        double linear, double quadratic, double angular) {
        return new FlightController.Profile(p.metersPerTile, p.massKg, p.inertiaXKgM2, p.inertiaYKgM2,
                p.inertiaZKgM2, p.maxThrustNewtons, gravity, linear, quadratic, angular,
                p.motorTimeConstantSeconds, p.rateResponseSeconds, p.maxTorqueNewtonMeters,
                p.pitchRateDegreesPerSecond, p.rollRateDegreesPerSecond, p.yawRateDegreesPerSecond,
                p.rateExpo, p.fixedStepSeconds, p.maxFrameSeconds);
    }

    private static void run(FlightController controller, double seconds, double dt,
                            double throttle, double pitch, double roll, double yaw) {
        int frames = (int)Math.round(seconds / dt);
        for (int i = 0; i < frames; i++) controller.step(dt, throttle, pitch, roll, yaw);
    }

    private static double[] values(FlightController.State s) {
        return new double[]{s.x, s.y, s.z, s.vx, s.vy, s.vz, s.qx, s.qy, s.qz, s.qw, s.wx, s.wy, s.wz};
    }

    private static void sameState(FlightController.State left, FlightController.State right, double tolerance, String label) {
        double[] a = values(left), b = values(right);
        for (int i = 0; i < a.length; i++) close(a[i], b[i], tolerance, label + " component " + i);
    }

    private static void rejects(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void close(double actual, double expected, double tolerance, String label) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
