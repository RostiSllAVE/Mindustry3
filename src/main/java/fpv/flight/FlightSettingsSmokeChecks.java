package fpv.flight;

import arc.Core;
import fpv.generated.FlightProfiles;
import fpv.generated.PerformanceConfig;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;

/** Opt-in checks against real native UnitType metadata and Arc settings; no unit is added to the world. */
public final class FlightSettingsSmokeChecks {
    private static final String[] keys = {"fpv-performance-preset", "fpv-power", "fpv-speed-reference", "fpv-mass",
            "fpv-drag", "fpv-response", "fpv-acceleration", "fpv-rate-scale",
            "fpv-pitch-rate", "fpv-roll-rate", "fpv-yaw-rate", "fpv-expo", "fpv-flight-speed"};

    private FlightSettingsSmokeChecks() { }

    public static String run() {
        require(Boolean.getBoolean("fpv.smoke") && Core.settings != null,
                "explicit initialized lab smoke required");
        Object[] old = new Object[keys.length];
        boolean[] existed = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            existed[i] = Core.settings.has(keys[i]);
            old[i] = Core.settings.get(keys[i], null);
        }
        try {
            var base = FlightProfiles.create();
            Core.settings.put("fpv-performance-preset", 1);
            Core.settings.put("fpv-flight-speed", 100);
            Core.settings.put("fpv-pitch-rate", (int)base.pitchRateDegreesPerSecond);
            Core.settings.put("fpv-roll-rate", (int)base.rollRateDegreesPerSecond);
            Core.settings.put("fpv-yaw-rate", (int)base.yawRateDegreesPerSecond);
            Core.settings.put("fpv-expo", (int)(base.rateExpo * 100));
            Unit small = UnitTypes.flare.create(Team.sharded);
            Unit medium = UnitTypes.antumbra.create(Team.sharded);
            Unit huge = UnitTypes.eclipse.create(Team.sharded);
            var settings = new FlightSettings(base);
            var controller = new FlightController(base);
            var a = settings.configure(controller, small);
            require("flare".equals(FlightSettings.metadata(small).nativeTypeName()),
                    "adapter retains exact native Flare identity");
            var nativeFlare = FlightSettings.metadata(small);
            var genericFlare = new FlightProfile.UnitMetadata(nativeFlare.nativeSpeedTilesPerSecond(),
                    nativeFlare.hitSizeTiles(), nativeFlare.maxHealth(), nativeFlare.flying());
            var priorFlare = FlightProfile.resolve(base, genericFlare, FlightProfile.preset(1));
            require(Math.abs(a.practicalSpeedMps() / priorFlare.practicalSpeedMps() - 1.5) < 1e-9,
                    "native Flare receives requested speed bonus without changing its metadata");
            require(settings.configure(controller, small) == a, "unchanged settings reuse cached resolved profile");
            var b = settings.configure(controller, medium);
            var c = settings.configure(controller, huge);
            require(a.massKg() < b.massKg() && b.massKg() < c.massKg(), "real native sizes produce increasing mass");
            require(a.practicalSpeedMps() > b.practicalSpeedMps() && b.practicalSpeedMps() > c.practicalSpeedMps(),
                    "real native sizes produce different practical speeds");
            double smallSpeed = sustained(a.profile()), hugeSpeed = sustained(c.profile());
            require(Math.abs(smallSpeed / sustained(priorFlare.profile()) - 1.5) < .025,
                    "native Flare actually flies about 1.5x its previous Freestyle speed");
            require(smallSpeed > hugeSpeed * 1.5, "actual integration makes small unit substantially faster than huge unit");
            require(!small.isAdded() && !medium.isAdded() && !huge.isAdded(), "profile checks never spawn world entities");
            require(small.type == UnitTypes.flare && medium.type == UnitTypes.antumbra && huge.type == UnitTypes.eclipse,
                    "native UnitType identities retained");
            double targetMass = FlightProfile.baseMassKg(FlightSettings.metadata(huge));

            for(int preset=0;preset<=4;preset++){
                Core.settings.put("fpv-performance-preset",preset);
                Core.settings.put("fpv-flight-speed",50);
                var slow=settings.configure(controller,huge);
                Core.settings.put("fpv-flight-speed",100);
                var fast=settings.configure(controller,huge);
                require(fast.practicalSpeedMps()>slow.practicalSpeedMps()*1.8,"live speed applies in preset "+preset);
                require(sustained(fast.profile())>sustained(slow.profile())*1.7,"measured actual flight responds in preset "+preset);
                require(fast.massKg()==slow.massKg()&&fast.profile().maxThrustNewtons==slow.profile().maxThrustNewtons,
                    "live speed retains mass and motors in preset "+preset);
            }

            controller.state().x = 3;
            controller.state().y = 4;
            controller.state().z = 5;
            controller.state().vx = 7;
            Core.settings.put("fpv-performance-preset", 3);
            FlightProfile insane = settings.configure(controller, huge);
            require(controller.state().x == 3 && controller.state().y == 4 && controller.state().z == 5
                    && controller.state().vx == 7, "preset change preserves XYZ and momentum");
            require(insane.practicalSpeedMps() > c.practicalSpeedMps(), "Insane remains a modifier on the huge unit");
            require(FlightProfile.baseMassKg(FlightSettings.metadata(huge)) == targetMass,
                    "target base mass ignores player's preset");

            // Corrupted preference types and extreme integers stay inside declared bounds.
            Core.settings.put("fpv-performance-preset", Integer.MAX_VALUE);
            Core.settings.put("fpv-power", "bad saved type");
            Core.settings.put("fpv-speed-reference", Integer.MAX_VALUE);
            Core.settings.put("fpv-mass", Integer.MIN_VALUE);
            Core.settings.put("fpv-drag", Integer.MIN_VALUE);
            Core.settings.put("fpv-response", Integer.MAX_VALUE);
            Core.settings.put("fpv-acceleration", Integer.MAX_VALUE);
            Core.settings.put("fpv-rate-scale", Integer.MAX_VALUE);
            // Arc accepts strings but rejects Double before the adapter can read it.
            // A stored nonnumeric string exercises the supported corruption path.
            Core.settings.put("fpv-pitch-rate", "NaN");
            Core.settings.put("fpv-roll-rate", Integer.MAX_VALUE);
            Core.settings.put("fpv-yaw-rate", Integer.MIN_VALUE);
            Core.settings.put("fpv-expo", Integer.MAX_VALUE);
            FlightProfile custom = settings.configure(controller, small);
            require(Double.isFinite(custom.massKg()) && custom.practicalSpeedMps() <= PerformanceConfig.MAX_PRACTICAL_SPEED_MPS,
                    "corrupted preferences resolve a bounded finite profile");
            require(custom.profile().rateExpo == 1 && custom.profile().rollRateDegreesPerSecond <= PerformanceConfig.MAX_RATE_DEGREES_PER_SECOND,
                    "corrupted saved rates are bounded");
            return "passed: native Flare/Antumbra/Eclipse metadata, measured small/huge flight speeds, "
                    + "cached settings, preserved XYZ/momentum, independent target mass, bounded corrupted preferences";
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (existed[i]) Core.settings.put(keys[i], old[i]);
                else Core.settings.remove(keys[i]);
            }
        }
    }

    private static double sustained(FlightController.Profile profile) {
        var controller = new FlightController(profile);
        double tilt = -Math.acos(profile.gravityMps2 / (profile.maxThrustNewtons / profile.massKg));
        controller.state().qx = Math.sin(tilt / 2); controller.state().qw = Math.cos(tilt / 2);
        for (int i = 0; i < 4000; i++) controller.step(profile.fixedStepSeconds, 1, 0, 0, 0);
        return Math.hypot(controller.state().vx, controller.state().vz) * profile.metersPerTile;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Flight settings smoke failed: " + label);
    }
}
