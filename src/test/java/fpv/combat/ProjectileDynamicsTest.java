package fpv.combat;

/** Regresses real vertical trajectories, high-altitude fall time and finite-body splash distance. */
public final class ProjectileDynamicsTest {
    private static int checks;
    public static void main(String[] args){
        var pause=ProjectileDynamics.integrate(12,-5,8,0,true,1);near(pause.dx(),0);near(pause.vz(),8);
        var coast=ProjectileDynamics.integrate(12,-5,8,2,false,0);near(coast.dx(),24);near(coast.dz(),16);
        var fall=ProjectileDynamics.integrate(12,-5,0,2,true,0);near(fall.dz(),-2*ProjectileDynamics.GRAVITY);near(fall.vz(),-2*ProjectileDynamics.GRAVITY);
        double fallTime=Math.sqrt(2*720/ProjectileDynamics.GRAVITY);require(fallTime<ProjectileDynamics.MAX_FALL_SECONDS);
        near(720+ProjectileDynamics.integrate(0,0,0,fallTime,true,0).dz(),0);
        for(int i=1;i<=120;i++){
            double t=i/20.0;var launch=ProjectileDynamics.arc(35,25,7,t);
            var landing=ProjectileDynamics.integrate(launch.x(),launch.y(),launch.z(),t,true,0);
            near(Math.hypot(landing.dx(),landing.dy()),25);near(landing.dz(),7);
            var turn=ProjectileDynamics.steer(10,0,0,0,0,30,Math.PI/6);
            near(Math.sqrt(turn.x()*turn.x()+turn.y()*turn.y()+turn.z()*turn.z()),10);near(turn.z(),5);
        }
        var reverse=ProjectileDynamics.steer(10,0,0,-10,0,0,Math.PI/4);near(Math.hypot(reverse.x(),reverse.y()),10);require(Double.isFinite(reverse.z()));
        near(ProjectileDynamics.cylinderDistance(0,0,8,0,0,1,2,.5),6.5);
        near(ProjectileDynamics.cylinderDistance(3,0,1,0,0,1,2,.5),1);
        near(ProjectileDynamics.boxDistance(0,0,8,-2,-2,0,2,2,2),6);
        near(ProjectileDynamics.boxDistance(0,0,1,-2,-2,0,2,2,2),0);
        boolean rejected=false;try{ProjectileDynamics.integrate(0,0,0,-1,true,0);}catch(IllegalArgumentException expected){rejected=true;}require(rejected);
        System.out.println("ProjectileDynamicsTest: "+checks+" checks passed");
    }
    private static void near(double a,double b){require(Math.abs(a-b)<1e-8);}
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("Projectile dynamics check "+checks);}
}
