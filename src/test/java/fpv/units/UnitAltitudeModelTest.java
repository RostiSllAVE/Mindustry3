package fpv.units;

/** Deterministic math checks; no OpenGL, native game state or controller required. */
public final class UnitAltitudeModelTest {
    private static int checks;
    public static void main(String[] args) {
        deterministicRanges();
        smoothClimbAndDescent();
        clearanceAndGround();
        attitudeAndPause();
        idleStationKeepingAndCommandedCircle();
        frameRatesAndLanding();
        invalidInput();
        System.out.println("UnitAltitudeModel: " + checks + " assertions passed (hybrid render sidecar; no 3D native combat claim).");
    }

    private static UnitAltitudeModel model() { return new UnitAltitudeModel(UnitAltitudeSystem.profile()); }

    private static void deterministicRanges() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.Profile p = UnitAltitudeSystem.profile();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 30; i++) {
            UnitAltitudeModel.State first = model.create(i, 5, 1, 0, 0, true);
            UnitAltitudeModel.State same = model.create(i, 5, 1, 0, 0, true);
            close(first.height(), same.height(), 0, "stable id/type seed");
            yes(first.cruise() >= p.smallCruiseMin() && first.cruise() <= p.smallCruiseMax(), "small range");
            min = Math.min(min, first.cruise()); max = Math.max(max, first.cruise());
        }
        yes(max - min > (p.smallCruiseMax() - p.smallCruiseMin()) * 0.75, "groups do not occupy one plane");
        UnitAltitudeModel.State medium = model.create(1, 2, (p.smallMaxSize() + p.mediumMaxSize()) / 2, 0, 0, true);
        yes(medium.cruise() >= p.mediumCruiseMin() && medium.cruise() <= p.mediumCruiseMax(), "medium range");
        UnitAltitudeModel.State large = model.create(1, 2, p.mediumMaxSize() * 2, 0, 0, true);
        yes(large.cruise() >= p.largeCruiseMin() && large.cruise() <= p.largeCruiseMax(), "large range");
    }

    private static void smoothClimbAndDescent() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.Profile p = UnitAltitudeSystem.profile();
        UnitAltitudeModel.State state = model.create(8, 3, 1, 0, 0, true);
        double previous = state.height();
        for (int i = 0; i < 300; i++) {
            model.update(state, 1.0 / 60, 0, 12, 2, 0, 0, true);
            if (i < 120) yes(state.height() >= previous - 1e-10, "lookahead climbs before slope");
            yes(state.height() - previous <= p.maxClimbSpeed() / 60 + 1e-10, "normal climb rate bounded");
            previous = state.height();
        }
        yes(state.height() > 12 + state.cruise(), "converges above predicted hill");
        yes(state.pitch() > -p.maxPitch() && state.pitch() <= p.maxPitch(), "pitch within limits");
        for (int i = 0; i < 600; i++) {
            double floor = i < 100 ? Math.min(12, i * 0.12) : 12;
            model.update(state, 1.0 / 60, floor, 12, 2, 0, 0, true);
            yes(state.height() >= floor + state.bodyOffset() + p.minClearance() - 1e-10, "slope never clips");
        }
        previous = state.height();
        model.update(state, 1.0 / 60, 0, 0, 2, 0, 0, true);
        yes(previous - state.height() <= p.maxDescentSpeed() / 60 + 1e-10, "smooth descent instead of dropping to flat floor");
        for (int i = 0; i < 900; i++) model.update(state, 1.0 / 60, 0, 0, 2, 0, 0, true);
        close(state.height(), state.bodyOffset() + p.minClearance() + state.cruise(), p.variation() + 0.02, "returns to cruise range");
    }

    private static void clearanceAndGround() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.Profile p = UnitAltitudeSystem.profile();
        UnitAltitudeModel.State state = model.create(8, 3, 1, 0, 0, true);
        model.update(state, 1.0 / 60, 30, 30, 10, 0, 0, true);
        yes(state.height() >= 30 + state.bodyOffset() + p.minClearance(), "abrupt native move projects above terrain");
        UnitAltitudeModel.State ground = model.create(6, 2, 2, 4, 90, false);
        close(ground.height(), 4 + ground.bodyOffset(), 0, "ground initialization");
        UnitAltitudeModel.State thick = model.create(6, 2, 0.2, 4, 90, false, 0.9);
        close(thick.height(), 4.9, 0, "actual mesh half-depth overrides narrow native hit size");
        UnitAltitudeModel.State broad = model.create(6, 2, 0.2, 4, 90, true, 0.9, 5);
        yes(broad.height() > thick.height() + broad.cruise() + 3, "bank/pitch envelope clears large sprite footprint");
        for (int i = 0; i < 120; i++) {
            double terrain = 3 + Math.sin(i / 20.0);
            model.update(ground, 1.0 / 60, terrain, 30, 1, 0, 0, false);
            close(ground.height(), terrain + ground.bodyOffset(), 0, "ground follows current surface, not ahead mountain");
            close(ground.verticalVelocity(), 0, 0, "ground gains no flying velocity");
            yes(!ground.flying(), "ground stays grounded");
        }
    }

    private static void attitudeAndPause() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.State state = model.create(1, 3, 1, 0, 350, true);
        double target = Math.toRadians(10);
        for (int i = 0; i < 20; i++) model.update(state, 1.0 / 60, 0, 8, Math.cos(target) * 4, -Math.sin(target) * 4, 180, true);
        yes(state.yaw() < 20 || state.yaw() > 350, "shortest heading arc across zero");
        yes(state.pitch() > 0, "climb tilts nose up");
        yes(state.roll() < 0, "increasing native heading banks left");
        double h = state.height(), v = state.verticalVelocity(), yaw = state.yaw(), pitch = state.pitch(), roll = state.roll();
        for (int i = 0; i < 10; i++) model.update(state, 0, 40, 40, 0, 6, 90, false);
        close(state.height(), h, 0, "pause altitude");
        close(state.verticalVelocity(), v, 0, "pause vertical velocity");
        close(state.yaw(), yaw, 0, "pause yaw");
        close(state.pitch(), pitch, 0, "pause pitch");
        close(state.roll(), roll, 0, "pause bank");
        yes(state.flying(), "pause cannot apply a transition early");
    }

    private static void frameRatesAndLanding() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.State a = model.create(1, 2, 1, 0, 0, true);
        UnitAltitudeModel.State b = model.create(1, 2, 1, 0, 0, true);
        UnitAltitudeModel.State c = model.create(1, 2, 1, 0, 0, true);
        for (int i = 0; i < 150; i++) model.update(a, 1.0 / 30, 0, 16, 1, -1, 45, true);
        for (int i = 0; i < 300; i++) model.update(b, 1.0 / 60, 0, 16, 1, -1, 45, true);
        for (int i = 0; i < 720; i++) model.update(c, 1.0 / 144, 0, 16, 1, -1, 45, true);
        close(a.height(), b.height(), 1e-9, "30/60 frame consistency");
        close(a.height(), c.height(), 0.002, "30/144 frame consistency");
        close(a.yaw(), c.yaw(), 1e-9, "heading frame consistency");
        double oldHeight = a.height();
        model.update(a, 1.0 / 60, 0, 0, 1, 0, 0, false);
        yes(a.height() < oldHeight && a.height() > a.bodyOffset() + 1, "native landing has a continuous descent");
        yes(!a.flying(), "landing keeps original grounded classification");
        for (int i = 0; i < 1200; i++) model.update(a, 1.0 / 60, 0, 0, 1, 0, 0, false);
        close(a.height(), a.bodyOffset(), 0, "landing settles on ground");
        close(a.pitch(), 0, 1e-9, "ground pitch levels");
        close(a.roll(), 0, 1e-9, "ground bank levels");
    }

    private static void idleStationKeepingAndCommandedCircle() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.State state = model.create(7, 3, 1, 0, 73, true);
        double firstHeight = state.height(), largestAltitudeChange = 0;
        for (int i = 0; i < 600; i++) {
            model.update(state, 1.0 / 60, 0, 0, 0, 0, 73, true);
            close(state.yaw(), 73, 1e-9, "idle altitude variation never turns heading");
            close(state.pitch(), 0, 1e-9, "idle altitude variation holds level pitch");
            close(state.roll(), 0, 1e-9, "idle altitude variation holds level bank");
            largestAltitudeChange = Math.max(largestAltitudeChange, Math.abs(state.height()-firstHeight));
        }
        yes(largestAltitudeChange > .05, "station keeping retains physical hover altitude variation");
        for (int i = 0; i < 360; i++) {
            double angle = Math.toRadians(73+i);
            model.update(state, 1.0 / 60, 0, 0, 4*Math.cos(angle), -4*Math.sin(angle), 73, true);
        }
        yes(state.roll() < -1, "intentional moving circular path still banks");
        for (int i = 0; i < 300; i++) model.update(state,1.0/60,0,0,0,0,73,true);
        close(state.yaw(),73,1e-6,"stopping returns to native stationary heading");
        close(state.pitch(),0,1e-6,"stopping settles pitch");
        close(state.roll(),0,1e-6,"stopping settles bank");
    }

    private static void invalidInput() {
        UnitAltitudeModel model = model();
        UnitAltitudeModel.State state = model.create(1, 1, 1, 0, 0, false);
        rejects(() -> model.update(state, -1, 0, 0, 0, 0, 0, false));
        rejects(() -> model.update(state, Double.NaN, 0, 0, 0, 0, 0, false));
        rejects(() -> model.update(state, 0.1, Double.NaN, 0, 0, 0, 0, false));
        rejects(() -> model.create(1, 1, -1, 0, 0, false));
    }

    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid altitude input accepted");
    }
    private static void yes(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void close(double value, double expected, double tolerance, String message) {
        yes(Double.isFinite(value) && Math.abs(value - expected) <= tolerance, message + ": " + value + " expected " + expected);
    }
}
