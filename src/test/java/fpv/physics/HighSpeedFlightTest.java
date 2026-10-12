package fpv.physics;

import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.generated.FlightProfiles;

/** Real high-performance integration coupled to the production substep collision boundary. */
public final class HighSpeedFlightTest {
    private static int checks;
    public static void main(String[] args){
        var resolved=FlightProfile.resolve(FlightProfiles.create(),new FlightProfile.UnitMetadata(22.5,1,150,true),FlightProfile.preset(3));
        var controller=new FlightController(resolved.profile());controller.reset(2,25,0,0);
        var state=controller.state();state.qz=-Math.sin(Math.PI/4);state.qw=Math.cos(Math.PI/4);
        var terrain=new SweptCollision((x,z)->x>=30?100:0,.4,.15,.08);
        double[] maxImpact={0};int[] hits={0};
        FlightController.StepObserver collision=(s,x,y,z,dt,elapsed)->{
            var hit=terrain.resolve(s,x,y,z);
            if(hit.hit()){hits[0]++;maxImpact[0]=Math.max(maxImpact[0],hit.normalSpeed()*resolved.profile().metersPerTile);}
            check(s.x<30,"substep cannot cross thin cliff boundary");
        };
        for(int frame=0;frame<30;frame++)controller.step(.05,1,0,0,0,collision);
        check(hits[0]>0&&maxImpact[0]>100,"actual Insane force integration reaches high-speed impact");
        check(Double.isFinite(state.x)&&Double.isFinite(state.vx),"collision feedback remains finite");
        // A single maximum-budget stalled frame still passes through 25 physical checks.
        controller.reset(0,20,0,0);state.vx=200;
        var thin=new SweptCollision((x,z)->x>=7&&x<=7.1?8:0,.22,.12,.04);
        state.y=2;int[] callbacks={0};
        controller.step(.1,0,0,0,0,(s,x,y,z,dt,elapsed)->{callbacks[0]++;thin.resolve(s,x,y,z);});
        check(callbacks[0]==25&&state.x<7,"400m/s stalled-frame flight cannot tunnel through thin solid");
        System.out.println("HighSpeedFlight: "+checks+" assertions passed (integrated profile and collision).");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
