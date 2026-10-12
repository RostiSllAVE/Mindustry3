package fpv.render;

/** One shared eight-sided hollow nozzle, normalized for inexpensive instance transforms. */
public final class AircraftBombPortGeometry {
    private AircraftBombPortGeometry(){}
    public static float[] generate(){
        float[] v=new float[8*4*6*8];int at=0;
        for(int i=0;i<8;i++){
            double a=i*Math.PI/4,b=(i+1)*Math.PI/4;float ax=(float)Math.cos(a)*.5f,az=(float)Math.sin(a)*.5f,bx=(float)Math.cos(b)*.5f,bz=(float)Math.sin(b)*.5f;
            float ix=ax*.5f,iz=az*.5f,jx=bx*.5f,jz=bz*.5f;
            at=quad(v,at,new float[]{ax,-.5f,az,bx,-.5f,bz,bx,.5f,bz,ax,.5f,az},(ax+bx),0,(az+bz));
            at=quad(v,at,new float[]{ix,.5f,iz,jx,.5f,jz,jx,-.5f,jz,ix,-.5f,iz},-(ax+bx),0,-(az+bz));
            at=quad(v,at,new float[]{ax,.5f,az,bx,.5f,bz,jx,.5f,jz,ix,.5f,iz},0,1,0);
            at=quad(v,at,new float[]{ix,-.5f,iz,jx,-.5f,jz,bx,-.5f,bz,ax,-.5f,az},0,-1,0);
        }return v;
    }
    private static int quad(float[] out,int at,float[] p,float nx,float ny,float nz){
        for(int i:new int[]{0,1,2,0,2,3}){out[at++]=p[i*3];out[at++]=p[i*3+1];out[at++]=p[i*3+2];out[at++]=nx;out[at++]=ny;out[at++]=nz;out[at++]=.5f;out[at++]=.5f;}return at;
    }
}
