package fpv.defense;

import arc.Core;
import arc.struct.LongMap;
import fpv.profile.WallProfile;
import fpv.combat.ProjectedDefense;
import fpv.generated.DefenseConfig;
import fpv.world.World3DAdapter;
import fpv.physics.SweptBodies;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.meta.BlockGroup;

/** Native wall sidecars: no entities, native health mutation, atlas dependency or save writes. */
public final class ProjectedWallSystem implements ProjectedDefense,AutoCloseable {
    public record Settings(double healthMultiplier,int segments,double regenDelay,double regenRate,boolean blocksUnits,double legStepMultiplier){
        public Settings(double hp,int segments,double delay,double rate,boolean units){this(hp,segments,delay,rate,units,DefenseConfig.LEG_STEP_HEIGHT_MULTIPLIER);}
        public Settings{
            if(!Double.isFinite(healthMultiplier+regenDelay+regenRate+legStepMultiplier)||healthMultiplier<.75||healthMultiplier>1||
                segments<1||segments>10||regenDelay<0||regenDelay>60||regenRate<0||regenRate>.2||legStepMultiplier<.5||legStepMultiplier>3)
                throw new IllegalArgumentException("Invalid projection settings");
        }
    }
    public record Replication(Settings settings,List<ProjectionSnapshot> damaged,long epoch){
        public Replication{damaged=List.copyOf(damaged);}
    }
    public record Volume(Building building,double x,double y,double halfWidth,double baseZ,double fullTopZ,
                         double topZ,float hp,float maxHp){
        public double fraction(){return maxHp>0?hp/maxHp:0;}
    }
    private static final class State{
        final Building building;final int id,blockId,teamId,anchorPos,minChunkX,maxChunkX,minChunkY,maxChunkY;
        float hp,maxHp,cooldown;double baseZ,topZ;long revision;
        long queryStamp,visibleStamp; Volume cachedVolume; RenderChunk renderChunk; boolean renderedActive;
        State(Building b){
            building=b;id=b.id;blockId=b.block.id;teamId=b.team.id;anchorPos=b.tile.pos();
            double half=b.block.size/2.0,x=b.x/Vars.tilesize,y=b.y/Vars.tilesize;
            minChunkX=chunk(x-half);maxChunkX=chunk(x+half);minChunkY=chunk(y-half);maxChunkY=chunk(y+half);
        }
    }
    private record Token(State state,double x,double y,double z,double radius){}
    private final World3DAdapter world;
    private final BooleanSupplier authority;
    private java.util.function.Predicate<Building> covered=building->false;
    /** Coverage suppresses the active projection without discarding its HP or regeneration state. */
    public void setCoveredPredicate(java.util.function.Predicate<Building> value){covered=value==null?building->false:value;lifecycleFrame=Long.MIN_VALUE;}
    private final Map<Integer,State> states=new HashMap<>();
    private final LongMap<ArrayList<State>> chunks=new LongMap<>();
    private final LongMap<Long> queriedChunks=new LongMap<>();
    private final HashSet<State> damaged=new HashSet<>();
    private final LongMap<RenderChunk> renderIndex=new LongMap<>();
    private final ArrayList<RenderChunk> renderChunks=new ArrayList<>();
    private final ArrayList<State> lifecycleChanges=new ArrayList<>();
    private long geometryRevision,generation,lifecycleFrame=Long.MIN_VALUE;
    /** Cached chunk membership. Logical health remains in individual native wall sidecars. */
    public final class RenderChunk {
        private final long key;private final ArrayList<State> members=new ArrayList<>();
        private final ArrayList<Volume> cachedVolumes=new ArrayList<>();
        private long revision,materializedRevision=-1;
        private double minX,minY,maxX,maxY,maxZ;
        private RenderChunk(long key){this.key=key;}
        public long key(){return key;} public long revision(){return revision;}
        public double minX(){return minX;} public double minY(){return minY;}
        public double maxX(){return maxX;} public double maxY(){return maxY;} public double maxZ(){return maxZ;}
        public List<Volume> volumes(){
            if(materializedRevision!=revision){
                cachedVolumes.clear();minX=minY=Double.POSITIVE_INFINITY;maxX=maxY=maxZ=Double.NEGATIVE_INFINITY;
                for(int i=0;i<members.size();i++){State s=members.get(i);if(!active(s))continue;Volume v=volume(s);cachedVolumes.add(v);
                    minX=Math.min(minX,v.x-v.halfWidth);minY=Math.min(minY,v.y-v.halfWidth);maxX=Math.max(maxX,v.x+v.halfWidth);maxY=Math.max(maxY,v.y+v.halfWidth);maxZ=Math.max(maxZ,v.topZ);
                }
                materializedRevision=revision;
            }
            return cachedVolumes;
        }
    }
    private final ArrayList<State> candidates=new ArrayList<>();
    private long queryStamp,visibleStamp;
    private boolean initialized;
    private List<Volume> volumes=List.of();
    private Object worldTiles;
    private int tileChanges=-1;
    private long revision;
    private long settingsFrame=Long.MIN_VALUE;
    private boolean volumesDirty=true;
    private double highestTop;
    
    private Settings settings=defaults();
    private int profiledCandidates;

    public ProjectedWallSystem(World3DAdapter world,BooleanSupplier authority){
        this.world=Objects.requireNonNull(world);this.authority=Objects.requireNonNull(authority);
    }
    public static Settings defaults(){return new Settings(DefenseConfig.PROJECTION_HEALTH_MULTIPLIER,(int)DefenseConfig.PROJECTION_SEGMENTS,
        DefenseConfig.REGEN_DELAY_SECONDS,DefenseConfig.REGEN_FRACTION_PER_SECOND,DefenseConfig.PROJECTION_BLOCKS_UNITS!=0);}
    private static Settings readSettings(){
        var d=defaults();if(Core.settings==null)return d;
        return new Settings(bound(Core.settings.getInt("fpv-wall-hp-percent",(int)Math.round(d.healthMultiplier*100)),75,100)/100.0,
            bound(Core.settings.getInt("fpv-wall-segments",d.segments),1,10),
            bound(Core.settings.getInt("fpv-wall-regen-delay",(int)d.regenDelay),0,60),
            bound(Core.settings.getInt("fpv-wall-regen-permille",(int)Math.round(d.regenRate*1000)),0,200)/1000.0,
            Core.settings.getBool("fpv-wall-unit-collision",d.blocksUnits),
            bound(Core.settings.getInt("fpv-leg-step-percent",(int)Math.round(d.legStepMultiplier*100)),50,300)/100.0);
    }
    public Settings settings(){sync();return settings;}
    public static boolean eligible(Building b){
        return attached(b)&&(b.block instanceof Wall||b.block.group==BlockGroup.walls)&&b.block.destructible&&b.maxHealth>0;
    }
    private static boolean attached(Building b){return b!=null&&b.tile!=null&&!b.dead&&Vars.world!=null&&
        Vars.world.tile(b.tile.x,b.tile.y)==b.tile&&b.tile.build==b;}
    private boolean active(State s){return !covered.test(s.building)&&eligible(s.building)&&s.building.id==s.id&&s.building.block.id==s.blockId&&
        s.building.team.id==s.teamId&&(s.building.block.solid||s.building.checkSolid())&&s.hp>0;}
    private void sync(){
        if(Vars.world==null||Vars.world.tiles==null){clear();return;}
        if(worldTiles!=Vars.world.tiles){clear();worldTiles=Vars.world.tiles;}
        long frame=Vars.state==null?0:Vars.state.updateId;
        if(authority.getAsBoolean()&&settingsFrame!=frame){
            settingsFrame=frame;
            Settings next=readSettings();if(!next.equals(settings)){settings=next;for(State s:states.values())configure(s,true);volumesDirty=true;}
        }
        if(!initialized||tileChanges!=Vars.world.tileChanges)rebuild();
        if(lifecycleFrame!=frame){lifecycleFrame=frame;checkLifecycle();}
    }
    private void configure(State s,boolean preserve){
        double ratio=preserve&&s.maxHp>0?s.hp/s.maxHp:1;
        s.maxHp=(float)(s.building.maxHealth*settings.healthMultiplier);s.hp=(float)(s.maxHp*Math.max(0,Math.min(1,ratio)));
        s.baseZ=world.surfaceHeight(s.building.x/Vars.tilesize,s.building.y/Vars.tilesize);
        s.topZ=s.baseZ+Math.max(.01,s.baseZ-world.terrainHeight(s.building.x/Vars.tilesize,s.building.y/Vars.tilesize))*settings.segments;
        highestTop=Math.max(highestTop,s.topZ);
        s.revision=++revision;s.cachedVolume=null;touch(s);
        if(s.hp<s.maxHp||s.cooldown>0)damaged.add(s);else damaged.remove(s);
    }
    private void rebuild(){
        WallProfile.fullScan();
        HashMap<Integer,State> old=new HashMap<>(states);states.clear();chunks.clear();damaged.clear();renderIndex.clear();renderChunks.clear();
        for(Tile tile:Vars.world.tiles){
            Building b=tile.build;if(b==null||b.tile!=tile||!eligible(b))continue;
            State s=old.get(tile.pos());
            if(s==null||s.building!=b||s.id!=b.id||s.blockId!=b.block.id||s.teamId!=b.team.id){
                s=new State(b);configure(s,false);
            }
            states.put(tile.pos(),s);index(s);if(s.hp<s.maxHp||s.cooldown>0)damaged.add(s);
        }
        tileChanges=Vars.world.tileChanges;initialized=true;volumesDirty=true;
    }
    /** Event hook avoids a full map scan for normal placement/removal, including multiblock edge events. */
    public void invalidate(Tile tile){
        if(tile==null||worldTiles!=Vars.world.tiles||!initialized||Vars.world.tileChanges>tileChanges+1){initialized=false;return;}
        ArrayList<State> remove=new ArrayList<>();
        // Every footprint is indexed into every overlapped chunk, so even a large block's edge
        // notification finds its old anchor without walking unrelated walls across the map.
        var local=chunks.get(key(chunk(tile.x),chunk(tile.y)));
        if(local!=null)for(State s:local)if(!eligible(s.building)||s.building.id!=s.id||s.building.team.id!=s.teamId)remove.add(s);
        for(State s:remove){states.remove(s.anchorPos,s);damaged.remove(s);unindex(s);}
        Building b=tile.build;if(eligible(b)&&!states.containsKey(b.tile.pos())){State s=new State(b);configure(s,false);states.put(b.tile.pos(),s);index(s);}
        tileChanges=Vars.world.tileChanges;initialized=true;volumesDirty=true;
    }
    private void index(State s){
        long owner=key(chunk(s.building.x/Vars.tilesize),chunk(s.building.y/Vars.tilesize));RenderChunk render=renderIndex.get(owner);
        if(render==null){render=new RenderChunk(owner);renderIndex.put(owner,render);renderChunks.add(render);}
        render.members.add(s);s.renderChunk=render;s.renderedActive=active(s);touch(s);
        for(int cx=s.minChunkX;cx<=s.maxChunkX;cx++)for(int cy=s.minChunkY;cy<=s.maxChunkY;cy++)
            {long key=key(cx,cy);ArrayList<State> list=chunks.get(key);if(list==null){list=new ArrayList<>();chunks.put(key,list);}list.add(s);}
    }
    private void unindex(State s){
        touch(s);RenderChunk render=s.renderChunk;if(render!=null){render.members.remove(s);if(render.members.isEmpty()){renderIndex.remove(render.key);renderChunks.remove(render);}s.renderChunk=null;}
        for(int cx=s.minChunkX;cx<=s.maxChunkX;cx++)for(int cy=s.minChunkY;cy<=s.maxChunkY;cy++){
            long key=key(cx,cy);var list=chunks.get(key);if(list!=null){list.remove(s);if(list.isEmpty())chunks.remove(key);}
        }
    }
    public void update(double dt){
        long started=WallProfile.start(),bytes=WallProfile.allocated();
        try{updateMeasured(dt);}finally{WallProfile.update(started,bytes);}
    }
    private void updateMeasured(double dt){
        if(!Double.isFinite(dt)||dt<0)throw new IllegalArgumentException("Finite nonnegative simulation seconds required");
        settingsFrame=Long.MIN_VALUE;lifecycleFrame=Long.MIN_VALUE;
        sync();if(!authority.getAsBoolean()||dt==0)return;
        for(var it=damaged.iterator();it.hasNext();){State s=it.next();if(!eligible(s.building)){it.remove();continue;}
            float hp=(float)ProjectionMath.regenerated(s.hp,s.maxHp,s.cooldown,dt,settings.regenRate);
            float cooldown=(float)Math.max(0,s.cooldown-dt);
            if(hp!=s.hp||cooldown!=s.cooldown){if(hp!=s.hp){s.cachedVolume=null;touch(s);}s.hp=hp;s.cooldown=cooldown;s.revision=++revision;volumesDirty=true;}
            if(s.hp>=s.maxHp&&s.cooldown<=0)it.remove();
        }
    }
    public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){
        return traceInternal(team,ax,ay,az,bx,by,bz,radius,true,false);
    }
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){
        return traceInternal(team,ax,ay,az,bx,by,bz,radius,true,true);
    }
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls){
        return absorbLaserWalls?traceLaser(team,ax,ay,az,bx,by,bz,radius):null;
    }
    /** Optional unit barrier: allies are also obstructed, but native leg-capable units always bypass projections. */
    public Contact traceUnit(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius){
        sync();if(!settings.blocksUnits||LegTraversal.capable(unit))return null;
        return traceInternal(unit==null?null:unit.team,ax,ay,az,bx,by,bz,radius,false,false);
    }
    /** Optional physical barrier with independent horizontal/vertical body extents and exact face normal. */
    public SweptBodies.Hit sweepUnit(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        long started=WallProfile.start(),bytes=WallProfile.allocated();profiledCandidates=0;
        try{return sweepUnitMeasured(unit,ax,ay,az,bx,by,bz,radius,halfHeight);}finally{WallProfile.query(started,bytes,profiledCandidates);}
    }
    private SweptBodies.Hit sweepUnitMeasured(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        sync();if(!settings.blocksUnits||LegTraversal.capable(unit)||radius<0||halfHeight<0||
            !Double.isFinite(ax+ay+az+bx+by+bz+radius+halfHeight))return null;
        collect(ax,ay,bx,by,radius);SweptBodies.Hit closest=null;
        for(int candidateIndex=0;candidateIndex<candidates.size();candidateIndex++){State s=candidates.get(candidateIndex);if(!active(s))continue;
            double x=s.building.x/Vars.tilesize,y=s.building.y/Vars.tilesize,h=s.building.block.size/2.0;
            var hit=SweptBodies.box(ax,ay,az,bx-ax,by-ay,bz-az,x-h-radius,y-h-radius,s.baseZ-halfHeight,
                x+h+radius,y+h+radius,ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp)+halfHeight);
            if(hit!=null&&(closest==null||hit.time()<closest.time()))closest=hit;
        }
        return closest;
    }
    private Contact traceInternal(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean friendlyPass,boolean laser){
        long started=WallProfile.start(),bytes=WallProfile.allocated();profiledCandidates=0;
        try{return traceMeasured(team,ax,ay,az,bx,by,bz,radius,friendlyPass,laser);}finally{WallProfile.query(started,bytes,profiledCandidates);}
    }
    private Contact traceMeasured(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean friendlyPass,boolean laser){
        sync();if(!Double.isFinite(ax+ay+az+bx+by+bz+radius)||radius<0)return null;
        collect(ax,ay,bx,by,radius);
        State closest=null;double fraction=Double.POSITIVE_INFINITY;
        for(int candidateIndex=0;candidateIndex<candidates.size();candidateIndex++){State s=candidates.get(candidateIndex);if(!active(s)||(friendlyPass&&team!=null&&s.teamId==team.id)||(laser&&!s.building.absorbLasers()))continue;
            double x=s.building.x/Vars.tilesize,y=s.building.y/Vars.tilesize,h=s.building.block.size/2.0;
            double t=ProjectionMath.sweep(ax,ay,az,bx,by,bz,x-h,y-h,s.baseZ,x+h,y+h,ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp),radius);
            if(t>=0&&t<fraction){fraction=t;closest=s;}
        }
        if(closest==null)return null;
        double x=ax+(bx-ax)*fraction,y=ay+(by-ay)*fraction,z=az+(bz-az)*fraction;
        return new Contact(fraction,x,y,z,new Token(closest,x,y,z,radius));
    }
    /** Traverse only ray-crossed grid buckets; stamps reuse storage without per-query HashSet nodes. */
    private void collect(double ax,double ay,double bx,double by,double radius){
        candidates.clear();queriedChunks.clear();queryStamp++;if(queryStamp==0){for(State s:states.values())s.queryStamp=0;queryStamp=1;}
        double size=DefenseConfig.CHUNK_TILES,dx=bx-ax,dy=by-ay;
        // Clip the traversal to the finite map envelope, without changing the exact narrow-phase ray.
        double enter=0,exit=1,padding=radius+size;
        for(int axis=0;axis<2;axis++){
            double origin=axis==0?ax:ay,delta=axis==0?dx:dy,min=-padding,max=(axis==0?Vars.world.width():Vars.world.height())+padding;
            if(Math.abs(delta)<1e-12){if(origin<min||origin>max)return;continue;}
            double lo=(min-origin)/delta,hi=(max-origin)/delta;if(lo>hi){double swap=lo;lo=hi;hi=swap;}
            enter=Math.max(enter,lo);exit=Math.min(exit,hi);if(enter>exit)return;
        }
        double startX=ax+dx*enter,startY=ay+dy*enter,endX=ax+dx*exit,endY=ay+dy*exit;
        int cx=chunk(startX),cy=chunk(startY),ex=chunk(endX),ey=chunk(endY);
        int halo=(int)Math.min(1000000,Math.ceil(radius/size));
        // Huge radii are bounded by actual populated buckets, never by off-map input magnitude.
        long visits=(long)(Math.abs(ex-cx)+Math.abs(ey-cy)+2)*(2L*halo+1)*(2L*halo+1);
        if(visits>Math.max(64,chunks.size*3L)){
            for(var e:chunks){int x=(int)(e.key>>32),y=(int)e.key;if(crossesChunk(ax,ay,bx,by,radius,x,y))addCandidates(e.value);}
        }else{
            int sx=Double.compare(dx,0),sy=Double.compare(dy,0);
            double tx=sx==0?Double.POSITIVE_INFINITY:(((cx+(sx>0?1:0))*size)-ax)/dx;
            double ty=sy==0?Double.POSITIVE_INFINITY:(((cy+(sy>0?1:0))*size)-ay)/dy;
            double txStep=sx==0?Double.POSITIVE_INFINITY:size/Math.abs(dx),tyStep=sy==0?Double.POSITIVE_INFINITY:size/Math.abs(dy);
            int budget=Math.abs(ex-cx)+Math.abs(ey-cy)+3;
            while(budget-->0){
                for(int x=cx-halo;x<=cx+halo;x++)for(int y=cy-halo;y<=cy+halo;y++){
                    long key=key(x,y);if(queriedChunks.containsKey(key))continue;queriedChunks.put(key,1L);
                    ArrayList<State> local=chunks.get(key);if(local!=null&&crossesChunk(ax,ay,bx,by,radius,x,y))addCandidates(local);
                }
                if(cx==ex&&cy==ey)break;
                // On exact corners visit both neighboring edge buckets as well, including grazing rays.
                if(Math.abs(tx-ty)<1e-12){
                    addCornerBucket(cx+sx,cy,ax,ay,bx,by,radius);addCornerBucket(cx,cy+sy,ax,ay,bx,by,radius);
                    cx+=sx;cy+=sy;tx+=txStep;ty+=tyStep;
                }else if(tx<ty){cx+=sx;tx+=txStep;}else{cy+=sy;ty+=tyStep;}
            }
        }
        profiledCandidates=candidates.size();
    }
    private void addCornerBucket(int x,int y,double ax,double ay,double bx,double by,double radius){
        ArrayList<State> local=chunks.get(key(x,y));if(local!=null&&crossesChunk(ax,ay,bx,by,radius,x,y))addCandidates(local);
    }
    private static boolean crossesChunk(double ax,double ay,double bx,double by,double radius,int x,int y){
        double size=DefenseConfig.CHUNK_TILES;
        return ProjectionMath.sweep(ax,ay,0,bx,by,0,x*size,y*size,-1,(x+1)*size,(y+1)*size,1,radius)>=0;
    }
    private void addCandidates(ArrayList<State> list){
        for(int i=0;i<list.size();i++){State s=list.get(i);if(s.queryStamp!=queryStamp){s.queryStamp=queryStamp;candidates.add(s);}}
    }
    public boolean hit(Contact contact,Bullet bullet,float damage){
        if(contact==null||!(contact.token() instanceof Token t)||!authority.getAsBoolean()||!Float.isFinite(damage)||damage<0)return false;
        sync();State s=t.state;
        if(states.get(s.building.tile.pos())!=s||!active(s)||(bullet!=null&&bullet.team.id==s.teamId)||
            t.z-t.radius>ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp)+1e-6)return false;
        if(damage>0){s.cachedVolume=null;touch(s);s.hp=Math.max(0,s.hp-damage);s.cooldown=(float)settings.regenDelay;s.revision=++revision;damaged.add(s);volumesDirty=true;}
        return true;
    }
    private Volume volume(State s){
        if(s.cachedVolume==null){WallProfile.volumeRecord();s.cachedVolume=new Volume(s.building,s.building.x/Vars.tilesize,s.building.y/Vars.tilesize,s.building.block.size/2.0,
            s.baseZ,s.topZ,ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp),s.hp,s.maxHp);}
        return s.cachedVolume;
    }
    public List<Volume> volumes(){
        sync();if(volumesDirty){ArrayList<Volume> result=new ArrayList<>(states.size());for(State s:states.values())result.add(volume(s));volumes=List.copyOf(result);volumesDirty=false;}
        return volumes;
    }
    /** O(1) native anchor lookup; no full-map snapshot is materialized by locomotion queries. */
    public Volume volume(Building building){sync();if(building==null||building.tile==null)return null;State s=states.get(building.tile.pos());return s!=null&&s.building==building?volume(s):null;}
    /** Optional physical projection surface. Negative infinity means no active unit barrier. */
    public double topAt(double x,double y){
        sync();if(!settings.blocksUnits||!Double.isFinite(x+y))return Double.NEGATIVE_INFINITY;
        Tile tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));
        if(tile==null||tile.build==null)return Double.NEGATIVE_INFINITY;
        State s=states.get(tile.build.tile.pos());return s!=null&&active(s)?ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp):Double.NEGATIVE_INFINITY;
    }
    @FunctionalInterface public interface BoundsTest{boolean visible(double x,double y,double z,double hx,double hy,double hz);}
    /** Chunk rejection precedes per-wall work; only visible wall records are materialized. */
    public void forEachVisible(BoundsTest bounds,Consumer<Volume> draw){
        sync();WallProfile.objects(states.size(),chunks.size,0);visibleStamp++;double size=DefenseConfig.CHUNK_TILES;
        for(var e:chunks){
            int cx=(int)(e.key>>32),cy=(int)e.key;
            if(!bounds.visible((cx+.5)*size,(cy+.5)*size,highestTop*.5,size*.5,size*.5,highestTop*.5))continue;
            for(State s:e.value)if(s.visibleStamp!=visibleStamp&&active(s)){s.visibleStamp=visibleStamp;
                double top=ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp),h=s.building.block.size/2.0,x=s.building.x/Vars.tilesize,y=s.building.y/Vars.tilesize;
                if(bounds.visible(x,y,(top+s.baseZ)*.5,h,h,(top-s.baseZ)*.5)){draw.accept(volume(s));}
            }
        }
    }
    /** Fully covered same-team faces can be dropped without merging independent health pools. */
    public boolean covers(Volume own,double x,double y,double bottom,double top){
        Tile tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));
        if(tile==null||tile.build==null||tile.build==own.building)return false;
        State s=states.get(tile.build.tile.pos());
        return s!=null&&active(s)&&s.teamId==own.building.team.id&&s.baseZ<=bottom+1e-5&&ProjectionMath.top(s.baseZ,s.topZ,s.hp,s.maxHp)>=top-1e-5;
    }
    public List<ProjectionSnapshot> snapshot(){sync();ArrayList<ProjectionSnapshot> out=new ArrayList<>(states.size());for(State s:states.values())out.add(snapshot(s));return List.copyOf(out);}
    private static ProjectionSnapshot snapshot(State s){return new ProjectionSnapshot(s.anchorPos,s.blockId,s.teamId,s.hp,s.maxHp,s.cooldown,(float)s.baseZ,(float)s.topZ,s.revision);}
    public Replication replication(){sync();ArrayList<ProjectionSnapshot> out=new ArrayList<>(damaged.size());for(State s:damaged)out.add(snapshot(s));return new Replication(settings,out,revision);}
    public void applyReplication(Replication value){
        if(authority.getAsBoolean())return;validate(value.damaged);sync();
        boolean changed=!settings.equals(value.settings);settings=value.settings;
        State[] reset=(changed?states.values():damaged).toArray(new State[0]);
        for(State s:reset){s.cooldown=0;configure(s,false);}applyValidated(value.damaged,false);revision=Math.max(revision,value.epoch);
    }
    public void applySnapshot(List<ProjectionSnapshot> values){if(authority.getAsBoolean())return;validate(values);sync();rebuild();applyValidated(values,true);}
    private void validate(List<ProjectionSnapshot> values){
        if(values==null||values.size()>DefenseConfig.MAX_SNAPSHOT_ENTRIES)throw new IllegalArgumentException("Projection snapshot exceeds bound");
        HashSet<Integer> seen=new HashSet<>();for(var v:values)if(v==null||!seen.add(v.anchorPos())||
            !Float.isFinite(v.hp()+v.maxHp()+v.cooldown()+v.baseZ()+v.topZ())||v.hp()<0||v.maxHp()<=0||v.hp()>v.maxHp()||v.cooldown()<0||v.topZ()<v.baseZ())
            throw new IllegalArgumentException("Invalid projection snapshot");
    }
    private void applyValidated(List<ProjectionSnapshot> values,boolean full){
        HashSet<Integer> keep=new HashSet<>();for(var v:values){State s=states.get(v.anchorPos());if(s==null||s.blockId!=v.blockId()||s.teamId!=v.teamId())continue;
            s.cachedVolume=null;touch(s);s.hp=v.hp();s.maxHp=v.maxHp();s.cooldown=v.cooldown();s.baseZ=v.baseZ();s.topZ=v.topZ();s.revision=v.revision();keep.add(v.anchorPos());
            highestTop=Math.max(highestTop,s.topZ);
            if(s.hp<s.maxHp||s.cooldown>0)damaged.add(s);else damaged.remove(s);
        }
        if(full)for(var it=states.entrySet().iterator();it.hasNext();){var e=it.next();if(!keep.contains(e.getKey())){unindex(e.getValue());damaged.remove(e.getValue());it.remove();}}
        volumesDirty=true;
    }
    private void touch(State s){
        long change=++geometryRevision;
        for(int x=s.minChunkX-1;x<=s.maxChunkX+1;x++)for(int y=s.minChunkY-1;y<=s.maxChunkY+1;y++){RenderChunk c=renderIndex.get(key(x,y));if(c!=null)c.revision=change;}
        if(s.renderChunk!=null)s.renderChunk.revision=change;
    }
    private void checkLifecycle(){
        lifecycleChanges.clear();
        for(State s:states.values()){
            if(!eligible(s.building)||s.id!=s.building.id||s.blockId!=s.building.block.id||s.teamId!=s.building.team.id){lifecycleChanges.add(s);continue;}
            boolean now=active(s);if(now!=s.renderedActive){s.renderedActive=now;touch(s);}
        }
        for(int i=0;i<lifecycleChanges.size();i++){
            State old=lifecycleChanges.get(i);states.remove(old.anchorPos,old);damaged.remove(old);unindex(old);
            Building b=old.building;if(eligible(b)){State replacement=new State(b);configure(replacement,false);states.put(replacement.anchorPos,replacement);index(replacement);}
            volumesDirty=true;
        }
    }
    /** Persistent chunks for rendering; callers must not mutate the returned membership lists. */
    public List<RenderChunk> renderChunks(){sync();WallProfile.objects(states.size(),chunks.size,0);return renderChunks;}
    public long generation(){return generation;}
    /** Cached adjacent wall lookup used only while rebuilding exposed surfaces. */
    public Volume neighbor(Volume own,double x,double y){
        Tile tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));
        if(tile==null||tile.build==null||tile.build==own.building)return null;State s=states.get(tile.build.tile.pos());
        return s!=null&&active(s)&&s.teamId==own.building.team.id?volume(s):null;
    }
    public void clear(){states.clear();chunks.clear();renderIndex.clear();renderChunks.clear();lifecycleChanges.clear();generation++;lifecycleFrame=Long.MIN_VALUE;damaged.clear();candidates.clear();queriedChunks.clear();initialized=false;volumes=List.of();volumesDirty=true;worldTiles=null;tileChanges=-1;highestTop=0;settingsFrame=Long.MIN_VALUE;}
    @Override public void close(){clear();}
    private static int bound(int n,int min,int max){return Math.max(min,Math.min(max,n));}
    private static int chunk(double x){return (int)Math.floor(x/DefenseConfig.CHUNK_TILES);}
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
}
