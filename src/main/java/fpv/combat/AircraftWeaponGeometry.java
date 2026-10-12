package fpv.combat;

import fpv.render.SpriteExtrusionGeometry;
import fpv.world.WorldCoordinates.Point;

/** Aircraft-local right/up/forward; canonical XYZ uses the renderer's established full attitude. */
public final class AircraftWeaponGeometry {
    public record Frame(Point right,Point up,Point forward){
        public Point world(double right,double up,double forward){return new Point(this.right.x()*right+this.up.x()*up+this.forward.x()*forward,this.right.y()*right+this.up.y()*up+this.forward.y()*forward,this.right.z()*right+this.up.z()*up+this.forward.z()*forward);}
        public Point local(Point p){return new Point(dot(p,right),dot(p,up),dot(p,forward));}
    }
    public record Profile(boolean bomber,double yawLimit,double pitchLimit,double hysteresis){
        public Profile{if(!Double.isFinite(yawLimit+pitchLimit+hysteresis)||yawLimit<0||yawLimit>180||pitchLimit<0||pitchLimit>90||hysteresis<0||hysteresis>15)throw new IllegalArgumentException("Aircraft angular profile");}
    }
    public static Frame frame(double yaw,double pitch,double roll){
        double[] b=new double[9];SpriteExtrusionGeometry.basis(yaw,pitch,roll,b);
        return new Frame(new Point(b[0],-b[2],b[1]),new Point(b[3],-b[5],b[4]),new Point(-b[6],b[8],-b[7]));
    }
    public static double dot(Point a,Point b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    public static double yaw(Point local){return Math.toDegrees(Math.atan2(local.x(),local.z()));}
    public static double pitch(Point local){return Math.toDegrees(Math.atan2(local.y(),Math.hypot(local.x(),local.z())));}
    public static int side(double pitch,int previous,double hysteresis){return pitch>hysteresis?1:pitch<-hysteresis?-1:previous==0?(pitch<0?-1:1):previous;}
    public static boolean allowed(Profile p,double yaw,double pitch){return Double.isFinite(yaw+pitch)&&Math.abs(yaw)<=p.yawLimit()+1e-6&&(p.bomber()?pitch<=-1&&pitch>=-90:Math.abs(pitch)<=p.pitchLimit()+1e-6);}
    public static Point direction(Frame f,double yaw,double pitch){double y=Math.toRadians(yaw),p=Math.toRadians(pitch);return f.world(Math.sin(y)*Math.cos(p),Math.sin(p),Math.cos(y)*Math.cos(p));}
    public static Point add(Point a,Point b){return new Point(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    public static Point scale(Point a,double s){return new Point(a.x()*s,a.y()*s,a.z()*s);}
    public static Point difference(Point a,Point b){return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    public static boolean intersectsBody(Point localMuzzle,Point localDirection,double radius,double halfHeight){
        double enter=0,exit=Double.POSITIVE_INFINITY;
        double[] origin={localMuzzle.x(),localMuzzle.y(),localMuzzle.z()},direction={localDirection.x(),localDirection.y(),localDirection.z()},extent={radius,halfHeight,radius};
        for(int i=0;i<3;i++){
            if(Math.abs(direction[i])<1e-10){if(Math.abs(origin[i])>extent[i])return false;continue;}
            double a=(-extent[i]-origin[i])/direction[i],b=(extent[i]-origin[i])/direction[i];enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));
        }return exit>=enter&&exit>1e-6;
    }
}
