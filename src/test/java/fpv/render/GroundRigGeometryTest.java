package fpv.render;

/** Allocation-free stepping and bounded GPU templates can be checked without an OpenGL context. */
public final class GroundRigGeometryTest {
    private static int checks;
    public static void main(String[] args){
        check(GroundRigGeometry.depthScale(.2,2,.6)==2,"ordinary family art retains doubled thickness inside the canonical hull");
        check(GroundRigGeometry.depthScale(.6,2,.3)==.5,"oversized online atlas body clamps to exact server half depth");
        for(int count:new int[]{0,4,6,8})for(boolean coarse:new boolean[]{false,true}){
            var rig=GroundRigGeometry.generate(count==0,count,coarse);
            check(rig.parts()==(count==0?5:1+count*4),"one joint plus exact wheel/leg parts");
            check(rig.parts()*3+8<=128,"compact affine palette fits minimum vertex uniform vectors");
            check(rig.vertexCount()<5000,"bounded geometry per rig");
            float[] v=rig.vertices();
            for(int i=0;i<v.length;i+=GroundRigGeometry.STRIDE){
                for(int j=0;j<GroundRigGeometry.STRIDE;j++)check(Float.isFinite(v[i+j]),"finite cached vertex");
                check(v[i+9]>=0&&v[i+9]<rig.parts()&&v[i+9]==(int)v[i+9],"valid palette part");
                double n=Math.sqrt(v[i+3]*v[i+3]+v[i+4]*v[i+4]+v[i+5]*v[i+5]);
                check(Math.abs(n-1)<1e-6,"unit local normal");
            }
        }
        var gait=new GroundGait(6);gait.update(0,0,0,2,1,.016,(x,y)->x*.1);
        double firstX=gait.x(0),firstY=gait.y(0);
        for(int i=0;i<60;i++)gait.update(0,0,0,2,1,.016,(x,y)->x*.1);
        check(gait.x(0)==firstX&&gait.y(0)==firstY,"idle feet never orbit body");
        gait.update(.10,0,0,2,1,.016,(x,y)->x*.1);
        check(gait.x(0)==firstX&&gait.y(0)==firstY,"plant remains anchored during small body movement");
        boolean alternating=false,lifted=false;
        for(int frame=1;frame<=120;frame++){
            gait.update(.10+frame*.035,0,0,2,1,.016,(x,y)->x*.1);
            int planted=0;
            for(int leg=0;leg<6;leg++){
                if(gait.planted(leg))planted++;
                if(gait.z(leg)>gait.x(leg)*.1+.02)lifted=true;
                check(gait.z(leg)>=gait.x(leg)*.1-1e-8,"foot never penetrates support terrain");
            }
            check(planted>=3,"at least a support tripod stays planted");
            alternating|=planted==3;
        }
        check(alternating&&lifted,"moving body produces alternating lifted step");
        firstX=gait.x(0);firstY=gait.y(0);double firstZ=gait.z(0);
        gait.update(4.30,0,0,2,1,0,(x,y)->x*.1);
        check(gait.x(0)==firstX&&gait.y(0)==firstY&&gait.z(0)==firstZ,"pause freezes gait phase");
        gait.update(200,200,0,2,1,.016,(x,y)->3);
        check(Math.hypot(gait.x(0)-200,gait.y(0)-200)<2.001&&gait.z(0)==3,"teleport resets feet to new support");
        boolean turned=false;
        for(int frame=0;frame<80;frame++){
            gait.update(200,200,Math.PI/2,2,1,.016,(x,y)->3);
            int planted=0;for(int i=0;i<6;i++)if(gait.planted(i))planted++;
            check(planted>=3,"turning preserves a support tripod");turned|=planted==3;
        }
        check(turned,"stationary body turning repositions feet by steps, not orbiting");
        gait.reset();gait.update(201,200,0,2,1,0,(x,y)->3);
        check(Math.abs(gait.x(0)-201-2*Math.cos(GroundGait.angle(0,6)))<1e-8,"reappearing visible rig reanchors feet without old offscreen stretch");
        System.out.println("GroundRigGeometry: "+checks+" assertions passed (bounded cached meshes, world-planted gait, terrain, pause).");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
