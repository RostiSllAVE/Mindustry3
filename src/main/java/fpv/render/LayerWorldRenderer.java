package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import arc.util.Time;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.DirectionBridge;
import mindustry.gen.Building;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.draw.DrawTurret;
import java.util.*;

/** Cached opaque construction chunks and a small transparent construction-plane pass. */
public final class LayerWorldRenderer implements AutoCloseable {
    private record Key(int x,int y,int layer){}
    private static final class Chunk {
        long signature;NativeAtlasMesh mesh;LayerCoordinates.Bounds bounds;
        Chunk(long signature,NativeAtlasMesh mesh,LayerCoordinates.Bounds bounds){this.signature=signature;this.mesh=mesh;this.bounds=bounds;}
    }
    private final Map<Key,Chunk> chunks=new LinkedHashMap<>();
    private final Map<Long,NativeAtlasMesh> fallingMeshes=new LinkedHashMap<>();
    private NativeAtlasMesh connections;
    private long connectionSignature=Long.MIN_VALUE;
    private final fpv.profile.VisualGpuTimer gpu=Boolean.getBoolean("fpv.layers.profile")?new fpv.profile.VisualGpuTimer():null;
    private final NativeAtlasMesh.Builder builder=new NativeAtlasMesh.Builder(false),overlay=new NativeAtlasMesh.Builder(true),animated=new NativeAtlasMesh.Builder(true);
    private long fogCheck,visibilitySignature=Long.MIN_VALUE;
    private final Set<Long> visibleIds=new HashSet<>(),activeFalls=new HashSet<>();
    private final Set<Long> externalBridgeSources=new HashSet<>();
    private LayerRuntime runtime;
    private LayerWorld previousWorld;
    private long revision=Long.MIN_VALUE,meshBuilds;
    private int groundChanges=Integer.MIN_VALUE;
    private Shader overlayShader;
    private final ConstructionVisualRenderer constructionVisuals=new ConstructionVisualRenderer();
    private final LayerSchematicRenderer schematicRenderer=new LayerSchematicRenderer();
    public void schematic(arc.struct.Seq<mindustry.entities.units.BuildPlan> plans,boolean[] valid,int layer){schematicRenderer.set(plans,valid,layer);}
    public void copySelection(LayerCoordinates.Bounds bounds){schematicRenderer.selection(bounds);}
    public boolean schematicActive(){return schematicRenderer.active();}
    public int schematicDrawn(){return schematicRenderer.lastDrawn();}
    private final BuildingInteractionRenderer interactionRenderer=new BuildingInteractionRenderer();
    public void interaction(BuildingMousePicker.Hit hover,BuildingMousePicker.Hit source,BuildingMousePicker.Hit target,boolean valid){interactionRenderer.set(hover,source,target,valid);}
    public void renderInteraction(Camera3D camera){interactionRenderer.render(camera);}
    public void removalPreview(BuildingMousePicker.Hit hit,boolean valid){interactionRenderer.removal(hit,valid);}
    private final LayerDisplayRenderer displays=new LayerDisplayRenderer();
    private final NativeBlockAnimationAdapter blockAnimations=new NativeBlockAnimationAdapter();
    private final TurretModelRenderer turretModels=new TurretModelRenderer();
    public LayerWorldRenderer(){displays.onRelease(animated::forget);}
    public void prepareDisplays(){displays.prepare(runtime);}
    private fpv.units.UnitAltitudeSystem altitudes;
    private Block previewBlock;
    private int previewRotation;
    private boolean previewPlatform;
    private final ArrayDeque<Activity> activities=new ArrayDeque<>();
    private record Activity(mindustry.gen.Unit unit,int x,int y,int layer,int size,boolean removing,long when){}
    public void setUnitAltitudes(fpv.units.UnitAltitudeSystem value){altitudes=value;}
    public ConstructionVisualRenderer.Stats constructionVisualStats(){return constructionVisuals.stats();}
    /** Cosmetic pulse for accepted/pending build actions; authority still owns the actual block. */
    public void constructionActivity(mindustry.gen.Unit unit,int x,int y,int layer,int size,boolean removing){
        if(unit==null)return;if(activities.size()>=32)activities.removeFirst();activities.addLast(new Activity(unit,x,y,layer,size,removing,Time.millis()));
    }
    private int selectedLayer,selectedX,selectedY,selectedSize=1;
    private boolean planeVisible,hasPick,validPick;
    private int submittedChunks;
    private double lastCpuMilliseconds;
    public void setRuntime(LayerRuntime value){runtime=value;invalidate();}
    public void preview(boolean visible,int layer,int x,int y,int size,boolean picked,boolean valid){
        planeVisible=visible;selectedLayer=layer;selectedX=x;selectedY=y;selectedSize=Math.max(1,size);hasPick=picked;validPick=valid;
        previewBlock=null;previewPlatform=false;
    }
    public void preview(boolean visible,int layer,int x,int y,int size,boolean picked,boolean valid,Block block,int rotation,boolean platform){
        preview(visible,layer,x,y,size,picked,valid);previewBlock=block;previewRotation=rotation;previewPlatform=platform;
    }
    public long meshBuilds(){return meshBuilds;}
    public int submittedChunks(){return submittedChunks;}
    public double cpuMilliseconds(){return lastCpuMilliseconds;}
    public double gpuMeanMs(){return gpu==null?-1:gpu.meanMs();}
    public long gpuSamples(){return gpu==null?0:gpu.samples();}
    public void resetProfile(){if(gpu!=null)gpu.reset();}
    public void invalidate(){revision=Long.MIN_VALUE;blockAnimations.clear();}
    public void render(Camera3D camera,Shader atlasShader){
        long start=System.nanoTime();submittedChunks=0;
        if(runtime==null)return;
        LayerWorld world=runtime.grid();refreshVisibility(world);sync(world);
        if(gpu!=null)gpu.begin();
        try{
        for(Chunk chunk:chunks.values()){
            var b=chunk.bounds;float x=(float)((b.minX()+b.maxX())*.5),y=(float)((b.minY()+b.maxY())*.5),z=(float)((b.minZ()+b.maxZ())*.5);
            if(!camera.frustum.containsBounds(x,z,-y,(float)((b.maxX()-b.minX())*.5),(float)((b.maxZ()-b.minZ())*.5),(float)((b.maxY()-b.minY())*.5)))continue;
            chunk.mesh.draw(atlasShader);submittedChunks++;
        }
        renderFalling(world,atlasShader);
        renderConnections(world,atlasShader);
        renderAnimated(world,camera,atlasShader);
        }finally{if(gpu!=null)gpu.end();}
        lastCpuMilliseconds=(System.nanoTime()-start)/1e6;
    }
    private void renderConnections(LayerWorld world,Shader shader){
        long signature=world.geometryRevision()*31+visibilitySignature;
        externalBridgeSources.clear();
        for(var link:runtime.transfers().links()){signature=signature*31+link.hashCode();if(link.kind().name().startsWith("BRIDGE"))externalBridgeSources.add(link.from());}
        for(var entry:runtime.nativeBuildings().entries())if(entry.building() instanceof ItemBridge.ItemBridgeBuild bridge)signature=signature*31+bridge.link;
        for(var link:runtime.nativeBuildings().powerLinks())signature=signature*31+link.hashCode();
        if(signature!=connectionSignature){
            if(connections!=null)connections.dispose();builder.clear();
            for(var link:runtime.transfers().links()){
                double[] a=endpoint(world,link.from()),b=endpoint(world,link.to());if(a==null||b==null)continue;
                if(link.kind().name().startsWith("BRIDGE")){
                    Building source=endpointBuilding(link.from());TextureRegion region=source!=null&&source.block instanceof ItemBridge bridge?bridge.bridgeRegion:
                        source!=null&&source.block instanceof DirectionBridge bridge?bridge.bridgeRegion:Core.atlas.white();
                    texturedSpan(builder,NativeAtlasMesh.valid(region)?region:Core.atlas.white(),a,b,.30f,.9f,.95f,1);
                    continue;
                }
                if(link.kind()==LayerVerticalTransfers.Kind.POWER)continue; // Draw once from the authoritative graph edges below.
                double low=Math.min(a[2],b[2]),high=Math.max(a[2],b[2]);
                var bounds=new LayerCoordinates.Bounds(a[0]-.09,a[1]-.09,low,a[0]+.09,a[1]+.09,high);
                boolean item=link.kind()==LayerVerticalTransfers.Kind.ITEMS,liquid=link.kind()==LayerVerticalTransfers.Kind.LIQUIDS;
                box(builder,Core.atlas.white(),bounds,item?.9f:liquid?.15f:1,item?.7f:liquid?.65f:.85f,item?.25f:liquid?1:.4f);
            }
            for(var entry:runtime.nativeBuildings().entries()){
                if(externalBridgeSources.contains(entry.id())||!visibleIds.contains(entry.id()))continue;
                Building source=entry.building(),target=null;TextureRegion region=null;float width=.3f;
                if(source instanceof ItemBridge.ItemBridgeBuild bridge&&source.block instanceof ItemBridge type&&bridge.link>=0){
                    var tile=runtime.nativeBuildings().tile(arc.math.geom.Point2.x(bridge.link),arc.math.geom.Point2.y(bridge.link),entry.layer());
                    target=tile==null?null:tile.build;region=type.bridgeRegion;width=Math.max(.12f,type.bridgeWidth/Vars.tilesize);
                    if(target==null||!type.linkValid(source.tile,target.tile))continue;
                }else if(source instanceof DirectionBridge.DirectionBridgeBuild bridge&&source.block instanceof DirectionBridge type){target=bridge.findLink();region=type.bridgeRegion;}
                if(target==null||target==source||target.team!=source.team)continue;
                var component=world.at(target.tile.x,target.tile.y,entry.layer(),LayerComponent.Kind.BLOCK);if(component==null||!visibleIds.contains(component.id()))continue;
                double[] a=endpoint(world,entry.id()),b=endpoint(world,component.id());if(a!=null&&b!=null)texturedSpan(builder,NativeAtlasMesh.valid(region)?region:Core.atlas.white(),a,b,width,1,1,1);
            }
            for(var link:runtime.nativeBuildings().powerLinks()){
                double[] a=endpoint(world,link.first()),b=endpoint(world,link.second());if(a==null||b==null)continue;
                texturedSpan(builder,Core.atlas.white(),a,b,.052f,1,.87f,.35f);
            }
            connections=builder.upload();connectionSignature=signature;
        }
        if(connections!=null)connections.draw(shader);
    }
    private Building endpointBuilding(long id){if(id>0)return runtime.building(id);long packed=-id-1;return packed>=0&&packed<=0xffffffffL?Vars.world.build((int)packed):null;}
    /** A world-space bridge ribbon follows both horizontal displacement and the real floor height. */
    private static void texturedSpan(NativeAtlasMesh.Builder out,TextureRegion region,double[] a,double[] b,float width,float r,float g,float blue){
        double dx=b[0]-a[0],dy=b[1]-a[1],horizontal=Math.hypot(dx,dy),half=width*.5;
        double ox=horizontal>.001?-dy/horizontal*half:half,oy=horizontal>.001?dx/horizontal*half:0;
        out.projectionFace(region,(float)(a[0]-ox),(float)a[2],(float)(-a[1]+oy),(float)(b[0]-ox),(float)b[2],(float)(-b[1]+oy),
            (float)(b[0]+ox),(float)b[2],(float)(-b[1]-oy),(float)(a[0]+ox),(float)a[2],(float)(-a[1]-oy),r,g,blue,0);
        // The second face gives thin bridge art readable thickness from the side and on vertical runs.
        double length=Math.sqrt(dx*dx+dy*dy+(b[2]-a[2])*(b[2]-a[2]));if(length<.001)return;
        double nx=-(b[2]-a[2])*oy,ny=(b[2]-a[2])*ox,nz=dx*oy-dy*ox,norm=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(norm<.001)return;nx=nx/norm*.065;ny=ny/norm*.065;nz=nz/norm*.065;
        out.projectionFace(region,(float)(a[0]-nx),(float)(a[2]-nz),(float)(-a[1]+ny),(float)(b[0]-nx),(float)(b[2]-nz),(float)(-b[1]+ny),
            (float)(b[0]+nx),(float)(b[2]+nz),(float)(-b[1]-ny),(float)(a[0]+nx),(float)(a[2]+nz),(float)(-a[1]-ny),r*.7f,g*.7f,blue*.7f,0);
    }
    private double[] endpoint(LayerWorld world,long id){
        if(id>0){var c=world.get(id);if(c==null||!visibleIds.contains(id))return null;var b=world.coordinates().bounds(c);return new double[]{b.centerX(),b.centerY(),b.maxZ()+.04};}
        long packed=-id-1;var b=packed>=0&&packed<=0xffffffffL?Vars.world.build((int)packed):null;
        return b==null||Vars.player==null||b.inFogTo(Vars.player.team())?null:new double[]{b.x/Vars.tilesize,b.y/Vars.tilesize,fpv.net.HeadlessGeometry.heightFor(b.block)+.04};
    }
    private boolean visible(LayerComponent c){
        return Vars.player!=null&&(c.team()==Vars.player.team().id||!Vars.state.rules.fog||Vars.fogControl==null||Vars.fogControl.isVisible(Vars.player.team(),c.x(),c.y()));
    }
    private void refreshVisibility(LayerWorld world){
        long now=Time.millis();
        if(previousWorld==world&&revision==world.geometryRevision()&&now-fogCheck<200)return;
        fogCheck=now;visibleIds.clear();long hash=17;
        for(var c:world.components())if(visible(c)){visibleIds.add(c.id());hash=hash*31+c.id();}
        if(hash!=visibilitySignature){visibilitySignature=hash;revision=Long.MIN_VALUE;}
    }
    private void sync(LayerWorld world){
        if(world==previousWorld&&revision==world.geometryRevision()&&groundChanges==Vars.world.tileChanges)return;
        if(world!=previousWorld){for(var c:chunks.values())c.mesh.dispose();chunks.clear();for(var mesh:fallingMeshes.values())mesh.dispose();fallingMeshes.clear();connectionSignature=Long.MIN_VALUE;previousWorld=world;}
        Map<Key,List<LayerComponent>> groups=new LinkedHashMap<>();
        for(var c:world.components())if(visibleIds.contains(c.id()))groups.computeIfAbsent(new Key(Math.floorDiv(c.x(),16),Math.floorDiv(c.y(),16),c.layer()),k->new ArrayList<>()).add(c);
        var it=chunks.entrySet().iterator();while(it.hasNext()){var e=it.next();if(!groups.containsKey(e.getKey())){e.getValue().mesh.dispose();it.remove();}}
        for(var entry:groups.entrySet()){
            long signature=17;
            for(var c:entry.getValue())signature=Objects.hash(signature,c.id(),c.x(),c.y(),c.layer(),c.size(),c.height(),c.blockName(),c.rotation(),c.team(),embeddedSlab(world,c));
            Chunk existing=chunks.get(entry.getKey());if(existing!=null&&existing.signature==signature)continue;
            if(existing!=null)existing.mesh.dispose();builder.clear();
            double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
            for(var c:entry.getValue()){
                var b=world.coordinates().bounds(c);minX=Math.min(minX,b.minX());minY=Math.min(minY,b.minY());minZ=Math.min(minZ,b.minZ());maxX=Math.max(maxX,b.maxX());maxY=Math.max(maxY,b.maxY());maxZ=Math.max(maxZ,b.maxZ());
                // A slab seated inside a column is the same solid material joint, not
                // a second coplanar skin. Keep its pick/support identity, emit no duplicate faces.
                if(embeddedSlab(world,c))continue;
                Block block=Vars.content.getByName(ContentType.block,c.blockName());
                TextureRegion region=c.kind()==LayerComponent.Kind.PLATFORM?Core.atlas.white():block==null?Core.atlas.white():block.fullIcon;
                var tint=mindustry.game.Team.get(c.team()).color;
                float r=c.kind()==LayerComponent.Kind.PLATFORM?.40f:1,g=c.kind()==LayerComponent.Kind.PLATFORM?.48f:1,blue=c.kind()==LayerComponent.Kind.PLATFORM?.54f:1;
                // Door state changes do not rebuild structural chunk meshes; the whole door is streamed below.
                if(block instanceof mindustry.world.blocks.defense.Door||block instanceof mindustry.world.blocks.defense.AutoDoor)continue;
                if(block instanceof Turret turret&&turretModels.supports(turret)){
                    // The shared turret pass owns its alpha-shaped stationary base as well as guns.
                    continue;
                }else if(block instanceof Turret turret){
                    TextureRegion base=turret.drawer instanceof DrawTurret drawer&&NativeAtlasMesh.valid(drawer.base)?drawer.base:region;
                    box(builder,base,new LayerCoordinates.Bounds(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.minZ()+c.height()*.55),r,g,blue);
                }else box(builder,region,b,r,g,blue,block!=null&&block.rotate?-c.rotation()*90:0,
                    !(block instanceof Conveyor||block instanceof mindustry.world.blocks.units.UnitBlock||block instanceof mindustry.world.blocks.liquid.LiquidBlock&&!(block instanceof Conduit)));
                // Small team plate, without recoloring the familiar block artwork.
                float cx=(float)((b.minX()+b.maxX())*.5),cy=(float)((b.minY()+b.maxY())*.5),top=(float)b.maxZ()+.003f;
                builder.top(Core.atlas.white(),cx,top,-cy,c.size()*.23f,c.size()*.23f,0,tint.r,tint.g,tint.b,false);
            }
            chunks.put(entry.getKey(),new Chunk(signature,builder.upload(),new LayerCoordinates.Bounds(minX,minY,minZ,maxX,maxY,maxZ)));meshBuilds++;
        }
        revision=world.geometryRevision();groundChanges=Vars.world.tileChanges;
    }
    private boolean embeddedSlab(LayerWorld world,LayerComponent c){
        if(c.kind()!=LayerComponent.Kind.PLATFORM)return false;
        var b=world.coordinates().bounds(c);
        for(var other:world.query(b))if(other.id()!=c.id()&&other.column()&&visibleIds.contains(other.id())){
            var w=world.coordinates().bounds(other);
            if(w.minX()<=b.minX()&&w.maxX()>=b.maxX()&&w.minY()<=b.minY()&&w.maxY()>=b.maxY()&&w.minZ()<=b.minZ()&&Math.abs(w.maxZ()-b.maxZ())<1e-7)return true;
        }
        var tile=Vars.world.tile(c.x(),c.y());
        return tile!=null&&tile.build!=null&&tile.build.block instanceof mindustry.world.blocks.defense.Wall&&Math.abs(fpv.net.HeadlessGeometry.heightFor(tile.build.block)-b.maxZ())<1e-6;
    }
    private void renderFalling(LayerWorld world,Shader shader){
        if(runtime.falling().isEmpty()&&fallingMeshes.isEmpty())return;
        activeFalls.clear();
        for(var fall:runtime.falling()){
            activeFalls.add(fall.id());NativeAtlasMesh mesh=fallingMeshes.get(fall.id());
            if(mesh==null){builder.clear();for(var c:fall.components()){
                if(!visible(c))continue;
                Block block=Vars.content.getByName(ContentType.block,c.blockName());
                TextureRegion region=block==null?Core.atlas.white():block.fullIcon;
                box(builder,region,world.coordinates().bounds(c),.85f,.65f,.55f);
            }mesh=builder.upload();fallingMeshes.put(fall.id(),mesh);meshBuilds++;}
            shader.bind();shader.setUniformf("u_offset",0,(float)fall.offsetZ(),0);mesh.draw(shader);
        }
        shader.bind();shader.setUniformf("u_offset",0,0,0);
        var iterator=fallingMeshes.entrySet().iterator();while(iterator.hasNext()){var e=iterator.next();if(!activeFalls.contains(e.getKey())){e.getValue().dispose();iterator.remove();}}
    }
    /** Native mutable state is streamed into one atlas batch; static hulls retain their meshes. */
    private void renderAnimated(LayerWorld world,Camera3D camera,Shader shader){
        animated.clear();animated.overflowShader=shader;
        for(var entry:runtime.nativeBuildings().entries()){
            if(!visibleIds.contains(entry.id()))continue;
            var c=world.get(entry.id());if(c==null)continue;var b=entry.building();
            float x=b.x/Vars.tilesize,z=-b.y/Vars.tilesize,base=(float)world.coordinates().plane(c.layer()),h=(float)c.height(),size=c.size();
            var pod=fpv.construction.LayerMassDriverBehavior.pod(b);
            if(pod!=null&&pod.active()){
                float phase=pod.progress(),px=Mathf.lerp(pod.x0,pod.x1,phase),py=Mathf.lerp(pod.y0,pod.y1,phase),pz=Mathf.lerp(pod.z0,pod.z1,phase)+h*.7f;
                if(camera.frustum.containsBounds(px,pz,-py,1,1,1)){
                    TextureRegion shell=Core.atlas.find("shell");if(!NativeAtlasMesh.valid(shell))shell=Core.atlas.white();
                    float heading=90-Mathf.angle(pod.x1-pod.x0,pod.y1-pod.y0);
                    animated.top(shell,px,pz+.08f,-py,.4f,.65f,heading,1,.85f,.45f,false);
                    box(animated,shell,new LayerCoordinates.Bounds(px-.14,py-.14,pz-.09,px+.14,py+.14,pz+.09),1,.75f,.3f);
                }
            }
            if(!camera.frustum.containsBounds(x,base+h*.5f,z,size,b instanceof Turret.TurretBuild?(h+size)*.5f:h*.5f,size))continue;
            if(b instanceof Turret.TurretBuild turret&&turretModels.render(turret,animated,x,base,z,h,(float)runtime.turretControls().pitch(turret)))continue;
            if(blockAnimations.render(b,animated,x,base,z,size,h))continue;
            TextureRegion display=displays.texture(b);
            if(NativeAtlasMesh.valid(display))animated.top(display,x,base+h+.008f,z,size*.91f,size*.91f,0,1,1,1,false);
            if(b instanceof mindustry.world.blocks.defense.Door.DoorBuild door){renderDoor(b,door.open,((mindustry.world.blocks.defense.Door)b.block).openRegion,x,base,z,size,h);continue;}
            if(b instanceof mindustry.world.blocks.defense.AutoDoor.AutoDoorBuild door){renderDoor(b,door.open,((mindustry.world.blocks.defense.AutoDoor)b.block).openRegion,x,base,z,size,h);continue;}
            if(b.block instanceof mindustry.world.blocks.campaign.LaunchPad launch){
                float timer=fpv.construction.LayerCampaignFamilies.launchPodTimer(b),rise=timer>0?(1-timer/120f)*25:0;
                if(NativeAtlasMesh.valid(launch.podRegion))animated.top(launch.podRegion,x,base+h+.04f+rise,z,size*.78f,size*.78f,rise*4,1,1,1,false);
            }else if(b instanceof mindustry.world.blocks.campaign.LandingPad.LandingPadBuild landing&&b.block instanceof mindustry.world.blocks.campaign.LandingPad type){
                float rise=landing.arriving==null?0:(1-Mathf.clamp(landing.arrivingTimer))*25;
                if(NativeAtlasMesh.valid(type.podRegion))animated.top(type.podRegion,x,base+h+.04f+rise,z,size*.78f,size*.78f,rise*4,1,1,1,false);
            }else if(b instanceof mindustry.world.blocks.campaign.Accelerator.AcceleratorBuild accelerator&&b.block instanceof mindustry.world.blocks.campaign.Accelerator type&&accelerator.progress>.001f){
                float scale=Mathf.clamp(accelerator.progress),rise=accelerator.launching?Math.max(0,accelerator.launchTime-type.chargeDuration)/60f*8:0;
                animated.top(type.launchBlock.fullIcon,x,base+h+.04f+rise,z,type.launchBlock.size*scale,type.launchBlock.size*scale,0,1,1,1,false);
            }
            if(b instanceof Turret.TurretBuild turret){
                animated.pitchedTop(NativeRegions.turret(b.block),x+turret.recoilOffset.x/Vars.tilesize,base+h+.006f,z-turret.recoilOffset.y/Vars.tilesize,size,turret.rotation,(float)runtime.turretControls().pitch(turret));
            }else if(b instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild driver){
                animated.top(b.block.region,x,base+h+.006f,z,size,size,90-driver.rotation,1,1,1,false);
            }else if(b instanceof GenericCrafter.GenericCrafterBuild factory){
                float glow=.15f+factory.warmup*.7f;
                animated.top(Core.atlas.white(),x,base+h+.006f,z+size*.25f,size*.35f,size*.12f,0,glow,glow*.75f,.15f,false);
            }else if(b.block instanceof Conduit&&b.liquids!=null&&b.liquids.currentAmount()>.01f){
                var color=b.liquids.current().color;float amount=Mathf.clamp(b.liquids.currentAmount()/b.block.liquidCapacity);
                animated.top(Core.atlas.white(),x,base+h+.006f,z,size*.17f,size*.8f,-b.rotation*90,color.r*amount,color.g*amount,color.b*amount,false);
            }
        }
        animated.drawAndClear(shader);
    }
    private void renderDoor(Building door,boolean open,TextureRegion openRegion,float x,float base,float z,float size,float height){
        double y=-z,half=size*.5;
        if(!open){box(animated,door.block.region,new LayerCoordinates.Bounds(x-half,y-half,base,x+half,y+half,base+height),1,1,1);return;}
        if(NativeAtlasMesh.valid(openRegion))animated.top(openRegion,x,base+.02f,z,size,size,0,1,1,1,false);
        double jamb=Math.max(.08,size*.12);
        box(animated,door.block.region,new LayerCoordinates.Bounds(x-half,y-half,base,x-half+jamb,y+half,base+height),1,1,1);
        box(animated,door.block.region,new LayerCoordinates.Bounds(x+half-jamb,y-half,base,x+half,y+half,base+height),1,1,1);
    }
    private static void box(NativeAtlasMesh.Builder out,TextureRegion region,LayerCoordinates.Bounds b,float r,float g,float blue){
        box(out,region,b,r,g,blue,0,true);
    }
    private static void box(NativeAtlasMesh.Builder out,TextureRegion region,LayerCoordinates.Bounds b,float r,float g,float blue,float rotation,boolean top){
        float l=(float)b.minX(),right=(float)b.maxX(),n=(float)-b.maxY(),s=(float)-b.minY(),low=(float)b.minZ(),high=(float)b.maxZ();
        if(top)out.top(region,(l+right)/2,high,(n+s)/2,right-l,s-n,rotation,r,g,blue,false);
        out.projectionFace(region,l,low,s,right,low,s,right,low,n,l,low,n,r*.4f,g*.4f,blue*.4f,0);
        out.projectionFace(region,l,low,s,l,high,s,right,high,s,right,low,s,r*.8f,g*.8f,blue*.8f,0);
        out.projectionFace(region,right,low,n,right,high,n,l,high,n,l,low,n,r*.6f,g*.6f,blue*.6f,0);
        out.projectionFace(region,l,low,n,l,high,n,l,high,s,l,low,s,r*.7f,g*.7f,blue*.7f,0);
        out.projectionFace(region,right,low,s,right,high,s,right,high,n,right,low,n,r*.9f,g*.9f,blue*.9f,0);
    }
    public void renderOverlay(Camera3D camera){
        if(runtime==null)return;
        schematicRenderer.render(camera,runtime);
        constructionVisuals.begin();long now=Time.millis();
        while(!activities.isEmpty()&&now-activities.peekFirst().when>400)activities.removeFirst();
        boolean showPlane=planeVisible&&selectedLayer>0;
        boolean hasWarning=false;for(var c:runtime.grid().components())if(visibleIds.contains(c.id())&&runtime.warning(c.id())){hasWarning=true;break;}
        if(!showPlane&&!hasWarning&&activities.isEmpty())return;
        if(overlayShader==null)overlayShader=new Shader(
            "attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec4 c;void main(){c=a_color;gl_Position=u_combined*vec4(a_position,1.0);}",
            "varying vec4 c;void main(){gl_FragColor=c;}");
        overlayShader.bind();overlayShader.setUniformMatrix4("u_combined",camera.combined.val);overlay.overflowShader=overlayShader;overlay.clear();
        float height=showPlane?(float)runtime.planeHeight(selectedLayer)+.008f:0;
        int centerX=hasPick?selectedX:(int)camera.position.x,centerY=hasPick?selectedY:(int)-camera.position.z;
        int left=Math.max(0,centerX-18),right=Math.min(Vars.world.width()-1,centerX+18),bottom=Math.max(0,centerY-18),top=Math.min(Vars.world.height()-1,centerY+18);
        boolean blend=Gl.isEnabled(Gl.blend),write=Gl.getInt(Gl.depthWritemask)!=0;
        Gl.enable(Gl.blend);Gl.blendFunc(Gl.srcAlpha,Gl.oneMinusSrcAlpha);Gl.depthMask(false);
        try{
            for(var c:runtime.grid().components())if(visibleIds.contains(c.id())&&runtime.warning(c.id())){var b=runtime.grid().coordinates().bounds(c);rect((float)b.minX(),(float)b.minY(),(float)b.maxX(),(float)b.maxY(),(float)b.maxZ()+.01f,1,.12f,.06f,.35f+(float)Math.sin(arc.util.Time.time*.15f)*.15f);}
            if(showPlane&&right>=left&&top>=bottom){
            for(int x=left;x<=right+1;x++)rect(x-.512f,bottom-.5f,x-.488f,top+.5f,height,.3f,.8f,1,.22f);
            for(int y=bottom;y<=top+1;y++)rect(left-.5f,y-.512f,right+.5f,y-.488f,height,.3f,.8f,1,.22f);
            if(hasPick){float x=(float)LayerCoordinates.center(selectedX,selectedSize),y=(float)LayerCoordinates.center(selectedY,selectedSize),half=selectedSize*.5f;
                rect(x-half,y-half,x+half,y+half,height+.005f,validPick?.25f:1,validPick?1:.2f,.3f,.30f);
                if(previewPlatform)constructionVisuals.ghost(Core.atlas.white(),x,y,runtime.planeHeight(selectedLayer)-LayerCoordinates.PLATFORM_THICKNESS,1,LayerCoordinates.PLATFORM_THICKNESS,0,validPick,.38f);
                else if(previewBlock!=null){
                    var entry=runtime.catalog().entry(previewBlock.name,false,runtime.grid().coordinates().spacing());
                    double ghostHeight=entry==null?fpv.net.HeadlessGeometry.heightFor(previewBlock):entry.height();
                    constructionVisuals.ghost(previewBlock,x,y,runtime.planeHeight(selectedLayer),ghostHeight,previewRotation,validPick,.48f);
                }
            }
            }
            overlay.drawAndClear(overlayShader);
        }finally{Gl.depthMask(write);if(!blend)Gl.disable(Gl.blend);}
        for(var activity:activities){
            if(altitudes==null||activity.unit.dead||activity.unit.inFogTo(Vars.player.team()))continue;
            var pose=altitudes.unitState(activity.unit);if(pose==null)continue;
            double x=LayerCoordinates.center(activity.x,activity.size),y=LayerCoordinates.center(activity.y,activity.size),z=runtime.planeHeight(activity.layer)+.035;
            var c=runtime.grid().at(activity.x,activity.y,activity.layer,LayerComponent.Kind.BLOCK);if(c!=null)z+=c.height();
            constructionVisuals.beam(pose.x(),pose.y(),pose.z()-.04,x,y,z,activity.size,activity.removing,(float)(1-(now-activity.when)/400.0));
        }
        constructionVisuals.draw(camera);
    }
    private void rect(float minX,float minY,float maxX,float maxY,float z,float r,float g,float b,float alpha){
        overlay.projectionFace(Core.atlas.white(),minX,z,-minY,maxX,z,-minY,maxX,z,-maxY,minX,z,-maxY,r,g,b,alpha);
    }
    @Override public void close(){schematicRenderer.close();interactionRenderer.close();displays.close();constructionVisuals.close();activities.clear();for(var c:chunks.values())c.mesh.dispose();chunks.clear();for(var m:fallingMeshes.values())m.dispose();fallingMeshes.clear();if(connections!=null)connections.dispose();builder.dispose();overlay.dispose();animated.dispose();if(overlayShader!=null)overlayShader.dispose();if(gpu!=null)gpu.close();}
}
