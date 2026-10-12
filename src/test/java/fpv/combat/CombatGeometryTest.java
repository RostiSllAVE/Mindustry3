package fpv.combat;

/** Swept XYZ contacts must distinguish altitude without depending on native XY collision. */
public final class CombatGeometryTest {
    private static int checks;
    public static void main(String[] args){
        altitudeAndVerticalShots();continuousContacts();movingFrameSubsteps();structureBounds();aimAndRange();
        System.out.println("CombatGeometry: "+checks+" assertions passed (true XYZ projectile/beam contact). ");
    }
    private static void altitudeAndVerticalShots(){
        equal(cylinder(-10,0,3,10,0,3),.475,1e-10,"horizontal body hit");
        miss(cylinder(-10,0,4,10,0,4),"same XY path above body misses");
        miss(cylinder(-10,0,2,10,0,2),"same XY path below body misses");
        equal(cylinder(0,0,10,0,0,0),.68,1e-10,"vertical downward shot enters top");
        equal(cylinder(0,0,0,0,0,10),.28,1e-10,"vertical upward shot enters bottom");
        equal(cylinder(0,0,3,0,0,4),0,0,"inside muzzle resolves overlap immediately");
        miss(cylinder(.6,0,10,.6,0,0),"vertical line beside cylinder misses");
        equal(cylinder(0,0,3,0,0,3),0,0,"stationary point inside overlaps");
        miss(cylinder(1,0,3,1,0,3),"stationary point outside misses");
        // Oppositely moving target: transform both segment endpoints into the target frame.
        equal(CombatGeometry.cylinder(-2,0,0,2,0,0,0,0,0,.5,.2),.375,1e-10,"relative target motion remains continuous");
    }
    private static void continuousContacts(){
        for(int distance=1;distance<=1000;distance+=7){
            double hit=cylinder(-distance,0,3,distance,0,3);
            equal(hit,(distance-.5)/(2*distance),1e-10,"long fast segment does not tunnel");
            miss(cylinder(-distance,0,3.201,distance,0,3.201),"arbitrarily fast elevated pass misses");
        }
        equal(cylinder(-5,.5,3,5,.5,3),.5,1e-10,"tangent silhouette contact");
        miss(cylinder(-5,.5001,3,5,.5001,3),"just outside tangent misses");
        equal(cylinder(5,0,3,-5,0,3),.45,1e-10,"reverse ray is symmetric");
        miss(cylinder(-5,0,0,5,0,3),"diagonal line can cross XY and altitude at different times");
    }
    private static void structureBounds(){
        equal(box(-10,0,1,10,0,1),.4,1e-10,"solid building front face");
        equal(box(0,0,8,0,0,-2),.5,1e-10,"downward beam roof contact");
        miss(box(-10,0,3.01,10,0,3.01),"beam above roof misses");
        equal(box(0,0,1,10,0,1),0,0,"inside structure contact");
        miss(box(-10,2.01,1,10,2.01,1),"outside structure side misses");
        equal(box(-10,2,1,10,2,1),.4,1e-10,"structure corner remains physical");
    }
    private static void movingFrameSubsteps(){
        // At frame end the target is y=8, but it crossed the stationary projectile at t=.2.
        equal(CombatGeometry.movingCylinder(0,0,3,0,0,3,0,8,3,0,10,0,.5,.2,.25,.75),.6,1e-10,"early substep rewinds target from frame end");
        miss(CombatGeometry.movingCylinder(0,0,3,0,0,3,0,8,3,0,10,0,.5,.2,.25,0),"late substep does not repeat an earlier crossing");
        equal(CombatGeometry.movingCylinder(0,0,3,0,0,3,0,0,11,0,0,10,.5,.2,.25,.75),.72,1e-10,"vertical moving body uses same frame clock");
        miss(CombatGeometry.movingCylinder(0,0,4,0,0,4,0,8,3,0,10,0,.5,.2,.25,.75),"moving XY crossing at wrong height still misses");
    }
    private static void aimAndRange(){
        var up=CombatGeometry.aim(90,10,10);
        equal(up.x(),0,1e-10,"native azimuth retained");equal(up.y(),Math.sqrt(.5),1e-10,"horizontal speed reduces with climb");
        equal(up.z(),Math.sqrt(.5),1e-10,"physical target height produces pitch");
        var turned=CombatGeometry.rotateAzimuth(up,90);
        equal(turned.x(),-Math.sqrt(.5),1e-10,"native spread rotates around altitude axis");equal(turned.z(),up.z(),0,"spread preserves elevation");
        var straight=CombatGeometry.direction(0,0,-9);
        equal(straight.z(),-1,0,"vertical camera aim normalizes");
        var huge=CombatGeometry.direction(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE);
        equal(huge.x(),1/Math.sqrt(3),1e-12,"finite extreme vector does not overflow while normalizing");
        var extreme=CombatGeometry.aim(270,0,720);
        equal(extreme.z(),1,1e-10,"same-XY extreme altitude has finite vertical aim");
        equal(Math.sqrt(up.x()*up.x()+up.y()*up.y()+up.z()*up.z())*12,12,1e-10,"range is length along XYZ ray");
        rejects(()->CombatGeometry.direction(0,0,0),"zero aim is invalid");
        rejects(()->CombatGeometry.direction(Double.NaN,0,0),"nonfinite aim rejected");
        rejects(()->CombatGeometry.cylinder(0,0,0,1,1,1,0,0,0,-1,1),"negative radius rejected");
    }
    private static double cylinder(double ax,double ay,double az,double bx,double by,double bz){return CombatGeometry.cylinder(ax,ay,az,bx,by,bz,0,0,3,.5,.2);}
    private static double box(double ax,double ay,double az,double bx,double by,double bz){return CombatGeometry.box(ax,ay,az,bx,by,bz,-2,-2,0,2,2,3);}
    private static void equal(double actual,double expected,double tolerance,String label){yes(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,label+": "+actual+" != "+expected);}
    private static void miss(double actual,String label){yes(actual==Double.POSITIVE_INFINITY,label);}
    private static void yes(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
    private static void rejects(Runnable action,String label){try{action.run();}catch(IllegalArgumentException expected){yes(true,label);return;}yes(false,label);}
}
