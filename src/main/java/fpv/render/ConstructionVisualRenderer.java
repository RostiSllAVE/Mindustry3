package fpv.render;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import mindustry.world.Block;

/** Bounded transparent atlas batch for oriented construction holograms and native-style build beams. */
public final class ConstructionVisualRenderer implements AutoCloseable {
    private static final int MAX_GHOSTS=256,MAX_BEAMS=256;
    private final NativeAtlasMesh.Builder batch=new NativeAtlasMesh.Builder(true);
    private Shader shader;
    private int ghosts,beams;
    public record Stats(int ghosts,int beams,int streamMeshes){}
    public Stats stats(){return new Stats(ghosts,beams,batch.streamMeshCount());}
    public void begin(){ghosts=beams=0;batch.clear();}
    public void ghost(Block block,double x,double y,double z,double height,int rotation,boolean valid,float alpha){
        if(block==null)return;ghost(NativeRegions.block(block),x,y,z,block.size,height,block.rotate?-rotation*90:0,valid,alpha);
    }
    public void ghost(TextureRegion region,double x,double y,double z,double size,double height,double angle,boolean valid,float alpha){
        if(ghosts>=MAX_GHOSTS||!NativeAtlasMesh.valid(region)||!Double.isFinite(x+y+z+size+height)||size<=0||height<0)return;
        ensure();float r=valid?.72f:1,g=valid?1:.36f,b=valid?1:.31f;
        float left=(float)(x-size*.5),right=(float)(x+size*.5),n=(float)(-y-size*.5),s=(float)(-y+size*.5),low=(float)z+.012f,high=(float)(z+height)+.018f;
        float hw=(float)size*.5f,c=Mathf.cosDeg((float)angle),sn=Mathf.sinDeg((float)angle),cx=(float)x,cz=(float)-y;
        batch.projectionFace(region,cx-hw*c+hw*sn,high,cz-hw*sn-hw*c,cx+hw*c+hw*sn,high,cz+hw*sn-hw*c,
            cx+hw*c-hw*sn,high,cz+hw*sn+hw*c,cx-hw*c-hw*sn,high,cz-hw*sn+hw*c,r,g,b,alpha);
        if(height>.03){
            batch.projectionFace(region,left,high,s,right,high,s,right,low,s,left,low,s,r,g,b,alpha*.45f);
            batch.projectionFace(region,right,high,n,left,high,n,left,low,n,right,low,n,r,g,b,alpha*.45f);
            batch.projectionFace(region,left,high,n,left,high,s,left,low,s,left,low,n,r,g,b,alpha*.4f);
            batch.projectionFace(region,right,high,s,right,high,n,right,low,n,right,low,s,r,g,b,alpha*.4f);
        }
        ghosts++;
    }
    /** Canonical XYZ input; four translucent sides converge at the real builder emitter. */
    public void beam(double ax,double ay,double az,double bx,double by,double bz,double size,boolean removing,float alpha){
        if(beams>=MAX_BEAMS||!Double.isFinite(ax+ay+az+bx+by+bz+size)||alpha<=0)return;
        ensure();Color color=removing?mindustry.graphics.Pal.remove:mindustry.graphics.Pal.accent;
        double half=Math.max(.18,size*.5),pulse=.55+.12*Math.sin(arc.util.Time.time*.13);
        for(int edge=0;edge<4;edge++){
            double x0=bx+(edge==0||edge==3?-half:half),y0=by+(edge<2?-half:half);
            int next=(edge+1)%4;double x1=bx+(next==0||next==3?-half:half),y1=by+(next<2?-half:half);
            batch.projectionFace(Core.atlas.white(),(float)ax,(float)az,(float)-ay,(float)x0,(float)bz,(float)-y0,
                (float)x1,(float)bz,(float)-y1,(float)ax,(float)az,(float)-ay,color.r,color.g,color.b,alpha*.085f);
            ribbon(ax,ay,az,x0+(x1-x0)*pulse,y0+(y1-y0)*pulse,bz,.024,color,alpha*.8f);
        }
        beams++;
    }
    private void ribbon(double ax,double ay,double az,double bx,double by,double bz,double width,Color c,float alpha){
        double dx=bx-ax,dy=by-ay,length=Math.hypot(dx,dy),ox=length>.0001?-dy/length*width:width,oy=length>.0001?dx/length*width:0;
        batch.projectionFace(Core.atlas.white(),(float)(ax-ox),(float)az,(float)(-ay+oy),(float)(ax+ox),(float)az,(float)(-ay-oy),
            (float)(bx+ox),(float)bz,(float)(-by-oy),(float)(bx-ox),(float)bz,(float)(-by+oy),c.r,c.g,c.b,alpha);
    }
    private void ensure(){
        if(shader==null)shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec4 c;varying vec2 uv;void main(){c=a_color;uv=a_texCoord0;gl_Position=u_combined*vec4(a_position,1.0);}",
            "uniform sampler2D u_texture;varying vec4 c;varying vec2 uv;void main(){vec4 t=texture2D(u_texture,uv);if(t.a<.1)discard;gl_FragColor=vec4(t.rgb*c.rgb,t.a*c.a);}");
        batch.overflowShader=shader;
    }
    public void draw(Camera3D camera){
        if(ghosts+beams==0||shader==null)return;
        boolean depth=Gl.isEnabled(Gl.depthTest),blend=Gl.isEnabled(Gl.blend),write=Gl.getInt(Gl.depthWritemask)!=0,cull=Gl.isEnabled(Gl.cullFace);
        int source=Gl.getInt(Gl.blendSrcRgb),dest=Gl.getInt(Gl.blendDstRgb),sourceA=Gl.getInt(Gl.blendSrcAlpha),destA=Gl.getInt(Gl.blendDstAlpha);
        try{Gl.enable(Gl.depthTest);Gl.disable(Gl.cullFace);Gl.enable(Gl.blend);Gl.blendFunc(Gl.srcAlpha,Gl.oneMinusSrcAlpha);Gl.depthMask(false);
            shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);batch.drawAndClear(shader);
        }finally{Gl.depthMask(write);enabled(Gl.depthTest,depth);enabled(Gl.blend,blend);enabled(Gl.cullFace,cull);Gl.blendFuncSeparate(source,dest,sourceA,destA);}
    }
    private static void enabled(int flag,boolean value){if(value)Gl.enable(flag);else Gl.disable(flag);}
    @Override public void close(){batch.dispose();if(shader!=null)shader.dispose();shader=null;}
}
