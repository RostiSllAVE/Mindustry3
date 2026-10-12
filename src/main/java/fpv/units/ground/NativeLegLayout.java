package fpv.units.ground;

import mindustry.Vars;
import mindustry.type.UnitType;

/** Native LegsComp layout in canonical XY tiles. Uses resolved definition values, never sprite-name guesses. */
public final class NativeLegLayout {
    public record Config(double length,double lengthScale,double baseOffset,double straightness,double baseStraightness,
                         double straightLength,double extension,int groupSize,boolean flipBack,boolean flipSide){
        public Config{if(!Double.isFinite(length+lengthScale+baseOffset+straightness+baseStraightness+straightLength+extension)||length<=0||lengthScale<=0||groupSize<1)throw new IllegalArgumentException("Native leg geometry required");}
    }
    private NativeLegLayout(){}
    public static Config config(UnitType t){return new Config(t.legLength/Vars.tilesize,t.legLengthScl,t.legBaseOffset/Vars.tilesize,
        t.legStraightness,t.baseLegStraightness,t.legStraightLength,t.legExtension/Vars.tilesize,Math.max(1,t.legGroupSize),t.flipBackLegs,t.flipLegSide);}
    public static double angle(UnitType t,int i,int count,double yaw){return angle(t.legStraightness,i,count,yaw);}
    public static double angle(Config c,int i,int count,double yaw){return angle(c.straightness,i,count,yaw);}
    private static double angle(double straight,int i,int count,double yaw){
        double original=(i+.5)*Math.PI*2/count,side=i>=count/2?-Math.PI/2:Math.PI/2;
        double delta=Math.atan2(Math.sin(side-original),Math.cos(side-original));
        return Math.toRadians(yaw)+original+delta*straight;
    }
    public static double hipX(UnitType t,int i,int count,double yaw){return hip(t.legBaseOffset/Vars.tilesize,t.legStraightness,t.baseLegStraightness,t.legStraightLength,i,count,yaw,false);}
    public static double hipY(UnitType t,int i,int count,double yaw){return hip(t.legBaseOffset/Vars.tilesize,t.legStraightness,t.baseLegStraightness,t.legStraightLength,i,count,yaw,true);}
    public static double hipX(Config c,int i,int count,double yaw){return hip(c.baseOffset,c.straightness,c.baseStraightness,c.straightLength,i,count,yaw,false);}
    public static double hipY(Config c,int i,int count,double yaw){return hip(c.baseOffset,c.straightness,c.baseStraightness,c.straightLength,i,count,yaw,true);}
    private static double hip(double base,double straight,double baseStraight,double straightLength,int i,int count,double yaw,boolean y){
        double a=(i+.5)*Math.PI*2/count,lx=Math.cos(a)*base,ly=Math.sin(a)*base;
        if(straight>0)ly+=(Math.signum(ly)*base*straightLength-ly)*baseStraight;
        double r=Math.toRadians(yaw);return y?lx*Math.sin(r)+ly*Math.cos(r):lx*Math.cos(r)-ly*Math.sin(r);
    }
    public static double footX(UnitType t,int i,int count,double yaw){return hipX(t,i,count,yaw)+Math.cos(angle(t,i,count,yaw))*t.legLength/Vars.tilesize*t.legLengthScl;}
    public static double footY(UnitType t,int i,int count,double yaw){return hipY(t,i,count,yaw)+Math.sin(angle(t,i,count,yaw))*t.legLength/Vars.tilesize*t.legLengthScl;}
    public static double footX(Config c,int i,int count,double yaw){return hipX(c,i,count,yaw)+Math.cos(angle(c,i,count,yaw))*c.length*c.lengthScale;}
    public static double footY(Config c,int i,int count,double yaw){return hipY(c,i,count,yaw)+Math.sin(angle(c,i,count,yaw))*c.length*c.lengthScale;}
    public static double spread(UnitType t,int count){double max=0;for(int i=0;i<count;i++)max=Math.max(max,Math.hypot(footX(t,i,count,0),footY(t,i,count,0)));return max;}
    public static double spread(Config c,int count){double max=0;for(int i=0;i<count;i++)max=Math.max(max,Math.hypot(footX(c,i,count,0),footY(c,i,count,0)));return max;}
    public static int bendSide(UnitType t,int i,int count){return bend(t.flipBackLegs,t.flipLegSide,i,count);}
    public static int bendSide(Config c,int i,int count){return bend(c.flipBack,c.flipSide,i,count);}
    private static int bend(boolean flipBack,boolean flipSide,int i,int count){boolean side=i<count/2;if(Math.abs((i+.5)-count/2.0)<=.501&&flipBack)side=!side;if(flipSide)side=!side;return side?1:-1;}
}
