package fpv.render;

import arc.Core;
import fpv.profile.CameraPerformanceProfile;
import fpv.profile.CameraPerformanceProfile.Part;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.GL30;
import arc.graphics.Pixmap;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.GLFrameBuffer.FrameBufferBuilder;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import arc.math.geom.Ray;
import arc.math.geom.Vec2;
import arc.math.geom.Vec3;
import arc.math.geom.Intersector3D;
import arc.struct.IntMap;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.effects.RadialExplosion;
import fpv.combat.Combat3DSystem;
import fpv.defense.ProjectedWallSystem;
import fpv.generated.CrashConfig;
import arc.util.Buffers;
import arc.util.Time;
import fpv.generated.RenderConfig;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Shaders;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.draw.DrawTurret;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Read-only 3D view of the currently loaded Mindustry world. All methods run on the render thread. */
public final class FpvWorldRenderer {
    public static final float WORLD_SCALE = 1f / Vars.tilesize;
    private static final int CHUNK = (int)RenderConfig.CHUNK_TILES;
    private static final int MAX_CACHED_CHUNKS = (int)RenderConfig.MAX_CACHED_CHUNKS;
    private static final float DETAIL_DISTANCE = (float)RenderConfig.DETAIL_DISTANCE_TILES;
    private static final VertexAttribute COLOR = new VertexAttribute(4, "a_color");
    private static final String VERTEX =
        "attribute vec3 a_position;\nattribute vec4 a_color;\n" +
        "uniform mat4 u_combined;\nuniform vec3 u_camera;\n" +
        "varying vec4 v_color;\nvarying float v_distance;\n" +
        "void main(){v_color=a_color;v_distance=distance(a_position,u_camera);" +
        "gl_Position=u_combined*vec4(a_position,1.0);}\n";
    private static final String FRAGMENT =
        "varying vec4 v_color;\nuniform float u_ambient;\n" +
        "void main(){gl_FragColor=vec4(v_color.rgb*mix(u_ambient,1.0,v_color.a),1.0);}\n";
    private static final String ATLAS_VERTEX =
        "attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;" +
        "uniform mat4 u_combined;uniform vec3 u_offset;varying vec4 v_color;varying vec2 v_uv;varying vec3 v_pos;" +
        "void main(){v_pos=a_position+u_offset;v_color=a_color;v_uv=a_texCoord0;gl_Position=u_combined*vec4(v_pos,1.0);}";
    private static final String ATLAS_FRAGMENT =
        "uniform sampler2D u_texture;uniform float u_ambient;uniform float u_time;uniform vec2 u_water;uniform float u_cutoff;" +
        "varying vec4 v_color;varying vec2 v_uv;varying vec3 v_pos;" +
        "void main(){vec4 t=texture2D(u_texture,v_uv);if(t.a<u_cutoff)discard;float light=1.0;" +
        "if(v_color.a>0.5)light+=u_water.x*sin(v_pos.x*2.4+v_pos.z*1.7+u_time*u_water.y)*sin(v_pos.z*3.1-u_time*u_water.y);" +
        "gl_FragColor=vec4(t.rgb*v_color.rgb*light*u_ambient,1.0);}";

    private final LinkedHashMap<Integer, ChunkMesh> chunks = new LinkedHashMap<>(64, .75f, true);
    private final Map<Integer, CoarseFogMask> coarseFog = new LinkedHashMap<>();
    // Groups.build is an updater list: sleep() removes still-placed buildings from it.
    private final IntMap<Building> placedBuildings = new IntMap<>();
    private final IntMap<Building> submittedBuildings = new IntMap<>();
    private final IntMap<Building> submittedBridges = new IntMap<>();
    private final IntMap<Unit> submittedUnits = new IntMap<>();
    private final IntMap<Unit> submittedUnitBodies = new IntMap<>();
    private final NativeBridgeLinks.Link bridgeLink=new NativeBridgeLinks.Link();
    private Object placedWorldTiles;
    private int placedTileChanges;
    private boolean placedBuildingsDirty = true, buildingFrameCompleted;
    private final Geometry terrain = new Geometry((int)RenderConfig.STATIC_MESH_INITIAL_VERTICES, false);
    private final AircraftWeaponRenderer aircraftWeapons=new AircraftWeaponRenderer();
    private final Geometry dynamic = new Geometry((int)RenderConfig.DYNAMIC_MESH_VERTICES, true);
    private final NativeBlockAnimationAdapter blockAnimations=new NativeBlockAnimationAdapter();
    private final TurretModelRenderer turretModels=new TurretModelRenderer();
    private final NativeAtlasMesh.Builder staticAtlas = new NativeAtlasMesh.Builder(false);
    private final NativeAtlasMesh.Builder streamAtlas = new NativeAtlasMesh.Builder(true);
    private final OrganicPropRenderer organicProps=new OrganicPropRenderer();
    private final UnitThreatMarkerRenderer threatMarkers=new UnitThreatMarkerRenderer();
    private final LayerWorldRenderer constructionRenderer=new LayerWorldRenderer();
    private final ConstructionVisualRenderer constructionVisuals=new ConstructionVisualRenderer();
    private final IntMap<BuildBeamState> buildBeams=new IntMap<>();
    public ConstructionVisualRenderer.Stats constructionVisualStats(){return constructionVisuals.stats();}
    public LayerWorldRenderer constructionRenderer(){return constructionRenderer;}
    private fpv.construction.LayerRuntime constructionRuntime;
    public void setConstruction(fpv.construction.LayerRuntime value){constructionRuntime=value;constructionRenderer.setRuntime(value);supportEffects.setConstruction(value);}
    public UnitThreatMarkerRenderer threatMarkers(){return threatMarkers;}
    private Object vegetationContent;
    private float vegetationHeight,vegetationPadding;
    public long organicPropSubmissions(){return organicProps.submissions();}
    private final EnvironmentRenderer environment = new EnvironmentRenderer();
    private final TerrainElevation elevation = new TerrainElevation();
    private long terrainRevision=-1;
    private final SpriteExtrusionRenderer extrusion=new SpriteExtrusionRenderer();
    private final GroundFamilyRenderer groundFamily=new GroundFamilyRenderer();
    private final CreatorFlightVisuals creatorVisuals=new CreatorFlightVisuals();
    private final arc.struct.IntSet creatorSubmitted=new arc.struct.IntSet();
    public boolean creatorFlightWasSubmitted(Unit unit){return unit!=null&&buildingFrameCompleted&&creatorSubmitted.contains(unit.id);}
    private java.util.function.Predicate<Unit> creatorFlight=unit->false;
    public void setCreatorFlightPredicate(java.util.function.Predicate<Unit> value){creatorFlight=value==null?unit->false:value;groundFamily.setCreatorFlightPredicate(creatorFlight);}
    private final java.util.function.DoubleBinaryOperator groundFootSurface=(x,y)->surfaceHeight(x,-y);
    private fpv.construction.LayerPhysicalQueries footSupportQueries;
    private double velaFootCeiling;
    private final java.util.function.DoubleBinaryOperator velaFootSurface=(x,y)->{
        double base=surfaceHeight(x,-y);
        return footSupportQueries==null?base:footSupportQueries.supportBelow(x,y,velaFootCeiling,base);
    };
    public void setFootSupportQueries(fpv.construction.LayerPhysicalQueries value){footSupportQueries=value;}
    public GroundFamilyRenderer groundFamily(){return groundFamily;}
    private final CombatVisualRenderer combatVisuals=new CombatVisualRenderer();
    private final FirstPersonRigRenderer firstPersonRig=new FirstPersonRigRenderer();
    private boolean firstPersonActive;
    public FirstPersonRigRenderer firstPersonRig(){return firstPersonRig;}
    public record AircraftStats(int draws,int ports,int models,long builds,long bytes,int sides){}
    public AircraftStats aircraftStats(){return new AircraftStats(aircraftWeapons.draws,aircraftWeapons.ports,aircraftWeapons.models(),aircraftWeapons.builds(),aircraftWeapons.bytes(),aircraftWeapons.sides());}
    private final NavalUnitRenderer naval=new NavalUnitRenderer();
    public NavalUnitRenderer naval(){return naval;}
    public void setFirstPersonActive(boolean value){firstPersonActive=value;combatVisuals.setFirstPerson(value);}
    public boolean firstPersonActive(){return firstPersonActive;}
    private final ForceFieldRenderer forceFields=new ForceFieldRenderer();
    private final ProjectedWallRenderer projectedWalls=new ProjectedWallRenderer();
    private ProjectedWallSystem projectedDefense;
    private final ForceProjectorRenderer forceProjectorRenderer=new ForceProjectorRenderer();
    private fpv.defense.ForceProjectorSystem forceProjectors;
    private final SupportEffectsRenderer supportEffects=new SupportEffectsRenderer();
    private final MiningVisualRenderer miningVisuals=new MiningVisualRenderer();
    public MiningVisualRenderer miningVisuals(){return miningVisuals;}
    public SupportEffectsRenderer supportEffects(){return supportEffects;}
    public void setForceProjectors(fpv.defense.ForceProjectorSystem value){forceProjectors=value;forceProjectorRenderer.clear();}
    public boolean wasForceProjectorSubmitted(Building value){return buildingFrameCompleted&&forceProjectorRenderer.wasSubmitted(value);}
    private boolean showControlledUnit;
    private Combat3DSystem combat;
    private final SupportEffectsRenderer.Sink combatSink=new SupportEffectsRenderer.Sink(){
        @Override public void laser(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b){dynamic.laser(ax,ay,az,bx,by,bz,width,r,g,b);}
        @Override public void disk(float x,float y,float z,float radiusX,float radiusZ,float r,float g,float b){dynamic.disk(x,y,z,radiusX,radiusZ,r,g,b);}
        @Override public void beam(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b){
            dynamic.beam(ax,ay,az,bx,by,bz,width,r,g,b);
        }
        @Override public void marker(float x,float y,float z,float radius,float r,float g,float b){
            dynamic.marker(x,y,z,radius,r,g,b);
        }
    };
    private UnitAltitudeSystem unitAltitudes;
    private RadialExplosion explosions;
    private final Vec3 terrainA=new Vec3(),terrainB=new Vec3(),terrainC=new Vec3(),terrainD=new Vec3(),terrainHit=new Vec3();
    private final ArrayList<Candidate> visibleChunks = new ArrayList<>();
    private final Set<Integer> pinnedChunks = new HashSet<>();
    private final Color color = new Color();
    private final Vec3 projectionPoint = new Vec3();
    private final FloatBuffer savedClearColor = Buffers.newFloatBuffer(4);
    private final IntBuffer savedViewport = Buffers.newIntBuffer(4);
    private Shader shader;
    private Shader atlasShader;
    private Mesh dynamicMesh;
    private FrameBuffer sceneBuffer;
    private Object worldTiles;
    private Object discoveryData;
    private boolean staticFogEnabled, coarseFogPruned;
    private int worldWidth, worldHeight, viewerTeam = -1;
    private long revision = 1, cachedBytes;
    private Camera3D camera;
    private boolean disposed;
    private int selectionX, selectionY, selectionSize = 1;
    private Block selectionBlock;
    private int selectionRotation;
    private boolean hasSelection, selectionValid;
    private int detailedChunks, coarseChunks;
    private double cpuMilliseconds;

    public FpvWorldRenderer() {}
    public EnvironmentRenderer environment() { return environment; }
    public TerrainElevation terrain(){return elevation;}
    public void setUnitAltitudes(UnitAltitudeSystem value){unitAltitudes=value;constructionRenderer.setUnitAltitudes(value);}
    public void setExplosions(RadialExplosion value){explosions=value;}
    public void setCombat(Combat3DSystem value){combat=value;combatVisuals.clear();groundFamily.setMechAim(value==null?null:value.mechWeapons());firstPersonRig.setMechAim(value==null?null:value.mechWeapons());naval.setAim(value==null?null:value.navalWeapons());firstPersonRig.setNavalAim(value==null?null:value.navalWeapons());aircraftWeapons.setAim(value==null?null:value.aircraftWeapons());firstPersonRig.setAircraftAim(value==null?null:value.aircraftWeapons());}
    public void setProjectedDefense(ProjectedWallSystem value){projectedDefense=value;projectedWalls.invalidate();}
    public boolean wasProjectionSubmitted(Building value){return buildingFrameCompleted&&projectedWalls.wasSubmitted(value);}
    public int projectedFaceCount(){return projectedWalls.faceCount();}
    public long projectedCacheBuildCount(){return projectedWalls.cacheBuildCount();}
    public long projectedCacheBytes(){return projectedWalls.cachedBytes();}
    public void setShowControlledUnit(boolean value){showControlledUnit=value;}
    public boolean showControlledUnit(){return showControlledUnit;}
    public boolean wasUnitSubmitted(Unit unit){return buildingFrameCompleted&&unit!=null&&submittedUnits.get(unit.id)==unit;}
    /** Separates enclosing body geometry from the first-person limbs-only submission path. */
    public boolean wasUnitBodySubmitted(Unit unit){return buildingFrameCompleted&&unit!=null&&submittedUnitBodies.get(unit.id)==unit;}
    public boolean wasShieldSubmitted(Unit unit){return buildingFrameCompleted&&forceFields.wasSubmitted(unit);}
    public CombatVisualRenderer.Stats combatVisualStats(){return combatVisuals.stats();}
    public boolean wasCombatVisualSubmitted(long id){return buildingFrameCompleted&&combatVisuals.wasSubmitted(id);}
    public boolean wasNativeBulletSubmitted(Bullet bullet){return buildingFrameCompleted&&combatVisuals.wasNativeSubmitted(bullet);}
    public SpriteExtrusionRenderer.Stats extrusionStats(){return extrusion.stats();}
    public SpriteExtrusionRenderer.GeometryStats unitGeometry(TextureRegion region){return extrusion.geometry(region);}
    public long extrusionTeamCellDraws(){return extrusion.teamCellDraws();}

    /** Shared physical top surface; native tile data remains unchanged. */
    public double surfaceHeight(double x,double z){
        Tile tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(-z+.5));
        if(tile==null)return 0;
        if(elevation.naturalWall(tile))return elevation.heightAt(x,z);
        return tile.build!=null||tile.block().solid?heightFor(tile.block()):0;
    }

    /** Last rendered frame, including fog-covered chunks. CPU submission time excludes GPU completion. */
    public Stats stats() {
        return new Stats(visibleChunks.size(), detailedChunks, coarseChunks, chunks.size(), cachedBytes, cpuMilliseconds);
    }

    public record Stats(int visibleChunks, int detailedChunks, int coarseChunks, int cachedChunks,
                        long cachedBytes, double cpuMilliseconds) {}

    /** Invalidates cached geometry after a world or tile change; resources rebuild lazily. */
    public void invalidate() {
        blockAnimations.clear();
        turretModels.clear();
        miningVisuals.clear();
        revision++; coarseFog.clear(); elevation.invalidate();
        placedBuildings.clear(); submittedBuildings.clear(); submittedBridges.clear(); submittedUnits.clear();submittedUnitBodies.clear();forceFields.clear();projectedWalls.invalidate();forceProjectorRenderer.clear();supportEffects.clear();groundFamily.clear();
        placedBuildingsDirty = true;
        buildingFrameCompleted = false;
    }

    /** Physical attachment, independent of native updater membership and activity. */
    public boolean hasPlacedBuilding(Building building) {
        if (disposed || Vars.world == null || Vars.world.tiles == null) return false;
        syncPlacedBuildings();
        return attached(building) && placedBuildings.get(building.tile.pos()) == building;
    }

    /** Geometry submitted in the last successfully completed render; not a pixel visibility test. */
    public boolean wasBuildingSubmitted(Building building) {
        return buildingFrameCompleted && attached(building) &&
            submittedBuildings.get(building.tile.pos()) == building;
    }

    public int placedBuildingCount() { return placedBuildings.size; }
    public int submittedBuildingCount() { return buildingFrameCompleted ? submittedBuildings.size : 0; }
    public int submittedBridgeCount() { return buildingFrameCompleted ? submittedBridges.size : 0; }
    public boolean wasBridgeSubmitted(Building source) {
        return buildingFrameCompleted && attached(source) && submittedBridges.get(source.tile.pos())==source;
    }

    private boolean attached(Building building) {
        return building != null && building.tile != null && !building.dead &&
            Vars.world != null && Vars.world.tile(building.tile.x, building.tile.y) == building.tile &&
            building.tile.build == building;
    }

    private void syncPlacedBuildings() {
        if (placedWorldTiles != Vars.world.tiles || placedTileChanges != Vars.world.tileChanges)
            placedBuildingsDirty = true;
        if (!placedBuildingsDirty) return;
        placedBuildings.clear();
        placedWorldTiles = Vars.world.tiles;
        for (int i = 0, count = Vars.world.width() * Vars.world.height(); i < count; i++) {
            Tile tile = Vars.world.tiles.geti(i);
            if (tile != null && tile.build != null && tile.build.tile == tile && attached(tile.build))
                placedBuildings.put(tile.pos(), tile.build);
        }
        placedTileChanges = Vars.world.tileChanges;
        placedBuildingsDirty = false;
    }

    private void updatePlacedBuilding(Tile tile) {
        if (Vars.world == null || placedWorldTiles != Vars.world.tiles) {
            placedBuildingsDirty = true;
            return;
        }
        int changes = Vars.world.tileChanges;
        // Floor/overlay events need not increment tileChanges. A missed block event triggers a reseed.
        if (changes != placedTileChanges && changes != placedTileChanges + 1) placedBuildingsDirty = true;
        placedTileChanges = changes;
        if (placedBuildingsDirty) return;
        Building old = placedBuildings.get(tile.pos());
        if (old != null && old != tile.build) placedBuildings.remove(tile.pos());
        Building building = tile.build;
        if (attached(building)) placedBuildings.put(building.tile.pos(), building);
    }

    private void renderPlacedBuildings() {
        syncPlacedBuildings();
        var entries = placedBuildings.entries();
        while (entries.hasNext()) {
            var entry = entries.next();
            Building building = entry.value;
            if (!attached(building)) { entries.remove(); continue; }
            if (renderBuilding(building)) submittedBuildings.put(entry.key, building);
            if (renderBridge(building)) submittedBridges.put(entry.key, building);
        }
    }

    /** Full-span culling is independent of endpoint culling, including idle/sleeping structures. */
    private boolean renderBridge(Building source){
        if(!NativeBridgeLinks.resolve(source,bridgeLink)||!bridgeLink.physicalSpan())return false;
        Building target=bridgeLink.target;
        if(source.inFogTo(Vars.player.team())||target.inFogTo(Vars.player.team()))return false;
        float ax=source.x*WORLD_SCALE,az=-source.y*WORLD_SCALE,bx=target.x*WORLD_SCALE,bz=-target.y*WORLD_SCALE;
        float dx=bx-ax,dz=bz-az,distance=(float)Math.hypot(dx,dz);
        if(distance<=.001f)return false;
        float h=Math.max(heightFor(source.block),heightFor(target.block));
        int samples=Math.max(1,Math.min((int)RenderConfig.BRIDGE_MAX_SURFACE_SAMPLES,(int)Math.ceil(distance*2)));
        for(int i=0;i<=samples;i++){
            float fraction=i/(float)samples,x=ax+dx*fraction,z=az+dz*fraction;
            Tile tile=Vars.world.tile((int)Math.floor(x+.5f),(int)Math.floor(-z+.5f));
            if(tile==null||!discovered(tile))return false;
            h=Math.max(h,(float)surfaceHeight(x,z));
        }
        h+=(float)RenderConfig.BRIDGE_CLEARANCE;
        float width=Mathf.clamp(bridgeLink.width,(float)RenderConfig.BRIDGE_MIN_WIDTH,(float)RenderConfig.BRIDGE_MAX_WIDTH);
        if(!camera.frustum.containsBounds((ax+bx)/2,h/2,(az+bz)/2,Math.abs(dx)/2+width,h/2+width,Math.abs(dz)/2+width))return false;
        float ux=dx/distance,uz=dz/distance;
        float start=source.block.size*.5f,end=target.block.size*.5f;
        float length=Math.max(.01f,distance-start-end);
        float sx=ax+ux*start,sz=az+uz*start,ex=bx-ux*end,ez=bz-uz*end;
        float cx=(sx+ex)/2,cz=(sz+ez)/2,angle=(float)Math.atan2(dz,dx)*Mathf.radDeg;
        float thickness=(float)RenderConfig.BRIDGE_DECK_THICKNESS;
        Color team=source.team.color;
        dynamic.box(cx,h-thickness/2,cz,length,thickness,width,angle,.38f,.43f,.49f);
        streamAtlas.top(bridgeLink.region,cx,h+(float)RenderConfig.ATLAS_SURFACE_OFFSET,cz,length,width,angle,1,1,1,false);
        if(bridgeLink.liquidColor!=null){
            Color liquid=bridgeLink.liquidColor;
            dynamic.box(cx,h+thickness*.25f,cz,length,thickness*.25f,width*.36f,angle,liquid.r,liquid.g,liquid.b);
        }
        float rail=width*.075f,offset=width*.48f;
        for(int side=-1;side<=1;side+=2){
            float rx=-uz*offset*side,rz=ux*offset*side;
            dynamic.beam(sx+rx,h+thickness,sz+rz,ex+rx,h+thickness,ez+rz,rail,
                .4f+team.r*.4f,.4f+team.g*.4f,.4f+team.b*.4f);
        }
        dynamic.beam(ax,heightFor(source.block),az,ax,h,az,width*.18f,.5f,.56f,.64f);
        dynamic.beam(bx,heightFor(target.block),bz,bx,h,bz,width*.18f,.5f,.56f,.64f);
        int arrows=Math.min((int)RenderConfig.BRIDGE_MAX_ARROWS,Math.max(1,(int)(length/RenderConfig.BRIDGE_ARROW_SPACING)));
        float phase=bridgeLink.phase-(float)Math.floor(bridgeLink.phase);
        for(int i=0;i<arrows;i++){
            float f=(i+.5f+phase)/(arrows+1f);
            streamAtlas.top(bridgeLink.arrow,sx+(ex-sx)*f,h+2*(float)RenderConfig.ATLAS_SURFACE_OFFSET+thickness*.5f,
                sz+(ez-sz)*f,width*.65f,width*.65f,angle,1,1,1,false);
        }
        return true;
    }

    public void setSelection(int tileX, int tileY, int size, boolean valid) {
        selectionX = tileX; selectionY = tileY; selectionSize = Math.max(1, size);
        selectionValid = valid; hasSelection = true;
        selectionBlock=null;
    }
    public void setSelection(int tileX,int tileY,Block block,int rotation,boolean valid){
        setSelection(tileX,tileY,block==null?1:block.size,valid);selectionBlock=block;selectionRotation=rotation;
    }

    public void clearSelection() { hasSelection = false; }

    /** Projects game-world XY to bottom-left screen coordinates for native configuration popups. */
    public boolean project(Camera3D camera, float worldX, float worldY, Vec2 out) {
        Tile tile = Vars.world == null ? null : Vars.world.tileWorld(worldX, worldY);
        float height = (float)RenderConfig.PROJECT_HEIGHT_OFFSET + visibleSurfaceHeight(tile);
        projectionPoint.set(worldX * WORLD_SCALE, height, -worldY * WORLD_SCALE);
        float forward = (projectionPoint.x - camera.position.x) * camera.direction.x +
            (projectionPoint.y - camera.position.y) * camera.direction.y +
            (projectionPoint.z - camera.position.z) * camera.direction.z;
        if (forward <= camera.near) return false;
        camera.update();
        camera.project(projectionPoint);
        out.set(projectionPoint.x, projectionPoint.y);
        return true;
    }

    /** More precise optional event hook. Multiblock footprints can cross a chunk boundary. */
    public void invalidate(Tile tile) {
        if (tile == null) { invalidate(); return; }
        updatePlacedBuilding(tile);
        elevation.invalidate(tile);
        int radius = Math.max(1, tile.block().size);
        for (int x = Math.max(0, tile.x - radius) / CHUNK; x <= (tile.x + radius) / CHUNK; x++) {
            for (int y = Math.max(0, tile.y - radius) / CHUNK; y <= (tile.y + radius) / CHUNK; y++) {
                ChunkMesh cached = chunks.get(key(x, y));
                if (cached != null) cached.revision = -1;
                coarseFog.remove(key(x, y));
            }
        }
    }

    public void render(Camera3D camera) {
        buildingFrameCompleted = false;
        submittedBuildings.clear();
        submittedBridges.clear();
        submittedUnits.clear();submittedUnitBodies.clear();forceFields.clear();projectedWalls.clear();forceProjectorRenderer.clear();supportEffects.clearFrame();
        if (disposed || Vars.world == null || Vars.state == null || !Vars.state.isGame() || Vars.player == null) return;
        if (Vars.world.width() == 0 || Vars.world.height() == 0) return;
        long frameStarted = System.nanoTime();
        this.camera = camera;
        syncWorld();
        if (shader == null) {
            shader = new Shader(VERTEX, FRAGMENT);
            atlasShader = new Shader(ATLAS_VERTEX, ATLAS_FRAGMENT);
            dynamicMesh = new Mesh(false, dynamic.maxVertices, 0, VertexAttribute.position3, COLOR);
        }
        Draw.flush();
        constructionRenderer.prepareDisplays();
        boolean depth = Gl.isEnabled(Gl.depthTest), blend = Gl.isEnabled(Gl.blend);
        boolean cull = Gl.isEnabled(Gl.cullFace), scissor = Gl.isEnabled(Gl.scissorTest);
        boolean stencil = Gl.isEnabled(Gl.stencilTest);
        boolean depthWrite = Gl.getInt(Gl.depthWritemask) != 0;
        int depthFunction = Gl.getInt(Gl.depthFunc), oldProgram = Gl.getInt(Gl.currentProgram);
        int oldActiveTexture = Gl.getInt(Gl.activeTexture);
        Gl.activeTexture(Gl.texture0);
        int oldTexture0 = Gl.getInt(Gl.textureBinding2d);
        savedClearColor.clear();
        Gl.getFloatv(Gl.colorClearValue, savedClearColor);
        savedViewport.clear();
        Gl.getIntegerv(Gl.viewport, savedViewport);
        boolean bufferBegun = false;
        try {
            // DesktopLauncher requests no default depth attachment. Own a depth-buffered target.
            int width = Math.max(2, savedViewport.get(2)), height = Math.max(2, savedViewport.get(3));
            if (sceneBuffer == null || sceneBuffer.getWidth()!=width || sceneBuffer.getHeight()!=height) {
                if (sceneBuffer!=null) sceneBuffer.dispose();
                sceneBuffer=createSceneBuffer(width,height);
            }
            sceneBuffer.begin();
            bufferBegun = true;
            Gl.disable(Gl.scissorTest);
            Gl.disable(Gl.stencilTest);
            Gl.disable(Gl.blend);
            Gl.disable(Gl.cullFace);
            Gl.enable(Gl.depthTest);
            Gl.depthMask(true);
            Gl.depthFunc(Gl.lequal);
            Color sky=environment.skyColor();
            Gl.clearColor(sky.r, sky.g, sky.b, 1f);
            Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
            camera.update();
            long profiled=CameraPerformanceProfile.start();
            environment.update(camera);
            environment.renderBackground(camera);
            CameraPerformanceProfile.end(Part.ENVIRONMENT,profiled);
            Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.depthFunc(Gl.lequal);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);
            shader.bind();
            shader.setUniformMatrix4("u_combined", camera.combined.val);
            shader.setUniformf("u_camera", camera.position);
            shader.setUniformf("u_ambient", environment.ambient());
            atlasShader.bind();
            atlasShader.setUniformMatrix4("u_combined",camera.combined.val);
            atlasShader.setUniformf("u_offset",0,0,0);
            atlasShader.setUniformi("u_texture",0);
            atlasShader.setUniformf("u_ambient",environment.ambient());
            atlasShader.setUniformf("u_time",Time.time/60f);
            atlasShader.setUniformf("u_water",(float)RenderConfig.WATER_RIPPLE_STRENGTH,(float)RenderConfig.WATER_RIPPLE_SPEED);
            atlasShader.setUniformf("u_cutoff",(float)RenderConfig.ATLAS_ALPHA_CUTOFF);
            streamAtlas.overflowShader=atlasShader;
            streamAtlas.clear();
            dynamic.clear();
            constructionVisuals.begin();
            profiled=CameraPerformanceProfile.start();
            renderTerrain();
            CameraPerformanceProfile.end(Part.TERRAIN,profiled);
            profiled=CameraPerformanceProfile.start();
            renderPlacedBuildings();
            CameraPerformanceProfile.end(Part.BUILDINGS,profiled);
            profiled=CameraPerformanceProfile.start();
            constructionRenderer.render(camera,atlasShader);
            CameraPerformanceProfile.end(Part.LAYERS,profiled);
            profiled=CameraPerformanceProfile.start();
            extrusion.begin();aircraftWeapons.begin();
            boolean groundActive=unitAltitudes!=null&&unitAltitudes.groundFamily()!=null;
            if(groundActive)groundFamily.beginFrame();
            try{for (Unit unit : Groups.unit) renderUnit(unit);}finally{if(groundActive)groundFamily.endFrame();extrusion.end();}
            CameraPerformanceProfile.end(Part.UNITS,profiled);
            renderPlans();
            renderBuildBeams();
            profiled=CameraPerformanceProfile.start();
            dynamic.emissive=true;
            try{
                combatVisuals.render(camera,combat,unitAltitudes,this::surfaceHeight,combatSink);
                supportEffects.render(camera,placedBuildings.values(),unitAltitudes,this::surfaceHeight,combatSink);
                miningVisuals.render(camera,Groups.unit,unitAltitudes,this::surfaceHeight,combatSink);
                creatorSubmitted.clear();
                if(unitAltitudes!=null)for(Unit unit:Groups.unit)if(creatorFlight.test(unit)&&!unit.inFogTo(Vars.player.team())&&creatorVisuals.render(unit,unitAltitudes.unitState(unit),camera,combatSink))creatorSubmitted.add(unit.id);
                renderExplosions();
            }finally{dynamic.emissive=false;}
            flushDynamic();
            streamAtlas.drawAndClear(atlasShader);
            CameraPerformanceProfile.end(Part.VFX,profiled);
            profiled=CameraPerformanceProfile.start();
            projectedWalls.render(camera,projectedDefense,sceneBuffer);
            CameraPerformanceProfile.end(Part.PROJECTIONS,profiled);
            profiled=CameraPerformanceProfile.start();
            forceProjectorRenderer.render(camera,forceProjectors,constructionRuntime);
            forceFields.render(camera,unitAltitudes);
            CameraPerformanceProfile.end(Part.FIELDS,profiled);
            profiled=CameraPerformanceProfile.start();
            threatMarkers.render(camera,unitAltitudes,sceneBuffer,showControlledUnit);
            CameraPerformanceProfile.end(Part.MARKERS,profiled);
            constructionRenderer.renderOverlay(camera);
            constructionRenderer.renderInteraction(camera);
            constructionVisuals.draw(camera);
            profiled=CameraPerformanceProfile.start();
            firstPersonRig.render(camera,Vars.player.unit(),environment.ambient(),firstPersonActive);
            CameraPerformanceProfile.end(Part.VIEWMODEL,profiled);
            sceneBuffer.end();
            bufferBegun = false;
            Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
            Gl.disable(Gl.depthTest);
            Gl.depthMask(false);
            sceneBuffer.blit(Shaders.screenspace);
            buildingFrameCompleted = true;
        } finally {
            if (bufferBegun) sceneBuffer.end();
            Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
            Gl.depthMask(depthWrite);
            Gl.depthFunc(depthFunction);
            enabled(Gl.depthTest, depth);
            enabled(Gl.blend, blend);
            enabled(Gl.cullFace, cull);
            enabled(Gl.scissorTest, scissor);
            enabled(Gl.stencilTest, stencil);
            Gl.clearColor(savedClearColor.get(0), savedClearColor.get(1), savedClearColor.get(2), savedClearColor.get(3));
            Gl.activeTexture(Gl.texture0);
            Gl.bindTexture(Gl.texture2d, oldTexture0);
            Gl.activeTexture(oldActiveTexture);
            Gl.useProgram(oldProgram);
            cpuMilliseconds = (System.nanoTime() - frameStarted) / 1_000_000.0;
        }
    }

    private void syncWorld() {
        if(vegetationContent!=Vars.content){
            vegetationContent=Vars.content;vegetationHeight=vegetationPadding=0;
            for(Block block:Vars.content.blocks())if(VegetationVisualScale.scale(block)>1){
                vegetationHeight=Math.max(vegetationHeight,VegetationVisualScale.height(block,heightFor(block)));
                vegetationPadding=Math.max(vegetationPadding,VegetationVisualScale.extentPadding(block));
            }
        }
        elevation.ensureWorld();
        if(terrainRevision!=elevation.revision()){terrainRevision=elevation.revision();revision++;coarseFog.clear();}
        if (worldTiles != Vars.world.tiles || worldWidth != Vars.world.width() || worldHeight != Vars.world.height()) {
            clearChunks();
            worldTiles = Vars.world.tiles;
            worldWidth = Vars.world.width();
            worldHeight = Vars.world.height();
            revision++;
        }
        if (viewerTeam != Vars.player.team().id) {
            viewerTeam = Vars.player.team().id;
            invalidate();
        }
        boolean fogEnabled = Vars.state.rules.fog && Vars.state.rules.staticFog;
        Object data = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Vars.player.team());
        // FogControl.resetFog() replaces its discovered Bits; old revealed geometry must not survive it.
        if (fogEnabled != staticFogEnabled || data != discoveryData) {
            staticFogEnabled = fogEnabled;
            discoveryData = data;
            invalidate();
        }
    }

    private void renderTerrain() {
        visibleChunks.clear(); pinnedChunks.clear();
        detailedChunks = coarseChunks = 0;
        coarseFogPruned = false;
        for (int cy=0;cy*CHUNK<worldHeight;cy++)for(int cx=0;cx*CHUNK<worldWidth;cx++) {
            float x=cx*CHUNK+(CHUNK-1)*.5f,z=-cy*CHUNK-(CHUNK-1)*.5f;
            if(!chunkVisible(cx,cy))continue;
            float dx=x-camera.position.x,dz=z-camera.position.z;
            visibleChunks.add(new Candidate(cx,cy,dx*dx+dz*dz));
        }
        visibleChunks.sort(Comparator.comparingDouble(c->c.distance2));
        // Conservative worst-case chunk byte estimate prevents selected visible chunks exceeding the budget.
        int detailLimit=Math.max(1,Math.min(MAX_CACHED_CHUNKS,(int)(RenderConfig.DETAIL_CACHE_BYTES/(CHUNK*CHUNK*48L*9*4))));
        for(Candidate c:visibleChunks)if(pinnedChunks.size()<detailLimit&&c.distance2<DETAIL_DISTANCE*DETAIL_DISTANCE)pinnedChunks.add(key(c.x,c.y));
        int uploads=(int)RenderConfig.DETAIL_UPLOADS_PER_FRAME;
        int stride=(int)RenderConfig.OVERVIEW_MIN_STRIDE;
        while((long)worldWidth*worldHeight/(stride*stride)>RenderConfig.OVERVIEW_MAX_CELLS)stride*=2;
        stride=Math.min(CHUNK,stride);
        for(Candidate c:visibleChunks){
            int key=key(c.x,c.y);
            ChunkMesh chunk=chunks.get(key);
            if(chunk!=null&&Vars.state.rules.fog&&Time.timeSinceMillis(chunk.fogChecked)>RenderConfig.FOG_REFRESH_MILLIS){
                long hash=discoveryHash(c.x,c.y);
                if(hash!=chunk.fogHash)chunk.revision=-1;
                chunk.fogChecked=Time.millis();
            }
            if(chunk!=null&&chunk.revision!=revision){
                cachedBytes-=chunk.bytes;chunk.dispose();chunks.remove(key);chunk=null;
            }
            if(pinnedChunks.contains(key)&&chunk==null&&uploads>0){
                evictInactive(detailLimit-1);
                if(chunks.size()<detailLimit){
                    chunk=buildChunk(c.x,c.y);chunks.put(key,chunk);cachedBytes+=chunk.bytes;uploads--;
                }
            }
            if(chunk!=null&&pinnedChunks.contains(key)){
                detailedChunks++;
                shader.bind();
                if(chunk.mesh!=null)chunk.mesh.render(shader,Gl.triangles);
                if(chunk.atlas!=null)chunk.atlas.draw(atlasShader);
            }else{
                coarseChunks++;
                // Reusable streaming buffers give full-map coverage without a far-chunk allocation/eviction loop.
                coarseChunk(c.x,c.y,stride);
            }
        }
        evictInactive(detailLimit);
        flushDynamic();streamAtlas.drawAndClear(atlasShader);
    }

    private void evictInactive(int target){
        Iterator<Map.Entry<Integer,ChunkMesh>> it=chunks.entrySet().iterator();
        while(it.hasNext()&&(chunks.size()>target||cachedBytes>RenderConfig.DETAIL_CACHE_BYTES)){
            var entry=it.next();
            if(pinnedChunks.contains(entry.getKey()))continue;
            cachedBytes-=entry.getValue().bytes;entry.getValue().dispose();it.remove();
        }
    }

    private ChunkMesh buildChunk(int cx, int cy) {
        terrain.clear();staticAtlas.clear();
        for (int y = cy * CHUNK; y < Math.min(worldHeight, (cy + 1) * CHUNK); y++) {
            for (int x = cx * CHUNK; x < Math.min(worldWidth, (cx + 1) * CHUNK); x++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;
                if (!discovered(tile)) {
                    terrain.top(x, 0, -y, 1, 1, (float)RenderConfig.UNDISCOVERED_R,
                        (float)RenderConfig.UNDISCOVERED_G, (float)RenderConfig.UNDISCOVERED_B);
                    continue;
                }
                color.set(tile.floor().mapColor);
                TextureRegion floor=NativeRegions.floor(tile.floor(),tile);
                if(NativeAtlasMesh.valid(floor))staticAtlas.top(floor,x,0,-y,1,1,0,1,1,1,tile.floor().isLiquid);
                else terrain.top(x,0,-y,1,1,color.r,color.g,color.b);
                Block block = tile.block();
                if(elevation.naturalWall(tile)){
                    terrainTile(tile,staticAtlas,terrain);
                    continue;
                }
                if (tile.build == null && block != mindustry.content.Blocks.air && tile.isCenter()) {
                    float h = heightFor(block);
                    if(!organicProps.append(tile,staticAtlas,x,-y,block.size,h))texturedWall(tile,staticAtlas,terrain,x,-y,block.size,h,true);
                }
                if(tile.overlay()!=mindustry.content.Blocks.air){
                    float height=tile.overlay().wallOre?heightFor(block):0;
                    staticAtlas.top(NativeRegions.floor(tile.overlay(),tile),x,height+(float)RenderConfig.ATLAS_SURFACE_OFFSET,-y,1,1,0,1,1,1,false);
                }
            }
        }
        Mesh mesh=null;
        if(terrain.count>0){mesh=new Mesh(true,terrain.count/7,0,VertexAttribute.position3,COLOR);mesh.setVertices(terrain.vertices,0,terrain.count);}
        return new ChunkMesh(mesh,staticAtlas.upload(),revision,(long)terrain.count*4,discoveryHash(cx,cy));
    }

    private void coarseChunk(int cx,int cy,int stride){
        int xEnd=Math.min(worldWidth,(cx+1)*CHUNK),yEnd=Math.min(worldHeight,(cy+1)*CHUNK);
        CoarseFogMask visibility=staticFogEnabled?coarseFogMask(cx,cy,stride):null;
        for(int y=cy*CHUNK;y<yEnd;y+=stride)for(int x=cx*CHUNK;x<xEnd;x+=stride){
            int width=Math.min(stride,xEnd-x),depth=Math.min(stride,yEnd-y);
            float centerX=x+(width-1)*.5f,centerZ=-y-(depth-1)*.5f;
            boolean known=!staticFogEnabled || (visibility!=null && visibility.known[(y-cy*CHUNK)/stride*visibility.side+(x-cx*CHUNK)/stride]);
            if(!known){dynamic.top(centerX,0,centerZ,width,depth,(float)RenderConfig.UNDISCOVERED_R,(float)RenderConfig.UNDISCOVERED_G,(float)RenderConfig.UNDISCOVERED_B);continue;}
            Tile sample=Vars.world.tile(x+width/2,y+depth/2);
            // Preserve real slopes and narrow mountain edges, even in the distant tier.
            boolean hasMountain=false;
            for(int yy=y;yy<y+depth&&!hasMountain;yy++)for(int xx=x;xx<x+width;xx++)if(elevation.naturalWall(xx,yy)){hasMountain=true;break;}
            if(hasMountain){
                for(int yy=y;yy<y+depth;yy++)for(int xx=x;xx<x+width;xx++){
                    Tile piece=Vars.world.tile(xx,yy);
                    if(elevation.naturalWall(piece))terrainTile(piece,streamAtlas,dynamic);
                    else streamAtlas.top(NativeRegions.floor(piece.floor(),piece),xx,0,-yy,1,1,0,1,1,1,piece.floor().isLiquid);
                }
                continue;
            }
            TextureRegion floor=NativeRegions.floor(sample.floor(),sample);
            if(NativeAtlasMesh.valid(floor))streamAtlas.top(floor,centerX,0,centerZ,width,depth,0,1,1,1,sample.floor().isLiquid);
            else{color.set(sample.floor().mapColor);dynamic.top(centerX,0,centerZ,width,depth,color.r,color.g,color.b);}
            if(sample.build==null&&(sample.block().solid||fpv.world.NaturalProps.supported(sample.block()))){
                if(!organicProps.append(sample,streamAtlas,sample.x,-sample.y,sample.block().size,heightFor(sample.block())))
                    texturedWall(sample,streamAtlas,dynamic,centerX,centerZ,Math.min(width,depth),heightFor(sample.block()),false);
            }
        }
    }

    private boolean chunkVisible(int cx,int cy){
        float h=Math.max(Math.max(6,vegetationHeight),elevation.maxHeight());
        return camera.frustum.containsBounds(cx*CHUNK+(CHUNK-1)*.5f,h/2,-cy*CHUNK-(CHUNK-1)*.5f,CHUNK*.5f+vegetationPadding,h/2,CHUNK*.5f+vegetationPadding);
    }

    private void terrainTile(Tile tile,NativeAtlasMesh.Builder atlas,Geometry fallback){
        float sw=elevation.tileCornerHeight(tile.x,tile.y,0),se=elevation.tileCornerHeight(tile.x,tile.y,1);
        float ne=elevation.tileCornerHeight(tile.x,tile.y,2),nw=elevation.tileCornerHeight(tile.x,tile.y,3);
        TextureRegion region=NativeRegions.wall(tile.block(),tile);
        float shade=Mathf.clamp(.88f+(sw+nw-se-ne)*.09f+(sw+se-ne-nw)*.055f,.56f,1.13f);
        atlas.terrainTop(region,tile.x,-tile.y,1,1,sw,se,ne,nw,shade);
        if(tile.overlay().wallOre)atlas.terrainTop(NativeRegions.floor(tile.overlay(),tile),tile.x,-tile.y,1,1,sw+.02f,se+.02f,ne+.02f,nw+.02f,shade);
        float l=tile.x-.5f,r=tile.x+.5f,n=-tile.y-.5f,s=-tile.y+.5f;
        if(!elevation.naturalWall(tile.x,tile.y-1))atlas.face(region,l,sw,s,r,se,s,r,0,s,l,0,s,.65f);
        if(!elevation.naturalWall(tile.x+1,tile.y))atlas.face(region,r,se,s,r,ne,n,r,0,n,r,0,s,.75f);
        if(!elevation.naturalWall(tile.x,tile.y+1))atlas.face(region,r,ne,n,l,nw,n,l,0,n,r,0,n,.8f);
        if(!elevation.naturalWall(tile.x-1,tile.y))atlas.face(region,l,nw,n,l,sw,s,l,0,s,l,0,n,.58f);
    }

    /** Conservative per-cell discovery, cached separately from the streamed coarse geometry. */
    private CoarseFogMask coarseFogMask(int cx,int cy,int stride){
        int id=key(cx,cy);
        CoarseFogMask mask=coarseFog.get(id);
        if(mask!=null && mask.stride!=stride){coarseFog.remove(id);mask=null;}
        if(mask==null){
            int limit=(int)RenderConfig.COARSE_FOG_CACHE_CHUNKS;
            if(coarseFog.size()>=limit && !coarseFogPruned){
                // Sweep only once when admitting a new mask; never churn visible chunks at capacity.
                coarseFogPruned=true;
                coarseFog.keySet().removeIf(k->!chunkVisible(k&0xffff,k>>>16));
            }
            if(coarseFog.size()>=limit)return null;
            mask=new CoarseFogMask(stride);
            coarseFog.put(id,mask);
        }
        long now=Time.millis();
        if(mask.checked==0 || now-mask.checked>=RenderConfig.FOG_REFRESH_MILLIS){
            int xEnd=Math.min(worldWidth,(cx+1)*CHUNK), yEnd=Math.min(worldHeight,(cy+1)*CHUNK);
            for(int y=cy*CHUNK;y<yEnd;y+=stride)for(int x=cx*CHUNK;x<xEnd;x+=stride){
                boolean known=true;
                outer:for(int yy=y;yy<Math.min(y+stride,yEnd);yy++)for(int xx=x;xx<Math.min(x+stride,xEnd);xx++){
                    if(!discovered(Vars.world.tile(xx,yy))){known=false;break outer;}
                }
                mask.known[(y-cy*CHUNK)/stride*mask.side+(x-cx*CHUNK)/stride]=known;
            }
            mask.checked=now;
        }
        return mask;
    }

    private void texturedWall(Tile tile,NativeAtlasMesh.Builder atlas,Geometry fallback,float x,float z,float size,float h,boolean hideInternal){
        TextureRegion region=NativeRegions.wall(tile.block(),tile);
        if(!NativeAtlasMesh.valid(region)){color.set(tile.block().mapColor);ensureColor(color);fallback.box(x,h/2,z,size,h,size,0,color.r,color.g,color.b);return;}
        float half=size*.5f;
        atlas.top(region,x,h,z,size,size,0,1,1,1,false);
        if(!hideInternal||wallOpen(tile,0,1,h))atlas.face(region,x-half,h,z-half,x+half,h,z-half,x+half,0,z-half,x-half,0,z-half,(float)RenderConfig.FACE_FRONT_SHADE);
        if(!hideInternal||wallOpen(tile,1,0,h))atlas.face(region,x+half,h,z-half,x+half,h,z+half,x+half,0,z+half,x+half,0,z-half,(float)RenderConfig.FACE_RIGHT_SHADE);
        if(!hideInternal||wallOpen(tile,0,-1,h))atlas.face(region,x+half,h,z+half,x-half,h,z+half,x-half,0,z+half,x+half,0,z+half,(float)RenderConfig.FACE_BACK_SHADE);
        if(!hideInternal||wallOpen(tile,-1,0,h))atlas.face(region,x-half,h,z+half,x-half,h,z-half,x-half,0,z-half,x-half,0,z+half,(float)RenderConfig.FACE_LEFT_SHADE);
    }
    private boolean wallOpen(Tile tile,int dx,int dy,float height){
        Tile neighbor=Vars.world.tile(tile.x+dx,tile.y+dy);
        return neighbor==null||neighbor.build!=null||!neighbor.block().solid||!discovered(neighbor)||heightFor(neighbor.block())<height;
    }
    private long discoveryHash(int cx,int cy){
        if(!Vars.state.rules.fog||!Vars.state.rules.staticFog)return 0;
        long hash=1469598103934665603L;
        for(int y=cy*CHUNK;y<Math.min(worldHeight,(cy+1)*CHUNK);y++)for(int x=cx*CHUNK;x<Math.min(worldWidth,(cx+1)*CHUNK);x++)hash=(hash^(discovered(Vars.world.tile(x,y))?1:0))*1099511628211L;
        return hash;
    }

    private boolean discovered(Tile tile) {
        return !Vars.state.rules.fog || Vars.fogControl == null || Vars.fogControl.isDiscovered(Vars.player.team(), tile.x, tile.y);
    }

    /** UI and plan outlines must not reveal the height of a hidden enemy building. */
    private float visibleSurfaceHeight(Tile tile) {
        if (tile == null || Vars.player == null || !discovered(tile) ||
            (tile.build != null && tile.build.inFogTo(Vars.player.team()))) return 0;
        return elevation.naturalWall(tile)?(float)elevation.heightAt(tile.x,-tile.y):heightFor(tile.block());
    }

    private boolean renderBuilding(Building b) {
        float x = b.x * WORLD_SCALE, z = -b.y * WORLD_SCALE;
        if (!near(x, z, b.block.size) || b.inFogTo(Vars.player.team())) return false;
        float size = b.block.size * (float)RenderConfig.BUILD_FOOTPRINT_SCALE, h = heightFor(b.block);
        if(b.block instanceof mindustry.world.blocks.defense.Wall){
            texturedWall(b.tile,streamAtlas,dynamic,x,z,size,h,false);
            return true;
        }
        if(camera.position.dst2(x,h*.5f,z)>DETAIL_DISTANCE*DETAIL_DISTANCE){
            renderDistantBuilding(b,x,z,size,h);
            return true;
        }
        if(b instanceof Turret.TurretBuild turret&&turretModels.render(turret,streamAtlas,x,0,z,h,
            constructionRuntime==null?0:(float)constructionRuntime.turretControls().pitch(turret)))return true;
        if (b.block instanceof CoreBlock) {
            renderCore(x, z, size, h, b.team.color);
            // Native core art belongs above its chamber, not hidden on the low plinth.
            float roofHeight = h * (float)RenderConfig.CORE_ROOF_HEIGHT_FRACTION;
            float thickness = h * (float)RenderConfig.CORE_ROOF_THICKNESS_FRACTION;
            dynamic.box(x, roofHeight-thickness/2, z, size, thickness, size, 0,
                (float)RenderConfig.CORE_METAL_R, (float)RenderConfig.CORE_METAL_G, (float)RenderConfig.CORE_METAL_B);
            streamAtlas.top(NativeRegions.block(b.block),x,roofHeight+(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,0,1,1,1,false);
            if (b.block.teamRegions != null && b.team.id < b.block.teamRegions.length) {
                TextureRegion teamRegion = b.block.teamRegions[b.team.id];
                Color tint = teamRegion == b.block.teamRegion ? b.team.color : Color.white;
                streamAtlas.top(teamRegion,x,roofHeight+2*(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,0,tint.r,tint.g,tint.b,false);
            }
            return true;
        }
        boolean detailedFamily=b.block instanceof Drill||b.block instanceof GenericCrafter||b.block instanceof PowerNode||b.block instanceof Turret;
        float baseHeight=detailedFamily?h*.55f:h;
        color.set(b.block.mapColor);
        ensureColor(color);
        color.lerp(b.team.color, (float)RenderConfig.BUILD_TEAM_TINT);
        dynamic.box(x, baseHeight / 2, z, size, baseHeight, size, 0, color.r, color.g, color.b);
        if(blockAnimations.render(b,streamAtlas,x,0,z,size,h))return true;
        Color team = b.team.color;
        float accentSize = size * (float)RenderConfig.BUILD_ACCENT_SCALE;
        float accentBrightness = (float)RenderConfig.BUILD_ACCENT_BRIGHTNESS;
        dynamic.top(x, baseHeight + (float)RenderConfig.BUILD_ACCENT_HEIGHT, z, accentSize, accentSize,
            team.r * accentBrightness, team.g * accentBrightness, team.b * accentBrightness);
        TextureRegion roof=NativeRegions.block(b.block);
        float roofAngle=b.block.rotate&&b.block.rotateDraw?-b.rotation*90f:0;
        float roofWidth=size,roofDepth=size;
        if (b instanceof Turret.TurretBuild turret) {
            float angle = turret.rotation;
            float dx = Mathf.cosDeg(angle), dz = -Mathf.sinDeg(angle);
            dynamic.box(x, h*.74f, z, size * .55f, h*.36f, size * .55f, -angle,
                .36f, .42f, .48f);
            dynamic.box(x + dx * size * .4f, h*.84f, z + dz * size * .4f,
                size * .78f, h*.14f, size * .18f, -angle, .65f, .7f, .74f);
            if(((Turret)b.block).drawer instanceof DrawTurret drawer&&NativeAtlasMesh.valid(drawer.base))roof=drawer.base;
            streamAtlas.top(b.block.region,x+turret.recoilOffset.x*WORLD_SCALE,h+(float)RenderConfig.ATLAS_SURFACE_OFFSET,
                z-turret.recoilOffset.y*WORLD_SCALE,size,size,90-angle,1,1,1,false);
        } else if(b instanceof Drill.DrillBuild drill){
            Drill type=(Drill)b.block;
            float head=size*(float)RenderConfig.DRILL_HEAD_WIDTH;
            dynamic.box(x,h*.77f,z,head,h*.4f,head,-drill.timeDrilled*type.rotateSpeed,.42f,.45f,.5f);
            streamAtlas.top(type.rotatorRegion,x,h+(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,-drill.timeDrilled*type.rotateSpeed,1,1,1,false);
            streamAtlas.top(type.topRegion,x,h+2*(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,0,1,1,1,false);
        } else if(b instanceof GenericCrafter.GenericCrafterBuild factory){
            float vent=size*(float)RenderConfig.FACTORY_VENT_WIDTH,ventH=h*(float)RenderConfig.FACTORY_VENT_HEIGHT;
            dynamic.box(x-size*.28f,baseHeight+ventH/2,z-size*.25f,vent,ventH,vent,0,.32f,.36f,.41f);
            dynamic.box(x+size*.28f,baseHeight+ventH/2,z-size*.25f,vent,ventH,vent,0,.32f,.36f,.41f);
            float glow=.18f+factory.warmup*.65f;
            dynamic.top(x,baseHeight+.035f,z+size*.22f,size*.42f,size*.21f,glow,glow*.72f,.15f);
        } else if(b.block instanceof PowerNode){
            dynamic.box(x,h*.75f,z,size*.17f,h*.48f,size*.17f,0,.65f,.68f,.74f);
            dynamic.box(x,h*.91f,z,size*.58f,h*.09f,size*.09f,0,team.r,team.g,team.b);
            if(b.power!=null)for(int i=0;i<b.power.links.size;i++){
                Building other=Vars.world.build(b.power.links.get(i));
                if(other==null||other.inFogTo(Vars.player.team())||(other.block instanceof PowerNode&&other.id>=b.id))continue;
                float otherX=other.x*WORLD_SCALE,otherZ=-other.y*WORLD_SCALE;
                dynamic.beam(x,h, z,otherX,heightFor(other.block),otherZ,(float)RenderConfig.POWER_LINK_WIDTH,team.r,team.g,team.b);
            }
        }
        streamAtlas.top(roof,x,baseHeight+(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,roofWidth,roofDepth,roofAngle,1,1,1,false);
        if(b.block.teamRegions!=null&&b.team.id<b.block.teamRegions.length)
            streamAtlas.top(b.block.teamRegions[b.team.id],x,baseHeight+2*(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,roofAngle,1,1,1,false);
        // Direction indicator reads the real building rotation, useful on belts and factories.
        if (b.block.rotate) {
            float dx = Mathf.cosDeg(b.rotation * 90f), dz = -Mathf.sinDeg(b.rotation * 90f);
            dynamic.box(x + dx * size * .32f, baseHeight + .03f, z + dz * size * .32f,
                size * .13f, .035f, size * .13f, 0, .9f, .83f, .52f);
        }
        return true;
    }

    /** Every visible distant building keeps its native material, footprint and orientation. */
    private void renderDistantBuilding(Building b,float x,float z,float size,float height){
        color.set(b.block.mapColor);ensureColor(color);
        color.lerp(b.team.color,(float)RenderConfig.BUILD_TEAM_TINT);
        dynamic.box(x,height/2,z,size,height,size,0,color.r,color.g,color.b);
        if(blockAnimations.render(b,streamAtlas,x,0,z,size,height))return;
        TextureRegion region=NativeRegions.block(b.block);
        float angle=b.block.rotate&&b.block.rotateDraw?-b.rotation*90f:0;
        float width=size,depth=size;
        if(b instanceof Conveyor.ConveyorBuild belt){
            region=((Conveyor)b.block).regions[belt.blendbits][0];
            width*=belt.blendsclx;depth*=belt.blendscly;
        }else if(b instanceof Turret.TurretBuild turret){
            angle=90-turret.rotation;
        }
        streamAtlas.top(region,x,height+(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,width,depth,angle,1,1,1,false);
        if(b.block.teamRegions!=null && b.team.id<b.block.teamRegions.length){
            TextureRegion teamRegion=b.block.teamRegions[b.team.id];
            Color tint=teamRegion==b.block.teamRegion?b.team.color:Color.white;
            streamAtlas.top(teamRegion,x,height+2*(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,size,size,angle,tint.r,tint.g,tint.b,false);
        }
    }

    /** Distinctive original low-poly core silhouette; all detail fits its collision/picking height. */
    private void renderCore(float x, float z, float size, float height, Color team) {
        float baseHeight = height * (float)RenderConfig.CORE_PLINTH_HEIGHT_FRACTION;
        float chamberWidth = size * (float)RenderConfig.CORE_CHAMBER_WIDTH_FRACTION;
        float chamberHeight = height * (float)RenderConfig.CORE_CHAMBER_HEIGHT_FRACTION;
        float pylonWidth = size * (float)RenderConfig.CORE_PYLON_WIDTH_FRACTION;
        float pylonHeight = height * (float)RenderConfig.CORE_PYLON_HEIGHT_FRACTION;
        float offset = size * (float)RenderConfig.CORE_PYLON_OFFSET_FRACTION;
        float panel = size * (float)RenderConfig.CORE_PANEL_WIDTH_FRACTION;
        float mr = (float)RenderConfig.CORE_METAL_R, mg = (float)RenderConfig.CORE_METAL_G, mb = (float)RenderConfig.CORE_METAL_B;
        float dark = (float)RenderConfig.CORE_DARK_FRACTION, surfaceOffset = (float)RenderConfig.BUILD_ACCENT_HEIGHT;
        dynamic.box(x, baseHeight / 2, z, size, baseHeight, size, 0, mr * dark, mg * dark, mb * dark);
        dynamic.box(x, baseHeight + chamberHeight / 2, z, chamberWidth, chamberHeight, chamberWidth,
            0, team.r * dark, team.g * dark, team.b * dark);
        float glowWidth = size * (float)RenderConfig.CORE_GLOW_FRACTION;
        dynamic.top(x, baseHeight + chamberHeight + surfaceOffset, z, glowWidth, glowWidth, team.r, team.g, team.b);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float px = x + sx * offset, pz = z + sz * offset;
                dynamic.box(px, baseHeight + pylonHeight / 2, pz, pylonWidth, pylonHeight, pylonWidth,
                    0, mr, mg, mb);
                dynamic.top(px, baseHeight + pylonHeight + surfaceOffset, pz, panel, panel, team.r, team.g, team.b);
            }
        }
    }

    private void renderUnit(Unit unit) {
        boolean controlled=unit==Vars.player.unit();
        if ((controlled&&!showControlledUnit&&!firstPersonActive) || unit.inFogTo(Vars.player.team())) return;
        float x = unit.x * WORLD_SCALE, z = -unit.y * WORLD_SCALE;
        float size = Math.max((float)RenderConfig.UNIT_MIN_SIZE, unit.hitSize * WORLD_SCALE * (float)RenderConfig.UNIT_HIT_SIZE_SCALE);
        float h = (float)(RenderConfig.UNIT_GROUND_OFFSET + size * RenderConfig.UNIT_HEIGHT_PER_SIZE + unit.elevation * RenderConfig.UNIT_FLIGHT_HEIGHT);
        boolean family=unitAltitudes!=null&&unitAltitudes.groundFamily()!=null&&GroundFamilyRenderer.supportsVisual(unit);
        var pose=unitAltitudes==null?null:unitAltitudes.pose(unit);
        var controlledPose=(controlled||family&&Vars.net.client())&&unitAltitudes!=null?unitAltitudes.unitState(unit):null;
        if(pose!=null)h=(float)pose.height();
        else h+=elevation.heightAt(x,z);
        if(controlledPose!=null){x=(float)controlledPose.x();h=(float)controlledPose.z();z=(float)-controlledPose.y();}
        float yaw=controlledPose!=null?(float)controlledPose.yaw():pose==null?unit.rotation:(float)pose.yaw();
        float pitch=controlledPose!=null?(float)controlledPose.pitch():pose==null?0:(float)pose.pitch();
        float roll=controlledPose!=null?(float)controlledPose.roll():pose==null?0:(float)pose.roll();
        float radius=Math.max(size,SpriteExtrusionRenderer.bodyRadius(unit));
        if(family){
            radius=Math.max(radius,(float)GroundFamilyRenderer.horizontalRadius(unit));
            float low=Math.min(h,(float)elevation.heightAt(x,z)),high=h+Math.max(radius,(float)(GroundFamilyRenderer.profileTop(unit,pitch,roll)+fpv.combat.MechWeaponAim3D.upperEnvelope(unit)));
            if("vela".equals(unit.type.name))radius=Math.max(radius,(float)VelaTripodLayout.cullingRadius(h-low,unit.hitSize*WORLD_SCALE*.5,fpv.units.ground.NativeMechLayout.spread(unit.type)));
            if(!camera.frustum.containsBounds(x,(low+high)*.5f,z,radius,(high-low)*.5f+.1f,radius))return;
        }else if(!camera.frustum.containsBounds(x,h,z,radius,radius,radius))return;
        submittedUnits.put(unit.id,unit);
        boolean vela="vela".equals(unit.type.name);velaFootCeiling=h-GroundFamilyRenderer.bodyHalfDepth(unit)*.45;
        var footSurface=vela?velaFootSurface:groundFootSurface;
        if(controlled&&firstPersonActive&&!showControlledUnit){
            if(family)groundFamily.renderLimbs(unit,x,h,z,yaw,pitch,roll,camera,environment.ambient(),extrusion,footSurface);
            return;
        }
        submittedUnitBodies.put(unit.id,unit);
        if(family&&groundFamily.render(unit,x,h,z,yaw,pitch,roll,camera,environment.ambient(),extrusion,footSurface)){aircraftWeapons.render(unit,camera,environment.ambient(),false);return;}
        if(NavalUnitRenderer.supports(unit)){
            var ship=unitAltitudes==null?null:unitAltitudes.unitState(unit);
            double half=ship==null?SpriteExtrusionRenderer.halfDepth(unit):ship.halfHeight();
            if(naval.render(unit,x,h,z,yaw,pitch,roll,half,surfaceHeight(x,z),camera,environment.ambient(),extrusion))return;
        }
        if(extrusion.render(unit,x,h,z,yaw,pitch,roll,camera,environment.ambient())){aircraftWeapons.render(unit,camera,environment.ambient(),false);return;}
        Color team = unit.team.color;
        dynamic.box(x, h, z, size, size * .45f, size * .62f, -yaw,
            team.r * .8f + .12f, team.g * .8f + .12f, team.b * .8f + .12f);
        float dx = Mathf.cosDeg(yaw), dz = -Mathf.sinDeg(yaw);
        dynamic.box(x + dx * size * .6f, h, z + dz * size * .6f,
            size * .45f, size * .13f, size * .13f, -yaw, .8f, .85f, .89f);
        if (unit.isFlying()) {
            dynamic.box(x, h, z, size * .4f, size * .08f, size * 1.5f, -yaw, .32f, .38f, .44f);
        }
        TextureRegion body=unit.type.region;
        if(NativeAtlasMesh.valid(body)){
            float width=body.width*body.scl()*WORLD_SCALE,depth=body.height*body.scl()*WORLD_SCALE;
            streamAtlas.top(body,x,h+size*.25f,z,width,depth,90-yaw,1,1,1,false);
            streamAtlas.top(unit.type.cellRegion,x,h+size*.25f+(float)RenderConfig.ATLAS_SURFACE_OFFSET,z,width,depth,90-yaw,team.r,team.g,team.b,false);
        }
        aircraftWeapons.render(unit,camera,environment.ambient(),false);
    }

    private void renderExplosions(){
        if(explosions==null)return;
        for(var burst:explosions.bursts()){
            float t=(float)burst.fraction(),r=(float)burst.spec.visualRadius(),age=(float)burst.age();
            float x=(float)burst.x,y=(float)burst.y,z=(float)burst.z;
            float flash=r*.38f*(1-t)*(1-t)*(1-(float)Math.exp(-age*55));
            dynamic.sphere(x,y,z,flash,1,.94f-t*.6f,.58f*(1-t));
            int count=burst.spec.style()==RadialExplosion.Style.pulse?32:(int)CrashConfig.EXPLOSION_PARTICLES;
            for(int i=0;i<count;i++){
                float angle=i*Mathf.PI2/count+burst.seed*.71f,ring=r*t;
                if(burst.spec.style()==RadialExplosion.Style.pulse){
                    float next=angle+Mathf.PI2/count;
                    dynamic.beam(x+Mathf.cos(angle)*ring,y,z+Mathf.sin(angle)*ring,x+Mathf.cos(next)*ring,y,z+Mathf.sin(next)*ring,.035f*(1-t),1,.58f*(1-t),.08f);
                }else{
                    float vy=Mathf.sin(i*7.31f+burst.seed)*r*1.4f;
                    float radius=r*(.18f+t),previous=Math.max(0,radius-.2f*r*(1-t));
                    float px=x+Mathf.cos(angle)*radius,py=y+vy*t+.1f-age*age,pz=z+Mathf.sin(angle)*radius;
                    dynamic.beam(x+Mathf.cos(angle)*previous,py-vy*.09f,z+Mathf.sin(angle)*previous,
                        px,py,pz,.025f*(1-t)+.008f,1,.88f-t*.55f,.18f);
                }
            }
        }
    }

    private void renderPlans() {
        if (Vars.player.unit() != null) {
            for (BuildPlan plan : Vars.player.unit().plans) renderPlan(plan);
        }
        if (Vars.control != null && Vars.control.input != null) {
            for (BuildPlan plan : Vars.control.input.linePlans) renderPlan(plan);
            if (!constructionRenderer.schematicActive() && (!(Vars.control.input instanceof fpv.input.FpvDesktopInput in) || !in.isFpvEnabled()))
                for (BuildPlan plan : Vars.control.input.selectPlans) if(!fpv.construction.LayerSchematic.platform(plan))renderPlan(plan);
        }
        if (hasSelection) {
            float offset = selectionSize % 2 == 0 ? .5f : 0;
            float x = selectionX + offset, z = -selectionY - offset;
            if (near(x, z, selectionSize)) {
                Tile tile = Vars.world.tile(selectionX, selectionY);
                float height = (float)RenderConfig.SELECTION_HEIGHT_OFFSET + visibleSurfaceHeight(tile);
                outline(x, height, z, selectionSize, selectionValid ? .32f : 1f,
                    selectionValid ? 1f : .28f, selectionValid ? .72f : .2f);
                if(selectionBlock!=null)constructionVisuals.ghost(selectionBlock,x,-z,0,heightFor(selectionBlock),selectionRotation,selectionValid,.48f);
            }
        }
    }

    private void renderPlan(BuildPlan plan) {
        if (plan == null) return;
        Tile tile = plan.tile();
        Block block = plan.block != null ? plan.block : tile == null ? null : tile.block();
        if (block == null) return;
        float x = plan.x + block.offset * WORLD_SCALE, z = -plan.y - block.offset * WORLD_SCALE;
        if (!near(x, z, block.size)) return;
        float h = (float)RenderConfig.PLAN_HEIGHT_OFFSET + visibleSurfaceHeight(tile);
        outline(x, h, z, block.size, plan.breaking ? 1f : .38f, plan.breaking ? .42f : .82f, plan.breaking ? .25f : 1f);
        if(!plan.breaking)constructionVisuals.ghost(block,x,-z,0,heightFor(block),plan.rotation,true,.28f);
    }

    /** Native builder activity supplies the target; this is only an XYZ view of that activity. */
    private void renderBuildBeams(){
        double now=Time.millis()/1000.0;
        for(Unit unit:Groups.unit){
            if(unit.dead||!unit.type.drawBuildBeam||unit.inFogTo(Vars.player.team()))continue;
            BuildPlan plan=unit.buildPlan();BuildBeamState saved=buildBeams.get(unit.id);
            if(unit.activelyBuilding()&&plan!=null&&plan.tile()!=null){
                Block block=plan.block==null?plan.tile().block():plan.block;
                if(saved==null||saved.unit!=unit){saved=new BuildBeamState(unit);buildBeams.put(unit.id,saved);}
                saved.x=plan.drawx()/Vars.tilesize;saved.y=plan.drawy()/Vars.tilesize;saved.z=Math.max(.035,heightFor(block));
                saved.size=block.size;saved.removing=plan.breaking;saved.time=now;
            }
            if(saved==null||now-saved.time>.3)continue;
            if(Vars.state.rules.fog&&Vars.fogControl!=null&&!Vars.fogControl.isVisible(Vars.player.team(),(float)saved.x*Vars.tilesize,(float)saved.y*Vars.tilesize))continue;
            var pose=unitAltitudes==null?null:unitAltitudes.unitState(unit);if(pose==null)continue;
            double focus=(unit.type.buildBeamOffset+Mathf.absin(Time.time,3,.6f))/Vars.tilesize,angle=Math.toRadians(pose.yaw());
            float alpha=(float)Math.max(0,1-(now-saved.time)/.3);
            constructionVisuals.beam(pose.x()+Math.cos(angle)*focus,pose.y()+Math.sin(angle)*focus,pose.z()-.04,
                saved.x,saved.y,saved.z,saved.size,saved.removing,alpha);
        }
        var entries=buildBeams.entries();while(entries.hasNext()){var value=entries.next().value;if(!value.unit.isAdded()||now-value.time>1)entries.remove();}
    }
    private static final class BuildBeamState {final Unit unit;double x,y,z,size,time;boolean removing;BuildBeamState(Unit unit){this.unit=unit;}}

    private void outline(float x, float y, float z, float size, float r, float g, float b) {
        float half = size * .5f, width = (float)RenderConfig.OUTLINE_WIDTH;
        dynamic.box(x-half, y, z, width, width, size, 0, r,g,b);
        dynamic.box(x+half, y, z, width, width, size, 0, r,g,b);
        dynamic.box(x, y, z-half, size, width, width, 0, r,g,b);
        dynamic.box(x, y, z+half, size, width, width, 0, r,g,b);
    }

    private boolean near(float x, float z, float extra) {
        float extent=Math.max(.5f,extra);
        return camera.frustum.containsBounds(x,4,z,extent,4,extent);
    }

    /** Render-space height, shared by geometry, picking and the optional drone collision adapter. */
    public static float heightFor(Block block) {
        return fpv.net.HeadlessGeometry.heightFor(block);
    }

    private static void ensureColor(Color c) {
        if (c.r + c.g + c.b < .14f) c.set(.28f, .33f, .38f, 1f);
    }

    private void flushDynamic() {
        if (dynamic.count == 0) return;
        shader.bind();
        dynamicMesh.setVertices(dynamic.vertices, 0, dynamic.count);
        dynamicMesh.render(shader, Gl.triangles);
        dynamic.clear();
    }

    /** Exact block/ground picking along the 3D ray; null means sky or outside the map. */
    public PickResult pick(Camera3D camera, float screenX, float screenY) {
        return pick(camera,screenX,screenY,false);
    }
    /** Decorations and projected walls are not interactive; natural terrain still occludes buildings. */
    public PickResult pickInteraction(Camera3D camera,float screenX,float screenY){return pick(camera,screenX,screenY,true);}
    private PickResult pick(Camera3D camera,float screenX,float screenY,boolean interaction){
        if (Vars.world == null || Vars.world.width() == 0 || Vars.player == null) return null;
        camera.update();
        Ray ray = camera.getPickRay(screenX, screenY);
        // Far clip is forward-axis distance, while this traversal uses distance along the ray.
        float forward=ray.direction.dot(camera.direction);
        float end=camera.far/Math.max(.001f,forward);
        float ground = ray.direction.y < -.000001f ? -ray.origin.y / ray.direction.y : Float.POSITIVE_INFINITY;
        PickResult result = null;
        if (ground >= 0 && ground <= end) result = resultAt(ray, ground);
        if (result != null) end = ground;
        // DDA visits each horizontal grid cell touched by the ray, including block sides.
        float ox = ray.origin.x, oy = -ray.origin.z, dx = ray.direction.x, dy = -ray.direction.z;
        float start = 0, stop = end;
        float[] interval = {start, stop};
        if (!clipAxis(ox, dx, -.5f, Vars.world.width() - .5f, interval) ||
            !clipAxis(oy, dy, -.5f, Vars.world.height() - .5f, interval)) return result;
        start = interval[0]; stop = interval[1];
        if (start > stop) return result;
        int tx = (int)Math.floor(ox + dx * (start + .00001f) + .5f);
        int ty = (int)Math.floor(oy + dy * (start + .00001f) + .5f);
        int sx = dx >= 0 ? 1 : -1, sy = dy >= 0 ? 1 : -1;
        float stepX = Math.abs(dx) < .000001f ? Float.POSITIVE_INFINITY : Math.abs(1f / dx);
        float stepY = Math.abs(dy) < .000001f ? Float.POSITIVE_INFINITY : Math.abs(1f / dy);
        float nextX = Math.abs(dx) < .000001f ? Float.POSITIVE_INFINITY : (tx + sx * .5f - ox) / dx;
        float nextY = Math.abs(dy) < .000001f ? Float.POSITIVE_INFINITY : (ty + sy * .5f - oy) / dy;
        float t = start;
        // A straight ray crosses no more than width + height grid boundaries in this rectangle.
        int maxCells = Vars.world.width() + Vars.world.height() + 4;
        for (int guard = 0; guard < maxCells && t <= stop; guard++) {
            fpv.profile.CameraPerformanceProfile.pickCell();
            Tile tile = Vars.world.tile(tx, ty);
            if (tile == null) break;
            Block block = tile.block();
            if (block != mindustry.content.Blocks.air && (!interaction||tile.build!=null||elevation.naturalWall(tile)) && discovered(tile) &&
                (tile.build == null || !tile.build.inFogTo(Vars.player.team()))) {
                if(elevation.naturalWall(tile)){
                    float hit=hitTerrain(ray,tile,stop);
                    if(hit>=0)return new PickResult((ray.origin.x+ray.direction.x*hit)*Vars.tilesize,
                        -(ray.origin.z+ray.direction.z*hit)*Vars.tilesize,ray.origin.y+ray.direction.y*hit,hit,tile);
                }else{
                float x = tile.build != null ? tile.build.x * WORLD_SCALE : tile.x + block.offset * WORLD_SCALE;
                float z = tile.build != null ? -tile.build.y * WORLD_SCALE : -tile.y - block.offset * WORLD_SCALE;
                float half = block.size * .5f, height = heightFor(block);
                float hit = hitBox(ray, x - half, 0, z - half, x + half, height, z + half, stop);
                if (hit >= 0) {
                    // Nudge inside the surface so an exact tile edge does not select its empty neighbor.
                    PickResult blockHit = resultAt(ray, hit + (float)RenderConfig.PICK_SURFACE_EPSILON);
                    if (blockHit != null) return blockHit;
                }
                }
            }
            if (nextX < nextY) { t = nextX; nextX += stepX; tx += sx; }
            else { t = nextY; nextY += stepY; ty += sy; }
        }
        return result;
    }

    private float hitTerrain(Ray ray,Tile tile,float limit){
        float l=tile.x-.5f,r=tile.x+.5f,n=-tile.y-.5f,s=-tile.y+.5f;
        terrainA.set(l,elevation.tileCornerHeight(tile.x,tile.y,0),s);
        terrainB.set(r,elevation.tileCornerHeight(tile.x,tile.y,1),s);
        terrainC.set(r,elevation.tileCornerHeight(tile.x,tile.y,2),n);
        terrainD.set(l,elevation.tileCornerHeight(tile.x,tile.y,3),n);
        float best=triangleHit(ray,terrainA,terrainB,terrainC,limit);
        best=Math.min(best,triangleHit(ray,terrainA,terrainC,terrainD,limit));
        Vec3[] v={terrainA,terrainB,terrainC,terrainD};
        int[] dx={0,1,0,-1},dy={-1,0,1,0};
        for(int i=0;i<4;i++)if(!elevation.naturalWall(tile.x+dx[i],tile.y+dy[i])){
            Vec3 a=v[i],b=v[(i+1)%4];
            Vec3 lowA=new Vec3(a.x,0,a.z),lowB=new Vec3(b.x,0,b.z);
            best=Math.min(best,triangleHit(ray,a,b,lowB,limit));best=Math.min(best,triangleHit(ray,a,lowB,lowA,limit));
        }
        return Float.isFinite(best)?best:-1;
    }
    private float triangleHit(Ray ray,Vec3 a,Vec3 b,Vec3 c,float limit){
        if(!Intersector3D.intersectRayTriangle(ray,a,b,c,terrainHit))return Float.POSITIVE_INFINITY;
        float distance=terrainHit.dst(ray.origin);
        return distance<=limit?distance:Float.POSITIVE_INFINITY;
    }

    private static PickResult resultAt(Ray ray, float t) {
        float x = ray.origin.x + ray.direction.x * t;
        float y = -(ray.origin.z + ray.direction.z * t);
        // Bias tiny edge errors into the struck cell instead of beyond a map boundary.
        int tx = (int)Math.floor(x + .5f), ty = (int)Math.floor(y + .5f);
        Tile tile = Vars.world.tile(tx, ty);
        if (tile == null) return null;
        return new PickResult(x * Vars.tilesize, y * Vars.tilesize,
            ray.origin.y + ray.direction.y * t, t, tile);
    }

    private static float hitBox(Ray ray, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float limit) {
        float[] interval = {0, limit};
        if (!clipAxis(ray.origin.x, ray.direction.x, minX, maxX, interval) ||
            !clipAxis(ray.origin.y, ray.direction.y, minY, maxY, interval) ||
            !clipAxis(ray.origin.z, ray.direction.z, minZ, maxZ, interval)) return -1;
        return interval[0];
    }

    private static boolean clipAxis(float origin, float direction, float min, float max, float[] interval) {
        if (Math.abs(direction) < .000001f) return origin >= min && origin <= max;
        float a = (min - origin) / direction, b = (max - origin) / direction;
        if (a > b) { float temp = a; a = b; b = temp; }
        interval[0] = Math.max(interval[0], a);
        interval[1] = Math.min(interval[1], b);
        return interval[0] <= interval[1];
    }

    public static final class PickResult {
        public final float worldX, worldY, height, distance;
        public final int tileX, tileY;
        public final Tile tile;
        private PickResult(float worldX, float worldY, float height, float distance, Tile tile) {
            this.worldX = worldX; this.worldY = worldY; this.height = height; this.distance = distance;
            this.tile = tile; tileX = tile.x; tileY = tile.y;
        }
    }

    public void dispose() {
        firstPersonRig.close();aircraftWeapons.close();
        miningVisuals.clear();
        constructionVisuals.close();buildBeams.clear();
        constructionRenderer.close();
        threatMarkers.close();
        if (disposed) return;
        disposed = true;
        placedBuildings.clear(); submittedBuildings.clear(); submittedBridges.clear(); submittedUnits.clear();submittedUnitBodies.clear();placedWorldTiles = null;
        buildingFrameCompleted = false;
        combatVisuals.clear();
        organicProps.clear();
        clearChunks();
        if (shader != null) shader.dispose();
        if (atlasShader != null) atlasShader.dispose();
        if (dynamicMesh != null) dynamicMesh.dispose();
        if (sceneBuffer != null) sceneBuffer.dispose();
        staticAtlas.dispose();streamAtlas.dispose();environment.dispose();extrusion.dispose();groundFamily.close();naval.close();forceFields.dispose();projectedWalls.dispose();forceProjectorRenderer.dispose();supportEffects.clear();
    }

    private void clearChunks() {
        for (ChunkMesh chunk : chunks.values()) chunk.dispose();
        chunks.clear();
        cachedBytes=0;
        coarseFog.clear();
    }

    private static int key(int x, int y) { return x | y << 16; }
    private static void enabled(int cap, boolean enabled) { if (enabled) Gl.enable(cap); else Gl.disable(cap); }

    private static final class ChunkMesh {
        final Mesh mesh;
        final NativeAtlasMesh atlas;
        final long bytes,fogHash;
        long fogChecked=Time.millis();
        long revision;
        ChunkMesh(Mesh mesh,NativeAtlasMesh atlas,long revision,long colorBytes,long fogHash) {
            this.mesh=mesh;this.atlas=atlas;this.revision=revision;this.bytes=colorBytes+atlas.bytes;this.fogHash=fogHash;
        }
        void dispose() { if (mesh != null) mesh.dispose();if(atlas!=null)atlas.dispose(); }
    }
    private record Candidate(int x,int y,float distance2){}

    private static final class CoarseFogMask {
        final int stride,side;
        final boolean[] known;
        long checked;
        CoarseFogMask(int stride){
            this.stride=stride;
            side=(CHUNK+stride-1)/stride;
            known=new boolean[side*side];
        }
    }

    private static FrameBuffer createSceneBuffer(int width,int height){
        if(Core.gl30!=null){
            try{return new DepthSceneBuffer(width,height);}catch(RuntimeException ignored){/* Compatible 16-bit fallback for unusual GPUs. */}
        }
        return new FrameBuffer(width,height,true);
    }
    private static final class DepthSceneBuffer extends FrameBuffer{
        DepthSceneBuffer(int width,int height){super(builder(width,height));}
        private static FrameBufferBuilder builder(int width,int height){
            FrameBufferBuilder builder=new FrameBufferBuilder(width,height);
            builder.addBasicColorTextureAttachment(Pixmap.Format.rgba8888);
            builder.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
            return builder;
        }
    }

    /** Non-indexed triangles avoid ushort index limits and use bounded streaming for entities. */
    private final class Geometry implements VolumetricBeamGeometry.Sink {
        final int maxVertices;
        final boolean streaming;
        boolean emissive;
        float[] vertices;
        int count;
        Geometry(int maxVertices, boolean streaming) {
            this.maxVertices = maxVertices;
            this.streaming = streaming;
            vertices = new float[maxVertices * 7];
        }
        void clear() { count = 0; }
        void reserve(int vertexCount) {
            int required = vertexCount * 7;
            if (streaming && count + required > vertices.length) flushDynamic();
            if (count + required > vertices.length) vertices = Arrays.copyOf(vertices, Math.max(vertices.length * 2, count + required));
        }
        public void vertex(float x, float y, float z, float r, float g, float b) {
            vertices[count++] = x; vertices[count++] = y; vertices[count++] = z;
            vertices[count++] = r; vertices[count++] = g; vertices[count++] = b; vertices[count++] = emissive?1:0;
        }
        void top(float x, float y, float z, float w, float d, float r, float g, float b) {
            reserve(6);
            float a = x - w / 2, c = x + w / 2, e = z - d / 2, f = z + d / 2;
            quad(a,y,e, c,y,e, c,y,f, a,y,f, r,g,b);
        }
        void sphere(float x,float y,float z,float radius,float r,float g,float b){
            for(int row=0;row<6;row++)for(int col=0;col<12;col++){
                float a=-Mathf.PI/2+row*Mathf.PI/6,c=a+Mathf.PI/6,u=col*Mathf.PI2/12,v=u+Mathf.PI2/12;
                reserve(6);
                quad(x+radius*Mathf.cos(a)*Mathf.cos(u),y+radius*Mathf.sin(a),z+radius*Mathf.cos(a)*Mathf.sin(u),
                    x+radius*Mathf.cos(a)*Mathf.cos(v),y+radius*Mathf.sin(a),z+radius*Mathf.cos(a)*Mathf.sin(v),
                    x+radius*Mathf.cos(c)*Mathf.cos(v),y+radius*Mathf.sin(c),z+radius*Mathf.cos(c)*Mathf.sin(v),
                    x+radius*Mathf.cos(c)*Mathf.cos(u),y+radius*Mathf.sin(c),z+radius*Mathf.cos(c)*Mathf.sin(u),r,g,b);
            }
        }
        void marker(float x,float y,float z,float radius,float r,float g,float b){
            reserve(24);
            for(int i=0;i<4;i++){
                float a=i*Mathf.PI/2,c=(i+1)*Mathf.PI/2;
                float ax=x+Mathf.cos(a)*radius,az=z+Mathf.sin(a)*radius;
                float bx=x+Mathf.cos(c)*radius,bz=z+Mathf.sin(c)*radius;
                vertex(x,y+radius,z,r,g,b);vertex(ax,y,az,r,g,b);vertex(bx,y,bz,r,g,b);
                vertex(x,y-radius,z,r,g,b);vertex(bx,y,bz,r,g,b);vertex(ax,y,az,r,g,b);
            }
        }
        void disk(float x,float y,float z,float radiusX,float radiusZ,float r,float g,float b){
            reserve(60);
            for(int i=0;i<20;i++){double a=i*Math.PI/10,c=(i+1)*Math.PI/10;
                vertex(x,y,z,r,g,b);
                vertex(x+(float)Math.cos(a)*radiusX,y,z+(float)Math.sin(a)*radiusZ,r,g,b);
                vertex(x+(float)Math.cos(c)*radiusX,y,z+(float)Math.sin(c)*radiusZ,r,g,b);
            }
        }
        void quad(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b) {
            vertex(ax,ay,az,r,g,b); vertex(bx,by,bz,r,g,b); vertex(cx,cy,cz,r,g,b);
            vertex(ax,ay,az,r,g,b); vertex(cx,cy,cz,r,g,b); vertex(dx,dy,dz,r,g,b);
        }
        void box(float x,float y,float z,float w,float h,float d,float angle,float r,float g,float b) {
            reserve(36);
            float cos = Mathf.cosDeg(angle), sin = Mathf.sinDeg(angle), hw = w/2, hd = d/2;
            float ax=x-hw*cos+hd*sin, az=z-hw*sin-hd*cos;
            float bx=x+hw*cos+hd*sin, bz=z+hw*sin-hd*cos;
            float cx=x+hw*cos-hd*sin, cz=z+hw*sin+hd*cos;
            float dx=x-hw*cos-hd*sin, dz=z-hw*sin+hd*cos;
            float lo=y-h/2, hi=y+h/2;
            quad(ax,hi,az,bx,hi,bz,cx,hi,cz,dx,hi,dz,r,g,b);
            float shade = (float)RenderConfig.FACE_BOTTOM_SHADE;
            quad(ax,lo,az,dx,lo,dz,cx,lo,cz,bx,lo,bz,r*shade,g*shade,b*shade);
            shade = (float)RenderConfig.FACE_FRONT_SHADE;
            quad(ax,lo,az,bx,lo,bz,bx,hi,bz,ax,hi,az,r*shade,g*shade,b*shade);
            shade = (float)RenderConfig.FACE_RIGHT_SHADE;
            quad(bx,lo,bz,cx,lo,cz,cx,hi,cz,bx,hi,bz,r*shade,g*shade,b*shade);
            shade = (float)RenderConfig.FACE_BACK_SHADE;
            quad(cx,lo,cz,dx,lo,dz,dx,hi,dz,cx,hi,cz,r*shade,g*shade,b*shade);
            shade = (float)RenderConfig.FACE_LEFT_SHADE;
            quad(dx,lo,dz,ax,lo,az,ax,hi,az,dx,hi,dz,r*shade,g*shade,b*shade);
        }
        void beam(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b){
            float dx=bx-ax,dy=by-ay,dz=bz-az,length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(length<.001f)return;
            dx/=length;dy/=length;dz/=length;
            float sx,sy,sz;
            if(Math.abs(dy)<.9f){sx=-dz;sy=0;sz=dx;}else{sx=0;sy=dz;sz=-dy;}
            float scale=width*.5f/(float)Math.sqrt(sx*sx+sy*sy+sz*sz);
            sx*=scale;sy*=scale;sz*=scale;
            float tx=dy*sz-dz*sy,ty=dz*sx-dx*sz,tz=dx*sy-dy*sx;
            reserve(12);
            quad(ax+sx,ay+sy,az+sz,bx+sx,by+sy,bz+sz,bx-sx,by-sy,bz-sz,ax-sx,ay-sy,az-sz,r,g,b);
            quad(ax+tx,ay+ty,az+tz,bx+tx,by+ty,bz+tz,bx-tx,by-ty,bz-tz,ax-tx,ay-ty,az-tz,r,g,b);
        }
        void laser(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b){
            reserve(VolumetricBeamGeometry.VERTICES);
            VolumetricBeamGeometry.emit(ax,ay,az,bx,by,bz,width,r,g,b,camera.position.x,camera.position.y,camera.position.z,this);
        }
    }
}
