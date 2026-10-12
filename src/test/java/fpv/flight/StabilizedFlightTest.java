package fpv.flight;

import fpv.generated.FlightProfiles;

public final class StabilizedFlightTest {
    private static int checks;
    public static void main(String[] args){
        var profile=FlightProfiles.create();var nav=new StabilizedFlight();var view=new FlightView();
        var a=new FlightController.State();var b=new FlightController.State();a.y=b.y=100;
        double[] look=new double[3];
        for(int i=0;i<100;i++){
            view.look(51,7);view.forward(look);
            nav.step(a,profile,.02,1,0,0,40,null);
            nav.step(b,profile,.02,1,0,0,40,null);
            check(a.x==b.x&&a.y==b.y&&a.z==b.z,"free look independent from travel");
            check(Math.abs(Math.sqrt(look[0]*look[0]+look[1]*look[1]+look[2]*look[2])-1)<1e-12,"normalized look");
        }
        check(a.z<0&&a.x==0&&a.y==100,"north moves on horizontal world axis");
        for(int i=0;i<250;i++)nav.step(a,profile,.02,0,0,0,40,null);
        check(Math.abs(a.vz)<1e-6,"autopilot brakes to hold");
        for(int i=0;i<300;i++)nav.step(a,profile,.02,1,1,1,40,null);
        check(Math.sqrt(a.vx*a.vx+a.vy*a.vy+a.vz*a.vz)<=40/profile.metersPerTile+1e-9,"diagonal limited");
        check(a.qx==0&&a.qz==0&&a.wx+a.wy+a.wz==0,"level attitude");
        int[] calls={0};double ox=a.x;
        nav.step(a,profile,.1,0,1,0,400,(s,x,y,z,dt,elapsed)->{calls[0]++;s.x=ox;s.vx=0;});
        check(calls[0]>=25&&a.x==ox,"collision evaluated each physical substep");
        view.align(0,0,-1);view.look(90,0);view.forward(look);check(Math.abs(look[0]-1)<1e-12,"rightward mouse looks east");
        for(int heading=0;heading<360;heading+=15){
            var c=new FlightController.State();c.y=100;
            double yaw=Math.toRadians(heading);
            nav.step(c,profile,.1,1,0,0,40,yaw,null);
            check(Math.abs(c.x*Math.cos(yaw)+c.z*Math.sin(yaw))<1e-10,"forward follows horizontal heading");
            check(c.x*Math.sin(yaw)-c.z*Math.cos(yaw)>0&&c.y==100,"forward keeps altitude");
            c=new FlightController.State();c.y=100;
            nav.step(c,profile,.1,0,1,0,40,yaw,null);
            check(c.x*Math.cos(yaw)+c.z*Math.sin(yaw)>0,"strafe goes right of heading");
            check(Math.abs(c.qy-Math.sin(-yaw*.5))<1e-12,"strafe does not turn nose away from guns");
            nav.step(c,profile,.1,0,0,0,40,yaw,null);
            check(Math.abs(c.qy-Math.sin(-yaw*.5))<1e-12,"braking retains pilot heading");
        }
        System.out.println("StabilizedFlight: "+checks+" assertions passed.");
    }
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
