package fpv.render;

import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.Texture;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.graphics.gl.FrameBuffer;
import arc.struct.IntSet;
import arc.struct.LongMap;
import fpv.defense.ProjectedWallSystem;
import fpv.generated.DefenseConfig;
import fpv.generated.RenderConfig;
import fpv.profile.WallProfile;
import fpv.profile.WallGpuTimer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import mindustry.Vars;
import mindustry.gen.Building;

/** Cached exposed chunk surfaces; atlas repetition replaces stacked segment tessellation. */
public final class ProjectedWallRenderer {
    private static final int STRIDE=13;
    private static final VertexAttribute COLOR=new VertexAttribute(4,"a_color"),REGION=new VertexAttribute(4,"a_region");
    private static final String VERTEX="attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;attribute vec4 a_region;uniform mat4 u_combined;uniform vec3 u_camera;varying vec4 v_color;varying vec2 v_uv;varying vec4 v_region;varying vec3 v_world;void main(){v_color=a_color;v_uv=a_texCoord0;v_region=a_region;v_world=a_position;gl_Position=u_combined*vec4(a_position,1.0);}";
    private static final String FRAGMENT="uniform sampler2D u_texture;uniform sampler2D u_opaqueDepth;uniform float u_opaqueCopy;uniform float u_depthEpsilon;uniform vec2 u_viewport;uniform float u_cutoff;varying vec4 v_color;varying vec2 v_uv;varying vec4 v_region;varying vec3 v_world;uniform vec3 u_camera;uniform vec2 u_viewDistance;void main(){float v_distance=distance(v_world,u_camera);if(v_distance>=u_viewDistance.y)discard;if(u_opaqueCopy>0.5&&gl_FragCoord.z>texture2D(u_opaqueDepth,gl_FragCoord.xy/u_viewport).r+u_depthEpsilon)discard;vec2 uv=fract(v_uv);vec4 t;if(v_distance>800.0){t=vec4(vec3(0.62+0.2*step(0.1,uv.y)),1.0);}else{t=texture2D(u_texture,v_region.xy+uv*v_region.zw);if(v_distance>320.0)t.rgb=mix(t.rgb,vec3(0.7),0.28);}if(t.a<u_cutoff)discard;gl_FragColor=vec4(t.rgb*v_color.rgb,t.a*v_color.a*(1.0-smoothstep(u_viewDistance.x,u_viewDistance.y,v_distance)));}";
    private final IntSet submitted=new IntSet();
    private final LongMap<ChunkCache> cache=new LongMap<>();
    private final IdentityHashMap<Texture,Stream> streams=new IdentityHashMap<>();
    private final ArrayList<Surface> surfaces=new ArrayList<>();
    private Shader shader;
    private final WallGpuTimer gpuTimer=new WallGpuTimer();
    private final ProjectionCompositeTarget compositor=new ProjectionCompositeTarget();
    public long compositorBytes(){return compositor.estimatedBytes();}
    public String compositorMode(){return compositor.mode();}
    private ProjectedWallSystem owner;
    private long generation=-1,frame,cacheBuilds;
    private int faces,segments;
    private long cacheBytes;
    public boolean wasSubmitted(Building b){return b!=null&&submitted.contains(b.id);}
    public int faceCount(){return faces;}
    public long cacheBuildCount(){return cacheBuilds;}
    public long cachedBytes(){return cacheBytes;}
    /** Per-frame reset deliberately preserves immutable chunk geometry. */
    public void clear(){submitted.clear();faces=segments=0;for(Stream s:streams.values())s.count=0;}
    /** World/renderer invalidation drops persistent CPU geometry; atlas textures remain game-owned. */
    public void invalidate(){clear();cache.clear();cacheBytes=0;owner=null;generation=-1;}
    /** Backward-compatible entry point resolves Arc's currently nested opaque scene target. */
    public void render(Camera3D camera,ProjectedWallSystem system){render(camera,system,ProjectionCompositeTarget.activeScene());}
    public void render(Camera3D camera,ProjectedWallSystem system,FrameBuffer opaqueScene){
        long started=WallProfile.start(),bytes=WallProfile.allocated();
        gpuTimer.begin();try{renderMeasured(camera,system,opaqueScene);}finally{gpuTimer.end();WallProfile.objects(0,0,streams.size());WallProfile.render(started,bytes,submitted.size,segments,faces);}
    }
    private void renderMeasured(Camera3D camera,ProjectedWallSystem system,FrameBuffer opaqueScene){
        clear();if(system==null)return;
        List<ProjectedWallSystem.RenderChunk> chunks=system.renderChunks();
        if(owner!=system||generation!=system.generation()){invalidate();owner=system;generation=system.generation();}
        frame++;
        int srcRgb=Gl.getInt(Gl.blendSrcRgb),dstRgb=Gl.getInt(Gl.blendDstRgb),srcAlpha=Gl.getInt(Gl.blendSrcAlpha),dstAlpha=Gl.getInt(Gl.blendDstAlpha);
        int eqRgb=Gl.getInt(Gl.blendEquationRgb),eqAlpha=Gl.getInt(Gl.blendEquationAlpha),program=Gl.getInt(Gl.currentProgram);
        int activeTexture=Gl.getInt(Gl.activeTexture);Gl.activeTexture(Gl.texture0);int texture=Gl.getInt(Gl.textureBinding2d);Gl.activeTexture(Gl.texture1);int texture1=Gl.getInt(Gl.textureBinding2d);Gl.activeTexture(Gl.texture0);
        int depthFunc=Gl.getInt(Gl.depthFunc);boolean blend=Gl.isEnabled(Gl.blend),cull=Gl.isEnabled(Gl.cullFace),depth=Gl.isEnabled(Gl.depthTest),write=Gl.getInt(Gl.depthWritemask)!=0;
        try{
            boolean rendering=false;double maximum=ProjectionViewSettings.maximum(),maxSquared=maximum*maximum;
            for(int i=0;i<chunks.size();i++){
                var chunk=chunks.get(i);List<ProjectedWallSystem.Volume> volumes=chunk.volumes();if(volumes.isEmpty())continue;
                double chunkX=(chunk.minX()+chunk.maxX())*.5,chunkY=(chunk.minY()+chunk.maxY())*.5,chunkZ=chunk.maxZ()*.5;
                double hx=(chunk.maxX()-chunk.minX())*.5,hy=(chunk.maxY()-chunk.minY())*.5,hz=chunk.maxZ()*.5;
                if(!viewVisible(camera,chunkX,chunkY,chunkZ,hx,hy,hz,maxSquared))continue;
                long fogHash=Vars.player==null?-1:Vars.player.team().id;boolean complete=true;int selected=0;
                for(int j=0;j<volumes.size();j++){
                    var v=volumes.get(j);boolean known=visible(v.building());fogHash=(fogHash*1099511628211L)^(known?v.building().id:0);
                    boolean draw=known&&viewVisible(camera,v.x(),v.y(),(v.baseZ()+v.topZ())*.5,v.halfWidth(),v.halfWidth(),(v.topZ()-v.baseZ())*.5,maxSquared);
                    if(draw){submitted.add(v.building().id);selected++;segments+=(int)Math.ceil((v.topZ()-v.baseZ())/Math.max(.01,FpvWorldRenderer.heightFor(v.building().block))-1e-7);}
                    else complete=false;
                }
                if(selected==0)continue;
                ChunkCache saved=cache.get(chunk.key());if(saved==null){saved=new ChunkCache();cache.put(chunk.key(),saved);}
                if(saved.revision!=chunk.revision()||saved.fogHash!=fogHash){cacheBytes-=saved.bytes;rebuild(saved,system,volumes);cacheBytes+=saved.bytes;saved.revision=chunk.revision();saved.fogHash=fogHash;}
                saved.seen=frame;
                if(!rendering){
                    if(shader==null)shader=new Shader(VERTEX,FRAGMENT);shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);
                    shader.setUniformf("u_cutoff",(float)DefenseConfig.PROJECTION_TEXTURE_CUTOFF);shader.setUniformf("u_camera",camera.position.x,camera.position.y,camera.position.z);
                    shader.setUniformf("u_viewDistance",(float)ProjectionViewSettings.fadeStart(),(float)maximum);
                    compositor.begin(opaqueScene);compositor.bindOpaqueDepth(shader);rendering=true;
                }
                if(complete){faces+=saved.faces;for(var part:saved.parts.entrySet())append(part.getKey(),part.getValue());}
                else for(int j=0;j<volumes.size();j++){
                    var v=volumes.get(j);if(!submitted.contains(v.building().id))continue;
                    WallPart part=saved.walls.get(v.building().id);if(part==null)continue;faces+=part.faces;
                    for(var material:part.parts.entrySet())append(material.getKey(),material.getValue());
                }
            }
            if(rendering){for(var entry:streams.entrySet())flush(entry.getKey(),entry.getValue());compositor.compose();}
            // Retain offscreen static chunks; retire stale entries after topology churn without a per-frame map scan.
            if((frame&255)==0){for(var it=cache.entries();it.hasNext();){var e=it.next();if(frame-e.value.seen>1800){cacheBytes-=e.value.bytes;it.remove();}}}
        }finally{
            compositor.cancel();for(Stream s:streams.values())s.count=0;
            Gl.depthMask(write);Gl.depthFunc(depthFunc);enabled(Gl.depthTest,depth);enabled(Gl.cullFace,cull);
            Gl.blendEquationSeparate(eqRgb,eqAlpha);Gl.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);enabled(Gl.blend,blend);
            Gl.activeTexture(Gl.texture1);Gl.bindTexture(Gl.texture2d,texture1);Gl.activeTexture(Gl.texture0);Gl.bindTexture(Gl.texture2d,texture);Gl.activeTexture(activeTexture);Gl.useProgram(program);
        }
    }
    private static boolean viewVisible(Camera3D c,double x,double y,double z,double hx,double hy,double hz,double maxSquared){
        return ProjectionViewSettings.distanceSquared(c.position.x,-c.position.z,c.position.y,x,y,z,hx,hy,hz)<maxSquared&&
            c.frustum.containsBounds((float)x,(float)z,(float)-y,(float)hx,(float)hz,(float)hy);
    }
    private static boolean visible(Building b){return Vars.player!=null&&!b.inFogTo(Vars.player.team());}
    private void rebuild(ChunkCache cached,ProjectedWallSystem system,List<ProjectedWallSystem.Volume> volumes){
        surfaces.clear();cached.parts.clear();cached.walls.clear();cached.bytes=0;cached.faces=0;
        for(int i=0;i<volumes.size();i++){
            var v=volumes.get(i);Building b=v.building();if(!visible(b))continue;
            TextureRegion region=NativeRegions.block(b.block);if(!NativeAtlasMesh.valid(region))continue;
            int firstSurface=surfaces.size();
            double h=v.halfWidth(),segment=Math.max(.01,FpvWorldRenderer.heightFor(b.block));
            float r=.7f+b.team.color.r*.3f,g=.7f+b.team.color.g*.3f,blue=.7f+b.team.color.b*.3f;
            float alpha=(float)(DefenseConfig.PROJECTION_MIN_ALPHA+(DefenseConfig.PROJECTION_ALPHA-DefenseConfig.PROJECTION_MIN_ALPHA)*v.fraction());
            for(int side=0;side<4;side++)for(int cell=0;cell<b.block.size;cell++){
                double along=(side<2?v.x():v.y())-h+cell,other=(side<2?v.y():v.x())+(side==0||side==3?h:-h);
                double nx=side<2?along+.5:other+(side==2?-.01:.01),ny=side<2?other+(side==0?.01:-.01):along+.5;
                var neighbor=system.neighbor(v,nx,ny);
                double low=v.baseZ(),top=v.topZ();
                if(neighbor==null){side(region,side,other,along,along+1,low,top,v,segment,r,g,blue,alpha);}
                else{
                    if(neighbor.baseZ()>low)side(region,side,other,along,along+1,low,Math.min(top,neighbor.baseZ()),v,segment,r,g,blue,alpha);
                    if(neighbor.topZ()<top)side(region,side,other,along,along+1,Math.max(low,neighbor.topZ()),top,v,segment,r,g,blue,alpha);
                }
            }
            surfaces.add(new Surface(region,4,v.topZ(),v.x()-h,v.x()+h,v.y()-h,v.y()+h,1/(2*h),1/(2*h),
                phase(-(v.x()-h)/(2*h)),phase((v.y()+h)/(2*h)),r,g,blue,alpha*.65f));
            // Both immutable representations are built on topology/HP changes only. Complete
            // chunks use merged surfaces; boundary chunks select exact native wall ranges.
            WallPart part=new WallPart();for(int n=firstSurface;n<surfaces.size();n++){emitData(part.parts,surfaces.get(n));part.faces++;}
            cached.walls.put(b.id,part);
        }
        surfaces.sort(SURFACE_ORDER);
        Surface merged=null;
        for(int i=0;i<surfaces.size();i++){
            Surface next=surfaces.get(i);
            if(merged!=null&&merged.compatible(next)&&Math.abs(merged.hi-next.lo)<1e-6)merged.hi=next.hi;
            else{if(merged!=null)emit(cached,merged);merged=next;}
        }
        if(merged!=null)emit(cached,merged);
        for(Data data:cached.parts.values()){data.values=Arrays.copyOf(data.values,data.count);cached.bytes+=(long)data.count*Float.BYTES;}
        for(WallPart part:cached.walls.values())for(Data data:part.parts.values()){data.values=Arrays.copyOf(data.values,data.count);cached.bytes+=(long)data.count*Float.BYTES;}
        cacheBuilds++;WallProfile.cacheBuild(cached.bytes);surfaces.clear();
    }
    private void side(TextureRegion region,int side,double plane,double lo,double hi,double bottom,double top,ProjectedWallSystem.Volume own,double segment,float r,float g,float b,float alpha){
        if(top-bottom<1e-6)return;
        double width=own.halfWidth()*2,origin=(side<2?own.x():own.y())-own.halfWidth(),sign=(side==1||side==2)?-1:1;
        surfaces.add(new Surface(region,side,plane,lo,hi,bottom,top,sign/width,1/segment,phase(-sign*origin/width),phase(-own.baseZ()/segment),r,g,b,alpha));
    }
    private static double phase(double value){double p=value-Math.floor(value);return Math.abs(p-1)<1e-7||Math.abs(p)<1e-7?0:p;}
    private static final Comparator<Surface> SURFACE_ORDER=(a,b)->{
        int c=Integer.compare(a.side,b.side);if(c!=0)return c;
        c=Integer.compare(System.identityHashCode(a.region),System.identityHashCode(b.region));if(c!=0)return c;
        c=Double.compare(a.plane,b.plane);if(c!=0)return c;c=Double.compare(a.bottom,b.bottom);if(c!=0)return c;c=Double.compare(a.top,b.top);if(c!=0)return c;
        c=Double.compare(a.su,b.su);if(c!=0)return c;c=Double.compare(a.sv,b.sv);if(c!=0)return c;c=Double.compare(a.pu,b.pu);if(c!=0)return c;c=Double.compare(a.pv,b.pv);if(c!=0)return c;
        c=Float.compare(a.r,b.r);if(c!=0)return c;c=Float.compare(a.g,b.g);if(c!=0)return c;c=Float.compare(a.b,b.b);if(c!=0)return c;c=Float.compare(a.alpha,b.alpha);if(c!=0)return c;
        return Double.compare(a.lo,b.lo);
    };
    private static final class Surface{
        final TextureRegion region;final int side;final double plane,lo,bottom,top,su,sv,pu,pv;double hi;final float r,g,b,alpha;
        Surface(TextureRegion region,int side,double plane,double lo,double hi,double bottom,double top,double su,double sv,double pu,double pv,float r,float g,float b,float alpha){
            this.region=region;this.side=side;this.plane=plane;this.lo=lo;this.hi=hi;this.bottom=bottom;this.top=top;this.su=su;this.sv=sv;this.pu=pu;this.pv=pv;this.r=r;this.g=g;this.b=b;this.alpha=alpha;
        }
        boolean compatible(Surface o){return region==o.region&&side==o.side&&plane==o.plane&&bottom==o.bottom&&top==o.top&&su==o.su&&sv==o.sv&&pu==o.pu&&pv==o.pv&&r==o.r&&g==o.g&&b==o.b&&alpha==o.alpha;}
    }
    private void emit(ChunkCache cache,Surface s){emitData(cache.parts,s);cache.faces++;}
    private void emitData(IdentityHashMap<Texture,Data> parts,Surface s){
        Data data=parts.get(s.region.texture);if(data==null){data=new Data(512);parts.put(s.region.texture,data);}data.ensure(6*STRIDE);
        if(s.side==4){
            vertex(data,s,s.lo,s.plane,-s.top,s.lo*s.su+s.pu,-s.top*s.sv+s.pv);
            vertex(data,s,s.hi,s.plane,-s.top,s.hi*s.su+s.pu,-s.top*s.sv+s.pv);
            vertex(data,s,s.hi,s.plane,-s.bottom,s.hi*s.su+s.pu,-s.bottom*s.sv+s.pv);
            vertex(data,s,s.lo,s.plane,-s.top,s.lo*s.su+s.pu,-s.top*s.sv+s.pv);
            vertex(data,s,s.hi,s.plane,-s.bottom,s.hi*s.su+s.pu,-s.bottom*s.sv+s.pv);
            vertex(data,s,s.lo,s.plane,-s.bottom,s.lo*s.su+s.pu,-s.bottom*s.sv+s.pv);
        }else{
            double ax=s.side<2?s.lo:s.plane,az=s.side<2?-s.plane:-s.lo,bx=s.side<2?s.hi:s.plane,bz=s.side<2?-s.plane:-s.hi;
            double ua=s.lo*s.su+s.pu,ub=s.hi*s.su+s.pu,va=s.bottom*s.sv+s.pv,vb=s.top*s.sv+s.pv;
            vertex(data,s,ax,s.bottom,az,ua,va);vertex(data,s,bx,s.bottom,bz,ub,va);vertex(data,s,bx,s.top,bz,ub,vb);
            vertex(data,s,ax,s.bottom,az,ua,va);vertex(data,s,bx,s.top,bz,ub,vb);vertex(data,s,ax,s.top,az,ua,vb);
        }
    }
    private static void vertex(Data d,Surface s,double x,double y,double z,double u,double v){
        float du=Math.signum(s.region.u2-s.region.u)*.5f/s.region.texture.width,dv=Math.signum(s.region.v2-s.region.v)*.5f/s.region.texture.height;
        float[] a=d.values;int n=d.count;a[n++]=(float)x;a[n++]=(float)y;a[n++]=(float)z;a[n++]=s.r;a[n++]=s.g;a[n++]=s.b;a[n++]=s.alpha;
        a[n++]=(float)u;a[n++]=(float)v;a[n++]=s.region.u+du;a[n++]=s.region.v+dv;a[n++]=s.region.u2-s.region.u-2*du;a[n++]=s.region.v2-s.region.v-2*dv;d.count=n;
    }
    private void append(Texture texture,Data data){
        Stream stream=streams.get(texture);if(stream==null){stream=new Stream();streams.put(texture,stream);}
        int offset=0;
        while(offset<data.count){int count=Math.min(data.count-offset,stream.values.length-stream.count);System.arraycopy(data.values,offset,stream.values,stream.count,count);stream.count+=count;offset+=count;if(stream.count==stream.values.length)flush(texture,stream);}
    }
    private void flush(Texture texture,Stream stream){
        if(stream.count==0)return;
        if(stream.mesh==null)stream.mesh=new Mesh(false,stream.values.length/STRIDE,0,VertexAttribute.position3,COLOR,VertexAttribute.texCoords,REGION);
        shader.bind();texture.bind(0);stream.mesh.setVertices(stream.values,0,stream.count);stream.mesh.render(shader,Gl.triangles);WallProfile.draw(stream.count/STRIDE,stream.count);stream.count=0;
    }
    private static class Data{
        float[] values;int count;Data(int capacity){values=new float[capacity];}
        void ensure(int extra){if(count+extra>values.length)values=Arrays.copyOf(values,Math.max(values.length*2,count+extra));}
    }
    private static final class Stream extends Data{Mesh mesh;Stream(){super(Math.max(6,(int)RenderConfig.ATLAS_STREAM_VERTICES/6*6)*STRIDE);}}
    private static final class WallPart{int faces;final IdentityHashMap<Texture,Data> parts=new IdentityHashMap<>();}
    private static final class ChunkCache{final arc.struct.IntMap<WallPart> walls=new arc.struct.IntMap<>();long revision=Long.MIN_VALUE,fogHash,seen,bytes;int faces;final IdentityHashMap<Texture,Data> parts=new IdentityHashMap<>();}
    private static void enabled(int cap,boolean value){if(value)Gl.enable(cap);else Gl.disable(cap);}
    public void dispose(){gpuTimer.close();compositor.close();invalidate();for(Stream s:streams.values())if(s.mesh!=null)s.mesh.dispose();streams.clear();if(shader!=null)shader.dispose();shader=null;}
}

