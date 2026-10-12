package fpv.net;

import fpv.flight.FlightController;
import fpv.generated.NetworkConfig;
import fpv.world.Unit3DState;
import java.util.*;

/** Display-only interpolation. Never supplies server physics or health and never extrapolates. */
public final class RemotePoses {
    private Map<Integer,Unit3DState> previous=Map.of(),current=Map.of();
    private NetProtocol.Flight priorFlight,flight;
    private double previousTime,currentTime;
    public void clear(){previous=current=Map.of();priorFlight=flight=null;previousTime=currentTime=0;}
    public void accept(NetProtocol.Snapshot value,double received){
        if(!Double.isFinite(received))throw new IllegalArgumentException("Finite clock required");
        previous=current;priorFlight=flight;previousTime=currentTime;currentTime=received;
        Map<Integer,Unit3DState> next=new HashMap<>();for(var state:value.units())next.put(state.unitId(),state);current=Map.copyOf(next);flight=value.local();
    }
    public NetProtocol.Flight latestFlight(){return flight;}
    public Unit3DState latest(int id){return current.get(id);}
    private double alpha(double now){return currentTime<=previousTime?1:Math.max(0,Math.min(1,(now-NetworkConfig.INTERPOLATION_DELAY_SECONDS-previousTime)/(currentTime-previousTime)));}
    public Unit3DState display(int id,double now){
        Unit3DState b=current.get(id),a=previous.get(id);if(b==null||a==null||a.typeId()!=b.typeId()||a.teamId()!=b.teamId()||a.distanceSquared(b)>4096)return b;
        double t=alpha(now);return new Unit3DState(b.unitId(),b.typeId(),b.teamId(),mix(a.x(),b.x(),t),mix(a.y(),b.y(),t),mix(a.z(),b.z(),t),b.vx(),b.vy(),b.vz(),angle(a.yaw(),b.yaw(),t),angle(a.pitch(),b.pitch(),t),angle(a.roll(),b.roll(),t),b.desiredAltitude(),b.radius(),b.halfHeight(),b.flying());
    }
    public boolean copyFlight(FlightController target,double now){
        if(flight==null)return false;target.configureProfile(flight.profile());var b=flight.state();var a=priorFlight==null?null:priorFlight.state();var out=target.state();NetProtocol.copy(b,out);
        if(a==null||priorFlight.unitId()!=flight.unitId()||sq(a.x-b.x)+sq(a.y-b.y)+sq(a.z-b.z)>4096)return true;
        double t=alpha(now);out.x=mix(a.x,b.x,t);out.y=mix(a.y,b.y,t);out.z=mix(a.z,b.z,t);
        double sign=a.qx*b.qx+a.qy*b.qy+a.qz*b.qz+a.qw*b.qw<0?-1:1;
        out.qx=mix(a.qx,b.qx*sign,t);out.qy=mix(a.qy,b.qy*sign,t);out.qz=mix(a.qz,b.qz*sign,t);out.qw=mix(a.qw,b.qw*sign,t);
        double norm=Math.sqrt(sq(out.qx)+sq(out.qy)+sq(out.qz)+sq(out.qw));out.qx/=norm;out.qy/=norm;out.qz/=norm;out.qw/=norm;return true;
    }
    private static double mix(double a,double b,double t){return a+(b-a)*t;}
    private static double angle(double a,double b,double t){return a+Math.IEEEremainder(b-a,360)*t;}
    private static double sq(double v){return v*v;}
}
