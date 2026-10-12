package fpv.control;

import fpv.flight.FlightController;
import fpv.generated.NavigationConfig;

/** Autopilot descent commands, in SI speed with the same response/acceleration as stabilized flight. */
public final class CreatorLandingMotion {
    private CreatorLandingMotion(){}
    public static boolean gentleTouchdown(double verticalTilesPerSecond,FlightController.Profile profile){
        if(profile==null||!Double.isFinite(verticalTilesPerSecond))throw new IllegalArgumentException("Finite touchdown motion required");
        return Math.abs(verticalTilesPerSecond)*profile.metersPerTile<=fpv.generated.CrashConfig.SAFE_SPEED_MPS;
    }
    public static double descentAxis(double gapTiles,FlightController.Profile profile,double speedMps){
        if(profile==null||!Double.isFinite(gapTiles+speedMps)||speedMps<0)throw new IllegalArgumentException("Finite landing geometry/speed required");
        if(gapTiles<=0||speedMps==0)return 0;
        double gap=gapTiles*profile.metersPerTile;
        double acceleration=Math.max(profile.gravityMps2,profile.maxThrustNewtons/profile.massKg);
        // The critically damped response envelope slows near contact independently of the unit's
        // configured cruise speed. Half available acceleration leaves a braking-distance margin.
        double responseBound=gap/(4*NavigationConfig.STABILIZED_RESPONSE_SECONDS);
        double brakingBound=Math.sqrt(acceleration*gap);
        return -Math.min(1,Math.min(responseBound,brakingBound)/speedMps);
    }
}
