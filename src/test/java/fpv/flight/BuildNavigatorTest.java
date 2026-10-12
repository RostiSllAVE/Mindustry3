package fpv.flight;

import fpv.ui.ViewSettings;

public final class BuildNavigatorTest {
    private static int checks;
    public static void main(String[] args){
        var nav=new BuildNavigator(2);var s=new FlightController.State();s.y=10;
        nav.step(s,.1,1,0,0,0,20);close(s.z,-1,"forward map displacement");close(s.y,10,"no gravity in stabilized navigation");
        nav.step(s,.1,1,1,1,0,20);close(Math.sqrt(s.vx*s.vx+s.vy*s.vy+s.vz*s.vz),10,"diagonal speed normalized");
        double x=s.x,z=s.z;nav.step(s,.1,0,0,0,1,20);close(s.x,x,"turn only preserves X");close(s.z,z,"turn only preserves Z");
        close(s.vx+s.vy+s.vz,0,"release holds position");
        nav.step(s,.1,1,0,0,0,0);close(s.vx+s.vy+s.vz,0,"zero build speed");
        close(s.qx+s.qz,0,"body remains level");
        close(ViewSettings.tiltFromAngle(0),-90,"look directly down");close(ViewSettings.tiltFromAngle(90),0,"look forward");
        close(ViewSettings.tiltFromAngle(180),90,"look directly up");
        close(ViewSettings.perspectiveFov(0),1,"zero FOV safe projection");close(ViewSettings.perspectiveFov(180),179,"180 FOV safe projection");
        System.out.println("BuildNavigator: "+checks+" assertions passed.");
    }
    private static void close(double a,double b,String message){checks++;if(Math.abs(a-b)>1e-9)throw new AssertionError(message+": "+a);}
}
