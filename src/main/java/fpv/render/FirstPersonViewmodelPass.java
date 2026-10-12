package fpv.render;

import arc.graphics.Gl;
import arc.graphics.Texture;
import arc.graphics.gl.FrameBuffer;
import arc.util.Buffers;
import mindustry.graphics.Shaders;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;

/** Private depth for weapon self-occlusion; composite writes color only, never world depth. */
final class FirstPersonViewmodelPass implements AutoCloseable {
    private final IntBuffer viewport=Buffers.newIntBuffer(4);
    private final FloatBuffer color=Buffers.newFloatBuffer(4);
    private FrameBuffer target;
    private int program,active,texture0,texture1,depthFunction,renderbuffer,src,dst,srcA,dstA,equation,equationA;
    private boolean depth,write,cull,blend,scissor,stencil,begun;
    long bytes(){return target==null?0:(long)target.getWidth()*target.getHeight()*6;}
    int framebuffer(){return target==null?0:target.getFramebufferHandle();}
    void begin(){
        viewport.clear();Gl.getIntegerv(Gl.viewport,viewport);color.clear();Gl.getFloatv(Gl.colorClearValue,color);
        program=Gl.getInt(Gl.currentProgram);active=Gl.getInt(Gl.activeTexture);
        Gl.activeTexture(Gl.texture0);texture0=Gl.getInt(Gl.textureBinding2d);
        Gl.activeTexture(Gl.texture1);texture1=Gl.getInt(Gl.textureBinding2d);Gl.activeTexture(active);
        renderbuffer=Gl.getInt(Gl.renderbufferBinding);depthFunction=Gl.getInt(Gl.depthFunc);
        depth=Gl.isEnabled(Gl.depthTest);write=Gl.getInt(Gl.depthWritemask)!=0;cull=Gl.isEnabled(Gl.cullFace);
        blend=Gl.isEnabled(Gl.blend);scissor=Gl.isEnabled(Gl.scissorTest);stencil=Gl.isEnabled(Gl.stencilTest);
        src=Gl.getInt(Gl.blendSrcRgb);dst=Gl.getInt(Gl.blendDstRgb);srcA=Gl.getInt(Gl.blendSrcAlpha);dstA=Gl.getInt(Gl.blendDstAlpha);
        equation=Gl.getInt(Gl.blendEquationRgb);equationA=Gl.getInt(Gl.blendEquationAlpha);
        try{
            if(target==null){target=new FrameBuffer(viewport.get(2),viewport.get(3),true);target.getTexture().setFilter(Texture.TextureFilter.nearest);}
            else target.resize(viewport.get(2),viewport.get(3));
            target.beginBind();begun=true;Gl.viewport(0,0,target.getWidth(),target.getHeight());
            Gl.disable(Gl.scissorTest);Gl.disable(Gl.stencilTest);Gl.disable(Gl.cullFace);Gl.disable(Gl.blend);
            Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.depthFunc(Gl.lequal);
            Gl.clearColor(0,0,0,0);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
        }catch(RuntimeException error){if(begun){target.endBind();begun=false;}restore();throw error;}
    }
    void end(){
        try{
            if(!begun)return;target.endBind();begun=false;
            Gl.viewport(viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3));
            Gl.disable(Gl.depthTest);Gl.depthMask(false);Gl.enable(Gl.blend);Gl.blendEquation(Gl.funcAdd);
            Gl.blendFuncSeparate(Gl.srcAlpha,Gl.oneMinusSrcAlpha,Gl.zero,Gl.one);
            target.blit(Shaders.screenspace);
        }finally{restore();}
    }
    private void restore(){
        Gl.viewport(viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3));
        Gl.clearColor(color.get(0),color.get(1),color.get(2),color.get(3));Gl.depthMask(write);Gl.depthFunc(depthFunction);
        enabled(Gl.depthTest,depth);enabled(Gl.cullFace,cull);enabled(Gl.blend,blend);enabled(Gl.scissorTest,scissor);enabled(Gl.stencilTest,stencil);
        Gl.blendFuncSeparate(src,dst,srcA,dstA);Gl.blendEquationSeparate(equation,equationA);
        Gl.bindRenderbuffer(Gl.renderbuffer,renderbuffer);
        Gl.activeTexture(Gl.texture0);Gl.bindTexture(Gl.texture2d,texture0);
        Gl.activeTexture(Gl.texture1);Gl.bindTexture(Gl.texture2d,texture1);Gl.activeTexture(active);Gl.useProgram(program);
    }
    private static void enabled(int flag,boolean value){if(value)Gl.enable(flag);else Gl.disable(flag);}
    public void close(){if(begun){target.endBind();begun=false;restore();}if(target!=null)target.dispose();target=null;}
}
