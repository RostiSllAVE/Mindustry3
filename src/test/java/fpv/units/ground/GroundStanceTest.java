package fpv.units.ground;

import java.util.List;

public final class GroundStanceTest {
    private static int checks;
    public static void main(String[] args){
        var profile=GroundUnitProfiles.profile("toxopid",6.5,GroundUnitProfiles.defaults());
        var stance=new GroundStanceState(2,profile);
        close(stance.defaultHeight(profile),.5*profile.maximumClearance()+2,1e-12,"requested stable default formula");
        var small=GroundUnitProfiles.profile("atrax",1.625,GroundUnitProfiles.defaults());
        close(new GroundStanceState(3,small).defaultHeight(small),small.maximumClearance(),0,"small walker clamps at own maximum");
        for(int id=0;id<1000;id++){double offset=GroundStanceState.offset(id,7);yes(offset>=1&&offset<=3,"stable offset bounded");close(offset,GroundStanceState.offset(id,7),0,"identity offset stable");}
        double original=stance.desired(profile);stance.control(profile,true,-1,.1,4);
        close(stance.desired(profile),original-.4,1e-12,"manual Q lowers desired clearance");
        double manual=stance.desired(profile);
        for(int i=0;i<100;i++)stance.control(profile,true,0,.1,4);
        close(stance.desired(profile),manual,0,"holding control with no input preserves stance target");
        stance.control(profile,true,1,.1,4);close(stance.desired(profile),original,1e-12,"manual E raises clearance");
        for(int i=0;i<500;i++)stance.control(profile,true,-1,.1,4);
        close(stance.desired(profile),profile.minimumClearance(),0,"manual lower bound");
        stance.control(profile,false,0,.1,4);close(stance.desired(profile),original,0,"release returns AI target to same stable default");
        var model=new GroundLocomotionModel(12,9,.2,.18);
        var surface=new GroundLocomotionModel.Surface(){public double support(double x,double y){return 30;}public double obstruction(double x,double y){return 30;}};
        var pose=model.create(profile,0,0,0,3.25,.5,surface);double initial=pose.z();
        model.step(pose,profile,0,0,0,3.25,.5,1.0/60,surface,stance.desired(profile));
        yes(pose.z()>initial&&pose.z()-initial<=.200001,"initial stance rises smoothly instead of teleporting");
        for(int i=0;i<240;i++)model.step(pose,profile,0,0,0,3.25,.5,1.0/60,surface,stance.desired(profile));
        close(pose.clearance(.5),stance.desired(profile),1e-9,"stance is above local supporting ground rather than world origin");
        double speed=pose.speedFraction();
        model.step(pose,profile,pose.x()+1,0,0,3.25,.5,.1,surface,stance.desired(profile));
        close(pose.x(),speed,1e-10,"extension penalty applied exactly once to displacement");
        yes(speed<1&&speed>=profile.minSpeedFraction(),"actual extension slows movement within configured minimum");
        model.seedHeight(pose,profile,pose.z()+2,.5);double air=pose.z();
        model.step(pose,profile,pose.x(),0,0,3.25,.5,.1,surface,stance.desired(profile));
        yes(pose.z()<air&&air-pose.z()<=.900001,"handoff from another body provider descends smoothly");
        var entry=new GroundStanceCodec.Entry(71,"toxopid",1,12.25,30,2,stance.desired(profile),pose.clearance(.5),true);
        var decoded=GroundStanceCodec.decode(GroundStanceCodec.encode(List.of(entry)));
        yes(decoded.equals(List.of(entry)),"offset target and actual clearance survive save payload roundtrip");
        bad(()->GroundStanceCodec.decode(new byte[]{1,2,3}),"truncated save chunk rejected");
        byte[] valid=GroundStanceCodec.encode(List.of(entry));byte[] extra=java.util.Arrays.copyOf(valid,valid.length+1);
        bad(()->GroundStanceCodec.decode(extra),"trailing save chunk bytes rejected");
        bad(()->GroundStanceCodec.decode(GroundStanceCodec.encode(List.of(entry,entry))),"duplicate unit identity rejected");
        bad(()->new GroundStanceCodec.Entry(1,"atrax",1,0,0,Double.NaN,1,1,false),"invalid saved offset rejected");
        bad(()->new GroundStanceState(.5,profile),"offset outside requested range rejected");
        System.out.println("GroundStance: "+checks+" assertions passed.");
    }
    private static void close(double a,double b,double e,String label){yes(Double.isFinite(a)&&Math.abs(a-b)<=e,label+": "+a+" / "+b);}
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void bad(Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}yes(rejected,label);}
}
