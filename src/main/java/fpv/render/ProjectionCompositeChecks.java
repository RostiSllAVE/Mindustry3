package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.util.ScreenUtils;
import java.util.Arrays;

/** Deterministic GPU pixel assertions; synchronous capture is restricted to explicit smoke runs. */
public final class ProjectionCompositeChecks {
    private ProjectionCompositeChecks(){}
    private static final int SIZE=256;
    private static final String VERTEX="attribute vec3 a_position;attribute vec4 a_color;varying vec4 v_color;void main(){v_color=a_color;gl_Position=vec4(a_position,1.0);}";
    private static final String FRAGMENT="uniform sampler2D u_opaqueDepth;uniform float u_opaqueCopy;uniform float u_depthEpsilon;uniform vec2 u_viewport;varying vec4 v_color;void main(){if(u_opaqueCopy>0.5&&gl_FragCoord.z>texture2D(u_opaqueDepth,gl_FragCoord.xy/u_viewport).r+u_depthEpsilon)discard;gl_FragColor=v_color;}";
    public static String verify(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Explicit isolated GPU smoke required");
        int program=Gl.getInt(Gl.currentProgram),depthFunc=Gl.getInt(Gl.depthFunc);boolean depth=Gl.isEnabled(Gl.depthTest),write=Gl.getInt(Gl.depthWritemask)!=0,blend=Gl.isEnabled(Gl.blend),cull=Gl.isEnabled(Gl.cullFace),scissor=Gl.isEnabled(Gl.scissorTest);
        int src=Gl.getInt(Gl.blendSrcRgb),dst=Gl.getInt(Gl.blendDstRgb),srcA=Gl.getInt(Gl.blendSrcAlpha),dstA=Gl.getInt(Gl.blendDstAlpha);
        FrameBuffer scene=new FrameBuffer(SIZE,SIZE,true);Shader shader=new Shader(VERTEX,FRAGMENT);
        Mesh mesh=new Mesh(false,6,0,VertexAttribute.position3,new VertexAttribute(4,"a_color"));float[] vertices=new float[42];
        int assertions=0;
        try(ProjectionCompositeTarget compositor=new ProjectionCompositeTarget();ProjectionCompositeTarget fallback=new ProjectionCompositeTarget(true)){
            Gl.disable(Gl.scissorTest);Gl.disable(Gl.cullFace);
            byte[] one=render(scene,compositor,shader,mesh,vertices,1,false,false,"wall-composite-front.png");
            byte[] several=render(scene,compositor,shader,mesh,vertices,3,false,false,"wall-composite-nearest.png");
            byte[] reverse=render(scene,compositor,shader,mesh,vertices,3,true,false,null);
            require(Arrays.equals(several,reverse),"nearest coverage is independent of front/back submission order");assertions++;
            require(close(pixel(one,128,145),pixel(several,128,145),0),"overlap has exactly the front-only color, no rear accumulation");assertions++;
            require(!close(pixel(one,220,170),pixel(several,220,170),8),"rear projection remains visible outside front horizontal coverage");assertions++;
            require(!close(pixel(one,128,220),pixel(several,128,220),8),"rear projection remains visible above front coverage");assertions++;
            require(close(pixel(several,30,145),new int[]{179,26,26,255},2),"opaque foreground depth blocks all projections");assertions++;
            require(!close(pixel(several,128,100),pixel(several,128,145),10),"different ordinary backgrounds remain visible through nearest projection");assertions++;
            require(close(pixel(several,128,145),new int[]{38,115,83,255},3),"single controlled straight-alpha composite has expected pixel color");assertions++;
            byte[] collapsed=render(scene,compositor,shader,mesh,vertices,2,false,false,"wall-composite-front-collapsed.png");
            require(!close(pixel(collapsed,128,145),pixel(several,128,145),8),"inactive front reveals next projection");assertions++;
            byte[] inside=render(scene,compositor,shader,mesh,vertices,3,true,true,"wall-composite-inside.png");
            require(close(pixel(inside,128,145),pixel(several,128,145),1),"reverse winding remains visible to inside-volume camera");assertions++;
            byte[] copied=render(scene,fallback,shader,mesh,vertices,3,false,false,"wall-composite-depth-copy.png");
            require(Arrays.equals(several,copied),"GPU depth-texture fallback matches framebuffer depth-blit output");assertions++;
            // Draw an ordinary object between background and projection AFTER composition. It must pass
            // the unchanged scene depth; writing shield depth into the main target would wrongly reject it.
            scene.begin();try{
                Gl.enable(Gl.depthTest);Gl.depthFunc(Gl.less);Gl.depthMask(true);Gl.disable(Gl.blend);shader.bind();shader.setUniformf("u_opaqueCopy",0);
                quad(mesh,shader,vertices,-.15f,-.15f,.15f,.15f,.5f,.8f,.2f,.7f,1,false);
                byte[] later=ScreenUtils.getFrameBufferPixels(0,0,SIZE,SIZE,false);require(close(pixel(later,128,128),new int[]{204,51,179,255},2),"projection never pollutes opaque scene depth");assertions++;
            }finally{scene.end();}
            int error=Gl.getError();require(error==0,"no GPU errors after both compositing paths: "+error);
            return "PASS: "+assertions+" native GPU pixel assertions, two/several overlapping surfaces, exact front-only alpha, rear side/top coverage, foreground depth, background transparency, order reversal, collapsed-front reveal, inside winding, depth-copy equivalence and unchanged opaque depth";
        }finally{
            scene.dispose();mesh.dispose();shader.dispose();Gl.useProgram(program);Gl.depthFunc(depthFunc);Gl.depthMask(write);enabled(Gl.depthTest,depth);enabled(Gl.blend,blend);enabled(Gl.cullFace,cull);enabled(Gl.scissorTest,scissor);Gl.blendFuncSeparate(src,dst,srcA,dstA);
        }
    }
    private static byte[] render(FrameBuffer scene,ProjectionCompositeTarget compositor,Shader shader,Mesh mesh,float[] vertices,int count,boolean reverse,boolean flip,String capture){
        scene.begin();try{
            Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.depthFunc(Gl.less);Gl.clearColor(.1f,.2f,.4f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
            shader.bind();shader.setUniformf("u_opaqueCopy",0);
            quad(mesh,shader,vertices,-1,-1,1,0,.9f,.1f,.45f,.2f,1,false);
            quad(mesh,shader,vertices,-.9f,-.8f,-.6f,.8f,-.7f,.7f,.1f,.1f,1,false);
            compositor.begin(scene);compositor.bindOpaqueDepth(shader);
            if(reverse){for(int i=2;i>=0;i--)if(include(i,count))projection(mesh,shader,vertices,i,flip);}
            else for(int i=0;i<3;i++)if(include(i,count))projection(mesh,shader,vertices,i,flip);
            compositor.compose();
            byte[] result=ScreenUtils.getFrameBufferPixels(0,0,SIZE,SIZE,false);
            if(capture!=null)ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output",System.getProperty("fpv.wallProfile.output"))).child(capture),0,0,SIZE,SIZE);
            return result;
        }finally{compositor.cancel();scene.end();}
    }
    private static boolean include(int index,int count){return count==3||(count==1?index==0:index>0);}
    private static void projection(Mesh mesh,Shader shader,float[] values,int index,boolean flip){
        if(index==0)quad(mesh,shader,values,-.5f,-.5f,.5f,.5f,-.2f,.2f,.7f,.25f,.5f,flip);
        else if(index==1)quad(mesh,shader,values,-.9f,-.9f,.9f,.9f,.2f,.7f,.25f,.15f,.6f,flip);
        else quad(mesh,shader,values,-.75f,-.75f,.75f,.75f,.5f,.15f,.3f,.9f,.7f,flip);
    }
    private static void quad(Mesh mesh,Shader shader,float[] a,float x0,float y0,float x1,float y1,float z,float r,float g,float b,float alpha,boolean flip){
        float[] xs={x0,x1,x1,x0,x1,x0},ys={y0,y0,y1,y0,y1,y1};
        for(int i=0;i<6;i++){int source=flip?5-i:i,n=i*7;a[n]=xs[source];a[n+1]=ys[source];a[n+2]=z;a[n+3]=r;a[n+4]=g;a[n+5]=b;a[n+6]=alpha;}
        shader.bind();mesh.setVertices(a);mesh.render(shader,Gl.triangles);
    }
    private static int[] pixel(byte[] data,int x,int y){int n=(y*SIZE+x)*4;return new int[]{data[n]&255,data[n+1]&255,data[n+2]&255,data[n+3]&255};}
    private static boolean close(int[] a,int[] b,int tolerance){for(int i=0;i<4;i++)if(Math.abs(a[i]-b[i])>tolerance)return false;return true;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Projection composite pixels: "+message);}
    private static void enabled(int capability,boolean on){if(on)Gl.enable(capability);else Gl.disable(capability);}
}
