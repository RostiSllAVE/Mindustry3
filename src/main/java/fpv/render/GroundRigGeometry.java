package fpv.render;

import java.util.Arrays;

/** Immutable small rig templates. Vertex part IDs select a compact affine palette; no mesh skinning allocations. */
public final class GroundRigGeometry {
    public static final int STRIDE=10,MAX_PARTS=33;
    private GroundRigGeometry(){}
    /** Clamp an atlas-derived body to the server's metadata-only vertical envelope, without inflating smaller art. */
    public static double depthScale(double spriteHalfDepth,double requested,double canonicalHalfDepth){
        if(!Double.isFinite(spriteHalfDepth+requested+canonicalHalfDepth)||spriteHalfDepth<=0||requested<=0||canonicalHalfDepth<=0)
            throw new IllegalArgumentException("Positive finite body dimensions required");
        return Math.min(requested,canonicalHalfDepth/spriteHalfDepth);
    }
    public record Result(float[] vertices,int parts){
        public int vertexCount(){return vertices.length/STRIDE;}
    }
    public static Result generate(boolean wheels,int legCount,boolean coarse){
        if(wheels&&legCount!=0||!wheels&&(legCount<2||legCount>8||legCount%2!=0))
            throw new IllegalArgumentException("Four wheels or two to eight paired legs required");
        Output out=new Output();int sides=coarse?6:10,rows=coarse?3:5;
        sphere(out,0,sides,rows,.35f,.27f,.43f);
        if(wheels){
            for(int i=0;i<4;i++)cylinder(out,i+1,sides,.26f,.24f,.3f,true);
            return new Result(out.finish(),5);
        }
        for(int i=0;i<legCount;i++){
            int part=1+i*4;
            cylinder(out,part,coarse?4:6,.47f,.36f,.55f,false);
            cylinder(out,part+1,coarse?4:6,.31f,.26f,.37f,false);
            sphere(out,part+2,coarse?4:6,coarse?2:3,.62f,.44f,.7f);
            cylinder(out,part+3,coarse?4:6,.24f,.21f,.29f,false);
        }
        return new Result(out.finish(),1+legCount*4);
    }
    private static void sphere(Output out,int part,int sides,int rows,float r,float g,float b){
        for(int row=0;row<rows;row++)for(int col=0;col<sides;col++){
            double a0=col*Math.PI*2/sides,a1=(col+1)*Math.PI*2/sides;
            double h0=-Math.PI/2+row*Math.PI/rows,h1=-Math.PI/2+(row+1)*Math.PI/rows;
            sphereVertex(out,part,h0,a0,r,g,b);sphereVertex(out,part,h1,a0,r,g,b);sphereVertex(out,part,h1,a1,r,g,b);
            sphereVertex(out,part,h0,a0,r,g,b);sphereVertex(out,part,h1,a1,r,g,b);sphereVertex(out,part,h0,a1,r,g,b);
        }
    }
    private static void sphereVertex(Output out,int part,double latitude,double longitude,float r,float g,float b){
        double x=Math.cos(latitude)*Math.cos(longitude),y=Math.sin(latitude),z=Math.cos(latitude)*Math.sin(longitude);
        out.vertex(x,y,z,x,y,z,r,g,b,part);
    }
    private static void cylinder(Output out,int part,int sides,float r,float g,float b,boolean tread){
        for(int side=0;side<sides;side++){
            double a0=side*Math.PI*2/sides,a1=(side+1)*Math.PI*2/sides;
            double x0=Math.cos(a0),z0=Math.sin(a0),x1=Math.cos(a1),z1=Math.sin(a1);
            float shade=tread&&side%2==0?.55f:1;
            out.vertex(x0,-.5,z0,x0,0,z0,r*shade,g*shade,b*shade,part);
            out.vertex(x1,-.5,z1,x1,0,z1,r*shade,g*shade,b*shade,part);
            out.vertex(x1,.5,z1,x1,0,z1,r*shade,g*shade,b*shade,part);
            out.vertex(x0,-.5,z0,x0,0,z0,r*shade,g*shade,b*shade,part);
            out.vertex(x1,.5,z1,x1,0,z1,r*shade,g*shade,b*shade,part);
            out.vertex(x0,.5,z0,x0,0,z0,r*shade,g*shade,b*shade,part);
            for(int sign=-1;sign<=1;sign+=2){
                out.vertex(0,sign*.5,0,0,sign,0,r*.8f,g*.8f,b*.8f,part);
                out.vertex(x0,sign*.5,z0,0,sign,0,r*.8f,g*.8f,b*.8f,part);
                out.vertex(x1,sign*.5,z1,0,sign,0,r*.8f,g*.8f,b*.8f,part);
            }
        }
    }
    private static final class Output{
        float[] values=new float[4096];int count;
        void vertex(double x,double y,double z,double nx,double ny,double nz,float r,float g,float b,int part){
            if(count+STRIDE>values.length)values=Arrays.copyOf(values,values.length*2);
            values[count++]=(float)x;values[count++]=(float)y;values[count++]=(float)z;
            values[count++]=(float)nx;values[count++]=(float)ny;values[count++]=(float)nz;
            values[count++]=r;values[count++]=g;values[count++]=b;values[count++]=part;
        }
        float[] finish(){return Arrays.copyOf(values,count);}
    }
}
