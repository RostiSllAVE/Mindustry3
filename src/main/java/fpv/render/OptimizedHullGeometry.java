package fpv.render;

import java.util.Arrays;

/** One exterior mesh: native roof, nested contour sides, exposed downward ledges and underside.
 * No internal caps, extra textures or runtime generation. Straight boundary runs share a face. */
public final class OptimizedHullGeometry {
    private OptimizedHullGeometry(){}
    private record Grid(int w,int h,boolean[] solid,float[] u,float[] v){
        boolean at(int x,int y){return x>=0&&y>=0&&x<w&&y<h&&solid[x+y*w];}
    }
    public static SpriteExtrusionGeometry.Result generate(byte[] alpha,int w,int h,LayeredHullProfile profile,
            int maxSide,int minSide,int threshold,double coverage,int cap){
        if(alpha==null||w<=0||h<=0||(long)w*h!=alpha.length||profile==null||minSide<1||maxSide<minSide
            ||threshold<1||threshold>255||!Double.isFinite(coverage)||coverage<=0||coverage>1||cap<36||cap>Integer.MAX_VALUE/8)
            throw new IllegalArgumentException("Bounded silhouette/profile required");
        byte[][] masks=new byte[profile.bands().size()][];
        for(int i=0;i<masks.length;i++)masks[i]=profile.bands().get(i).scale()==1?alpha:LayeredHullGeometry.inset(alpha,w,h,profile.bands().get(i).scale(),threshold);
        for(int side=maxSide;;side=Math.max(minSide,side/2)){
            Grid[] grids=new Grid[masks.length];for(int i=0;i<grids.length;i++)grids[i]=sample(masks[i],w,h,side,threshold,coverage);
            // Irregular native holes must never produce a larger lower contour or hidden top islands.
            for(int i=grids.length-2;i>=0;i--)for(int c=0;c<grids[i].solid.length;c++)grids[i].solid[c]&=grids[i+1].solid[c];
            try{return shell(grids,profile,cap);}catch(Limit limit){if(side==minSide)throw new IllegalArgumentException("Exterior hull exceeds vertex budget");}
        }
    }
    private static Grid sample(byte[] alpha,int w,int h,int side,int threshold,double coverage){
        int longest=Math.max(w,h),gw=Math.min(w,Math.max(1,(int)Math.ceil(w*(double)side/longest))),gh=Math.min(h,Math.max(1,(int)Math.ceil(h*(double)side/longest)));
        boolean[] solid=new boolean[gw*gh];float[] u=new float[solid.length],v=new float[solid.length];
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
            int x0=x*w/gw,x1=(x+1)*w/gw,y0=y*h/gh,y1=(y+1)*h/gh,occupied=0,bx=x0,by=y0;
            double nearest=Double.POSITIVE_INFINITY,cx=(x0+x1-1)/2.0,cy=(y0+y1-1)/2.0;
            for(int yy=y0;yy<y1;yy++)for(int xx=x0;xx<x1;xx++)if((alpha[xx+yy*w]&255)>=threshold){
                occupied++;double d=(xx-cx)*(xx-cx)+(yy-cy)*(yy-cy);if(d<nearest){nearest=d;bx=xx;by=yy;}
            }
            int c=x+y*gw;solid[c]=occupied>0&&occupied/(double)((x1-x0)*(y1-y0))>=coverage;u[c]=(bx+.5f)/w;v[c]=(by+.5f)/h;
        }
        return new Grid(gw,gh,solid,u,v);
    }
    private static SpriteExtrusionGeometry.Result shell(Grid[] grids,LayeredHullProfile profile,int cap){
        Output out=new Output(cap);Grid roof=grids[grids.length-1];int cells=0;for(boolean s:roof.solid)if(s)cells++;
        int tops=caps(out,roof,roof.solid,.5f,true),bottoms=0,sides=0;double bottom=0,total=profile.depth();
        for(int i=0;i<grids.length;i++){
            Grid grid=grids[i];float lo=(float)(bottom/total-.5),hi=(float)((bottom+profile.bands().get(i).height())/total-.5);
            boolean[] exposed=grid.solid.clone();if(i>0)for(int c=0;c<exposed.length;c++)exposed[c]&=!grids[i-1].solid[c];
            // Only visible underside: lower cap, then downward armor ledges outside the lower contour.
            bottoms+=caps(out,grid,exposed,lo,false);
            for(int face=0;face<4;face++){
                int rows=face<2?grid.h:grid.w,columns=face<2?grid.w:grid.h;
                for(int row=0;row<rows;row++)for(int col=0;col<columns;){
                    if(!boundary(grid,face,col,row)){col++;continue;}int first=col++;
                    while(col<columns&&boundary(grid,face,col,row))col++;
                    side(out,grid,face,first,col,row,lo,hi);sides++;
                }
            }
            bottom+=profile.bands().get(i).height();
        }
        return new SpriteExtrusionGeometry.Result(Arrays.copyOf(out.values,out.count),roof.w,roof.h,cells,tops,bottoms,sides);
    }
    private static boolean boundary(Grid g,int face,int col,int row){
        int x=face<2?col:row,y=face<2?row:col;
        return g.at(x,y)&&!g.at(x+(face==2?-1:face==3?1:0),y+(face==0?-1:face==1?1:0));
    }
    private static void side(Output o,Grid g,int face,int first,int end,int row,float lo,float hi){
        int mid=(first+end-1)/2,c=face<2?mid+row*g.w:row+mid*g.w;float u=g.u[c],v=g.v[c];
        if(face<2){float a=first/(float)g.w-.5f,b=end/(float)g.w-.5f,z=(row+(face==1?1:0))/(float)g.h-.5f;
            if(face==0)o.face(a,lo,z,a,hi,z,b,hi,z,b,lo,z,0,0,-1,u,v);
            else o.face(a,lo,z,b,lo,z,b,hi,z,a,hi,z,0,0,1,u,v);
        }else{float a=first/(float)g.h-.5f,b=end/(float)g.h-.5f,x=(row+(face==3?1:0))/(float)g.w-.5f;
            if(face==2)o.face(x,lo,a,x,lo,b,x,hi,b,x,hi,a,-1,0,0,u,v);
            else o.face(x,lo,a,x,hi,a,x,hi,b,x,lo,b,1,0,0,u,v);
        }
    }
    private static int caps(Output o,Grid g,boolean[] mask,float height,boolean top){
        boolean[] used=new boolean[mask.length];int count=0;
        for(int y=0;y<g.h;y++)for(int x=0;x<g.w;x++){
            int c=x+y*g.w;if(!mask[c]||used[c])continue;int width=1;
            while(x+width<g.w&&mask[c+width]&&!used[c+width])width++;
            int length=1;outer:while(y+length<g.h){for(int xx=x;xx<x+width;xx++)if(!mask[xx+(y+length)*g.w]||used[xx+(y+length)*g.w])break outer;length++;}
            for(int yy=y;yy<y+length;yy++)Arrays.fill(used,x+yy*g.w,x+width+yy*g.w,true);
            float x0=x/(float)g.w-.5f,x1=(x+width)/(float)g.w-.5f,z0=y/(float)g.h-.5f,z1=(y+length)/(float)g.h-.5f;
            if(top)o.face(x0,height,z0,x0,height,z1,x1,height,z1,x1,height,z0,0,1,0,-1,-1);
            else o.face(x0,height,z0,x1,height,z0,x1,height,z1,x0,height,z1,0,-1,0,g.u[c],g.v[c]);
            count++;
        }
        return count;
    }
    private static final class Limit extends RuntimeException {Limit(){super(null,null,false,false);}}
    private static final class Output {
        final int cap;float[] values;int count;
        Output(int vertices){cap=vertices*8;values=new float[Math.min(cap,288)];}
        void face(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float nx,float ny,float nz,float u,float v){
            int need=count+48;if(need>cap)throw new Limit();if(need>values.length)values=Arrays.copyOf(values,Math.min(cap,Math.max(need,values.length*2)));
            vertex(ax,ay,az,nx,ny,nz,u,v);vertex(bx,by,bz,nx,ny,nz,u,v);vertex(cx,cy,cz,nx,ny,nz,u,v);
            vertex(ax,ay,az,nx,ny,nz,u,v);vertex(cx,cy,cz,nx,ny,nz,u,v);vertex(dx,dy,dz,nx,ny,nz,u,v);
        }
        void vertex(float x,float y,float z,float nx,float ny,float nz,float u,float v){
            values[count++]=x;values[count++]=y;values[count++]=z;values[count++]=nx;values[count++]=ny;values[count++]=nz;values[count++]=u<0?x+.5f:u;values[count++]=v<0?z+.5f:v;
        }
    }
}
