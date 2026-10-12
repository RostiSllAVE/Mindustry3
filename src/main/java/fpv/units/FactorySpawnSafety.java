package fpv.units;

import arc.Events;
import arc.func.Cons;
import arc.util.Time;
import java.util.ArrayList;
import fpv.world.Unit3DState;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitUnloadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild;
import mindustry.world.blocks.payloads.UnitPayload;

/** Native payload production/transport remains intact. Only the final add-to-world boundary gains full XYZ clearance. */
public final class FactorySpawnSafety implements AutoCloseable {
    private static final double MARGIN=.06,STEP=.2;
    private final World3DAdapter world;
    private final UnitAltitudeSystem altitudes;
    private boolean installed,closed;
    private final Cons<UnitCreateEvent> create=e->{if(!closed&&e.spawner!=null)wrap(e.spawner);};
    private Pending pending;
    private record Pending(Unit unit,Placement placement,float vx,float vy){}
    private final Cons<UnitUnloadEvent> unload=e->initializeRelease(e.unit);
    private final Cons<WorldLoadEvent> load=e->{if(!closed)wrapLoadedPayloads();};
    private int releases,blocked;
    private final ArrayList<Unit> sameFrameReleases=new ArrayList<>();
    private float releaseFrame=Float.NaN;
    private Object releaseWorld;
    private String obstruction="";
    public String lastObstruction(){return obstruction;}
    private boolean obstructed(String reason){obstruction=reason;return false;}
    public FactorySpawnSafety(World3DAdapter world,UnitAltitudeSystem altitudes){if(world==null||altitudes==null)throw new IllegalArgumentException("Spawn world required");this.world=world;this.altitudes=altitudes;}
    private void initializeRelease(Unit unit){
        Pending release=pending;if(closed||release==null||release.unit!=unit||!unit.isAdded()||unit.dead)return;
        // Before Units.notifyUnitSpawn: the first native network packet must contain safe birth velocity.
        unit.vel.set(release.vx,release.vy);altitudes.initializePayloadRelease(unit,release.placement.z);
        sameFrameReleases.add(unit);releases++;
    }
    public void install(){if(installed)return;installed=true;Events.on(UnitCreateEvent.class,create);Events.on(UnitUnloadEvent.class,unload);Events.on(WorldLoadEvent.class,load);wrapLoadedPayloads();}
    private void wrapLoadedPayloads(){if(Vars.net.client())return;for(Building building:Groups.build)wrap(building);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private void wrap(Building building){
        if(closed||Vars.net.client()||building.tile instanceof fpv.construction.LayerTile||!(building instanceof PayloadBlockBuild holder)||!(holder.payload instanceof UnitPayload original))return;
        if(original instanceof FactorySpawnSafety.SafePayload safe){safe.holder=holder;safe.retryAt=0;return;}
        if(original.getClass()!=UnitPayload.class)return;
        SafePayload replacement=new SafePayload(original.unit,holder);replacement.overlayTime=original.overlayTime;replacement.overlayRegion=original.overlayRegion;holder.payload=replacement;
    }
    public int releases(){return releases;}
    public int blockedAttempts(){return blocked;}
    /** Upper factories release through this boundary instead of UnitPayload.dump's XY solidity/RPCs. */
    public boolean releaseUpper(Building source,Unit unit,double nativeX,double nativeY){
        if(closed||Vars.net.client()||source==null||!(source.tile instanceof fpv.construction.LayerTile)||unit==null||unit.type==null||unit.isAdded()||unit.dead||
            !unit.spawnedByCore&&!mindustry.entities.Units.canCreate(unit.team,unit.type)||!Double.isFinite(nativeX+nativeY))return false;
        Placement placement=upperPlacement(source,unit,nativeX,nativeY);if(placement==null){blocked++;return false;}
        unit.set((float)(placement.x*Vars.tilesize),(float)(placement.y*Vars.tilesize));
        unit.vel.setZero();unit.add();if(!unit.isAdded())return false;
        altitudes.initializePayloadRelease(unit,placement.z);unit.unloaded();sameFrameReleases.add(unit);releases++;
        Events.fire(new UnitUnloadEvent(unit));mindustry.entities.Units.notifyUnitSpawn(unit);return true;
    }
    /** Read-only probe used by assembly areas and tests; all positions are native XY at this boundary. */
    public Placement upperPlacement(Building source,Unit unit,double nativeX,double nativeY){
        if(source==null||source.dead||!(source.tile instanceof fpv.construction.LayerTile layer)||unit==null||unit.type==null||!Double.isFinite(nativeX+nativeY))return null;
        if(releaseFrame!=Time.time||releaseWorld!=Vars.world.tiles){sameFrameReleases.clear();releaseFrame=Time.time;releaseWorld=Vars.world.tiles;}
        double ax=nativeX/Vars.tilesize,ay=nativeY/Vars.tilesize,angle=Math.toRadians(source.rotdeg()),dx=Math.cos(angle),dy=Math.sin(angle);
        Unit3DState body=altitudes.previewPayloadRelease(unit,ax,ay);
        double along=(ax-source.x/Vars.tilesize)*dx+(ay-source.y/Vars.tilesize)*dy;
        double distance=Math.max(0,source.block.size*.5+body.radius()+MARGIN-along);
        double x=ax+dx*distance,y=ay+dy*distance;
        body=altitudes.previewPayloadRelease(unit,x,y);
        double localHeight=body.z()-world.terrainHeight(x,y),z=world.buildingBase(source)+Math.max(body.halfHeight()+.015,localHeight);
        if(unit instanceof mindustry.gen.WaterMovec&&!unit.isFlying()){
            // Upper slabs are not water. Do not create a boat suspended over the Z0 liquid.
            var solid=unit.solidity();
            if(Math.abs(world.buildingBase(source)-world.terrainHeight(x,y))>.02||solid!=null&&solid.solid((int)Math.floor(x+.5),(int)Math.floor(y+.5)))return null;
            z=world.terrainHeight(x,y)+body.halfHeight();
        }
        if(!Double.isFinite(z)||distance>128)return null;
        int steps=Math.max(1,(int)Math.ceil(distance/STEP));if(steps>512)return null;
        for(int i=0;i<=steps;i++){
            double t=i/(double)steps;
            if(!clearUpper(unit,source,ax+(x-ax)*t,ay+(y-ay)*t,z,body.radius(),body.halfHeight()))return null;
        }
        // Own producer is deliberately excluded by clearUpper; retain force/projected shields
        // without reintroducing its solid housing through the combined construction barrier.
        if(world.traceExternalUnitBarrier(unit,ax,ay,z,x,y,z,body.radius(),body.halfHeight())!=null)return null;
        return new Placement(x,y,z,body.radius(),body.halfHeight());
    }
    private boolean clearUpper(Unit unit,Building source,double x,double y,double z,double radius,double half){
        int minX=(int)Math.ceil(x-radius-.5),maxX=(int)Math.floor(x+radius+.5),minY=(int)Math.ceil(y-radius-.5),maxY=(int)Math.floor(y+radius+.5);
        for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
            Tile tile=Vars.world.tile(tx,ty);if(tile==null)return false;
            if(world.terrainHeight(tx,ty)>z-half+.02||world.surfaceHeight(tx,ty)>z-half+.02)return false;
        }
        var construction=world.construction();var grid=construction==null?null:construction.grid();
        if(grid!=null){
            var own=grid.at(source.tile.x,source.tile.y,((fpv.construction.LayerTile)source.tile).layer,fpv.construction.LayerComponent.Kind.BLOCK);
            var body=new fpv.construction.LayerCoordinates.Bounds(x-radius,y-radius,z-half,x+radius,y+radius,z+half);
            for(var component:grid.query(body))if((own==null||component.id()!=own.id())&&construction.solid(component)&&grid.coordinates().bounds(component).overlaps(body))return false;
        }
        // This release boundary is infrequent, and the full live list includes same-tick births
        // before the native unit quadtree has caught up. Vertical separation is authoritative.
        for(Unit other:Groups.unit){
            if(other==unit||other.dead||!other.isAdded())continue;Unit3DState state=world.unitState(other);if(state==null)continue;
            if(Math.abs(state.z()-z)<state.halfHeight()+half&&Math.hypot(state.x()-x,state.y()-y)<state.radius()+radius+MARGIN)return false;
        }
        return true;
    }
    /** Exposed read-only result also lets native fixtures verify exact release geometry. */
    public record Placement(double x,double y,double z,double radius,double halfHeight){}
    public Placement placement(Unit unit,Building source){
        obstruction="";
        if(unit==null||unit.type==null||source==null||source.dead||source.tile==null)return null;
        if(releaseFrame!=Time.time||releaseWorld!=Vars.world.tiles){sameFrameReleases.clear();releaseFrame=Time.time;releaseWorld=Vars.world.tiles;}
        double ax=unit.x/(double)Vars.tilesize,ay=unit.y/(double)Vars.tilesize;
        double angle=Math.toRadians(source.rotdeg()),dx=Math.cos(angle),dy=Math.sin(angle);
        Unit3DState body=altitudes.previewPayloadRelease(unit,ax,ay);
        double cx=source.x/(double)Vars.tilesize,cy=source.y/(double)Vars.tilesize;
        double along=(ax-cx)*dx+(ay-cy)*dy,edge=source.block.size*.5;
        double distance=Math.max(0,edge+body.radius()+MARGIN-along);
        // A production payload exits straight through its own mouth, never teleports around a blocked wall.
        if(!Double.isFinite(distance)||distance>source.block.size+body.radius()+1)return null;
        double x=ax+dx*distance,y=ay+dy*distance;
        body=altitudes.previewPayloadRelease(unit,x,y);
        double z=body.z();
        boolean water=unit instanceof mindustry.gen.WaterMovec&&!unit.isFlying();
        // Native water navigation checks the center tile. Hull edges may overhang a flat shore;
        // they must still clear solid objects, but dry floor alone is not a physical wall.
        if(water){
            var solid=unit.solidity();
            int tx=mindustry.core.World.toTile((float)(x*Vars.tilesize)),ty=mindustry.core.World.toTile((float)(y*Vars.tilesize));
            if(solid!=null&&solid.solid(tx,ty)){var tile=Vars.world.tile(tx,ty);obstruction=tile==null?"output outside map":tile.solid()?"solid output: "+tile.block().name+" at "+tx+","+ty:"output requires water at "+tx+","+ty;return null;}
            z=world.terrainHeight(x,y)+body.halfHeight();
        }else if(!unit.isFlying()){
            var profile=fpv.units.ground.GroundUnitProfiles.profile(unit);
            double stance=profile==null?.015:profile.normalStance();
            double step=profile==null?fpv.generated.UnitsConfig.GROUND_SUPPORT_STEP_TILES:
                profile.legged()?profile.maximumClearance():profile.normalStance()+fpv.generated.GroundConfig.CRAWLER_STEP_TILES;
            z=Math.max(z,passableExitSupport(unit,source,x,y,body.radius(),step)+body.halfHeight()+stance);
        }
        int steps=Math.max(1,(int)Math.ceil(distance/STEP));if(steps>256)return null;
        for(int i=0;i<=steps;i++){
            double t=i/(double)steps,sx=ax+(x-ax)*t,sy=ay+(y-ay)*t;
            if(!clear(unit,source,sx,sy,z,body.radius(),body.halfHeight(),i==steps))return null;
        }
        if(world.traceUnitBarrier(unit,ax,ay,z,x,y,z,body.radius(),body.halfHeight())!=null)return null;
        return new Placement(x,y,z,body.radius(),body.halfHeight());
    }
    /** Low native-passable structures such as conveyors are a physical floor, not a blocked exit. */
    private double passableExitSupport(Unit unit,Building source,double x,double y,double radius,double step){
        double support=world.terrainHeight(x,y);var solid=unit.solidity();
        int minX=(int)Math.ceil(x-radius-.5),maxX=(int)Math.floor(x+radius+.5);
        int minY=(int)Math.ceil(y-radius-.5),maxY=(int)Math.floor(y+radius+.5);
        for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
            double nx=Math.max(tx-.5,Math.min(tx+.5,x)),ny=Math.max(ty-.5,Math.min(ty+.5,y));
            if(Math.hypot(x-nx,y-ny)>radius+1e-7)continue;
            Tile tile=Vars.world.tile(tx,ty);
            if(tile==null||tile.build==null||tile.build==source||tile.block().solid||solid!=null&&solid.solid(tx,ty))continue;
            double floor=world.terrainHeight(tx,ty),top=world.surfaceHeight(tx,ty);
            if(top-floor<=step+1e-6)support=Math.max(support,top);
        }
        return support;
    }
    private boolean clear(Unit unit,Building source,double x,double y,double z,double radius,double half,boolean finalPosition){
        int minX=(int)Math.ceil(x-radius-.5),maxX=(int)Math.floor(x+radius+.5);
        int minY=(int)Math.ceil(y-radius-.5),maxY=(int)Math.floor(y+radius+.5);
        var solid=unit.solidity();
        for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
            double nx=Math.max(tx-.5,Math.min(tx+.5,x)),ny=Math.max(ty-.5,Math.min(ty+.5,y));
            if(Math.hypot(x-nx,y-ny)>radius+1e-7)continue;
            Tile tile=Vars.world.tile(tx,ty);if(tile==null)return false;
            if(tile.build==source){if(finalPosition)return false;continue;}
            // Preserve native movement categories (water, mech, leg-step, air) while testing the whole hull.
            if(unit instanceof mindustry.gen.WaterMovec&&!unit.isFlying()){
                if(tile.solid())return obstructed("solid hull obstruction: "+tile.block().name+" at "+tx+","+ty);
            }else if(solid!=null&&solid.solid(tx,ty))return false;
            if(world.terrainHeight(nx,ny)>z-half+.02)return obstructed("terrain intersects hull at "+tx+","+ty);
            if((tile.build!=null||tile.block().solid)&&world.surfaceHeight(tx,ty)>z-half+.02)return obstructed("structure intersects hull: "+tile.block().name+" at "+tx+","+ty);
        }
        final boolean[] occupied={false};float r=(float)(radius*Vars.tilesize);
        // Native broad phase is rebuilt once per tick, not by unit.add; explicitly include births later in this same tick.
        Groups.unit.intersect((float)(x*Vars.tilesize-r),(float)(y*Vars.tilesize-r),r*2,r*2,other->{
            if(occupied[0]||other==unit||other.dead)return;Unit3DState p=world.unitState(other);if(p==null)return;
            if(Math.abs(p.z()-z)<p.halfHeight()+half&&Math.hypot(p.x()-x,p.y()-y)<p.radius()+radius+MARGIN)occupied[0]=true;
        });
        if(occupied[0])return false;
        for(Unit other:sameFrameReleases){
            if(other==unit||other.dead||!other.isAdded())continue;Unit3DState p=world.unitState(other);if(p==null)continue;
            if(Math.abs(p.z()-z)<p.halfHeight()+half&&Math.hypot(p.x()-x,p.y()-y)<p.radius()+radius+MARGIN)return false;
        }
        return true;
    }
    private final class SafePayload extends UnitPayload {
        private Building holder;
        private float retryAt;
        private String reportedObstruction="";
        SafePayload(Unit unit,Building holder){super(unit);this.holder=holder;}
        @Override public void update(Unit unitHolder,Building buildingHolder){holder=buildingHolder;super.update(unitHolder,buildingHolder);}
        @Override public boolean dump(){
            if(holder!=null&&holder.getPayload()!=this){
                holder=null;for(Building b:Groups.build)if(b.getPayload()==this){holder=b;break;}
            }
            if(closed||holder==null||holder.dead||holder.tile==null||Vars.net.client())return super.dump();
            if(Time.time<retryAt)return false;
            Placement p=placement(unit,holder);
            if(p==null){
                blocked++;retryAt=Time.time+6;
                if(unit instanceof mindustry.gen.WaterMovec){
                    String reason=obstruction.isEmpty()?"output hull overlaps another unit or 3D barrier":obstruction;
                    if(!reason.equals(reportedObstruction)){arc.util.Log.info("[FPV] @ waiting at @,@: @",unit.type.name,holder.tile.x,holder.tile.y,reason);reportedObstruction=reason;}
                    if(!Vars.headless)showOverlay(mindustry.gen.Icon.warning);
                }
                return false;
            }
            reportedObstruction="";
            float oldX=unit.x,oldY=unit.y,oldVX=unit.vel.x,oldVY=unit.vel.y;
            unit.set((float)(p.x*Vars.tilesize),(float)(p.y*Vars.tilesize));
            boolean added=false;Pending previous=pending;pending=new Pending(unit,p,oldVX,oldVY);
            try{
                if(!super.dump()){retryAt=Time.time+6;return false;}
                added=unit.isAdded();
                return true;
            }finally{pending=previous;if(!added)unit.set(oldX,oldY);}
        }
    }
    @Override public void close(){if(closed)return;closed=true;sameFrameReleases.clear();if(installed){Events.remove(UnitCreateEvent.class,create);Events.remove(UnitUnloadEvent.class,unload);Events.remove(WorldLoadEvent.class,load);}installed=false;}
}
