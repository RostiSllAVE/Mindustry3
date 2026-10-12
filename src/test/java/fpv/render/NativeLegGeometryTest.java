package fpv.render;

import fpv.units.ground.NativeLegLayout;

/** Native proportional geometry and planted gait are deterministic without loading a game or GL. */
public final class NativeLegGeometryTest {
    private static int checks;
    public static void main(String[] args){
        var nativeGeometry=new NativeLegLayout.Config(9.375,1,1,0,0,1,-.5,2,true,false);
        check(Math.abs(NativeLegLayout.spread(nativeGeometry,8)-10.375)<1e-9,"native Toxopid-scale footprint uses original long span");
        for(int count:new int[]{4,6,8})for(int leg=0;leg<count;leg++){
            double x=NativeLegLayout.footX(nativeGeometry,leg,count,0),y=NativeLegLayout.footY(nativeGeometry,leg,count,0);
            check(Math.abs(NativeLegLayout.footX(nativeGeometry,leg,count,90)+y)<1e-8,"layout rotates with body X");
            check(Math.abs(NativeLegLayout.footY(nativeGeometry,leg,count,90)-x)<1e-8,"layout rotates with body Y");
            check(Math.abs(Math.hypot(x-NativeLegLayout.hipX(nativeGeometry,leg,count,0),y-NativeLegLayout.hipY(nativeGeometry,leg,count,0))-9.375)<1e-8,"native link span excludes hip offset");
        }
        var straight=new NativeLegLayout.Config(4,1,.5,1,1,1.4,0,2,false,false);
        for(int leg=0;leg<6;leg++)check(Math.abs(Math.cos(NativeLegLayout.angle(straight,leg,6,0)))<1e-8,"native straight layout points toward paired flanks");
        check(NativeLegLayout.bendSide(nativeGeometry,3,8)==-1&&NativeLegLayout.bendSide(nativeGeometry,4,8)==1,"native back-leg bend reversal retained");
        double[] knee=new double[3],mirror=new double[3];
        for(double height:new double[]{.5,5,30}){
            ArticulatedLegIK.knee(0,0,height,8,0,0,9.375,1,0,1,knee);
            ArticulatedLegIK.knee(0,0,height,8,0,0,9.375,1,0,-1,mirror);
            double a=Math.sqrt(knee[0]*knee[0]+knee[1]*knee[1]+(knee[2]-height)*(knee[2]-height));
            double b=Math.sqrt((knee[0]-8)*(knee[0]-8)+knee[1]*knee[1]+knee[2]*knee[2]);
            check(Math.abs(a-b)<1e-8,"IK preserves equal upper/lower links while telescoping");
            check(knee[1]>.3&&mirror[1]<-.3,"visible stable outward mirrored knees, never table pegs");
            check(Math.abs(knee[0]-mirror[0])+Math.abs(knee[2]-mirror[2])+Math.abs(knee[1]+mirror[1])<1e-8,"symmetric native knee handedness");
        }
        boolean rejected=false;try{ArticulatedLegIK.knee(0,0,0,Double.NaN,0,0,1,1,0,1,knee);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"reject invalid physical endpoints");
        GroundGait gait=new GroundGait(8);gait.update(0,0,0,10.375,2,.016,(x,y)->x>2?.8:0,nativeGeometry);
        double fixedX=gait.x(1),fixedY=gait.y(1);boolean lifted=false;
        for(int frame=0;frame<160;frame++){
            gait.update(frame*.03,0,frame*.004,10.375,2,.016,(x,y)->x>2?.8:0,nativeGeometry);
            int planted=0;for(int leg=0;leg<8;leg++){
                if(gait.planted(leg))planted++;else lifted=true;
                check(gait.z(leg)>=(gait.x(leg)>2?.8:0),"foot avoids terrain penetration during swing");
            }
            check(planted>=6,"native groupSize2 swings only one staggered pair");
            if(frame==1)check(gait.x(1)==fixedX&&gait.y(1)==fixedY,"unselected feet stay anchored during translation and yaw");
        }
        check(lifted,"native long legs actually step while body travels");
        double px=gait.x(0),py=gait.y(0),pz=gait.z(0);gait.update(4.77,0,.636,10.375,2,0,(x,y)->x>2?.8:0,nativeGeometry);
        check(gait.x(0)==px&&gait.y(0)==py&&gait.z(0)==pz,"pause freezes planted and swinging pose");
        System.out.println("NativeLegGeometry: "+checks+" assertions passed (native spread, mirrored IK, telescoping, staggered support and pause).");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
