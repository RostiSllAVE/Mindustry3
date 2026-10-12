package fpv.flight;

/**
 * Original, dependency-free prototype acro flight model.
 *
 * <p>World axes are right-handed, Y-up, with local forward -Z. Position and
 * linear velocity use tile units; body angular velocity uses radians/second.
 * Positive pitch raises the nose, positive roll banks right, and positive yaw
 * turns right. This is independent project code, not Liftoff physics.</p>
 */
public final class FlightController {
    /** Numeric tuning comes from design/flight.sheet.json via generated code. */
    public static final class Profile {
        public final double metersPerTile;
        public final double massKg;
        public final double inertiaXKgM2;
        public final double inertiaYKgM2;
        public final double inertiaZKgM2;
        public final double maxThrustNewtons;
        public final double gravityMps2;
        public final double linearDragPerSecond;
        public final double quadraticDragPerMeter;
        public final double angularDragPerSecond;
        public final double motorTimeConstantSeconds;
        public final double rateResponseSeconds;
        public final double maxTorqueNewtonMeters;
        public final double pitchRateDegreesPerSecond;
        public final double rollRateDegreesPerSecond;
        public final double yawRateDegreesPerSecond;
        public final double rateExpo;
        public final double fixedStepSeconds;
        public final double maxFrameSeconds;

        public Profile(double metersPerTile, double massKg,
                       double inertiaXKgM2, double inertiaYKgM2, double inertiaZKgM2,
                       double maxThrustNewtons, double gravityMps2,
                       double linearDragPerSecond, double quadraticDragPerMeter,
                       double angularDragPerSecond, double motorTimeConstantSeconds,
                       double rateResponseSeconds, double maxTorqueNewtonMeters,
                       double pitchRateDegreesPerSecond, double rollRateDegreesPerSecond,
                       double yawRateDegreesPerSecond, double rateExpo,
                       double fixedStepSeconds, double maxFrameSeconds) {
            this.metersPerTile = positive("metersPerTile", metersPerTile);
            this.massKg = positive("massKg", massKg);
            this.inertiaXKgM2 = positive("inertiaXKgM2", inertiaXKgM2);
            this.inertiaYKgM2 = positive("inertiaYKgM2", inertiaYKgM2);
            this.inertiaZKgM2 = positive("inertiaZKgM2", inertiaZKgM2);
            this.maxThrustNewtons = positive("maxThrustNewtons", maxThrustNewtons);
            this.gravityMps2 = nonnegative("gravityMps2", gravityMps2);
            this.linearDragPerSecond = nonnegative("linearDragPerSecond", linearDragPerSecond);
            this.quadraticDragPerMeter = nonnegative("quadraticDragPerMeter", quadraticDragPerMeter);
            this.angularDragPerSecond = nonnegative("angularDragPerSecond", angularDragPerSecond);
            this.motorTimeConstantSeconds = positive("motorTimeConstantSeconds", motorTimeConstantSeconds);
            this.rateResponseSeconds = positive("rateResponseSeconds", rateResponseSeconds);
            this.maxTorqueNewtonMeters = positive("maxTorqueNewtonMeters", maxTorqueNewtonMeters);
            this.pitchRateDegreesPerSecond = positive("pitchRateDegreesPerSecond", pitchRateDegreesPerSecond);
            this.rollRateDegreesPerSecond = positive("rollRateDegreesPerSecond", rollRateDegreesPerSecond);
            this.yawRateDegreesPerSecond = positive("yawRateDegreesPerSecond", yawRateDegreesPerSecond);
            this.rateExpo = nonnegative("rateExpo", rateExpo);
            this.fixedStepSeconds = positive("fixedStepSeconds", fixedStepSeconds);
            this.maxFrameSeconds = positive("maxFrameSeconds", maxFrameSeconds);
            if (rateExpo > 1.0) throw new IllegalArgumentException("rateExpo must be at most one");
            if (fixedStepSeconds > rateResponseSeconds) {
                throw new IllegalArgumentException("fixedStepSeconds must not exceed rateResponseSeconds");
            }
            if (maxFrameSeconds < fixedStepSeconds || maxFrameSeconds / fixedStepSeconds > 1000.0) {
                throw new IllegalArgumentException("Frame budget must contain between 1 and 1000 fixed steps");
            }
            if (inertiaXKgM2 > inertiaYKgM2 + inertiaZKgM2
                    || inertiaYKgM2 > inertiaXKgM2 + inertiaZKgM2
                    || inertiaZKgM2 > inertiaXKgM2 + inertiaYKgM2) {
                throw new IllegalArgumentException("Principal inertias must satisfy the triangle inequalities");
            }
        }

        /** Equilibrium collective throttle for a level craft after motor spin-up. */
        public double hoverThrottle() {
            return massKg * gravityMps2 / maxThrustNewtons;
        }
    }

    /** Mutable game-adapter boundary. wx/wy/wz are body-axis radians/second. */
    public static final class State {
        public double x, y, z;
        public double vx, vy, vz;
        public double qx, qy, qz, qw = 1.0;
        public double wx, wy, wz;
    }

    /** Called synchronously after integration, before the next fixed step. */
    @FunctionalInterface
    public interface StepObserver {
        void afterStep(State state, double oldX, double oldY, double oldZ,
                       double stepSeconds, double elapsedWithinFrame);
    }

    private Profile profile;
    private final State state = new State();
    private double accumulator;
    private double motorThrottle;
    private double pitchRateDegreesPerSecond;
    private double rollRateDegreesPerSecond;
    private double yawRateDegreesPerSecond;
    private double rateExpo;

    public FlightController(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is required");
        this.profile = profile;
        configureRates(profile.pitchRateDegreesPerSecond, profile.rollRateDegreesPerSecond,
                profile.yawRateDegreesPerSecond, profile.rateExpo);
    }

    public State state() {
        return state;
    }

    /** Current immutable tuning; gameplay units and clocks remain owned by the adapter. */
    public Profile profile() {
        return profile;
    }

    /**
     * Apply physical tuning on the simulation thread without resetting flight.
     * Coordinate scale and integration clocks cannot change under existing state.
     * Motor spool, momentum, attitude and fractional elapsed time are retained.
     */
    public void configureProfile(Profile replacement) {
        if (replacement == null) throw new IllegalArgumentException("profile is required");
        if (replacement.metersPerTile != profile.metersPerTile
                || replacement.fixedStepSeconds != profile.fixedStepSeconds
                || replacement.maxFrameSeconds != profile.maxFrameSeconds) {
            throw new IllegalArgumentException("Live profile changes must retain scale and integration clocks");
        }
        configureRates(replacement.pitchRateDegreesPerSecond, replacement.rollRateDegreesPerSecond,
                replacement.yawRateDegreesPerSecond, replacement.rateExpo);
        profile = replacement;
    }

    /**
     * Update the original cubic rate curve on the owning simulation thread.
     * All values are validated before applying the update. Pose, momentum,
     * motor response and fractional elapsed time remain unchanged; the new
     * curve takes effect on the next integrated step. Reset retains this tuning.
     * These values are not an imported Betaflight or Liftoff profile.
     */
    public void configureRates(double pitchDegreesPerSecond, double rollDegreesPerSecond,
                               double yawDegreesPerSecond, double expo) {
        positive("pitchDegreesPerSecond", pitchDegreesPerSecond);
        positive("rollDegreesPerSecond", rollDegreesPerSecond);
        positive("yawDegreesPerSecond", yawDegreesPerSecond);
        nonnegative("expo", expo);
        if (expo > 1.0) throw new IllegalArgumentException("expo must be at most one");
        pitchRateDegreesPerSecond = pitchDegreesPerSecond;
        rollRateDegreesPerSecond = rollDegreesPerSecond;
        yawRateDegreesPerSecond = yawDegreesPerSecond;
        rateExpo = expo;
    }

    /** Reset integration memory and pose; retain configured rates. Motors start spooled down. */
    public void reset(double x, double y, double z, double yawDegrees) {
        finite("x", x);
        finite("y", y);
        finite("z", z);
        finite("yawDegrees", yawDegrees);
        state.x = x;
        state.y = y;
        state.z = z;
        state.vx = state.vy = state.vz = 0.0;
        state.wx = state.wy = state.wz = 0.0;
        double halfYaw = -Math.toRadians(yawDegrees % 360.0) * 0.5;
        state.qx = state.qz = 0.0;
        state.qy = Math.sin(halfYaw);
        state.qw = Math.cos(halfYaw);
        accumulator = motorThrottle = 0.0;
    }

    /**
     * Accumulate elapsed seconds and integrate at the profile's fixed timestep.
     * Excess frame time above maxFrameSeconds is discarded to bound catch-up.
     * A call's clamped inputs apply to every fixed step processed by that call.
     * During Build Mode the integration layer should simply not call this method.
     */
    public void step(double dt, double throttle, double pitch, double roll, double yaw) {
        step(dt, throttle, pitch, roll, yaw, null);
    }

    /**
     * Integrate and expose each fixed-step segment for allocation-free collision
     * handling. The observer may correct finite position and velocity before the
     * next step; it must not call step/reset/configure or change angular state.
     * elapsedWithinFrame is the segment's start time in this accepted frame,
     * excluding carried fractional time. A first step spanning a previous call's
     * remainder reports zero; stepSeconds still reports the full fixed step.
     * Observer exceptions propagate and nonfinite corrections fail immediately.
     */
    public void step(double dt, double throttle, double pitch, double roll, double yaw,
                     StepObserver observer) {
        nonnegative("dt", dt);
        finite("throttle", throttle);
        finite("pitch", pitch);
        finite("roll", roll);
        finite("yaw", yaw);
        if (dt == 0.0) return;
        requireFiniteState();
        normalizeAttitude();
        throttle = clamp(throttle, 0.0, 1.0);
        double targetX = Math.toRadians(pitchRateDegreesPerSecond) * rateCurve(pitch);
        double targetY = -Math.toRadians(yawRateDegreesPerSecond) * rateCurve(yaw);
        double targetZ = -Math.toRadians(rollRateDegreesPerSecond) * rateCurve(roll);
        double h = profile.fixedStepSeconds;
        double carriedRemainder = accumulator;
        accumulator += Math.min(dt, profile.maxFrameSeconds);
        // A few ulps prevent identical elapsed time yielding one fewer step at
        // frame rates that cannot represent h exactly in binary floating point.
        double epsilon = Math.ulp(profile.maxFrameSeconds) * 8.0;
        int steps = (int)Math.floor((accumulator + epsilon) / h);
        accumulator -= steps * h;
        if (accumulator < 0.0) accumulator = 0.0;
        for (int i = 0; i < steps; i++) {
            double oldX = state.x, oldY = state.y, oldZ = state.z;
            integrate(h, throttle, targetX, targetY, targetZ);
            if (observer != null) {
                observer.afterStep(state, oldX, oldY, oldZ, h,
                        Math.max(0.0, i * h - carriedRemainder));
                requireFiniteState();
            }
        }
        requireFiniteState();
    }

    public void getForward(double[] output) {
        rotateLocal(0.0, 0.0, -1.0, output);
    }

    public void getUp(double[] output) {
        rotateLocal(0.0, 1.0, 0.0, output);
    }

    /** Rotate a local vector using the current attitude without allocating. */
    public void rotateLocal(double x, double y, double z, double[] output) {
        if (output == null || output.length < 3) {
            throw new IllegalArgumentException("output must have at least three elements");
        }
        double tx = 2.0 * (state.qy * z - state.qz * y);
        double ty = 2.0 * (state.qz * x - state.qx * z);
        double tz = 2.0 * (state.qx * y - state.qy * x);
        output[0] = x + state.qw * tx + state.qy * tz - state.qz * ty;
        output[1] = y + state.qw * ty + state.qz * tx - state.qx * tz;
        output[2] = z + state.qw * tz + state.qx * ty - state.qy * tx;
    }

    private void integrate(double h, double throttle, double targetX, double targetY, double targetZ) {
        double ix = profile.inertiaXKgM2;
        double iy = profile.inertiaYKgM2;
        double iz = profile.inertiaZKgM2;
        double tx = torque(ix * (targetX - state.wx) / profile.rateResponseSeconds);
        double ty = torque(iy * (targetY - state.wy) / profile.rateResponseSeconds);
        double tz = torque(iz * (targetZ - state.wz) / profile.rateResponseSeconds);
        // Euler's rigid-body equation in principal body axes: I*w' = torque - w x I*w.
        double ax = (tx - (iz - iy) * state.wy * state.wz) / ix;
        double ay = (ty - (ix - iz) * state.wz * state.wx) / iy;
        double az = (tz - (iy - ix) * state.wx * state.wy) / iz;
        double angularDecay = Math.exp(-profile.angularDragPerSecond * h);
        state.wx = (state.wx + ax * h) * angularDecay;
        state.wy = (state.wy + ay * h) * angularDecay;
        state.wz = (state.wz + az * h) * angularDecay;
        integrateAttitude(h);

        motorThrottle += (throttle - motorThrottle)
                * -Math.expm1(-h / profile.motorTimeConstantSeconds);
        double acceleration = motorThrottle * profile.maxThrustNewtons
                / (profile.massKg * profile.metersPerTile);
        double ux = 2.0 * (state.qx * state.qy - state.qw * state.qz);
        double uy = 1.0 - 2.0 * (state.qx * state.qx + state.qz * state.qz);
        double uz = 2.0 * (state.qy * state.qz + state.qw * state.qx);
        state.vx += ux * acceleration * h;
        state.vy += (uy * acceleration - profile.gravityMps2 / profile.metersPerTile) * h;
        state.vz += uz * acceleration * h;

        // An exponential drag kick remains dissipative at large speed, avoiding
        // the velocity reversal that a large explicit quadratic-drag kick causes.
        double speedMeters = Math.sqrt(state.vx * state.vx + state.vy * state.vy
                + state.vz * state.vz) * profile.metersPerTile;
        double dragDecay = Math.exp(-(profile.linearDragPerSecond
                + profile.quadraticDragPerMeter * speedMeters) * h);
        state.vx *= dragDecay;
        state.vy *= dragDecay;
        state.vz *= dragDecay;
        state.x += state.vx * h;
        state.y += state.vy * h;
        state.z += state.vz * h;
    }

    private void integrateAttitude(double h) {
        double speed = Math.sqrt(state.wx * state.wx + state.wy * state.wy + state.wz * state.wz);
        if (speed == 0.0) return;
        double halfAngle = speed * h * 0.5;
        double factor = Math.sin(halfAngle) / speed;
        double dx = state.wx * factor;
        double dy = state.wy * factor;
        double dz = state.wz * factor;
        double dw = Math.cos(halfAngle);
        double qx = state.qx, qy = state.qy, qz = state.qz, qw = state.qw;
        // Body-axis rotation is postmultiplied onto the world attitude.
        state.qx = qw * dx + qx * dw + qy * dz - qz * dy;
        state.qy = qw * dy - qx * dz + qy * dw + qz * dx;
        state.qz = qw * dz + qx * dy - qy * dx + qz * dw;
        state.qw = qw * dw - qx * dx - qy * dy - qz * dz;
        normalizeAttitude();
    }

    private void normalizeAttitude() {
        double norm = Math.sqrt(state.qx * state.qx + state.qy * state.qy
                + state.qz * state.qz + state.qw * state.qw);
        if (!Double.isFinite(norm) || norm == 0.0) {
            throw new IllegalStateException("Flight attitude must have a finite nonzero norm");
        }
        state.qx /= norm;
        state.qy /= norm;
        state.qz /= norm;
        state.qw /= norm;
    }

    private double rateCurve(double input) {
        input = clamp(input, -1.0, 1.0);
        return (1.0 - rateExpo) * input + rateExpo * input * input * input;
    }

    private double torque(double value) {
        return clamp(value, -profile.maxTorqueNewtonMeters, profile.maxTorqueNewtonMeters);
    }

    private void requireFiniteState() {
        if (!(Double.isFinite(state.x) && Double.isFinite(state.y) && Double.isFinite(state.z)
                && Double.isFinite(state.vx) && Double.isFinite(state.vy) && Double.isFinite(state.vz)
                && Double.isFinite(state.qx) && Double.isFinite(state.qy) && Double.isFinite(state.qz)
                && Double.isFinite(state.qw) && Double.isFinite(state.wx) && Double.isFinite(state.wy)
                && Double.isFinite(state.wz))) {
            throw new IllegalStateException("Flight state must remain finite");
        }
    }

    private static double finite(String name, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }

    private static double positive(String name, double value) {
        finite(name, value);
        if (value <= 0.0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static double nonnegative(String name, double value) {
        finite(name, value);
        if (value < 0.0) throw new IllegalArgumentException(name + " must be nonnegative");
        return value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
