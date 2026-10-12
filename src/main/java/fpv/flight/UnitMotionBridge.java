package fpv.flight;

import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;

/**
 * Maps an original FPV displacement onto the original Mindustry velocity update.
 * No method writes unit position, replaces the unit, or disables native physics.
 *
 * <p>Required order: syncFromSimulation, flight.step/collision, beforeSimulation,
 * original entity simulation, afterSimulation. The last call belongs to
 * Trigger.afterGameUpdate. The delta passed to beforeSimulation is the scaled
 * native Time.delta in ticks, not seconds. This bridge is version-pinned to
 * Mindustry 160.5's VelComp ordering.</p>
 */
public final class UnitMotionBridge {
    private Unit trackedUnit;
    private int trackedId;
    private boolean pending;
    private boolean holdPose;
    private float publishedVx, publishedVy;
    private float startX, startY;
    private float commandVx, commandVy;
    private float expectedVx, expectedVy;

    /** Forget bookkeeping after death/recover/world reset; never mutates a unit. */
    public void reset() {
        trackedUnit = null;
        pending = false;
        publishedVx = publishedVy = 0f;
    }

    /**
     * Import original position corrections and newly added native impulses.
     * Call before calculating the next FPV pose, never after flight.step.
     * On a new unit, its existing native horizontal velocity becomes initial
     * flight velocity. Root must reset altitude/orientation separately.
     */
    public void syncFromSimulation(Unit unit, FlightController.State state) {
        require(unit, state);
        if (pending) throw new IllegalStateException("Previous native motion update was not reconciled");
        if (!tracks(unit)) {
            trackedUnit = unit;
            trackedId = unit.id;
            state.vx = nativeToFlight(unit.vel.x);
            state.vz = -nativeToFlight(unit.vel.y);
        } else {
            // The previous publication has already been included in sidecar
            // velocity. Only the difference is new; do not inject it twice.
            state.vx += nativeToFlight(unit.vel.x - publishedVx);
            state.vz -= nativeToFlight(unit.vel.y - publishedVy);
        }
        importPosition(unit, state);
        rememberPublication(unit);
    }

    public boolean beforeSimulation(Unit unit, FlightController.State state, float deltaTicks) {
        return beforeSimulation(unit, state, deltaTicks, false);
    }

    /**
     * Queue desired horizontal motion as native velocity. Returns false at zero
     * delta. In hold mode stored FPV momentum does not command movement, while
     * native impulses/collision corrections remain authoritative.
     */
    public boolean beforeSimulation(Unit unit, FlightController.State state, float deltaTicks, boolean hold) {
        require(unit, state);
        if (!Float.isFinite(deltaTicks) || deltaTicks < 0f) {
            throw new IllegalArgumentException("Native delta must be finite and nonnegative");
        }
        if (!tracks(unit)) throw new IllegalStateException("Call syncFromSimulation before queueing unit motion");
        if (pending) throw new IllegalStateException("A native motion update is already queued");
        if (deltaTicks == 0f) {
            importPosition(unit, state);
            return false;
        }
        startX = unit.x;
        startY = unit.y;
        holdPose = hold;
        commandVx = hold ? 0f : finiteFloat((state.x * Vars.tilesize - unit.x) / deltaTicks);
        commandVy = hold ? 0f : finiteFloat((-state.z * Vars.tilesize - unit.y) / deltaTicks);
        // Match VelComp's float arithmetic. Its first operation moves using
        // velocity, then applies this drag. FPV drag was already integrated.
        float dragFactor = Math.max(1f - unit.drag * deltaTicks, 0f);
        expectedVx = commandVx * dragFactor;
        expectedVy = commandVy * dragFactor;
        unit.vel.set(commandVx, commandVy);
        pending = true;
        return true;
    }

    /**
     * Reconcile exactly once after original entity simulation. Native velocity
     * differences include knockback regardless of whether it was applied before
     * or after the unit's own VelComp update in the entity list.
     */
    public void afterSimulation(Unit unit, FlightController.State state) {
        require(unit, state);
        if (!pending) return;
        if (!tracks(unit)) {
            reset();
            return;
        }
        pending = false;
        importPosition(unit, state);
        if (unit.dead) {
            // Death/wreck dynamics belong entirely to the original game.
            reset();
            return;
        }

        boolean blockedX = commandVx != 0f && Mathf.equal(startX, unit.x);
        boolean blockedY = commandVy != 0f && Mathf.equal(startY, unit.y);
        if (blockedX) {
            expectedVx = 0f;
            if (!holdPose) state.vx = 0.0;
        }
        if (blockedY) {
            expectedVy = 0f;
            if (!holdPose) state.vz = 0.0;
        }
        state.vx += nativeToFlight(unit.vel.x - expectedVx);
        state.vz -= nativeToFlight(unit.vel.y - expectedVy);
        // Expose meaningful motion for targeting and sensors between updates.
        // A held craft advertises no controller motion, even though its sidecar
        // stores inertia/impulses for the next intentional flight step.
        unit.vel.set(holdPose ? 0f : flightToNative(state.vx),
                holdPose ? 0f : -flightToNative(state.vz));
        rememberPublication(unit);
    }

    /**
     * Hand control back to native movement, transferring momentum once. This
     * also cancels an update queued just before a pause/disabled-mode transition.
     */
    public void release(Unit unit, FlightController.State state) {
        if (unit != null && state != null && tracks(unit) && !unit.dead) {
            unit.vel.set(flightToNative(state.vx), -flightToNative(state.vz));
        }
        reset();
    }

    public boolean pending() {
        return pending;
    }

    private boolean tracks(Unit unit) {
        return unit == trackedUnit && unit.id == trackedId;
    }

    private static void importPosition(Unit unit, FlightController.State state) {
        state.x = unit.x / (double)Vars.tilesize;
        state.z = -unit.y / (double)Vars.tilesize;
    }

    private void rememberPublication(Unit unit) {
        publishedVx = unit.vel.x;
        publishedVy = unit.vel.y;
    }

    private static double nativeToFlight(float value) {
        return value * (double)Time.toSeconds / Vars.tilesize;
    }

    private static float flightToNative(double value) {
        return finiteFloat(value * Vars.tilesize / Time.toSeconds);
    }

    private static float finiteFloat(double value) {
        float converted = (float)value;
        if (!Float.isFinite(converted)) throw new IllegalArgumentException("Native motion must remain finite");
        return converted;
    }

    private static void require(Unit unit, FlightController.State state) {
        if (unit == null || state == null || unit.vel == null) {
            throw new IllegalArgumentException("Unit, flight state and native velocity are required");
        }
        if (!(Float.isFinite(unit.x) && Float.isFinite(unit.y)
                && Float.isFinite(unit.vel.x) && Float.isFinite(unit.vel.y) && Float.isFinite(unit.drag)
                && Double.isFinite(state.x) && Double.isFinite(state.z)
                && Double.isFinite(state.vx) && Double.isFinite(state.vz))) {
            throw new IllegalArgumentException("Unit/flight horizontal motion must remain finite");
        }
    }
}
