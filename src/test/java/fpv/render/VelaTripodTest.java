package fpv.render;

/** Visual triangle and sequential grounded phases; no native unit/profile/AI mutation. */
public final class VelaTripodTest {
    private static int checks;
    public static void main(String[] args){
        double spread=1.14,radius=1.5;var gait=GroundGait.velaTripod();check(gait.count()==3,"visual tripod only");
        for(double yaw:new double[]{0,.5,1.3,Math.PI,5.2}){
            near(VelaTripodLayout.footX(0,yaw,spread),Math.sin(yaw)*spread,"native left X");
            near(VelaTripodLayout.footY(0,yaw,spread),-Math.cos(yaw)*spread,"native left Y");
            near(VelaTripodLayout.footX(1,yaw,spread),-Math.sin(yaw)*spread,"native right X");
            near(VelaTripodLayout.footY(1,yaw,spread),Math.cos(yaw)*spread,"native right Y");
            double rearX=VelaTripodLayout.footX(2,yaw,spread),rearY=VelaTripodLayout.footY(2,yaw,spread);
            near(rearX*Math.sin(yaw)-rearY*Math.cos(yaw),0,"rear foot centered laterally");
            check(rearX*Math.cos(yaw)+rearY*Math.sin(yaw)<-spread,"rear foot behind body");
            double area=Math.abs((VelaTripodLayout.hipX(1,yaw,radius)-VelaTripodLayout.hipX(0,yaw,radius))*(VelaTripodLayout.hipY(2,yaw,radius)-VelaTripodLayout.hipY(0,yaw,radius))
                -(VelaTripodLayout.hipY(1,yaw,radius)-VelaTripodLayout.hipY(0,yaw,radius))*(VelaTripodLayout.hipX(2,yaw,radius)-VelaTripodLayout.hipX(0,yaw,radius)));
            check(area>.5,"three non-collinear underside attachments");
        }
        java.util.function.DoubleBinaryOperator surface=(x,y)->x<-1?.7:y>0?.25:0;
        gait.update(0,0,0,spread,radius,1/60d,surface);check(gait.planted(0)&&gait.planted(1)&&gait.planted(2),"stationary feet start planted");
        near(gait.z(2),.7,"rear samples existing terrain utility");
        double[] x=new double[3],y=new double[3];boolean[] swings=new boolean[3],prior=new boolean[3];
        for(int frame=0;frame<900;frame++){
            for(int leg=0;leg<3;leg++){x[leg]=gait.x(leg);y[leg]=gait.y(leg);prior[leg]=gait.planted(leg);}
            double bodyX=frame<300?frame*.025:frame<600?(600-frame)*.025:0;
            double yaw=frame<600?0:(frame-600)*.01;
            gait.update(bodyX,0,yaw,spread,radius,1/60d,surface);int planted=0;
            for(int leg=0;leg<3;leg++){
                if(gait.planted(leg)){planted++;if(frame>0)check(Math.hypot(gait.x(leg)-x[leg],gait.y(leg)-y[leg])<1e-9||!prior[leg],"planted feet are world anchored");}
                else swings[leg]=true;
                check(gait.z(leg)>=surface.applyAsDouble(gait.x(leg),gait.y(leg)),"all feet clear uneven supports");
            }
            check(planted>=2,"at least two planted while walking/reversing/turning");
        }
        for(boolean stepped:swings)check(stepped,"each leg gets independent swing phase");
        for(int frame=0;frame<100;frame++)gait.update(0,0,2.99,spread,radius,1/60d,surface);
        double[] feet=new double[9];for(int leg=0;leg<3;leg++){feet[leg*3]=gait.x(leg);feet[leg*3+1]=gait.y(leg);feet[leg*3+2]=gait.z(leg);}
        for(int frame=0;frame<60;frame++){
            gait.update(0,0,2.99,spread,radius,0,surface);
            for(int leg=0;leg<3;leg++){near(gait.x(leg),feet[leg*3],"paused X");near(gait.y(leg),feet[leg*3+1],"paused Y");near(gait.z(leg),feet[leg*3+2],"paused Z");}
        }
        double[] knee=new double[3];for(double height:new double[]{.5,1.1,8,20}){
            VelaLegGeometry.rearKnee(-.75,0,height,-1.54,0,.14,1.5,0,knee);
            near(knee[1],0,"rear knee stays centered");check(Double.isFinite(knee[0]+knee[2]),"finite rear articulation at all heights");
            check(Math.hypot(knee[0],knee[1])+.7<VelaTripodLayout.cullingRadius(height,radius,spread),"tall rear knee fits visual culling envelope");
        }
        gait.reset();gait.update(50,30,1,spread,radius,1/60d,surface);
        near(gait.x(2),50+VelaTripodLayout.footX(2,1,spread),"landing/reset begins at current body");
        check(new GroundGait(2).count()==2&&new GroundGait(4).count()==4&&new GroundGait(8).count()==8,"native paired rigs unchanged");
        boolean rejected=false;try{new GroundGait(3);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"tripod cannot silently alter native constructors");
        System.out.println("VelaTripodTest: "+checks+" layout/phase/terrain/pause/rear-IK/native-rig checks passed");
    }
    private static void near(double actual,double expected,String label){check(Math.abs(actual-expected)<1e-8,label);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
