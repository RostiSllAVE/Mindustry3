package fpv.flight;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.Unit;

/** Opt-in actual UnitEntity checks inside the initialized, isolated smoke game. */
public final class UnitMotionSmokeChecks {
    private UnitMotionSmokeChecks() { }

    public static String run() {
        require(Boolean.getBoolean("fpv.smoke"), "Motion checks require an explicit smoke launch");
        require(Vars.player != null && !Vars.player.dead() && Vars.world.width() > 0,
                "Motion checks require a living player and loaded native map");
        float previousDelta = Time.delta;
        try {
            Time.delta = 1f;
            singleIntegrationAndDrag();
            nativeImpulse(true);
            nativeImpulse(false);
            correctionAndHold();
            return "passed: actual Unit.update/move/impulse; single integration, native drag baseline, "
                    + "early/late impulses, no duplicate injection, position correction, hold and release";
        } finally {
            Time.delta = previousDelta;
        }
    }

    private static void singleIntegrationAndDrag() {
        Unit unit = fixture();
        UnitMotionBridge bridge = new UnitMotionBridge();
        FlightController.State state = new FlightController.State();
        bridge.syncFromSimulation(unit, state);
        float x = unit.x;
        state.x += 1.0 / Vars.tilesize;
        state.vx = Time.toSeconds / Vars.tilesize;
        bridge.beforeSimulation(unit, state, Time.delta);
        close(unit.x, x, "Queueing must not move original position");
        close(unit.vel.x, 1.0, "Native targeting must see commanded velocity");
        unit.update(); // The installed host's complete original UnitEntity update.
        bridge.afterSimulation(unit, state);
        close(unit.x, x + 1.0, "Native update must integrate displacement once");
        close(state.x * Vars.tilesize, unit.x, "Sidecar reconciles actual native position");
        close(state.vx, Time.toSeconds / Vars.tilesize, "Native drag must not double-damp FPV velocity");
        require(!bridge.pending(), "Motion command was not consumed");
        state.x += 10.0;
        require(!bridge.beforeSimulation(unit, state, 0f), "Zero delta queued movement");
        close(state.x * Vars.tilesize, unit.x, "Zero delta leaves native position authoritative");
    }

    private static void nativeImpulse(boolean beforeUpdate) {
        Unit unit = fixture();
        UnitMotionBridge bridge = new UnitMotionBridge();
        FlightController.State state = new FlightController.State();
        bridge.syncFromSimulation(unit, state);
        state.x += 1.0 / Vars.tilesize;
        state.vx = Time.toSeconds / Vars.tilesize;
        float dragFactor = Math.max(1f - unit.drag * Time.delta, 0f);
        bridge.beforeSimulation(unit, state, Time.delta);
        double baseFlightVelocity = state.vx;
        float impulse;
        if (beforeUpdate) {
            float previous = unit.vel.x;
            unit.impulse(unit.mass() * 0.25f, 0f);
            impulse = unit.vel.x - previous;
            unit.update();
            impulse *= dragFactor;
        } else {
            unit.update();
            float previous = unit.vel.x;
            unit.impulse(unit.mass() * 0.25f, 0f);
            impulse = unit.vel.x - previous;
        }
        bridge.afterSimulation(unit, state);
        close(state.vx, baseFlightVelocity + impulse * Time.toSeconds / Vars.tilesize,
                "Native " + (beforeUpdate ? "early" : "late") + " impulse must survive reconciliation");
        double once = state.vx;
        bridge.syncFromSimulation(unit, state);
        close(state.vx, once, "Published impulse must not be injected twice");
    }

    private static void correctionAndHold() {
        Unit unit = fixture();
        UnitMotionBridge bridge = new UnitMotionBridge();
        FlightController.State state = new FlightController.State();
        bridge.syncFromSimulation(unit, state);
        unit.move(1.25f, -0.5f); // Native correction; this does not claim the async solver itself was exercised.
        bridge.syncFromSimulation(unit, state);
        close(state.x * Vars.tilesize, unit.x, "Native X correction must remain authoritative");
        close(-state.z * Vars.tilesize, unit.y, "Native Y correction must remain authoritative");
        float x = unit.x, y = unit.y;
        state.vx = 3.0;
        state.vz = -2.0;
        state.x += 5.0;
        state.z += 5.0;
        bridge.beforeSimulation(unit, state, Time.delta, true);
        unit.update();
        bridge.afterSimulation(unit, state);
        close(unit.x, x, "Hold must not apply stored FPV horizontal momentum");
        close(unit.y, y, "Hold must not apply desired pose displacement");
        close(unit.vel.x, 0.0, "Held native unit advertises zero controller velocity");
        close(state.vx, 3.0, "Hold retains sidecar momentum for resuming");
        bridge.release(unit, state);
        close(unit.vel.x, 3.0 * Vars.tilesize / Time.toSeconds, "Native handoff transfers X momentum");
        close(unit.vel.y, 2.0 * Vars.tilesize / Time.toSeconds, "Native handoff transfers Y momentum");
    }

    private static Unit fixture() {
        // A native flying unit with a no-input controller, never added to any
        // entity group. The player, saves and native content definitions are not mutated.
        Unit unit = UnitTypes.alpha.create(Vars.player.team());
        unit.set(Vars.player.x, Vars.player.y);
        unit.elevation = 1f;
        unit.drag = 0.1f;
        unit.vel.setZero();
        unit.controller(new UnitController() {
            private Unit owner;
            @Override public void unit(Unit value) { owner = value; }
            @Override public Unit unit() { return owner; }
        });
        return unit;
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 0.001,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
