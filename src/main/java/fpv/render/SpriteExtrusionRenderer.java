package fpv.render;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.Texture;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.PixmapRegion;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.util.Log;
import fpv.generated.ExtrusionConfig;
import mindustry.Vars;
import mindustry.gen.Crawlc;
import mindustry.gen.Unit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns only generated meshes/shader. Host atlas textures and pixmaps remain owned by Mindustry. */
public final class SpriteExtrusionRenderer implements AutoCloseable {
    private static final String VERTEX = """
        attribute vec3 a_position;
        attribute vec3 a_normal;
        attribute vec2 a_texCoord0;
        uniform mat4 u_combined;
        uniform vec3 u_center,u_size,u_right,u_up,u_back;
        varying vec2 v_uv;
        varying vec3 v_normal;
        varying float v_cap;
        varying float v_height;
        void main(){
            vec3 p=a_position*u_size;
            vec3 world=u_center+u_right*p.x+u_up*p.y+u_back*p.z;
            v_normal=u_right*a_normal.x+u_up*a_normal.y+u_back*a_normal.z;
            v_cap=a_normal.y;
            v_height=a_position.y;
            v_uv=a_texCoord0;
            gl_Position=u_combined*vec4(world,1.0);
        }
        """;
    private static final String FRAGMENT = """
        uniform sampler2D u_texture;
        uniform vec4 u_uv;
        uniform vec2 u_inset;
        uniform vec3 u_light;
        uniform vec4 u_cellUv;
        uniform vec2 u_cellScale;
        uniform vec3 u_cellColor;
        uniform float u_cellEnabled;
        uniform float u_ambient,u_minShade,u_cutoff,u_sideShade;
        uniform float u_pixelSnap;
        uniform vec2 u_texelSize;
        uniform vec2 u_sideInset;
        varying vec2 v_uv;
        varying vec3 v_normal;
        varying float v_cap;
        varying float v_height;
        void main(){
            vec2 coords=mix(u_uv.xy,u_uv.zw,v_uv);
            coords=clamp(coords,u_uv.xy+u_inset,u_uv.zw-u_inset);
            if(u_pixelSnap>0.5)coords=(floor(coords/u_texelSize)+vec2(.5))*u_texelSize;
            vec4 tex=texture2D(u_texture,coords);
            // The native outline is nearly black. Sample two original interior pixels for
            // thick armor sides, retaining the edge where its inward sample is transparent.
            if(abs(v_cap)<0.5 && u_sideInset.x>0.0){
                vec2 local=clamp(v_uv+sign(vec2(.5)-v_uv)*u_sideInset,vec2(0.0),vec2(1.0));
                vec2 inside=mix(u_uv.xy,u_uv.zw,local);
                inside=clamp(inside,u_uv.xy+u_inset,u_uv.zw-u_inset);
                inside=(floor(inside/u_texelSize)+vec2(.5))*u_texelSize;
                vec4 edge=texture2D(u_texture,inside);
                if(edge.a>=u_cutoff)tex.rgb=edge.rgb;
            }
            if(abs(v_cap)>0.5 && tex.a<u_cutoff)discard;
            if(v_cap>0.5 && v_height>0.499 && u_cellEnabled>0.5){
                vec2 cellLocal=(v_uv-vec2(.5))*u_cellScale+vec2(.5);
                if(all(greaterThanEqual(cellLocal,vec2(0.0))) && all(lessThanEqual(cellLocal,vec2(1.0)))){
                    vec2 cellUv=mix(u_cellUv.xy,u_cellUv.zw,cellLocal);
                    cellUv=clamp(cellUv,u_cellUv.xy+u_inset,u_cellUv.zw-u_inset);
                    if(u_pixelSnap>0.5)cellUv=(floor(cellUv/u_texelSize)+vec2(.5))*u_texelSize;
                    vec4 cell=texture2D(u_texture,cellUv);
                    tex.rgb=mix(tex.rgb,cell.rgb*u_cellColor,cell.a);
                }
            }
            float shade=mix(u_minShade,1.0,max(0.0,dot(normalize(v_normal),normalize(u_light))));
            gl_FragColor=vec4(tex.rgb*shade*u_ambient*(abs(v_cap)<0.5?u_sideShade:1.0),1.0);
        }
        """;

    private final LinkedHashMap<TextureRegion, Entry> cache = new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<ProceduralUnitModelProfile,Entry> profileCache=new LinkedHashMap<>(4,.75f,true);
    private final LinkedHashMap<TextureRegion,Entry> navalCache=new LinkedHashMap<>(16,.75f,true);
    private final double[] basis = new double[9];
    private Shader shader;
    private TextureAtlas atlas;
    private boolean disposed, failed, batch;
    private long cacheBytes, frame = Long.MIN_VALUE, buildCount, fallbackCount, renderedCount, teamCellCount;
    private int frameBuilds, previousProgram, previousTexture, previousActive;
    private boolean previousDepth, previousDepthWrite, previousCull, previousBlend;

    public record Stats(int entries, long estimatedCacheBytes, long builds, long rendered, long fallbacks) {}
    public Stats stats() { return new Stats(cache.size()+profileCache.size()+navalCache.size(),cacheBytes,buildCount,renderedCount,fallbackCount); }
    public long teamCellDraws() { return teamCellCount; }
    public record GeometryStats(int vertices,int topQuads,int bottomQuads,int sideQuads){}
    public GeometryStats geometry(TextureRegion region){
        for(Entry entry:navalCache.values())if(entry.matches(region))return entry.geometry;
        for(Entry entry:profileCache.values())if(entry.matches(region))return entry.geometry;
        Entry entry=cache.get(region);return entry==null?null:entry.geometry;
    }

    /** Read-only geometry envelope for the independent visual altitude adapter. */
    public static float halfDepth(Unit unit) {
        TextureRegion region=unit==null||unit.type==null?null:unit.type.region;
        if(region==null||region.width<=0||region.height<=0)return 0;
        float width=region.width*region.scl()/Vars.tilesize,depth=region.height*region.scl()/Vars.tilesize;
        if(!Float.isFinite(width)||!Float.isFinite(depth)||width<=0||depth<=0)return 0;
        return (float)(Math.max(ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(ExtrusionConfig.MAX_DEPTH_TILES,Math.min(width,depth)*ExtrusionConfig.DEPTH_FRACTION))*.5);
    }

    /** Conservative horizontal half-diagonal of the native sprite footprint, in tiles. */
    public static float bodyRadius(Unit unit) {
        TextureRegion region=unit==null||unit.type==null?null:unit.type.region;
        if(region==null||region.width<=0||region.height<=0)return 0;
        double value=Math.hypot(region.width,region.height)*region.scl()/Vars.tilesize*.5;
        return Double.isFinite(value)&&value>0?(float)value:0;
    }

    /** Optional batching: call once before the unit pass and end in finally. */
    public void begin() {
        if (disposed) return;
        if (batch) throw new IllegalStateException("Extrusion batch already active");
        previousProgram=Gl.getInt(Gl.currentProgram);
        previousActive=Gl.getInt(Gl.activeTexture);
        Gl.activeTexture(Gl.texture0);
        previousTexture=Gl.getInt(Gl.textureBinding2d);
        previousDepth=Gl.isEnabled(Gl.depthTest);
        previousDepthWrite=Gl.getInt(Gl.depthWritemask)!=0;
        previousCull=Gl.isEnabled(Gl.cullFace);
        previousBlend=Gl.isEnabled(Gl.blend);
        batch=true;
    }

    /** Restores the caller's GL state; render leaves no per-unit mesh or temporary texture. */
    public void end() {
        if (!batch) return;
        Gl.activeTexture(Gl.texture0); Gl.bindTexture(Gl.texture2d,previousTexture);
        Gl.activeTexture(previousActive); Gl.useProgram(previousProgram);
        enabled(Gl.depthTest,previousDepth); Gl.depthMask(previousDepthWrite);
        enabled(Gl.cullFace,previousCull); enabled(Gl.blend,previousBlend);
        batch=false;
    }

    /**
     * Height is body center in render tile units. Angles are degrees: native yaw0=east/90=north,
     * positive pitch lifts the nose and positive roll lowers the right wing.
     * Returns false when the caller should use its previous renderer (including deferred cache builds).
     */
    public boolean render(Unit unit,float centerX,float height,float renderZ,float yaw,float pitch,float roll,
                          Camera3D camera,float ambient) {
        return render(unit,centerX,height,renderZ,yaw,pitch,roll,camera,ambient,1f);
    }

    /** Family-local physical depth multiplier. The cached alpha mesh and every default/flying caller stay unchanged. */
    public boolean render(Unit unit,float centerX,float height,float renderZ,float yaw,float pitch,float roll,
                          Camera3D camera,float ambient,float depthMultiplier) {
        return renderRegion(unit,unit==null||unit.type==null?null:unit.type.region,centerX,height,renderZ,yaw,pitch,roll,
            camera,ambient,depthMultiplier,0,1,1,false,true,false,null,null);
    }

    /** Reuses the original alpha mesh, lighting, team overlay and cache, with presentation-only dimensions. */
    public boolean renderProfile(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,Camera3D camera,
            float ambient,ProceduralUnitModelProfile profile,float supportHalfDepth){
        return renderProfile(unit,x,y,z,yaw,pitch,roll,camera,ambient,profile,supportHalfDepth,null);
    }
    /** Naval-only extra geometry shares the same shader, atlas, vertex budget and lifecycle. */
    public boolean renderNavalProfile(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,Camera3D camera,
            float ambient,NavalHullProfile profile){
        return profile!=null&&renderProfile(unit,x,y,z,yaw,pitch,roll,camera,ambient,profile.body(),0,profile);
    }
    private boolean renderProfile(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,Camera3D camera,
            float ambient,ProceduralUnitModelProfile profile,float supportHalfDepth,NavalHullProfile naval){
        if(unit==null||unit.type==null||profile==null||!finite(x,y,z,yaw,pitch,roll,ambient))return false;
        SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);
        double lift=profile.bodyCenterLift(supportHalfDepth);
        return renderRegion(unit,unit.type.region,x+(float)(basis[0]*profile.originRight()-basis[6]*profile.originForward()+basis[3]*lift),
            y+profile.groundClearance()+(float)(basis[1]*profile.originRight()-basis[7]*profile.originForward()+basis[4]*lift),
            z+(float)(basis[2]*profile.originRight()-basis[8]*profile.originForward()+basis[5]*lift),
            yaw,pitch,roll,camera,ambient,1,profile.bodyThickness(),profile.bodyScale(),profile.sideShade(),false,true,true,profile.hull()==null?null:profile,naval);
    }
    /** Native weapon regions share the same cached silhouette extrusion; mirrored art needs no extra mesh. */
    public boolean renderComponent(Unit unit,TextureRegion region,float x,float y,float z,float yaw,float pitch,float roll,
            Camera3D camera,float ambient,float thickness,boolean flip,float sideShade){
        return renderRegion(unit,region,x,y,z,yaw,pitch,roll,camera,ambient,1,thickness,1,sideShade,flip,false,true,null,null);
    }
    private boolean renderRegion(Unit unit,TextureRegion region,float centerX,float height,float renderZ,float yaw,float pitch,float roll,
            Camera3D camera,float ambient,float depthMultiplier,float explicitThickness,float scale,float sideShade,boolean flip,boolean bodyCell,boolean pixelSnap,
            ProceduralUnitModelProfile meshProfile,NavalHullProfile naval){
        if (disposed || failed || unit==null || unit.type==null || camera==null || Core.atlas==null
            || !finite(centerX,height,renderZ,yaw,pitch,roll,ambient)
            || !Float.isFinite(depthMultiplier)||depthMultiplier<=0||depthMultiplier>8
            || !Float.isFinite(explicitThickness)||explicitThickness<0||!Float.isFinite(scale)||scale<=0
            || !Float.isFinite(sideShade)||sideShade<0||sideShade>1) return false;
        if (!(region instanceof AtlasRegion ar) || ar.rotate || region.isFlipX() || region.isFlipY()
            || !region.found() || region.texture==null || region.width<=0 || region.height<=0
            || (long)region.width*region.height>(long)ExtrusionConfig.MAX_SOURCE_PIXELS) {
            fallbackCount++; return false;
        }
        float width=region.width*region.scl()/Vars.tilesize*scale;
        float depth=region.height*region.scl()/Vars.tilesize*scale;
        if (!Float.isFinite(width) || !Float.isFinite(depth) || width<=0 || depth<=0) return false;
        // Match the base UnitType.drawCell eligibility and centered native sprite dimensions.
        boolean drawCell=bodyCell && unit.type.drawCell && !(unit instanceof Crawlc);
        TextureRegion cell=unit.type.cellRegion;
        float cellWidth=width,cellDepth=depth;
        if(drawCell){
            if(cell==null||!cell.found()||cell.texture!=region.texture||cell.isFlipX()||cell.isFlipY()
                ||(cell instanceof AtlasRegion cellAtlas && cellAtlas.rotate)||cell.width<=0||cell.height<=0){
                fallbackCount++;return false;
            }
            cellWidth=cell.width*cell.scl()/Vars.tilesize;
            cellDepth=cell.height*cell.scl()/Vars.tilesize;
            if(!Float.isFinite(cellWidth)||!Float.isFinite(cellDepth)||cellWidth<=0||cellDepth<=0){
                fallbackCount++;return false;
            }
        }
        boolean standalone=!batch;
        if (standalone) begin();
        try {
            if (atlas!=Core.atlas) { clearCache(); atlas=Core.atlas; }
            if (shader==null) shader=new Shader(VERTEX,FRAGMENT);
            Entry entry=naval!=null?navalCache.get(region):meshProfile==null?cache.get(region):profileCache.get(meshProfile);
            if (entry!=null && (!entry.matches(region)||naval!=null&&!naval.equals(entry.navalProfile))) {
                if(naval!=null)navalCache.remove(region);else if(meshProfile==null)cache.remove(region);else profileCache.remove(meshProfile);
                cacheBytes-=entry.bytes; entry.dispose(); entry=null;
            }
            if (entry==null) {
                long now=Core.graphics.getFrameId();
                if (now!=frame) { frame=now; frameBuilds=0; }
                if (frameBuilds>=(int)ExtrusionConfig.MAX_BUILDS_PER_FRAME) { fallbackCount++; return false; }
                frameBuilds++;
                entry=build(ar,meshProfile,naval);
                trimFor(entry.bytes);
                if(naval!=null){entry.navalProfile=naval;navalCache.put(region,entry);}else if(meshProfile==null)cache.put(region,entry);else profileCache.put(meshProfile,entry);
                cacheBytes+=entry.bytes;
            }
            if (entry.mesh==null) { fallbackCount++; return false; }
            SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);
            float thickness=(float)Math.max(ExtrusionConfig.MIN_DEPTH_TILES,
                Math.min(ExtrusionConfig.MAX_DEPTH_TILES,Math.min(width,depth)*ExtrusionConfig.DEPTH_FRACTION));
            thickness*=depthMultiplier;
            if(explicitThickness>0)thickness=explicitThickness;
            Gl.enable(Gl.depthTest); Gl.depthMask(true); Gl.disable(Gl.cullFace); Gl.disable(Gl.blend);
            shader.bind(); region.texture.bind(0);
            shader.setUniformMatrix4("u_combined",camera.combined.val);
            shader.setUniformi("u_texture",0);
            shader.setUniformf("u_center",centerX,height,renderZ);
            shader.setUniformf("u_size",width,thickness,depth);
            float mirror=flip?-1:1;
            shader.setUniformf("u_right",(float)basis[0]*mirror,(float)basis[1]*mirror,(float)basis[2]*mirror);
            shader.setUniformf("u_up",(float)basis[3],(float)basis[4],(float)basis[5]);
            shader.setUniformf("u_back",(float)basis[6],(float)basis[7],(float)basis[8]);
            shader.setUniformf("u_uv",region.u,region.v,region.u2,region.v2);
            shader.setUniformf("u_inset",.5f/region.texture.width,.5f/region.texture.height);
            shader.setUniformf("u_cellEnabled",drawCell?1f:0f);
            if(drawCell){
                shader.setUniformf("u_cellUv",cell.u,cell.v,cell.u2,cell.v2);
                shader.setUniformf("u_cellScale",width/cellWidth,depth/cellDepth);
                Color cellColor=unit.type.cellColor(unit);
                shader.setUniformf("u_cellColor",cellColor.r,cellColor.g,cellColor.b);
            }
            shader.setUniformf("u_light",(float)ExtrusionConfig.LIGHT_X,(float)ExtrusionConfig.LIGHT_Y,(float)ExtrusionConfig.LIGHT_Z);
            shader.setUniformf("u_ambient",Math.max(0,Math.min(1,ambient)));
            shader.setUniformf("u_minShade",(float)ExtrusionConfig.MIN_SHADE);
            shader.setUniformf("u_sideShade",sideShade);
            shader.setUniformf("u_pixelSnap",pixelSnap?1:0);
            shader.setUniformf("u_texelSize",1f/region.texture.width,1f/region.texture.height);
            shader.setUniformf("u_sideInset",bodyCell&&pixelSnap?2f/region.width:0,bodyCell&&pixelSnap?2f/region.height:0);
            shader.setUniformf("u_cutoff",(float)ExtrusionConfig.ALPHA_THRESHOLD/255f);
            entry.mesh.render(shader,Gl.triangles);
            if(drawCell)teamCellCount++;
            renderedCount++; return true;
        } catch (RuntimeException exception) {
            failed=true;
            clearCache();
            if (shader!=null) { shader.dispose(); shader=null; }
            Log.err("[FPV] Sprite extrusion disabled; retaining unit fallback.",exception);
            fallbackCount++; return false;
        } finally {
            if (standalone) end();
        }
    }

    private Entry build(AtlasRegion region,ProceduralUnitModelProfile profile,NavalHullProfile naval) {
        Mesh mesh=null;
        try {
            PixmapRegion pixels=Core.atlas.getPixmap(region);
            if (pixels==null || pixels.pixmap==null || pixels.width!=region.width || pixels.height!=region.height)
                return new Entry(region,null,0);
            byte[] alpha=new byte[pixels.width*pixels.height];
            for (int y=0;y<pixels.height;y++) for (int x=0;x<pixels.width;x++) alpha[x+y*pixels.width]=(byte)pixels.getA(x,y);
            SpriteExtrusionGeometry.Result shape=profile==null?SpriteExtrusionGeometry.generate(alpha,pixels.width,pixels.height,
                (int)ExtrusionConfig.MAX_GRID_SIDE,(int)ExtrusionConfig.MIN_GRID_SIDE,(int)ExtrusionConfig.ALPHA_THRESHOLD,
                ExtrusionConfig.MIN_CELL_COVERAGE,(int)ExtrusionConfig.MAX_VERTICES):
                LayeredHullGeometry.generate(alpha,pixels.width,pixels.height,profile.hull(),(int)ExtrusionConfig.MAX_GRID_SIDE,
                    (int)ExtrusionConfig.MIN_GRID_SIDE,(int)ExtrusionConfig.ALPHA_THRESHOLD,ExtrusionConfig.MIN_CELL_COVERAGE,(int)ExtrusionConfig.MAX_VERTICES);
            if(naval!=null)shape=NavalBodyGeometry.generate(shape,naval.sections(),profile.bodyThickness(),(int)ExtrusionConfig.MAX_VERTICES);
            // Arc retains CPU vertex storage beside the GPU VBO, so budget both copies.
            long bytes=shape.vertexBytes()*2;
            if (shape.vertexCount()==0 || bytes>(long)ExtrusionConfig.MAX_CACHE_BYTES) return new Entry(region,null,0);
            trimFor(bytes);
            mesh=new Mesh(true,shape.vertexCount(),0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords);
            mesh.setVertices(shape.vertices());
            buildCount++;
            return new Entry(region,mesh,bytes,new GeometryStats(shape.vertexCount(),shape.topQuads(),shape.bottomQuads(),shape.sideQuads()));
        } catch (RuntimeException exception) {
            if (mesh!=null) mesh.dispose();
            // A missing CPU-readable native atlas region is not a reason to break all other units.
            return new Entry(region,null,0);
        }
    }

    private void trimFor(long bytes) {
        Iterator<Map.Entry<TextureRegion,Entry>> it=cache.entrySet().iterator();
        while (it.hasNext() && (cache.size()+profileCache.size()+navalCache.size()>=(int)ExtrusionConfig.MAX_CACHE_ENTRIES
            || cacheBytes+bytes>(long)ExtrusionConfig.MAX_CACHE_BYTES)) {
            Entry entry=it.next().getValue(); cacheBytes-=entry.bytes; entry.dispose(); it.remove();
        }
        var profiles=profileCache.entrySet().iterator();
        while(profiles.hasNext()&&(cache.size()+profileCache.size()+navalCache.size()>=(int)ExtrusionConfig.MAX_CACHE_ENTRIES||cacheBytes+bytes>(long)ExtrusionConfig.MAX_CACHE_BYTES)){
            Entry entry=profiles.next().getValue();cacheBytes-=entry.bytes;entry.dispose();profiles.remove();
        }
        var ships=navalCache.entrySet().iterator();
        while(ships.hasNext()&&(cache.size()+profileCache.size()+navalCache.size()>=(int)ExtrusionConfig.MAX_CACHE_ENTRIES||cacheBytes+bytes>(long)ExtrusionConfig.MAX_CACHE_BYTES)){
            Entry entry=ships.next().getValue();cacheBytes-=entry.bytes;entry.dispose();ships.remove();
        }
    }
    private static final class Entry {
        NavalHullProfile navalProfile;
        final Mesh mesh;
        final Texture texture;
        final float u,v,u2,v2;
        final int width,height;
        final long bytes;
        final GeometryStats geometry;
        Entry(TextureRegion region,Mesh mesh,long bytes) {
            this(region,mesh,bytes,null);
        }
        Entry(TextureRegion region,Mesh mesh,long bytes,GeometryStats geometry) {
            this.geometry=geometry;
            this.mesh=mesh; this.bytes=bytes; texture=region.texture;
            u=region.u;v=region.v;u2=region.u2;v2=region.v2;width=region.width;height=region.height;
        }
        boolean matches(TextureRegion region) {
            return texture==region.texture && u==region.u && v==region.v && u2==region.u2 && v2==region.v2
                && width==region.width && height==region.height;
        }
        void dispose() { if (mesh!=null) mesh.dispose(); }
    }
    private void clearCache() {
        for (Entry entry:cache.values()) entry.dispose();
        for(Entry entry:profileCache.values())entry.dispose();
        for(Entry entry:navalCache.values())entry.dispose();
        cache.clear();profileCache.clear();navalCache.clear();cacheBytes=0;
    }
    public void dispose() { close(); }
    @Override public void close() {
        if (disposed) return;
        end(); clearCache();
        if (shader!=null) { shader.dispose(); shader=null; }
        atlas=null; disposed=true;
    }
    private static boolean finite(float x,float y,float z,float yaw,float pitch,float roll,float ambient) {
        return Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)&&Float.isFinite(yaw)
            &&Float.isFinite(pitch)&&Float.isFinite(roll)&&Float.isFinite(ambient);
    }
    private static void enabled(int flag,boolean value) { if(value)Gl.enable(flag);else Gl.disable(flag); }
}
