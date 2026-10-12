package fpv.render;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.*;
import arc.math.geom.Vec3;
import arc.util.Buffers;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.gen.*;
import java.nio.IntBuffer;
import java.util.Arrays;

/** Visual-only camera-facing glyph batch. Opaque scene depth gates both body visibility and glyph pixels. */
public final class UnitThreatMarkerRenderer implements AutoCloseable {
    public static final String SETTING="fpv-threat-markers";
    private static final int MAX=8192, DRAW_MAX=2048, STRIDE=15, BUCKETS=201, GRID=32;
    private static final String VERTEX="attribute vec3 a_position;attribute vec2 a_texCoord0;attribute vec4 a_color;attribute vec3 a_body;attribute float a_controlled;attribute vec2 a_offset;uniform mat4 u_combined;uniform vec2 u_viewport;varying vec2 v_uv;varying vec4 v_color;varying vec3 v_body;varying float v_controlled;void main(){v_uv=a_texCoord0;v_color=a_color;v_body=a_body;v_controlled=a_controlled;vec4 p=u_combined*vec4(a_position,1.0);p.xy+=a_offset*2.0/u_viewport*p.w;gl_Position=p;}";
    private static final String FRAGMENT="uniform sampler2D u_texture;uniform sampler2D u_depth;uniform float u_epsilon;varying vec2 v_uv;varying vec4 v_color;varying vec3 v_body;varying float v_controlled;void main(){if(texture2D(u_depth,v_body.xy).r+u_epsilon<v_body.z)discard;vec3 m=texture2D(u_texture,v_uv).rgb;float a=m.g;vec3 c=vec3(.035)*(m.g-m.r)+v_color.rgb*m.r;if(v_controlled>.5){a=m.b;c+=vec3(m.b-m.g);}if(a*v_color.a<.005)discard;gl_FragColor=vec4(c/max(a,.001),a*v_color.a);}";
    private static final class Candidate {
        Unit unit;int tier,next;double x,y,z,az;float sx,sy,size,alpha,r,g,b,du,dv,depth;boolean controlled,submitted;
    }
    public record Stats(int candidates,int submitted,int culledDistance,int culledFrustum,int culledFog,int overlapSuppressed,int draws,int vertices,int depthCopies,long cpuNanos){}
    public record Diagnostic(int unitId,int tier,double x,double y,double z,double anchorX,double anchorY,double anchorZ,float screenX,float screenY,float sizePixels,float alpha,boolean controlled,float r,float g,float b,boolean submitted){}
    private final Candidate[] candidates=new Candidate[MAX];
    private final int[] buckets=new int[BUCKETS];
    private final float[] vertices=new float[DRAW_MAX*6*STRIDE];
    private int[] occupied=new int[0];
    private final Vec3 point=new Vec3(),front=new Vec3();
    private final IntBuffer integer=Buffers.newIntBuffer(1);
    private UnitThreatTiers tiers;private Object content;
    private Texture atlas,depthTexture;private Shader shader;private Mesh mesh;
    private int width,height,depthHandle,depthFormat,count,submitted,distanceCulls,frustumCulls,fogCulls,overlap,draws,copies,used,cols;
    private long cpu;
    public Stats stats(){return new Stats(count,submitted,distanceCulls,frustumCulls,fogCulls,overlap,draws,used/STRIDE,copies,cpu);}
    public Diagnostic diagnostic(Unit unit){for(int i=0;i<count;i++){Candidate c=candidates[i];if(c.unit==unit)return new Diagnostic(unit.id,c.tier,c.x,c.y,c.z,c.x,c.y,c.az,c.sx,c.sy,c.size,c.alpha,c.controlled,c.r,c.g,c.b,c.submitted);}return null;}
    public boolean wasSubmitted(Unit unit){for(int i=0;i<count;i++)if(candidates[i].unit==unit)return candidates[i].submitted;return false;}
    public static float fade(double distance){return (float)Math.max(0,Math.min(1,(100-distance)/20));}
    public void render(Camera3D camera,UnitAltitudeSystem altitudes,FrameBuffer scene,boolean showControlledUnit){
        long started=System.nanoTime();count=submitted=distanceCulls=frustumCulls=fogCulls=overlap=draws=copies=used=0;cpu=0;
        if(!Core.settings.getBool(SETTING,true)||altitudes==null||Vars.player==null)return;
        if(content!=Vars.content){content=Vars.content;tiers=new UnitThreatTiers(Vars.content);}
        int w=scene.getWidth(),h=scene.getHeight();Arrays.fill(buckets,-1);
        Unit controlled=Vars.player.unit();int controlledIndex=-1;
        for(Unit unit:Groups.unit){
            if(unit.dead||!unit.isAdded())continue;
            if(unit.inFogTo(Vars.player.team())){fogCulls++;continue;}
            if(unit==controlled&&!showControlledUnit)continue;
            double dx=unit.x/(double)Vars.tilesize-camera.position.x,dz=-unit.y/(double)Vars.tilesize-camera.position.z;
            if(dx*dx+dz*dz>=10000){distanceCulls++;continue;}
            var state=altitudes.unitState(unit);if(state==null)continue;
            double dy=state.z()-camera.position.y;dx=state.x()-camera.position.x;dz=-state.y()-camera.position.z;
            double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(distance>=100){distanceCulls++;continue;}
            // Multiplayer hit bounds can be narrower than the native artwork. Occlusion/anchor
            // use a visual envelope so the model cannot hide its own glyph; physics is untouched.
            double radius=Math.max(state.radius(),SpriteExtrusionRenderer.bodyRadius(unit));
            double up=Math.cos(Math.toRadians(state.pitch()))*Math.cos(Math.toRadians(state.roll()));
            double halfHeight=Math.max(state.halfHeight(),GroundFamilyRenderer.bodyHalfDepth(unit)*Math.abs(up)+radius*Math.sqrt(Math.max(0,1-up*up)));
            halfHeight=Math.max(halfHeight,GroundFamilyRenderer.profileTop(unit,state.pitch(),state.roll()));
            // A cache-miss frame can still draw the renderer's ordinary fallback body.
            halfHeight=Math.max(halfHeight,.25*Math.max(fpv.generated.RenderConfig.UNIT_MIN_SIZE,unit.hitSize/(double)Vars.tilesize*fpv.generated.RenderConfig.UNIT_HIT_SIZE_SCALE));
            float forward=(float)(dx*camera.direction.x+dy*camera.direction.y+dz*camera.direction.z);
            if(forward<=camera.near){frustumCulls++;continue;}
            float size=Math.max(unit==controlled?26:22,Math.min(32,h*.7f/Math.max(1,forward)));
            // Leave space for the fixed-pixel glyph at distance; its lower half must not
            // disappear into the unit's own opaque upper surface in a shallow FPV view.
            double gap=Math.max(.30,.15+size*.5*forward*2*Math.tan(Math.toRadians(camera.fov*.5))/h);
            double az=state.z()+Math.max(.12,halfHeight)+gap;
            camera.project(point.set((float)state.x(),(float)az,(float)-state.y()),0,0,w,h);
            if(point.z<0||point.z>1||point.x<-16||point.y<-16||point.x>w+16||point.y>h+16){frustumCulls++;continue;}
            float sx=point.x,sy=point.y;
            // Nearest point on the canonical body cylinder along the center ray, not its far-side sprite.
            double horizontal=Math.hypot(dx,dz),frontOffset=Math.min(horizontal>1e-9?radius*distance/horizontal:1e9,Math.abs(dy)>1e-9?halfHeight*distance/Math.abs(dy):1e9);
            frontOffset=Math.min(distance*.95,Math.max(.02,frontOffset));
            camera.project(front.set((float)(state.x()-dx*frontOffset/distance),(float)(state.z()-dy*frontOffset/distance),(float)(-state.y()-dz*frontOffset/distance)),0,0,w,h);
            if(front.x<0||front.x>w||front.y<0||front.y>h||front.z<0||front.z>1){frustumCulls++;continue;}
            // Reserve a slot even when the pilot is last in a very large native group.
            if(count>=(unit==controlled?MAX:MAX-1))continue;
            Candidate c=candidates[count];if(c==null)candidates[count]=c=new Candidate();
            c.unit=unit;c.tier=tiers.tier(unit.type);c.x=state.x();c.y=state.y();c.z=state.z();c.az=az;c.sx=sx;c.sy=sy;c.alpha=fade(distance);c.controlled=unit==controlled;c.submitted=false;
            c.size=size;c.r=unit.team.color.r;c.g=unit.team.color.g;c.b=unit.team.color.b;
            c.du=front.x/w;c.dv=front.y/h;c.depth=front.z;
            int bucket=Math.min(BUCKETS-1,(int)(distance*2));c.next=buckets[bucket];buckets[bucket]=count;
            if(c.controlled)controlledIndex=count;count++;
        }
        cols=(w+GRID-1)/GRID;int cells=cols*((h+GRID-1)/GRID);
        if(occupied.length<cells)occupied=new int[cells];Arrays.fill(occupied,0,cells,0);
        if(controlledIndex>=0)submit(candidates[controlledIndex],w,h);
        for(int bucket=0;bucket<BUCKETS;bucket++)for(int i=buckets[bucket];i>=0;i=candidates[i].next)if(i!=controlledIndex)submit(candidates[i],w,h);
        if(submitted>0)draw(camera,scene);cpu=System.nanoTime()-started;
    }
    private void submit(Candidate c,int w,int h){
        if(submitted>=DRAW_MAX){overlap++;return;}
        int x0=Math.max(0,(int)((c.sx-c.size*.5f)/GRID)),x1=Math.min(cols-1,(int)((c.sx+c.size*.5f)/GRID));
        int rows=(h+GRID-1)/GRID,y0=Math.max(0,(int)((c.sy-c.size*.5f)/GRID)),y1=Math.min(rows-1,(int)((c.sy+c.size*.5f)/GRID));
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++)if(occupied[x+y*cols]!=0){overlap++;return;}
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++)occupied[x+y*cols]=1;
        c.submitted=true;submitted++;
        vertex(c,0,0);vertex(c,1,0);vertex(c,1,1);vertex(c,1,1);vertex(c,0,1);vertex(c,0,0);
    }
    private void vertex(Candidate c,int x,int y){
        vertices[used++]=(float)c.x;vertices[used++]=(float)c.az;vertices[used++]=(float)-c.y;
        vertices[used++]=(c.tier+(x==0?.5f/64:1-.5f/64))/6f;vertices[used++]=y==0?1-.5f/64:.5f/64;
        vertices[used++]=c.r;vertices[used++]=c.g;vertices[used++]=c.b;vertices[used++]=c.alpha;
        vertices[used++]=c.du;vertices[used++]=c.dv;vertices[used++]=c.depth;vertices[used++]=c.controlled?1:0;
        vertices[used++]=(x-.5f)*c.size;vertices[used++]=(y-.5f)*c.size;
    }
    private void ensure(FrameBuffer scene){
        if(shader==null){
            shader=new Shader(VERTEX,FRAGMENT);mesh=new Mesh(false,DRAW_MAX*6,0,VertexAttribute.position3,VertexAttribute.texCoords,new VertexAttribute(4,"a_color"),new VertexAttribute(3,"a_body"),new VertexAttribute(1,"a_controlled"),new VertexAttribute(2,"a_offset"));
            Pixmap pixels=new Pixmap(ThreatMarkerSymbols.CELL*6,ThreatMarkerSymbols.CELL);int[] rgba=ThreatMarkerSymbols.pixels();
            for(int y=0;y<pixels.height;y++)for(int x=0;x<pixels.width;x++)pixels.set(x,y,rgba[x+y*pixels.width]);
            atlas=new Texture(pixels);pixels.dispose();atlas.setFilter(Texture.TextureFilter.linear);atlas.setWrap(Texture.TextureWrap.clampToEdge);
        }
        if(depthTexture!=null&&width==scene.getWidth()&&height==scene.getHeight()&&depthHandle==scene.getDepthBufferHandle())return;
        if(depthTexture!=null)depthTexture.dispose();width=scene.getWidth();height=scene.getHeight();depthHandle=scene.getDepthBufferHandle();depthFormat=Gl.depthComponent16;
        if(depthHandle!=0){int previous=Gl.getInt(Gl.renderbufferBinding);Gl.bindRenderbuffer(Gl.renderbuffer,depthHandle);integer.clear();Gl.getRenderbufferParameteriv(Gl.renderbuffer,Gl.renderbufferInternalFormat,integer);depthFormat=integer.get(0);Gl.bindRenderbuffer(Gl.renderbuffer,previous);}
        depthTexture=new Texture(new GLOnlyTextureData(width,height,0,depthFormat,Gl.depthComponent,Gl.unsignedInt));depthTexture.setFilter(Texture.TextureFilter.nearest);depthTexture.setWrap(Texture.TextureWrap.clampToEdge);
    }
    private void draw(Camera3D camera,FrameBuffer scene){
        int active=Gl.getInt(Gl.activeTexture),program=Gl.getInt(Gl.currentProgram),depthFunc=Gl.getInt(Gl.depthFunc);
        boolean blend=Gl.isEnabled(Gl.blend),depth=Gl.isEnabled(Gl.depthTest),write=Gl.getInt(Gl.depthWritemask)!=0;
        int srcRGB=Gl.getInt(Gl.blendSrcRgb),dstRGB=Gl.getInt(Gl.blendDstRgb),srcA=Gl.getInt(Gl.blendSrcAlpha),dstA=Gl.getInt(Gl.blendDstAlpha);
        int eqRGB=Gl.getInt(Gl.blendEquationRgb),eqA=Gl.getInt(Gl.blendEquationAlpha);
        Gl.activeTexture(Gl.texture0);int tex0=Gl.getInt(Gl.textureBinding2d);Gl.activeTexture(Gl.texture0+1);int tex1=Gl.getInt(Gl.textureBinding2d);
        try{
            ensure(scene);depthTexture.bind(1);Gl.copyTexSubImage2D(Gl.texture2d,0,0,0,0,0,width,height);copies++;
            atlas.bind(0);shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformf("u_viewport",(float)width,(float)height);shader.setUniformi("u_texture",0);shader.setUniformi("u_depth",1);shader.setUniformf("u_epsilon",depthFormat==Gl.depthComponent16?2f/65535:2f/16777215);
            Gl.enable(Gl.depthTest);Gl.depthFunc(Gl.lequal);Gl.depthMask(false);Gl.enable(Gl.blend);Gl.blendEquation(Gl.funcAdd);Gl.blendFuncSeparate(Gl.srcAlpha,Gl.oneMinusSrcAlpha,Gl.zero,Gl.one);
            mesh.setVertices(vertices,0,used);mesh.render(shader,Gl.triangles,0,used/STRIDE);draws++;
        }finally{
            Gl.depthMask(write);Gl.depthFunc(depthFunc);if(depth)Gl.enable(Gl.depthTest);else Gl.disable(Gl.depthTest);if(blend)Gl.enable(Gl.blend);else Gl.disable(Gl.blend);
            Gl.blendEquationSeparate(eqRGB,eqA);Gl.blendFuncSeparate(srcRGB,dstRGB,srcA,dstA);Gl.activeTexture(Gl.texture0+1);Gl.bindTexture(Gl.texture2d,tex1);Gl.activeTexture(Gl.texture0);Gl.bindTexture(Gl.texture2d,tex0);Gl.activeTexture(active);Gl.useProgram(program);
        }
    }
    @Override public void close(){if(mesh!=null)mesh.dispose();if(shader!=null)shader.dispose();if(atlas!=null)atlas.dispose();if(depthTexture!=null)depthTexture.dispose();mesh=null;shader=null;atlas=null;depthTexture=null;}
}
