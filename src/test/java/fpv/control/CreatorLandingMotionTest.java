package fpv.control;

import fpv.flight.*;
import fpv.generated.FlightProfiles;
import fpv.generated.CrashConfig;
import fpv.physics.SweptCollision;

/** Uses the production stabilized integrator and real swept contact, not a copied landing algorithm. */
public final class CreatorLandingMotionTest {
    private static int checks;
    public static void main(String[] args){
        var base=FlightProfiles.create();
        var types=new FlightProfile.UnitMetadata[]{new FlightProfile.UnitMetadata(3.75,1,150,false,"dagger"),new FlightProfile.UnitMetadata(4.65,1.625,600,false,"atrax"),new FlightProfile.UnitMetadata(3.3,3,8200,false,"vela")};
        for(var unit:types)for(int preset=0;preset<4;preset++)for(double dt:new double[]{1d/60,.05,.1})for(double height:new double[]{1,6,120}){
            var profile=FlightProfile.resolve(base,unit,FlightProfile.preset(preset)).profile();
            run(profile,dt,height,0,true);
            run(profile,dt,120,-25/profile.metersPerTile,true);
        }
        // An unrecoverable externally imposed near-ground fall still reaches the actual crash sweep.
        run(base,.1,.3,-80/base.metersPerTile,false);
        check(CreatorLandingMotion.descentAxis(0,base,25)==0,"touchdown commands no downward motion");
        System.out.println("CreatorLandingMotion: "+checks+" assertions passed.");
    }
    private static void run(FlightController.Profile profile,double dt,double height,double initialVy,boolean recoverable){
        double half=.24,target=half+.035;
        var state=new FlightController.State();state.y=target+height;state.vy=initialVy;
        var nav=new StabilizedFlight();var collision=new SweptCollision((x,z)->0,.8125,half,.1);double[] impact={0};boolean arrived=false;
        for(int tick=0;tick<36000;tick++){
            double axis=CreatorLandingMotion.descentAxis(state.y-target,profile,25);
            nav.step(state,profile,dt,0,0,axis,25,(s,x,y,z,step,elapsed)->{
                var hit=collision.resolve(s,x,y,z);impact[0]=Math.max(impact[0],hit.normalSpeed()*profile.metersPerTile);
                if(s.y<target&&CreatorLandingMotion.gentleTouchdown(s.vy,profile)){s.y=target;s.vy=0;}
            });
            if(state.y<=target+.06&&Math.abs(state.vy)<.8){arrived=true;break;}
        }
        check(arrived,"actual bounded descent reaches physical landing clearance");
        if(recoverable)check(impact[0]<CrashConfig.SAFE_SPEED_MPS,"autopilot never queues damaging ground contact: "+impact[0]);
        else check(impact[0]>CrashConfig.SAFE_SPEED_MPS,"unrecoverable external velocity still produces damaging contact");
    }
    private static void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
}
