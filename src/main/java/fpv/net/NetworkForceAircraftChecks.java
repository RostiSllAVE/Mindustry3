package fpv.net;

import arc.Core;
import fpv.defense.ForceProjectorSystem;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Unit;

/** Narrow checks for actual replicated host policy in the isolated two-client process lab. */
public final class NetworkForceAircraftChecks {
    private NetworkForceAircraftChecks(){}
    public static void disagreeWithHost(int stage){
        if(stage>=29&&stage<=31)Core.settings.put(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,stage!=29);
    }
    public static boolean clientReady(int stage,ForceProjectorSystem fields,Building projector,Unit enemy,Unit ally){
        if(!Vars.net.client()||System.getProperty("fpv.netSmokeRoot","").isBlank())throw new IllegalStateException("Isolated native client only");
        boolean enabled=stage==29;if(fields.aircraftCollisionEnabled()!=enabled)return false;
        require(Core.settings.getBool(ForceProjectorSystem.AIRCRAFT_COLLISION_SETTING,false)!=enabled,"local preference deliberately contradicts host");
        if(stage==30)return true; // Collapsed-state geometry is checked by the enclosing native fixture.
        var v=fields.volume(projector);if(v==null||enemy==null||ally==null)return false;
        require(enemy.team!=projector.team&&ally.team==projector.team,"fixture uses actual hostile and allied pilot units");
        double ax=v.x(),ay=v.y()-v.radius()-2,bx=v.x(),by=v.y()+v.radius()+2,z=(v.bottom()+v.top())*.5;
        require((fields.sweepUnit(enemy,ax,ay,z,bx,by,z,.5,.2)!=null)==enabled,"replicated host flag controls actual hostile XYZ aircraft boundary");
        require(fields.sweepUnit(ally,ax,ay,z,bx,by,z,.5,.2)==null,"allied pilot passes the host-owned shield");
        require(fields.trace(enemy.team,ax,ay,z,bx,by,z,0)!=null,"projectile shield stays active regardless of aircraft option");
        return true;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError("Network Force aircraft: "+message);}
}
