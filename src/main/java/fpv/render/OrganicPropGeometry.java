package fpv.render;

import fpv.world.NaturalProps.Kind;
import java.util.Arrays;

/** Reusable low-poly templates. Local X/Z stay inside the original tile envelope, Y spans 0..1. */
public final class OrganicPropGeometry {
    public static final int STRIDE=6; // XYZ, UV, face shade
    private static final float[][][] CACHE=new float[Kind.values().length][8][];
    private OrganicPropGeometry(){}
    public static float[] template(Kind kind,int variant){
        if(kind==null)throw new IllegalArgumentException("Natural prop kind required");
        int v=Math.floorMod(variant,8);float[] cached=CACHE[kind.ordinal()][v];
        if(cached==null)CACHE[kind.ordinal()][v]=cached=create(kind,v);return cached;
    }
    private static float[] create(Kind kind,int variant){
        Builder b=new Builder();b.flat=kind==Kind.DEAD_TREE;double angle=variant*Math.PI*.173;
        switch(kind){
            case PINE->{
                b.frustum(0,0,0,0,.8,0,.085,.045,6,angle,.48);
                b.frustum(0,.28,0,0,.73,0,.48,.025,8,angle,.92);
                b.frustum(.018,.48,-.015,0,.9,0,.37,.014,8,angle+.2,.98);
                b.frustum(0,.69,0,0,1,0,.25,0,8,angle,1.05);
            }
            case LEAF_TREE->{
                b.frustum(0,0,0,.025,.7,-.02,.095,.045,6,angle,.46);
                b.frustum(0,.36,0,-.23,.76,.02,.043,.015,5,angle,.52);
                b.frustum(0,.45,0,.22,.79,-.09,.036,.012,5,angle,.52);
                b.rounded(-.17,.73,.02,.28,.25,.32,7,3,variant,.94);
                b.rounded(.19,.77,-.06,.27,.22,.30,7,3,variant+3,1);
                b.rounded(.01,.84,.11,.26,.16,.29,7,3,variant+5,1.03);
            }
            case DEAD_TREE->{
                b.frustum(0,0,0,.025,1,-.03,.09,.016,6,angle,.76);
                for(int i=0;i<4;i++){
                    double a=angle+i*2.4,y=.28+i*.13;
                    b.frustum(0,y,0,Math.cos(a)*.39,y+.24,Math.sin(a)*.39,.046,.013,5,a,.88);
                    b.frustum(Math.cos(a)*.29,y+.18,Math.sin(a)*.29,Math.cos(a+.45)*.43,y+.36,Math.sin(a+.45)*.43,.025,.004,4,a,1);
                }
            }
            case BUSH->{
                b.rounded(-.17,.38,-.06,.29,.38,.32,7,4,variant,.94);
                b.rounded(.2,.39,.03,.27,.39,.31,7,4,variant+2,1);
                b.rounded(.01,.55,-.05,.28,.45,.31,7,4,variant+5,1.05);
            }
            case ROCK->b.rounded(0,.49,0,.47,.49,.45,8,3,variant,1);
        }
        return Arrays.copyOf(b.values,b.count);
    }
    private static final class Builder{
        float[] values=new float[4096];int count;boolean flat;
        void vertex(double x,double y,double z,double shade){
            double radius=Math.hypot(x,z);if(radius>.495){x*=.495/radius;z*=.495/radius;}
            y=Math.max(0,Math.min(1,y));
            if(count+STRIDE>values.length)values=Arrays.copyOf(values,values.length*2);
            values[count++]=(float)x;values[count++]=(float)y;values[count++]=(float)z;
            values[count++]=flat||shade<.6?-1:(float)Math.max(.03,Math.min(.97,x+.5));values[count++]=flat||shade<.6?-1:(float)Math.max(.03,Math.min(.97,z+.5));values[count++]=(float)shade;
        }
        void triangle(double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz,double shade){
            double ux=bx-ax,uy=by-ay,uz=bz-az,vx=cx-ax,vy=cy-ay,vz=cz-az;
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,n=Math.sqrt(nx*nx+ny*ny+nz*nz);
            double light=n<1e-10?.75:.7+.3*Math.abs((nx*.35+ny*.8-nz*.4)/n);
            vertex(ax,ay,az,shade*light);vertex(bx,by,bz,shade*light);vertex(cx,cy,cz,shade*light);
        }
        void frustum(double ax,double ay,double az,double bx,double by,double bz,double ar,double br,int sides,double angle,double shade){
            // Small branch tilts share horizontal polygon rings; cached faceting is sufficient at prop scale.
            for(int i=0;i<sides;i++){
                double a=angle+i*Math.PI*2/sides,c=angle+(i+1)*Math.PI*2/sides;
                double x0=Math.cos(a),z0=Math.sin(a),x1=Math.cos(c),z1=Math.sin(c);
                triangle(ax+x0*ar,ay,az+z0*ar,ax+x1*ar,ay,az+z1*ar,bx+x1*br,by,bz+z1*br,shade);
                if(br>0)triangle(ax+x0*ar,ay,az+z0*ar,bx+x1*br,by,bz+z1*br,bx+x0*br,by,bz+z0*br,shade);
                if(br>0)triangle(bx,by,bz,bx+x0*br,by,bz+z0*br,bx+x1*br,by,bz+z1*br,shade);
            }
        }
        void rounded(double cx,double cy,double cz,double rx,double ry,double rz,int sides,int rings,int variant,double shade){
            for(int ring=0;ring<rings;ring++)for(int i=0;i<sides;i++){
                double a=i*Math.PI*2/sides,b=(i+1)*Math.PI*2/sides,p=-Math.PI/2+ring*Math.PI/rings,q=p+Math.PI/rings;
                double sa=1+.06*Math.sin(i*2.81+variant),sb=1+.06*Math.sin((i+1)*2.81+variant);
                double ax=cx+Math.cos(a)*Math.cos(p)*rx*sa,ay=cy+Math.sin(p)*ry,az=cz+Math.sin(a)*Math.cos(p)*rz*sa;
                double bx=cx+Math.cos(b)*Math.cos(p)*rx*sb,by=ay,bz=cz+Math.sin(b)*Math.cos(p)*rz*sb;
                double dx=cx+Math.cos(a)*Math.cos(q)*rx*sa,dy=cy+Math.sin(q)*ry,dz=cz+Math.sin(a)*Math.cos(q)*rz*sa;
                double ex=cx+Math.cos(b)*Math.cos(q)*rx*sb,ey=dy,ez=cz+Math.sin(b)*Math.cos(q)*rz*sb;
                if(ring>0)triangle(ax,ay,az,bx,by,bz,ex,ey,ez,shade);
                if(ring<rings-1)triangle(ax,ay,az,ex,ey,ez,dx,dy,dz,shade);
            }
        }
    }
}
