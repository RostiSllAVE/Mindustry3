package fpv.combat;
import fpv.world.WorldCoordinates.Point;
import fpv.render.AircraftBombPortGeometry;
import java.util.*;
public final class AircraftWeaponGeometryTest {
    private static int checks;
    static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        for(double yaw:new double[]{0,90,180,270})for(double pitch:new double[]{-80,-30,0,30,80})for(double roll:new double[]{-90,0,90,180,270}){
            var f=AircraftWeaponGeometry.frame(yaw,pitch,roll);
            for(var p:List.of(new Point(1,0,0),new Point(0,1,0),new Point(0,0,1),new Point(.2,-.3,.9))){var q=f.local(f.world(p.x(),p.y(),p.z()));check(Math.abs(q.x()-p.x())+Math.abs(q.y()-p.y())+Math.abs(q.z()-p.z())<1e-9,"full attitude roundtrip");}
            check(Math.abs(AircraftWeaponGeometry.dot(f.right(),f.up()))<1e-9,"orthogonal basis");
        }
        var upside=AircraftWeaponGeometry.frame(0,0,180);check(AircraftWeaponGeometry.pitch(upside.local(new Point(1,0,1)))<-40,"world sky is local underside when inverted");
        check(AircraftWeaponGeometry.pitch(upside.local(new Point(1,0,-1)))>40,"world ground is local top when inverted");
        int side=-1;for(double p:new double[]{-2,2,-1,1,0}){side=AircraftWeaponGeometry.side(p,side,5);check(side==-1,"hysteresis doesn't flicker");}check(AircraftWeaponGeometry.side(6,side,5)==1,"crossing hysteresis changes side");
        var gun=new AircraftWeaponGeometry.Profile(false,45,35,5);check(!AircraftWeaponGeometry.allowed(gun,0,-89),"ordinary gun cannot bomb vertically");check(!AircraftWeaponGeometry.allowed(gun,91,0),"no rear gimbal firing");check(AircraftWeaponGeometry.allowed(new AircraftWeaponGeometry.Profile(true,180,90,5),0,-89),"bomber downward sector");
        check(!AircraftWeaponGeometry.intersectsBody(new Point(0,.5,1.1),new Point(0,.3,1),1,.2),"top muzzle clears hull");
        check(!AircraftWeaponGeometry.intersectsBody(new Point(0,-.5,0),new Point(1,0,0),1,.2),"underside bomb release clears hull");
        check(AircraftWeaponGeometry.intersectsBody(new Point(0,.3,0),new Point(0,-1,0),1,.2),"cannot shoot back into own hull");
        check(!AircraftWeaponGeometry.intersectsBody(new Point(0,.3,0),new Point(0,1,0),1,.2),"away from hull is legal");
        var old=List.of(new MechWeaponPitchCodec.Entry(5,3,1,new float[]{20}));var aircraft=List.of(new AircraftWeaponCodec.Entry(6,4,2,new float[]{-30},new float[]{-90},new byte[]{-1}));var packet=AircraftWeaponCodec.encode(old,aircraft);var decoded=AircraftWeaponCodec.decode(packet);check(decoded.existing().get(0).pitch(0)==20&&decoded.aircraft().get(0).side()[0]==-1,"existing channel preserves old pitch and new local orientation");
        aircraft.get(0).yaw()[0]=99;aircraft.get(0).side()[0]=1;check(aircraft.get(0).yaw(0)==-30&&aircraft.get(0).side(0)==-1,"snapshot arrays cannot mutate cached state");
        var limits=new AircraftWeaponCodec.Limits(30,40,6);check(AircraftWeaponCodec.decode(AircraftWeaponCodec.encode(old,aircraft,limits)).limits().equals(limits),"host limits are replicated on existing channel");
        for(int n=0;n<packet.length;n++){boolean fail=false;try{AircraftWeaponCodec.decode(Arrays.copyOf(packet,n));}catch(IllegalArgumentException e){fail=true;}check(fail,"truncated state rejected");}
        var ring=AircraftBombPortGeometry.generate();check(ring.length==8*4*6*8,"shared eight-sided port is64 triangles");for(int i=0;i<ring.length;i+=8){check(Math.hypot(ring[i],ring[i+2])>=.249,"opening remains hollow");check(Float.isFinite(ring[i+1]),"finite geometry");}
        System.out.println("AircraftWeaponGeometryTest: "+checks+" local rotation/inversion/hysteresis/sectors/codec/hollow-port checks passed");
    }
}
