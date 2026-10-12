package fpv.units.ground;

/** CPU regression suite exercises actual movement decisions rather than shader/model snapshots. */
public final class GroundLocomotionTest {
    private static int checks;
    private static GroundLocomotionModel.Surface surface(java.util.function.DoubleBinaryOperator physical,java.util.function.DoubleBinaryOperator obstruction){
        return new GroundLocomotionModel.Surface(){public double support(double x,double y){return physical.applyAsDouble(x,y);}public double obstruction(double x,double y){return obstruction.applyAsDouble(x,y);}};
    }
    public static void main(String[] args){
        var settings=GroundUnitProfiles.defaults();
        var crawler=GroundUnitProfiles.profile("crawler",1,settings);
        var atrax=GroundUnitProfiles.profile("atrax",1.625,settings);
        var spiroct=GroundUnitProfiles.profile("spiroct",1.875,settings);
        var arkyid=GroundUnitProfiles.profile("arkyid",2.875,settings);
        var toxopid=GroundUnitProfiles.profile("toxopid",6.5,settings);
        yes(crawler.wheelCount()==4&&crawler.legCount()==0&&!crawler.legged()&&crawler.maxExtension()==0,"Crawler four wheels and no extension");
        yes(atrax.legCount()==4&&spiroct.legCount()==6&&arkyid.legCount()==6&&toxopid.legCount()==8,"tier leg progression");
        for(var p:new GroundUnit3DModelProfile[]{atrax,spiroct,arkyid,toxopid}){
            close(p.thicknessMultiplier(),2,0,"double body volume");
            close(p.speedFraction(0),1,0,"normal stance full speed");
            close(p.speedFraction(p.maxExtension()),.25,1e-12,"full extension heavy slowdown");
            yes(p.speedFraction(p.maxExtension()*.5)>.25&&p.speedFraction(p.maxExtension()*.5)<1,"partial extension smooth slowdown");
            double sx=0,sy=0;for(int i=0;i<p.legCount();i++){sx+=ProceduralLegLayout.x(i,p.legCount());sy+=ProceduralLegLayout.y(i,p.legCount());}
            close(sx,0,1e-12,"symmetric leg ring X");close(sy,0,1e-12,"symmetric leg ring Y");
        }
        close(spiroct.maxExtension(),atrax.maxExtension()*2,1e-12,"T3 extension doubles");
        close(arkyid.maxExtension(),spiroct.maxExtension()*2,1e-12,"T4 extension doubles");
        close(toxopid.maxExtension(),arkyid.maxExtension()*2,1e-12,"T5 extension doubles");
        throwsBad(()->new GroundUnit3DModelProfile("invalid",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,5,2,.3,4,.25,1.35),"odd leg count rejected before GPU");
        throwsBad(()->new GroundUnit3DModelProfile("invalid",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,10,2,.3,4,.25,1.35),"oversized rig rejected before GPU");
        yes(GroundUnitProfiles.profile("flare",1,settings)==null,"flying family not selected");
        yes(GroundUnitProfiles.profile("dagger",1,settings)==null,"unrequested ground family not selected");
        var decoded=GroundUnitProfiles.decode(GroundUnitProfiles.encode(settings));yes(decoded.equals(settings),"server settings roundtrip");
        throwsBad(()->GroundUnitProfiles.decode(new byte[GroundUnitProfiles.ENCODED_BYTES-1]),"short payload rejected");
        throwsBad(()->GroundUnitProfiles.decode(new byte[GroundUnitProfiles.ENCODED_BYTES+1]),"trailing payload rejected");
        byte[] bad=GroundUnitProfiles.encode(settings);java.nio.ByteBuffer.wrap(bad).putDouble(Double.NaN);throwsBad(()->GroundUnitProfiles.decode(bad),"NaN settings rejected");
        throwsBad(()->new GroundUnitProfiles.Settings(-1,2,3,4,.25),"negative extension rejected");
        throwsBad(()->new GroundUnitProfiles.Settings(1,2,3,4,0),"zero speed fraction rejected");
        close(GroundUnitProfiles.scaledGreenExtension("corvus",.5,3,settings)+.5,10.5,1e-12,"Corvus triples total standing maximum");
        close(GroundUnitProfiles.scaledGreenExtension("vela",.5,3,settings)+.5,10.5,1e-12,"Vela triples native-derived standing maximum");
        close(GroundUnitProfiles.scaledGreenExtension("quasar",.5,3,settings),3,0,"other green tiers unchanged");
        throwsBad(()->new GroundUnitProfiles.Settings(1,2,3,4,.25,Double.NaN),"invalid green multiplier rejected");
        close(GroundIntentMath.restore(4,4,.03,true),4.03,1e-12,"native blocked intent may reach 3D solver");
        close(GroundIntentMath.restore(4,4,0,true),4,0,"stationary unit gains no motion");
        close(GroundIntentMath.restore(4,4,.03,false),4,0,"no bypass without native tile rejection");
        close(GroundIntentMath.restore(4,4.03,.03,true),4.03,0,"full native displacement is not doubled");
        close(GroundIntentMath.restore(4,3.99,.03,true),3.99,0,"opposing collision impulse preserved");
        close(GroundIntentMath.restore(4,4.005,.03,true),4.03,1e-12,"partial rejected native displacement restores only original intent");
        var model=new GroundLocomotionModel(12,9,.2,.18);
        var flat=surface((x,y)->0,(x,y)->0);
        var normal=model.create(atrax,0,0,0,.8,.2,flat);
        close(normal.z(),atrax.normalStance()+.2,1e-12,"body supported over real ground");
        for(int i=0;i<120;i++)model.step(normal,atrax,normal.x()+1.0/60,0,0,.8,.2,1.0/60,flat);
        close(normal.x(),2,1e-9,"flat normal speed unchanged");close(normal.extension(),0,1e-9,"flat travel not extension");
        double frozen=normal.x();model.step(normal,atrax,100,100,0,.8,.2,0,flat);close(normal.x(),frozen,0,"pause cannot move");
        double lowHeight=atrax.maxExtension()*.55;
        var low=surface((x,y)->x>=2&&x<=3?lowHeight:0,(x,y)->x>=2&&x<=3?lowHeight:0);
        var climber=model.create(atrax,0,0,0,.8,.2,low);double peak=0,minimumSpeed=1;boolean waited=false;
        for(int i=0;i<1800;i++){
            model.step(climber,atrax,climber.x()+2.0/60,0,0,.8,.2,1.0/60,low);
            peak=Math.max(peak,climber.z());minimumSpeed=Math.min(minimumSpeed,climber.speedFraction());waited|=climber.blocked();
            yes(climber.z()>=.2,"grounded center always above baseline");if(climber.x()>5)break;
        }
        yes(climber.x()>4,"allowed wall crossed");yes(peak>lowHeight+.15,"body rises over wall");yes(waited,"climb waits for physical body clearance");yes(minimumSpeed<.98,"extension actually reduces movement speed");
        double wall=(atrax.maxExtension()+spiroct.maxExtension())*.5;
        var high=surface((x,y)->x>=2&&x<=3?wall:0,(x,y)->x>=2&&x<=3?wall:0);
        var small=model.create(atrax,0,0,0,.8,.2,high);var large=model.create(spiroct,0,0,0,.8,.2,high);
        for(int i=0;i<1800;i++){
            model.step(small,atrax,small.x()+2.0/60,0,0,.8,.2,1.0/60,high);
            model.step(large,spiroct,large.x()+2.0/60,0,0,.8,.2,1.0/60,high);
            if(large.x()>5)break;
        }
        yes(small.x()<2,"low tier cannot cross higher obstacle");yes(large.x()>4,"next tier crosses higher obstacle");
        var wheels=model.create(crawler,0,0,0,.5,.12,low);
        for(int i=0;i<600;i++)model.step(wheels,crawler,wheels.x()+.05,0,0,.5,.12,1.0/60,low);
        yes(wheels.x()<2&&wheels.extension()==0,"Crawler does not acquire spider extension");
        var projected=surface((x,y)->0,(x,y)->x>=2&&x<=3?wall:0);
        var tower=model.create(spiroct,0,0,0,.8,.2,projected);
        for(int i=0;i<1800&&tower.x()<4;i++)model.step(tower,spiroct,tower.x()+2.0/60,0,0,.8,.2,1.0/60,projected);
        yes(tower.x()>3,"capable tier crosses projected obstruction on physical ground feet");
        var enclosingProjection=surface((x,y)->0,(x,y)->100);
        var enclosed=model.create(atrax,0,0,0,.8,.2,enclosingProjection);
        for(int i=0;i<60;i++)model.step(enclosed,atrax,0,0,0,.8,.2,1.0/60,enclosingProjection);
        yes(enclosed.z()<=atrax.normalStance()+atrax.maxExtension()+.201,"new/regenerating shield cannot elevate low tier past physical reach");
        var slope=surface((x,y)->.1*x+.04*y,(x,y)->.1*x+.04*y);
        var tilted=model.create(atrax,1,2,0,.8,.2,slope);
        yes(tilted.pitch()>0&&Math.abs(tilted.roll())>0,"terrain gradient produces grounded attitude");
        model.reset(tilted,atrax,20,20,0,.8,.2,slope);yes(tilted.velocityX()==0&&tilted.verticalVelocity()==0,"teleport resets kinetic history");
        System.out.println("GroundLocomotion: "+checks+" assertions passed.");
    }
    private static void close(double a,double b,double e,String label){yes(Double.isFinite(a)&&Math.abs(a-b)<=e,label+": "+a+" vs "+b);}
    private static void yes(boolean result,String label){checks++;if(!result)throw new AssertionError(label);}
    private static void throwsBad(Runnable run,String label){boolean rejected=false;try{run.run();}catch(IllegalArgumentException expected){rejected=true;}yes(rejected,label);}
}
