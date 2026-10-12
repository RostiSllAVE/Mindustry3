package fpv.physics;
import fpv.flight.FlightController;
public final class SweptCollisionTest {
    private static int count;
    private static void check(boolean v,String name){count++;if(!v)throw new AssertionError(name);}
    public static void main(String[] args){
        var ground=new SweptCollision((x,z)->0,.22,.08);
        var s=new FlightController.State();s.x=1;s.y=-10;s.z=2;s.vy=-35;
        var hit=ground.resolve(s,1,8,2);
        check(hit.hit()&&Math.abs(s.y-.22)<.001,"Vertical high speed ground sweep");
        check(Math.abs(hit.normalSpeed()-35)<.001&&s.vy==0,"Normal velocity resolved");
        s.x=8;s.y=2;s.z=0;s.vx=100;s.vy=0;
        var cliff=new SweptCollision((x,z)->x>=3?7:0,.22,.08);
        hit=cliff.resolve(s,0,2,0);
        check(hit.hit()&&s.x<3&&s.x>2.7,"Cliff sweep cannot tunnel");
        check(hit.normalSpeed()>99,"Horizontal cliff impact counts");
        check(hit.ny()==0&&s.vy==0,"Vertical cliff does not launch craft upward");
        s.x=20;s.y=8;s.z=0;s.vx=100;s.vy=0;
        check(!cliff.resolve(s,0,8,0).hit(),"Clearance above cliff");
        s.x=9;s.y=-.1;s.z=0;s.vx=200;s.vy=-1;
        hit=ground.resolve(s,0,.23,0);
        check(hit.hit()&&Math.abs(hit.normalSpeed()-1)<.001,"Grazing tangential speed is not crash severity");
        check(s.x>8.99&&s.y>=.22-1e-6,"Ground glance preserves remaining tangential displacement");
        var slope=new SweptCollision((x,z)->Math.max(0,x)*.5,.22,.08);
        s.x=4;s.y=1;s.z=0;s.vx=10;s.vy=-2;
        hit=slope.resolve(s,0,2,0);
        check(hit.hit()&&hit.nx()<0&&hit.ny()>0,"Slope normal");
        check(s.y-.22>=Math.max(0,s.x+.22)*.5-1e-5,"Slope slide keeps conservative footprint outside terrain");
        s.x=0;s.y=-1;s.z=0;s.vx=s.vy=s.vz=0;
        ground.resolve(s,0,-1,0);check(s.y>=.22,"Initial overlap can recover");
        initialOverlapHasNoImpactEnergy(ground);
        secondContactDuringSlide();
        plateauEdgeLanding();
        repeatedGroundSliding(ground);
        check(SweptCollision.damageFraction(3,3.5,19)==0,"Safe impact");
        check(SweptCollision.damageFraction(19,3.5,19)==1,"Lethal impact");
        check(SweptCollision.damageFraction(12,3.5,19)>SweptCollision.damageFraction(7,3.5,19),"Damage increases with speed");
        check(SweptCollision.damageFraction(999,3.5,19)==1,"Bounded damage");
        rejects(()->new SweptCollision((x,z)->0,Double.POSITIVE_INFINITY,.08),"Infinite radius rejected");
        rejects(()->new SweptCollision((x,z)->0,.22,Double.POSITIVE_INFINITY),"Infinite sweep step rejected");
        rejects(()->SweptCollision.damageFraction(20,3.5,Double.POSITIVE_INFINITY),"Infinite damage threshold rejected");
        System.out.println("SweptCollision: "+count+" assertions passed.");
    }

    private static void initialOverlapHasNoImpactEnergy(SweptCollision ground){
        var s=new FlightController.State();s.x=2;s.y=-9;s.z=3;s.vx=12;s.vy=-40;
        var hit=ground.resolve(s,1,-1,3);
        check(hit.hit()&&hit.normalSpeed()==0,"Fast downward pre-existing overlap has zero impact severity");
        check(SweptCollision.damageFraction(hit.normalSpeed()*2,3.5,19)==0,"Recovery cannot generate crash damage");
        check(s.x==1&&s.z==3&&s.y>=.22&&s.vy==0,"Recovery lifts old pose out without horizontal teleport");
        check(s.vx==12,"Recovery retains horizontal momentum for following valid flight frame");
        var cliff=new SweptCollision((x,z)->x>=3?7:0,.22,.08);
        s.x=8;s.y=1;s.z=0;s.vx=100;s.vy=-40;
        hit=cliff.resolve(s,4,2,0);
        check(hit.normalSpeed()==0&&s.y>=7.22,"Terrain raised around moving craft recovers without false lethal collision");
    }

    private static void secondContactDuringSlide(){
        var corner=new SweptCollision((x,z)->x>=3?7:0,.22,.08);
        var s=new FlightController.State();s.x=9;s.y=-.1;s.z=0;s.vx=200;s.vy=-1;
        var hit=corner.resolve(s,0,.23,0);
        check(hit.hit()&&s.x>2.7&&s.x<2.8,"Ground slide sweeps and stops at second cliff instead of tunneling");
        check(hit.normalSpeed()>199&&hit.nx()<-.999&&hit.ny()==0,"Return strongest second impact rather than harmless initial ground glance");
        check(Math.abs(hit.x()-3)<.001&&Math.abs(hit.y()-.22)<.001,"Second impact reports actual cliff contact position");
        check(Math.abs(s.vx)<1e-9&&s.y>=.22-1e-6,"Second contact resolves horizontal speed without falling into ground");
    }

    private static void repeatedGroundSliding(SweptCollision ground){
        var s=new FlightController.State();s.y=.22001;
        for(int i=0;i<8;i++){
            double ox=s.x,oy=s.y;
            s.x+=1;s.y-=.01;s.vx=10;s.vy=-.1;
            var hit=ground.resolve(s,ox,oy,0);
            check(hit.hit()&&s.x>ox+.99,"Repeated gentle floor contact does not stick at t=0");
            check(hit.normalSpeed()<.11&&s.y>=.22-1e-6,"Repeated ground slide stays safe and above floor");
        }
    }

    private static void plateauEdgeLanding(){
        var cliff=new SweptCollision((x,z)->x>=3?7:0,.22,.08);
        var s=new FlightController.State();s.x=3.01;s.y=1;s.z=0;s.vy=-40;
        var hit=cliff.resolve(s,3.01,10,0);
        check(hit.hit()&&hit.ny()>.999&&Math.abs(hit.nx())<1e-9,"Plateau edge landing uses top rather than cliff-side normal");
        check(hit.normalSpeed()>39.99&&s.vy==0&&s.y>=7.22-1e-6,"Fast vertical landing near cliff remains damaging and cannot tunnel");
        check(Math.abs(hit.y()-7)<1e-9,"Plateau landing explosion uses top surface height");
    }

    private static void rejects(Runnable action,String message){
        try{action.run();}catch(IllegalArgumentException expected){check(true,message);return;}
        check(false,message);
    }
}
