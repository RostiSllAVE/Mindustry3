package fpv.units;

/** Dependency-free physical altitude integrator, shared by rendering and XYZ combat queries. Distances are tiles. */
public final class UnitAltitudeModel {
    /** Parameters are generated from design/units.sheet.json by the native adapter. */
    public record Profile(double smallMaxSize, double mediumMaxSize,
                          double smallCruiseMin, double smallCruiseMax,
                          double mediumCruiseMin, double mediumCruiseMax,
                          double largeCruiseMin, double largeCruiseMax,
                          double minBodyHalfDepth, double bodyHalfDepthPerSize,
                          double minClearance, double responseSeconds,
                          double maxClimbSpeed, double maxDescentSpeed,
                          double variation, double variationSeconds,
                          double attitudeResponseSeconds, double maxPitch,
                          double maxRoll, double turnBankSeconds,
                          double minTravelSpeed, double integrationStep,
                          double maxFrameSeconds, double maxBodyOffset) {
        public Profile {
            double[] values = {smallMaxSize, mediumMaxSize, smallCruiseMin, smallCruiseMax,
                mediumCruiseMin, mediumCruiseMax, largeCruiseMin, largeCruiseMax,
                minBodyHalfDepth, bodyHalfDepthPerSize, minClearance, responseSeconds,
                maxClimbSpeed, maxDescentSpeed, variation, variationSeconds,
                attitudeResponseSeconds, maxPitch, maxRoll, turnBankSeconds,
                minTravelSpeed, integrationStep, maxFrameSeconds, maxBodyOffset};
            for (double v : values) if (!Double.isFinite(v) || v < 0) {
                throw new IllegalArgumentException("Altitude tuning must be finite and nonnegative");
            }
            if (smallMaxSize > mediumMaxSize || smallCruiseMin > smallCruiseMax
                || mediumCruiseMin > mediumCruiseMax || largeCruiseMin > largeCruiseMax
                || responseSeconds == 0 || variationSeconds == 0 || attitudeResponseSeconds == 0
                || integrationStep == 0 || maxFrameSeconds < integrationStep
                || maxFrameSeconds / integrationStep > 1000 || minBodyHalfDepth > maxBodyOffset) {
                throw new IllegalArgumentException("Invalid altitude tuning ranges or time constants");
            }
        }
    }

    /** Live read-only pose view; retaining a pose does not freeze it at the previous frame. */
    public interface Pose {
        double height();
        double verticalVelocity();
        double desiredHeight();
        double yaw();
        double pitch();
        double roll();
        boolean flying();
    }

    public static final class State implements Pose {
        private double height, verticalVelocity, desiredHeight, yaw, pitch, roll, elapsed;
        private final double cruise, phase, bodyOffset, flightBodyOffset;
        private boolean flying, landing;
        private State(double height, double yaw, double cruise, double phase, double bodyOffset, double flightBodyOffset, boolean flying) {
            this.height = this.desiredHeight = height;
            this.yaw = normalize(yaw);
            this.cruise = cruise;
            this.phase = phase;
            this.bodyOffset = bodyOffset;
            this.flightBodyOffset = flightBodyOffset;
            this.flying = flying;
        }
        public double height() { return height; }
        public double verticalVelocity() { return verticalVelocity; }
        public double desiredHeight() { return desiredHeight; }
        public double yaw() { return yaw; }
        public double pitch() { return pitch; }
        public double roll() { return roll; }
        public boolean flying() { return flying; }
        public double cruise() { return cruise; }
        public double bodyOffset() { return bodyOffset; }
    }

    private final Profile p;
    public UnitAltitudeModel(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Altitude profile is required");
        p = profile;
    }

    public State create(int unitId, int typeId, double size, double terrain, double yaw, boolean flying) {
        return create(unitId, typeId, size, terrain, yaw, flying, 0);
    }

    /** The optional mesh half-depth keeps unknown/custom sprite sizes above the surface. */
    public State create(int unitId, int typeId, double size, double terrain, double yaw, boolean flying, double meshHalfDepth) {
        return create(unitId, typeId, size, terrain, yaw, flying, meshHalfDepth, 0);
    }

    /** Radius includes the sprite half-diagonal, allowing the maximum bank/pitch to clear terrain. */
    public State create(int unitId, int typeId, double size, double terrain, double yaw, boolean flying,
                        double meshHalfDepth, double meshRadius) {
        finite(size); finite(terrain); finite(yaw); finite(meshHalfDepth); finite(meshRadius);
        if (size < 0 || meshHalfDepth < 0 || meshRadius < 0) throw new IllegalArgumentException("Unit dimensions must be nonnegative");
        long seed = mix(((long)typeId << 32) ^ Integer.toUnsignedLong(unitId));
        double fraction = fraction(seed);
        double min = size <= p.smallMaxSize ? p.smallCruiseMin : size <= p.mediumMaxSize ? p.mediumCruiseMin : p.largeCruiseMin;
        double max = size <= p.smallMaxSize ? p.smallCruiseMax : size <= p.mediumMaxSize ? p.mediumCruiseMax : p.largeCruiseMax;
        double cruise = min + (max - min) * fraction;
        double body = Math.max(meshHalfDepth, Math.min(p.maxBodyOffset, Math.max(p.minBodyHalfDepth, size * p.bodyHalfDepthPerSize)));
        double upY = Math.cos(Math.toRadians(p.maxPitch)) * Math.cos(Math.toRadians(p.maxRoll));
        double flightBody = Math.max(body, meshHalfDepth + meshRadius * Math.sqrt(Math.max(0, 1 - upY * upY)));
        double height = terrain + (flying ? flightBody + p.minClearance + cruise : body);
        return new State(height, yaw, cruise, fraction(mix(seed)) * Math.PI * 2, body, flightBody, flying);
    }

    /**
     * Current terrain is the maximum under the footprint; ahead terrain is a sampled route maximum.
     * The safety projection wins over rate limits if the native simulation moves through a cliff.
     * This preserves original horizontal AI while keeping the shared physical body above terrain.
     */
    public void update(State state, double seconds, double terrain, double aheadTerrain,
                       double vx, double vz, double nativeYaw, boolean flying) {
        update(state,seconds,terrain,aheadTerrain,vx,vz,nativeYaw,flying,0,0,
            terrain+(state==null?0:state.bodyOffset));
    }

    /** Ground contact includes slope attitude and the tilted mesh's actual resting center. */
    public void update(State state, double seconds, double terrain, double aheadTerrain,
                       double vx, double vz, double nativeYaw, boolean flying,
                       double groundPitch, double groundRoll, double groundCenterHeight) {
        if (state == null) throw new IllegalArgumentException("Altitude state is required");
        finite(seconds); finite(terrain); finite(aheadTerrain); finite(vx); finite(vz); finite(nativeYaw);
        finite(groundPitch);finite(groundRoll);finite(groundCenterHeight);
        if (seconds < 0) throw new IllegalArgumentException("Simulation delta must be nonnegative");
        if (seconds == 0) return;
        if (state.flying && !flying) state.landing = true;
        if (flying) state.landing = false;
        state.flying = flying;
        double dt = Math.min(seconds, p.maxFrameSeconds);
        int steps = (int)Math.ceil(dt / p.integrationStep);
        double step = dt / steps;
        double speed = Math.hypot(vx, vz);
        double travelYaw = flying&&speed >= p.minTravelSpeed ? Math.toDegrees(Math.atan2(-vz, vx)) : nativeYaw;
        for (int i = 0; i < steps; i++) {
            state.elapsed += step;
            double floor = flying ? terrain + state.flightBodyOffset + p.minClearance : groundCenterHeight;
            double wave = p.variation * Math.sin(state.phase + state.elapsed * 2 * Math.PI / p.variationSeconds);
            double target = flying ? Math.max(terrain, aheadTerrain) + state.flightBodyOffset + p.minClearance
                + Math.max(0, state.cruise + wave) : groundCenterHeight;
            state.desiredHeight = target;
            if (!flying && !state.landing) {
                state.height = target;
                state.verticalVelocity = 0;
            } else {
                // Exact critically damped spring step for the current target, followed by speed limits.
                double omega = 2 / p.responseSeconds;
                double displacement = state.height - target;
                double intermediate = state.verticalVelocity + omega * displacement;
                double decay = Math.exp(-omega * step);
                double nextHeight = target + (displacement + intermediate * step) * decay;
                double nextVelocity = (state.verticalVelocity - omega * intermediate * step) * decay;
                double delta = clamp(nextHeight - state.height, -p.maxDescentSpeed * step, p.maxClimbSpeed * step);
                state.height += delta;
                state.verticalVelocity = clamp(nextVelocity, -p.maxDescentSpeed, p.maxClimbSpeed);
                if (state.height < floor) {
                    state.height = floor;
                    state.verticalVelocity = Math.max(0, state.verticalVelocity);
                }
                if (!flying && Math.abs(state.height - target) < 0.005 && Math.abs(state.verticalVelocity) < 0.025) {
                    state.height = target;
                    state.verticalVelocity = 0;
                    state.landing = false;
                }
            }
            double attitudeMix = -Math.expm1(-step / p.attitudeResponseSeconds);
            double oldYaw = state.yaw;
            state.yaw = !flying&&!state.landing ? normalize(nativeYaw) : normalize(state.yaw + difference(travelYaw, state.yaw) * attitudeMix);
            double yawRate = difference(state.yaw, oldYaw) / step;
            double targetPitch = flying ? speed >= p.minTravelSpeed
                ? clamp(Math.toDegrees(Math.atan2(state.verticalVelocity, Math.max(speed, 0.4))), -p.maxPitch, p.maxPitch)
                : 0 : groundPitch;
            double targetRoll = flying ? speed >= p.minTravelSpeed ? clamp(-yawRate * p.turnBankSeconds, -p.maxRoll, p.maxRoll) : 0 : groundRoll;
            if(!flying&&!state.landing){
                // Ground contact must align immediately with the support plane to avoid clipping during tilt lag.
                state.pitch=targetPitch;state.roll=targetRoll;
            }else{
                state.pitch += (targetPitch - state.pitch) * attitudeMix;
                state.roll += (targetRoll - state.roll) * attitudeMix;
            }
        }
    }

    /** Apply a bounded physical vertical impulse to this same altitude state, never a visual-only offset. */
    public boolean addVerticalVelocity(State state,double delta,double maximumImpulse){
        if(state==null)throw new IllegalArgumentException("Altitude state required");
        finite(delta);finite(maximumImpulse);
        if(maximumImpulse<=0)throw new IllegalArgumentException("Positive impulse bound required");
        if(!state.flying)return false;
        state.verticalVelocity=clamp(state.verticalVelocity+clamp(delta,-maximumImpulse,maximumImpulse),
            -Math.max(p.maxDescentSpeed,maximumImpulse),Math.max(p.maxClimbSpeed,maximumImpulse));
        return true;
    }

    /** First authoritative payload release: no fictitious fall speed or attitude from its stored 2D pose. */
    public void seedSpawnHeight(State state,double height){
        if(state==null)throw new IllegalArgumentException("Spawn state required");finite(height);
        state.height=state.desiredHeight=height;state.verticalVelocity=0;state.pitch=state.roll=0;state.landing=false;
    }

    /** Initial grounded pose or immediate native relocation, without advancing simulation time. */
    /** Constraint projection from a swept airborne barrier; retains cruise, yaw and flight identity. */
    public void resolveAirContact(State state,double height,double verticalVelocity){finite(height);finite(verticalVelocity);if(state==null||!state.flying)throw new IllegalArgumentException("Air pose required");state.height=height;state.verticalVelocity=verticalVelocity;}

    public void alignGround(State state,double height,double pitch,double roll){
        if(state==null||state.flying)throw new IllegalArgumentException("Grounded state required");
        finite(height);finite(pitch);finite(roll);
        state.height=state.desiredHeight=height;state.verticalVelocity=0;state.pitch=pitch;state.roll=roll;
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Altitude inputs must be finite");
    }
    private static double clamp(double x, double min, double max) { return Math.max(min, Math.min(max, x)); }
    private static double normalize(double degrees) { return ((degrees % 360) + 360) % 360; }
    private static double difference(double target, double current) { return ((target - current + 540) % 360 + 360) % 360 - 180; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static double fraction(long value) { return (value >>> 11) * 0x1.0p-53; }
}
