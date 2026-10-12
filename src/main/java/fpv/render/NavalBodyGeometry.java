package fpv.render;

import java.util.Arrays;
import java.util.List;

/** Reusable sprite-section relief: replace the deck with one exterior height field, never stack full sprites. */
public final class NavalBodyGeometry {
    private NavalBodyGeometry(){}
    /** Normalized original-sprite rectangle, chamfered corners; height is in Mindustry tiles. */
    public record Section(float x,float y,float width,float length,float height,float chamfer){
        public Section{
            if(!Float.isFinite(x+y+width+length+height+chamfer)||x<0||y<0||width<=0||length<=0||x+width>1||y+length>1
                ||height<=0||height>.45f||chamfer<0||chamfer>.45f)throw new IllegalArgumentException("Bounded sprite section required");
        }
        public boolean contains(double u,double v){
            double a=(u-x)/width,b=(v-y)/length;
            return a>=0&&a<=1&&b>=0&&b<=1&&Math.min(a,1-a)+Math.min(b,1-b)>=chamfer;
        }
    }
    public static SpriteExtrusionGeometry.Result generate(SpriteExtrusionGeometry.Result shell,List<Section> sections,float depth,int cap){
        if(shell==null||sections==null||sections.size()>3||!Float.isFinite(depth)||depth<=0||cap<36||cap>Integer.MAX_VALUE/8
            ||shell.gridWidth()<1||shell.gridHeight()<1||shell.vertices().length%48!=0)throw new IllegalArgumentException("Bounded naval body required");
        if(sections.isEmpty())return shell;
        int w=shell.gridWidth(),h=shell.gridHeight();boolean[] occupied=new boolean[w*h];float[] level=new float[w*h];
        var out=new Output(cap);float[] source=shell.vertices();
        for(int i=0;i<source.length;i+=48){
            if(source[i+4]<.9f){out.copy(source,i,48);continue;}
            float minX=1,maxX=-1,minZ=1,maxZ=-1;
            for(int k=0;k<48;k+=8){minX=Math.min(minX,source[i+k]);maxX=Math.max(maxX,source[i+k]);minZ=Math.min(minZ,source[i+k+2]);maxZ=Math.max(maxZ,source[i+k+2]);}
            int x0=Math.max(0,Math.round((minX+.5f)*w)),x1=Math.min(w,Math.round((maxX+.5f)*w));
            int y0=Math.max(0,Math.round((minZ+.5f)*h)),y1=Math.min(h,Math.round((maxZ+.5f)*h));
            for(int y=y0;y<y1;y++)Arrays.fill(occupied,x0+y*w,x1+y*w,true);
        }
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(occupied[x+y*w]){
            for(var section:sections)if(section.contains((x+.5)/w,(y+.5)/h))level[x+y*w]=Math.max(level[x+y*w],section.height()/depth);
        }
        boolean[] used=new boolean[w*h];int roofs=0,sides=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=x+y*w;if(!occupied[c]||used[c])continue;float l=level[c];int width=1;
            while(x+width<w&&occupied[c+width]&&!used[c+width]&&level[c+width]==l)width++;
            int length=1;outer:while(y+length<h){for(int xx=x;xx<x+width;xx++){
                int n=xx+(y+length)*w;if(!occupied[n]||used[n]||level[n]!=l)break outer;
            }length++;}
            for(int yy=y;yy<y+length;yy++)Arrays.fill(used,x+yy*w,x+width+yy*w,true);
            float a=x/(float)w-.5f,b=(x+width)/(float)w-.5f,z=y/(float)h-.5f,t=(y+length)/(float)h-.5f;
            out.face(a,.5f+l,z,a,.5f+l,t,b,.5f+l,t,b,.5f+l,z,0,1,0,-1,-1);roofs++;
        }
        // Only exterior risers: no horizontal deck hidden under a raised section and no internal walls.
        for(int face=0;face<4;face++){
            int rows=face<2?h:w,columns=face<2?w:h;
            for(int row=0;row<rows;row++)for(int col=0;col<columns;){
                int x=face<2?col:row,y=face<2?row:col,c=x+y*w;
                float hi=level[c],lo=neighbor(level,w,h,x,y,face);if(!occupied[c]||hi<=lo){col++;continue;}
                int first=col++;
                while(col<columns){int nx=face<2?col:row,ny=face<2?row:col,n=nx+ny*w;
                    if(!occupied[n]||level[n]!=hi||neighbor(level,w,h,nx,ny,face)!=lo)break;col++;
                }
                float u=(x+.5f)/w,v=(y+.5f)/h,a=first/(float)(face<2?w:h)-.5f,b=col/(float)(face<2?w:h)-.5f;
                float edge=(row+(face==1||face==3?1:0))/(float)(face<2?h:w)-.5f;
                if(face==0)out.face(a,.5f+lo,edge,a,.5f+hi,edge,b,.5f+hi,edge,b,.5f+lo,edge,0,0,-1,u,v);
                else if(face==1)out.face(a,.5f+lo,edge,b,.5f+lo,edge,b,.5f+hi,edge,a,.5f+hi,edge,0,0,1,u,v);
                else if(face==2)out.face(edge,.5f+lo,a,edge,.5f+lo,b,edge,.5f+hi,b,edge,.5f+hi,a,-1,0,0,u,v);
                else out.face(edge,.5f+lo,a,edge,.5f+hi,a,edge,.5f+hi,b,edge,.5f+lo,b,1,0,0,u,v);
                sides++;
            }
        }
        return new SpriteExtrusionGeometry.Result(Arrays.copyOf(out.data,out.size),w,h,shell.opaqueCells(),roofs,shell.bottomQuads(),shell.sideQuads()+sides);
    }
    private static float neighbor(float[] levels,int w,int h,int x,int y,int face){
        x+=face==2?-1:face==3?1:0;y+=face==0?-1:face==1?1:0;
        return x<0||x>=w||y<0||y>=h?0:levels[x+y*w];
    }
    private static final class Output{
        final int limit;float[] data=new float[288];int size;
        Output(int cap){limit=cap*8;}
        void reserve(int n){if(size+n>limit)throw new IllegalArgumentException("Naval relief exceeds vertex budget");if(size+n>data.length)data=Arrays.copyOf(data,Math.min(limit,Math.max(size+n,data.length*2)));}
        void copy(float[] input,int start,int n){reserve(n);System.arraycopy(input,start,data,size,n);size+=n;}
        void face(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float nx,float ny,float nz,float u,float v){
            reserve(48);vertex(ax,ay,az,nx,ny,nz,u,v);vertex(bx,by,bz,nx,ny,nz,u,v);vertex(cx,cy,cz,nx,ny,nz,u,v);
            vertex(ax,ay,az,nx,ny,nz,u,v);vertex(cx,cy,cz,nx,ny,nz,u,v);vertex(dx,dy,dz,nx,ny,nz,u,v);
        }
        void vertex(float x,float y,float z,float nx,float ny,float nz,float u,float v){data[size++]=x;data[size++]=y;data[size++]=z;data[size++]=nx;data[size++]=ny;data[size++]=nz;data[size++]=u<0?x+.5f:u;data[size++]=v<0?z+.5f:v;}
    }
}
