package fpv;

import arc.Core;
import arc.math.Mathf;
import arc.util.Time;
import fpv.flight.FlightController;
import fpv.generated.RuntimeConfig;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.gen.Unit;

/** Synchronous, reversible navigation checks in the explicit isolated SDL lab. */
final class BuildNavigationSmokeChecks {
    private static final String[] keys = {"fpv-time", "fpv-build-speed", "fpv-pov-angle", "fpv-fov", "fpv-build-tilt"};
    private final FpvMod mod;
    private int checks;

    BuildNavigationSmokeChecks(FpvMod mod) { this.mod = mod; }

    String run() {
        require(Boolean.getBoolean("fpv.smoke") && Vars.state.isGame() && !Vars.player.dead(), "initialized lab only");
        require(!mod.motion.pending(), "navigation test starts outside a queued native move");
        Unit unit = Vars.player.unit();
        require(unit.type.flying, "native flying player fixture");
        var state = mod.flight.state();
        double[] saved = values(state);
        float x = unit.x, y = unit.y, vx = unit.vel.x, vy = unit.vel.y, rotation = unit.rotation;
        float delta = Time.delta, health = unit.health;
        double tick = Vars.state.tick;
        float wave = Vars.state.wavetime;
        State gameState = Vars.state.getState();
        boolean enabled = mod.enabled, ready = mod.ready, build = mod.buildMode;
        boolean pausedBefore = mod.pausedBeforeBuild, ownedPause = mod.buildOwnsPause;
        int timeIndex = mod.timeIndex;
        Object[] savedSettings = new Object[keys.length];
        boolean[] hadSetting = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            hadSetting[i] = Core.settings.has(keys[i]);
            savedSettings[i] = Core.settings.get(keys[i], null);
        }
        long mapBefore = mapFingerprint();
        try {
            mod.enabled = mod.ready = true;
            mod.timeIndex = 0;
            Core.settings.put("fpv-time", 0);
            Core.settings.put("fpv-build-speed", 12);
            mod.setBuildMode(true);
            Vars.state.set(State.paused);
            // A high, short path tests movement rather than depending on ground layout.
            double altitude = Math.min(RuntimeConfig.MAX_HEIGHT - 1,
                    Math.max(30, mod.renderer3d.surfaceHeight(x / Vars.tilesize, -y / Vars.tilesize) + 12));
            fixture(unit, x, y, altitude);
            for (int i = 0; i < 5; i++) mod.navigateBuild(.05, 1, 0, 0, 0);
            require(unit.y > y + Vars.tilesize && Math.abs(unit.x - x) < .001,
                    "0% Build forward moves original unit in native +Y / render -Z");
            close(state.x * Vars.tilesize, unit.x, .001, "paused original and sidecar X agree");
            close(-state.z * Vars.tilesize, unit.y, .001, "paused original and sidecar Y agree");
            require(Vars.state.tick == tick && Vars.state.wavetime == wave && mapFingerprint() == mapBefore,
                    "paused navigation leaves world tick, waves, tiles and building inventories unchanged");
            float heldX = unit.x, heldY = unit.y;
            double heldAltitude = state.y;
            mod.navigateBuild(.1, 0, 0, 0, 0);
            close(unit.x, heldX, 0, "released navigation holds X");
            close(unit.y, heldY, 0, "released navigation holds Y");
            close(state.y, heldAltitude, 0, "released navigation holds altitude");
            Core.settings.put("fpv-build-speed", 0);
            mod.navigateBuild(.1, 1, 1, 1, 0);
            close(unit.x, heldX, 0, "zero build speed holds X despite input");
            close(unit.y, heldY, 0, "zero build speed holds Y despite input");
            close(state.y, heldAltitude, 0, "zero build speed holds altitude despite input");

            Core.settings.put("fpv-build-speed", 12);
            fixture(unit, x, y, altitude);
            mod.navigateBuild(.1, 0, 1, 0, 0);
            double normalDistance = unit.x - x;
            require(normalDistance > 0 && Math.abs(unit.y - y) < .001, "Build right moves along render/native +X");
            Core.settings.put("fpv-build-speed", 24);
            fixture(unit, x, y, altitude);
            mod.navigateBuild(.1, 0, 1, 0, 0);
            close(unit.x - x, normalDistance * 2, .001, "build speed slider doubles measured native movement");
            double low = state.y;
            mod.navigateBuild(.05, 0, 0, 1, 0);
            require(state.y > low, "Build climb increases render Y altitude");
            fixture(unit, x, y, altitude);
            mod.navigateBuild(.1, 0, 0, 0, 1);
            double[] heading = new double[3];
            mod.flight.getForward(heading);
            require(heading[0] > 0 && heading[2] < 0 && unit.rotation > 0 && unit.rotation < 90,
                    "Build positive turn rotates right and updates native unit heading while paused");

            // Exercise the same bridge command at 10% without ticking the world or
            // calling Unit.update a second time inside an existing native update.
            fixture(unit, x, y, altitude);
            Core.settings.put("fpv-build-speed", 12);
            Core.settings.put("fpv-time", 2);
            mod.timeIndex = 2;
            mod.buildOwnsPause = false;
            Vars.state.set(State.playing);
            require(RuntimeConfig.TIME_PERCENT[mod.timeIndex] == 10, "10% preset selected");
            mod.navigateBuild(1.0 / 60, 0, 1, 0, 0);
            require(state.x > x / (double)Vars.tilesize && unit.x == x && unit.y == y,
                    "running Build queues desired displacement without pre-integrating native XY");
            double desiredX = state.x;
            Time.delta = .1f;
            require(mod.motion.beforeSimulation(unit, state, Time.delta, false), "10% bridge accepts Build displacement");
            float beforeX = unit.x, beforeY = unit.y;
            unit.move(unit.vel.x * Time.delta, unit.vel.y * Time.delta);
            if (Mathf.equal(beforeX, unit.x)) unit.vel.x = 0;
            if (Mathf.equal(beforeY, unit.y)) unit.vel.y = 0;
            unit.vel.scl(Math.max(1f - unit.drag * Time.delta, 0f));
            mod.motion.afterSimulation(unit, state);
            close(unit.x / (double)Vars.tilesize, desiredX, .0001, "10% native bridge integrates Build displacement exactly once");
            require(Vars.state.tick == tick && Vars.state.wavetime == wave,
                    "isolated bridge movement does not perform a duplicate native simulation tick");

            fixture(unit, x, y, altitude);
            cameraChecks();
            require(unit.health == health && !unit.dead, "Build navigation never damages the controlled unit");
            require(mapFingerprint() == mapBefore, "navigation and camera checks preserve the native map fingerprint");
            return "passed: " + checks + " assertions; paused native XY navigation with frozen tick/map, release/zero-speed hold, "
                    + "speed scaling, axes, 10% desired/native bridge, finite camera angle/FOV endpoints; scripted API input only";
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (hadSetting[i]) Core.settings.put(keys[i], savedSettings[i]);
                else Core.settings.remove(keys[i]);
            }
            mod.enabled = enabled;
            mod.ready = ready;
            mod.buildMode = build;
            mod.pausedBeforeBuild = pausedBefore;
            mod.buildOwnsPause = ownedPause;
            mod.timeIndex = timeIndex;
            Vars.state.set(gameState);
            Time.delta = delta;
            unit.set(x, y);
            unit.vel.set(vx, vy);
            unit.rotation = rotation;
            mod.motion.reset();
            mod.motion.syncFromSimulation(unit, state);
            restore(state, saved);
            if (mod.input != null) mod.input.setBuildMode(build);
            if (mod.hud != null) mod.hud.update();
            mod.updateCamera();
        }
    }

    private void fixture(Unit unit, float x, float y, double altitude) {
        unit.set(x, y);
        unit.vel.setZero();
        var s = mod.flight.state();
        s.x = x / (double)Vars.tilesize; s.y = altitude; s.z = -y / (double)Vars.tilesize;
        s.vx = s.vy = s.vz = s.wx = s.wy = s.wz = 0;
        s.qx = s.qy = s.qz = 0; s.qw = 1;
        mod.motion.reset();
        mod.motion.syncFromSimulation(unit, s);
    }

    private void cameraChecks() {
        mod.buildMode = false;
        for (int angle : new int[]{0, 90, 180}) for (int fov : new int[]{0, 90, 180}) {
            Core.settings.put("fpv-pov-angle", angle);
            Core.settings.put("fpv-fov", fov);
            mod.updateCamera();
            close(mod.camera.fov, Math.max(1, Math.min(179, fov)), 0, "FOV endpoint uses a finite perspective clamp");
            double tilt = Math.toRadians(angle - 90);
            close(mod.camera.direction.x, 0, .00002, "camera angle retains forward X");
            close(mod.camera.direction.y, Math.sin(tilt), .00002, "0/90/180 camera angle means down/forward/up");
            close(mod.camera.direction.z, -Math.cos(tilt), .00002, "camera forward convention is render -Z");
            close(mod.camera.direction.dot(mod.camera.up), 0, .00002, "camera direction/up remain orthogonal at endpoints");
            close(mod.camera.direction.len(), 1, .00002, "camera direction remains normalized");
            close(mod.camera.up.len(), 1, .00002, "camera up remains normalized");
            for (float value : mod.camera.combined.val) require(Float.isFinite(value), "finite camera matrix");
            for (float value : mod.camera.invProjectionView.val) require(Float.isFinite(value), "finite inverse camera matrix");
        }
        mod.buildMode = true;
        for (int tilt : new int[]{-75, -10}) {
            Core.settings.put("fpv-build-tilt", tilt);
            Core.settings.put("fpv-pov-angle", 180);
            mod.updateCamera();
            close(mod.camera.direction.y, Math.sin(Math.toRadians(tilt)), .001,
                    "Build camera retains its independent downward tilt");
        }
    }

    private static long mapFingerprint() {
        long hash = 0xcbf29ce484222325L;
        for (var tile : Vars.world.tiles) {
            hash = mix(hash, tile.block().id);
            hash = mix(hash, tile.floor().id);
            hash = mix(hash, tile.overlay().id);
            hash = mix(hash, tile.team().id);
            var build = tile.build;
            if (build == null) continue;
            hash = mix(hash, build.id);
            hash = mix(hash, build.rotation);
            hash = mix(hash, Float.floatToIntBits(build.health));
            if (build.items != null) for (var item : Vars.content.items()) hash = mix(hash, build.items.get(item));
            if (build.liquids != null) for (var liquid : Vars.content.liquids())
                hash = mix(hash, Float.floatToIntBits(build.liquids.get(liquid)));
            if (build.power != null) hash = mix(hash, Float.floatToIntBits(build.power.status));
        }
        return hash;
    }

    private static long mix(long hash, int value) { return (hash ^ value) * 0x100000001b3L; }
    private static double[] values(FlightController.State s) {
        return new double[]{s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz};
    }
    private static void restore(FlightController.State s, double[] a) {
        s.x=a[0];s.y=a[1];s.z=a[2];s.vx=a[3];s.vy=a[4];s.vz=a[5];
        s.qx=a[6];s.qy=a[7];s.qz=a[8];s.qw=a[9];s.wx=a[10];s.wy=a[11];s.wz=a[12];
    }
    private void close(double actual, double expected, double tolerance, String label) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + ": expected " + expected + ", got " + actual);
    }
    private void require(boolean value, String label) {
        if (!value) throw new IllegalStateException("Build navigation smoke: " + label);
        checks++;
    }
}
