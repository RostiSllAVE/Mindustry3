package fpv.render;

/** Cached closed octagonal tube; draw-time work is affine transforms and face shading only. */
public final class VolumetricBeamGeometry {
    public interface Sink {void vertex(float x,float y,float z,float r,float g,float b);}
    public static final int SIDES=8,VERTICES=SIDES*12;
    private static final float[] TEMPLATE=template();
    private VolumetricBeamGeometry(){}
    private static float[] template(){
        float[] data=new float[VERTICES*6];int p=0;
        for(int i=0;i<SIDES;i++){
            double a=i*Math.PI*2/SIDES,b=(i+1)*Math.PI*2/SIDES;
            float ax=(float)Math.cos(a),ay=(float)Math.sin(a),bx=(float)Math.cos(b),by=(float)Math.sin(b),nx=(float)Math.cos((a+b)*.5),ny=(float)Math.sin((a+b)*.5);
            p=put(data,p,ax,ay,0,nx,ny,0);p=put(data,p,bx,by,0,nx,ny,0);p=put(data,p,bx,by,1,nx,ny,0);
            p=put(data,p,ax,ay,0,nx,ny,0);p=put(data,p,bx,by,1,nx,ny,0);p=put(data,p,ax,ay,1,nx,ny,0);
            p=put(data,p,0,0,0,0,0,-1);p=put(data,p,bx,by,0,0,0,-1);p=put(data,p,ax,ay,0,0,0,-1);
            p=put(data,p,0,0,1,0,0,1);p=put(data,p,ax,ay,1,0,0,1);p=put(data,p,bx,by,1,0,0,1);
        }
        return data;
    }
    private static int put(float[] a,int p,float x,float y,float z,float nx,float ny,float nz){a[p++]=x;a[p++]=y;a[p++]=z;a[p++]=nx;a[p++]=ny;a[p++]=nz;return p;}
    public static int emit(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b,float eyeX,float eyeY,float eyeZ,Sink out){
        double dx=bx-ax,dy=by-ay,dz=bz-az,length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!Double.isFinite(length)||length<.001||!Float.isFinite(width)||width<=0)return 0;
        double nx=dx/length,ny=dy/length,nz=dz/length,sx,sy,sz;
        if(Math.abs(ny)<.9){sx=-nz;sy=0;sz=nx;}else{sx=0;sy=nz;sz=-ny;}
        double sn=Math.sqrt(sx*sx+sy*sy+sz*sz);sx/=sn;sy/=sn;sz/=sn;
        double tx=ny*sz-nz*sy,ty=nz*sx-nx*sz,tz=nx*sy-ny*sx;
        double ex=eyeX-(ax+bx)*.5,ey=eyeY-(ay+by)*.5,ez=eyeZ-(az+bz)*.5,en=Math.max(.001,Math.sqrt(ex*ex+ey*ey+ez*ez));ex/=en;ey/=en;ez/=en;
        double radius=width*.5;
        for(int i=0;i<TEMPLATE.length;i+=6){
            double u=TEMPLATE[i]*radius,v=TEMPLATE[i+1]*radius,w=TEMPLATE[i+2];
            double normalX=sx*TEMPLATE[i+3]+tx*TEMPLATE[i+4]+nx*TEMPLATE[i+5],normalY=sy*TEMPLATE[i+3]+ty*TEMPLATE[i+4]+ny*TEMPLATE[i+5],normalZ=sz*TEMPLATE[i+3]+tz*TEMPLATE[i+4]+nz*TEMPLATE[i+5];
            float shade=(float)(.58+.42*Math.max(0,normalX*ex+normalY*ey+normalZ*ez));
            out.vertex((float)(ax+dx*w+sx*u+tx*v),(float)(ay+dy*w+sy*u+ty*v),(float)(az+dz*w+sz*u+tz*v),r*shade,g*shade,b*shade);
        }
        return VERTICES;
    }
}
