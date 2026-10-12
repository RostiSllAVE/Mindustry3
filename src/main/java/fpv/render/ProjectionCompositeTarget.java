package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.GL30;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.Texture.TextureWrap;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.GLOnlyTextureData;
import arc.graphics.gl.Shader;
import arc.util.Buffers;
import fpv.profile.WallProfile;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import mindustry.graphics.Shaders;

/** Private projection depth/color selection followed by one alpha composite; never writes scene depth. */
final class ProjectionCompositeTarget implements AutoCloseable {
    private final IntBuffer integer=Buffers.newIntBuffer(1);
    private final FloatBuffer clearColor=Buffers.newFloatBuffer(4);
    private FrameBuffer target;
    private Texture opaqueDepth;
    private int format,sourceDepth;
    private boolean begun,copyDepthTexture;
    private final boolean forceTextureCopy;
    static FrameBuffer activeScene(){return DepthTarget.activeScene();}
    ProjectionCompositeTarget(){this(false);}
    ProjectionCompositeTarget(boolean forceTextureCopy){this.forceTextureCopy=forceTextureCopy;}
    private void ensure(FrameBuffer scene){
        if(target!=null&&target.getWidth()==scene.getWidth()&&target.getHeight()==scene.getHeight()&&sourceDepth==scene.getDepthBufferHandle())return;
        close();sourceDepth=scene.getDepthBufferHandle();format=Gl.depthComponent16;
        if(sourceDepth!=0){
            int previous=Gl.getInt(Gl.renderbufferBinding);Gl.bindRenderbuffer(Gl.renderbuffer,sourceDepth);integer.clear();
            Gl.getRenderbufferParameteriv(Gl.renderbuffer,Gl.renderbufferInternalFormat,integer);format=integer.get(0);Gl.bindRenderbuffer(Gl.renderbuffer,previous);
        }
        target=new DepthTarget(scene.getWidth(),scene.getHeight(),format);
        target.getTexture().setFilter(TextureFilter.nearest);target.getTexture().setWrap(TextureWrap.clampToEdge);
        copyDepthTexture=Core.gl30==null||forceTextureCopy;
        if(copyDepthTexture){
            opaqueDepth=new Texture(new GLOnlyTextureData(scene.getWidth(),scene.getHeight(),0,format,Gl.depthComponent,Gl.unsignedInt));
            opaqueDepth.setFilter(TextureFilter.nearest);opaqueDepth.setWrap(TextureWrap.clampToEdge);
        }
    }
    void begin(FrameBuffer scene){
        if(scene==null||!scene.isBound())throw new IllegalStateException("Nearest projected walls require the active opaque scene framebuffer");
        ensure(scene);
        if(copyDepthTexture){
            opaqueDepth.bind(1);Gl.copyTexSubImage2D(Gl.texture2d,0,0,0,0,0,scene.getWidth(),scene.getHeight());
        }
        clearColor.clear();Gl.getFloatv(Gl.colorClearValue,clearColor);
        target.begin();begun=true;
        Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.depthFunc(Gl.less);
        Gl.clearColor(0,0,0,0);Gl.clear(Gl.colorBufferBit|(copyDepthTexture?Gl.depthBufferBit:0));
        Gl.clearColor(clearColor.get(0),clearColor.get(1),clearColor.get(2),clearColor.get(3));
        if(!copyDepthTexture){
            Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER,scene.getFramebufferHandle());
            Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,target.getFramebufferHandle());
            Core.gl30.glBlitFramebuffer(0,0,scene.getWidth(),scene.getHeight(),0,0,target.getWidth(),target.getHeight(),Gl.depthBufferBit,Gl.nearest);
            Gl.bindFramebuffer(Gl.framebuffer,target.getFramebufferHandle());
        }
        WallProfile.depthCopy();
    }
    void bindOpaqueDepth(Shader shader){
        shader.bind();shader.setUniformf("u_opaqueCopy",copyDepthTexture?1f:0f);
        shader.setUniformf("u_viewport",(float)target.getWidth(),(float)target.getHeight());shader.setUniformi("u_opaqueDepth",1);
        shader.setUniformf("u_depthEpsilon",format==Gl.depthComponent16?1f/65535f:1f/16777215f);
        if(opaqueDepth!=null)opaqueDepth.bind(1);Gl.activeTexture(Gl.texture0);
    }
    void compose(){
        if(!begun)return;
        target.end();begun=false;
        Gl.disable(Gl.depthTest);Gl.depthMask(false);Gl.enable(Gl.blend);Gl.blendEquation(Gl.funcAdd);
        Gl.blendFuncSeparate(Gl.srcAlpha,Gl.oneMinusSrcAlpha,Gl.zero,Gl.one);
        target.blit(Shaders.screenspace);WallProfile.composition();
    }
    void cancel(){if(begun){target.end();begun=false;}}
    long estimatedBytes(){return target==null?0:(long)target.getWidth()*target.getHeight()*(4+(format==Gl.depthComponent16?2:4)+(opaqueDepth==null?0:4));}
    String mode(){return copyDepthTexture?"private-depth + GPU opaque-depth texture copy":"private-depth + GPU opaque-depth blit";}
    FrameBuffer targetForSmoke(){return target;}
    @Override public void close(){cancel();if(target!=null)target.dispose();if(opaqueDepth!=null)opaqueDepth.dispose();target=null;opaqueDepth=null;sourceDepth=0;}
    private static final class DepthTarget extends FrameBuffer{
        DepthTarget(int width,int height,int depthFormat){super(builder(width,height,depthFormat));}
        static FrameBuffer activeScene(){return currentBoundFramebuffer instanceof FrameBuffer buffer?buffer:null;}
        private static FrameBufferBuilder builder(int width,int height,int depthFormat){
            FrameBufferBuilder result=new FrameBufferBuilder(width,height);result.addBasicColorTextureAttachment(Pixmap.Format.rgba8888);result.addDepthRenderBuffer(depthFormat);return result;
        }
    }
}
