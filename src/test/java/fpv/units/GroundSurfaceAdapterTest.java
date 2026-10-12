package fpv.units;

/** Actual body support and orientation on slopes, independent of native pathfinding. */
public final class GroundSurfaceAdapterTest {
    private static int checks;
    public static void main(String[] args){
        var flat=new GroundSurfaceAdapter((x,y)->4,.35,50,.4);
        var contact=flat.contact(10,20,0,2,.3);
        close(contact.centerHeight(),4.3,1e-12,"flat body rests at exact half-depth");
        close(contact.pitch(),0,1e-12,"flat pitch");close(contact.roll(),0,1e-12,"flat roll");
        var east=new GroundSurfaceAdapter((x,y)->.5*x,.35,50,.4);
        contact=east.contact(2,3,0,2,.2);
        close(contact.pitch(),Math.toDegrees(Math.atan(.5)),1e-10,"eastbound nose points uphill");
        close(contact.roll(),0,1e-10,"no bank on longitudinal incline");
        close(contact.centerHeight(),1+.2*Math.sqrt(1.25),1e-10,"tilted body rests on plane, not footprint maximum");
        var broad=east.contact(2,3,0,12,.2);
        close(broad.centerHeight(),contact.centerHeight(),1e-10,"large ground silhouette does not float uphill by radius");
        contact=east.contact(2,3,180,2,.2);
        close(contact.pitch(),-Math.toDegrees(Math.atan(.5)),1e-10,"westbound nose points downhill");
        contact=east.contact(2,3,90,2,.2);
        close(contact.pitch(),0,1e-10,"cross-slope pitch");close(contact.roll(),-Math.toDegrees(Math.atan(.5)),1e-10,"uphill right side raises correct wing");
        var mixed=new GroundSurfaceAdapter((x,y)->.5*x+.25*y,.35,50,.4);
        for(int yaw=0;yaw<360;yaw+=15){
            contact=mixed.contact(2,4,yaw,2,.3);
            close(contact.centerHeight(),2+.3*Math.sqrt(1+.25+.0625),1e-10,"any heading rests on same tilted plane");
            double p=Math.toRadians(contact.pitch()),r=Math.toRadians(contact.roll()),a=Math.toRadians(yaw);
            double ux=-Math.cos(a)*Math.sin(p)*Math.cos(r)+Math.sin(a)*Math.sin(r);
            double uy=-Math.sin(a)*Math.sin(p)*Math.cos(r)-Math.cos(a)*Math.sin(r);
            double uz=Math.cos(p)*Math.cos(r),norm=Math.sqrt(1+.25+.0625);
            close(ux,-.5/norm,1e-10,"body up aligns terrain X normal");close(uy,-.25/norm,1e-10,"body up aligns terrain Y normal");
            close(uz,1/norm,1e-10,"body up aligns terrain Z normal");
        }
        var cliff=new GroundSurfaceAdapter((x,y)->x>=1?20:0,.35,50,.4);
        contact=cliff.contact(.8,0,0,1,.2);
        close(contact.centerHeight(),.2,1e-10,"neighboring blocked cliff does not levitate a ground unit");
        close(contact.pitch(),0,1e-10,"cliff is not treated as a traversable fifty-degree ramp");
        var model=new UnitAltitudeModel(UnitAltitudeSystem.profile());
        var ground=model.create(1,2,1,0,0,false);
        model.update(ground,1.0/60,0,0,1,0,90,false,12,-7,.8);
        close(ground.height(),.8,0,"physical state consumes support height");close(ground.pitch(),12,0,"physical state consumes slope pitch");
        close(ground.roll(),-7,0,"physical state consumes slope roll");close(ground.yaw(),90,0,"ground native heading preserved");
        model.update(ground,0,10,10,1,0,180,false,30,20,12);
        close(ground.height(),.8,0,"paused support remains frozen");close(ground.pitch(),12,0,"paused slope attitude remains frozen");
        yes(!model.addVerticalVelocity(ground,10,30),"ground impulse does not turn native ground unit into flyer");
        var flyer=model.create(1,2,1,0,0,true);double old=flyer.height();
        yes(model.addVerticalVelocity(flyer,4,30),"airborne physical vertical impulse accepted");
        close(flyer.verticalVelocity(),4,0,"impulse updates authoritative altitude velocity");
        model.update(flyer,1.0/60,0,0,1,0,0,true);
        yes(flyer.height()>old,"vertical impulse actually changes physical altitude");
        System.out.println("GroundSurfaceAdapter: "+checks+" assertions passed.");
    }
    private static void close(double actual,double expected,double epsilon,String label){yes(Double.isFinite(actual)&&Math.abs(actual-expected)<=epsilon,label+": "+actual+" vs "+expected);}
    private static void yes(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
}
