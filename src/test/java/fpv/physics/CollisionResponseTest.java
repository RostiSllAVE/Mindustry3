package fpv.physics;

public final class CollisionResponseTest {
    private static int assertions;
    public static void main(String[] args){
        var same=CollisionResponse.inelastic(1,1,-1,0,0,2,0,0,0,true,200,30);
        close(same.ownX(),-1,"safe equal-mass own delta");
        close(same.targetX(),1,"safe equal-mass target delta");
        close(2+same.ownX()-same.targetX(),0,"equal normal velocities after inelastic contact");
        for(double own:new double[]{.05,.65,10,3000})for(double target:new double[]{.05,.65,10,3000}){
            var response=CollisionResponse.inelastic(own,target,-.6,-.8,0,12,16,3,0,true,200,30);
            close(own*response.ownX()+target*response.targetX(),0,"X momentum conserved");
            close(own*response.ownY()+target*response.targetY(),0,"Y momentum conserved");
            close(response.ownZ(),0,"tangential Z untouched");
            close((12+response.ownX()-response.targetX())*.6+(16+response.ownY()-response.targetY())*.8,0,
                "relative normal velocity closed");
        }
        var bounded=CollisionResponse.inelastic(100,1,-1,0,0,1000,0,0,0,true,200,30);
        close(bounded.targetX(),200,"native horizontal delta limit");
        close(100*bounded.ownX()+bounded.targetX(),0,"limit scales both impulses together");
        var vertical=CollisionResponse.inelastic(1,1,0,0,-1,0,0,20,29,true,200,30);
        close(vertical.targetZ(),1,"existing upward target speed leaves one unit of headroom");
        close(vertical.ownZ(),-1,"vertical saturation preserves impulse pair");
        var ground=CollisionResponse.inelastic(1,1,0,0,-1,0,0,20,0,false,200,30);
        close(ground.ownZ(),-20,"ground support stops inward vertical motion");
        close(ground.targetZ(),0,"ground target cannot receive vertical velocity");
        var separating=CollisionResponse.inelastic(1,1,-1,0,0,-2,0,0,0,true,200,30);
        check(separating==CollisionResponse.Delta.ZERO,"separation adds no impulse");

        var timeline=new CollisionResponse.Timeline();
        timeline.add(.1,5,-1,2);timeline.add(.15,-2,0,0);timeline.add(.3,100,100,100);
        var motion=new CollisionResponse.Motion();motion.set(2,3,4,1,2,3,.2);timeline.apply(.2,motion);
        close(motion.x,2.6,"target X uses each kick's contact time");
        close(motion.y,3.3,"target Y uses kick age");close(motion.z,4.8,"target altitude uses kick age");
        close(motion.vx,4,"later sweep sees both earlier X impulses");
        close(motion.vy,1,"later sweep sees earlier Y impulse");close(motion.vz,5,"future impulse is excluded");
        motion.set(2,3,4,1,2,3,.1);timeline.apply(.1,motion);
        close(motion.x,2.1,"instantaneous kick does not teleport target");
        close(motion.vx,6,"instantaneous kick changes velocity at contact time");
        boolean rejected=false;
        try{CollisionResponse.inelastic(0,1,1,0,0,1,0,0,0,true,200,30);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid mass rejected");
        System.out.println("CollisionResponseTest: "+assertions+" assertions passed");
    }
    private static void close(double value,double expected,String message){check(Double.isFinite(value)&&Math.abs(value-expected)<1e-8,message+": "+value);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
