package fpv.defense;

import arc.Events;
import fpv.combat.ProjectedDefense;
import fpv.generated.ForceConfig;
import fpv.world.World3DAdapter;
import java.util.*;
import java.util.function.BooleanSupplier;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.ForceProjector.ForceBuild;

/** Real XYZ boundary backed by the original native projector's resource and shield lifecycle. */
public final class ForceProjectorSystem implements ProjectedDefense,AutoCloseable {
    public record Snapshot(int anchorPos,int blockId,int teamId,boolean broken,float buildup,float radscl,float hit,float warmup,float phaseHeat){}
    public record Volume(ForceBuild building,double x,double y,double bottom,double top,double radius,int sides,double rotation,float flash){}
    private record Token(ForceBuild building,int blockId,int teamId){}
    private final World3DAdapter world;
    private final BooleanSupplier authority;
    private final Map<Integer,ForceBuild> buildings=new HashMap<>();
    private java.util.function.Supplier<Collection<fpv.construction.LayerBuildingAdapter.Entry>> upperSource=List::of;
    private java.util.function.LongSupplier upperRevision=()->0;
    private long lastUpperRevision=Long.MIN_VALUE;
    private Collection<fpv.construction.LayerBuildingAdapter.Entry> lastUpperEntries;
    private final List<ForceBuild> upper=new ArrayList<>(),all=new ArrayList<>();
    public void setUpperBuildings(java.util.function.Supplier<Collection<fpv.construction.LayerBuildingAdapter.Entry>> source,java.util.function.LongSupplier revision){upperSource=source;upperRevision=revision;lastUpperRevision=Long.MIN_VALUE;}
    private final Map<Integer,Snapshot> remote=new HashMap<>();
    private Object worldTiles;
    private int tileChanges=-1;
    private boolean remoteReceived,remoteAircraftCollision,initialized;
    public static final String AIRCRAFT_COLLISION_SETTING="fpv-force-aircraft-collision";
    public ForceProjectorSystem(World3DAdapter world,BooleanSupplier authority){this.world=Objects.requireNonNull(world);this.authority=Objects.requireNonNull(authority);}
    public static boolean attached(Building b){return b instanceof ForceBuild&&b.block instanceof ForceProjector&&!b.dead&&b.tile!=null&&
        Vars.world!=null&&(b.tile instanceof fpv.construction.LayerTile||Vars.world.tile(b.tile.x,b.tile.y)==b.tile)&&b.tile.build==b;}
    private void sync(){
        if(Vars.world==null||Vars.world.tiles==null){clear();return;}
        if(worldTiles!=Vars.world.tiles){clear();worldTiles=Vars.world.tiles;}
        if(!initialized||tileChanges!=Vars.world.tileChanges){
            buildings.clear();for(Tile tile:Vars.world.tiles)if(tile.build!=null&&tile.build.tile==tile&&attached(tile.build))buildings.put(tile.pos(),(ForceBuild)tile.build);
            remote.entrySet().removeIf(e->{ForceBuild b=buildings.get(e.getKey());return b==null||b.block.id!=e.getValue().blockId||b.team.id!=e.getValue().teamId;});
            tileChanges=Vars.world.tileChanges;initialized=true;
            all.clear();all.addAll(buildings.values());all.addAll(upper);
        }
        long revision=upperRevision.getAsLong();var entries=upperSource.get();
        if(lastUpperRevision!=revision||lastUpperEntries!=entries){upper.clear();for(var entry:entries)if(entry.building() instanceof ForceBuild force&&attached(force))upper.add(force);lastUpperRevision=revision;lastUpperEntries=entries;all.clear();all.addAll(buildings.values());all.addAll(upper);}
    }
    public void invalidate(Tile tile){
        if(tile==null||worldTiles!=Vars.world.tiles||!initialized||Vars.world.tileChanges>tileChanges+1){initialized=false;return;}
        buildings.entrySet().removeIf(e->{if(attached(e.getValue()))return false;remote.remove(e.getKey());return true;});
        if(attached(tile.build))buildings.put(tile.build.tile.pos(),(ForceBuild)tile.build);
        tileChanges=Vars.world.tileChanges;
        all.clear();all.addAll(buildings.values());all.addAll(upper);
    }
    /** Coolant, energy, resource consumption and recovery are exclusively native updateTile work. */
    public void update(double seconds){if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Finite nonnegative seconds required");sync();}
    private Volume volume(ForceBuild b){
        if(!attached(b))return null;ForceProjector block=(ForceProjector)b.block;
        boolean elevated=b.tile instanceof fpv.construction.LayerTile;
        Snapshot s=authority.getAsBoolean()||elevated?null:remote.get(b.tile.pos());
        if(s!=null&&(s.blockId!=b.block.id||s.teamId!=b.team.id))s=null;
        if(!authority.getAsBoolean()&&!elevated&&remoteReceived&&s==null)return null;
        boolean broken=s==null?b.broken:s.broken;float phase=s==null?b.phaseHeat:s.phaseHeat,scale=s==null?b.radscl:s.radscl,flash=s==null?b.hit:s.hit;
        double radius=(block.radius+block.phaseRadiusBoost*phase)*scale/Vars.tilesize;
        if(broken||!Double.isFinite(radius)||radius<=.001||block.sides<3||!Float.isFinite(block.shieldRotation))return null;
        int sides=Math.min(block.sides,(int)ForceConfig.MAX_SIDES);
        double x=b.x/(double)Vars.tilesize,y=b.y/(double)Vars.tilesize,bottom=world.buildingBase(b);
        return new Volume(b,x,y,bottom,bottom+radius*ForceConfig.HEIGHT_RADIUS_MULTIPLIER,radius,sides,Math.toRadians(block.shieldRotation),Math.max(0,Math.min(1,flash)));
    }
    public List<Volume> volumes(){sync();var out=new ArrayList<Volume>(all.size());for(ForceBuild b:all){var v=volume(b);if(v!=null)out.add(v);}return List.copyOf(out);}
    public Volume volume(Building b){sync();return b instanceof ForceBuild force?volume(force):null;}
    /** Host-authoritative aircraft-only option; ordinary projectile defense never reads it. */
    public boolean aircraftCollisionEnabled(){return authority.getAsBoolean()?arc.Core.settings.getBool(AIRCRAFT_COLLISION_SETTING,false):remoteAircraftCollision;}
    public byte[] aircraftSettingsBytes(){return new byte[]{(byte)(aircraftCollisionEnabled()?1:0)};}
    public void applyAircraftSettings(byte[] bytes){
        if(authority.getAsBoolean())return;
        if(bytes==null||bytes.length!=1||(bytes[0]!=0&&bytes[0]!=1))throw new IllegalArgumentException("Force aircraft setting");
        sync();remoteAircraftCollision=bytes[0]!=0;
    }
    public fpv.physics.SweptBodies.Hit sweepUnit(mindustry.gen.Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        if(!aircraftCollisionEnabled()||unit==null||unit.dead||radius<0||halfHeight<0||!Double.isFinite(ax+ay+az+bx+by+bz+radius+halfHeight))return null;
        // Creator-controlled ground types fly through the canonical XYZ pose without changing
        // native UnitType.flying. This read never invokes the barrier, so it cannot recurse.
        if(!unit.isFlying()){var pose=world.unitState(unit);if(pose==null||!pose.flying())return null;}
        sync();fpv.physics.SweptBodies.Hit nearest=null;
        for(ForceBuild b:all){
            if(b.team==unit.team)continue;var v=volume(b);if(v==null)continue;
            if(Math.max(ax,bx)+radius<v.x-v.radius||Math.min(ax,bx)-radius>v.x+v.radius||
               Math.max(ay,by)+radius<v.y-v.radius||Math.min(ay,by)-radius>v.y+v.radius||
               Math.max(az,bz)+halfHeight<v.bottom||Math.min(az,bz)-halfHeight>v.top)continue;
            var hit=ForceGeometry.sweepBody(ax,ay,az,bx,by,bz,v.x,v.y,v.bottom,v.top,v.radius,v.sides,v.rotation,radius,halfHeight);
            if(hit!=null&&(nearest==null||hit.time()<nearest.time()))nearest=hit;
        }
        return nearest;
    }
    /** Installed once: preserve nearest physical contact with every already registered wall barrier. */
    public World3DAdapter.UnitBarrier combine(World3DAdapter.UnitBarrier existing){return (unit,ax,ay,az,bx,by,bz,radius,halfHeight)->{
        var a=existing==null?null:existing.trace(unit,ax,ay,az,bx,by,bz,radius,halfHeight);
        var b=sweepUnit(unit,ax,ay,az,bx,by,bz,radius,halfHeight);
        return a==null?b:b==null||a.time()<=b.time()?a:b;
    };}
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double padding){
        sync();if(padding<0||!Double.isFinite(ax+ay+az+bx+by+bz+padding))return null;
        double nearest=Double.POSITIVE_INFINITY;Volume chosen=null;
        for(ForceBuild b:all){
            if(team!=null&&b.team==team)continue;var v=volume(b);if(v==null)continue;
            // Cheap finite AABB rejection avoids polygon work for distant native projectors.
            if(Math.max(ax,bx)+padding<v.x-v.radius||Math.min(ax,bx)-padding>v.x+v.radius||
                Math.max(ay,by)+padding<v.y-v.radius||Math.min(ay,by)-padding>v.y+v.radius||
                Math.max(az,bz)+padding<v.bottom||Math.min(az,bz)-padding>v.top)continue;
            double hit=ForceGeometry.sweep(ax,ay,az,bx,by,bz,v.x,v.y,v.bottom,v.top,v.radius,v.sides,v.rotation,padding);
            if(hit>=0&&hit<nearest){nearest=hit;chosen=v;}
        }
        if(chosen==null)return null;ForceBuild b=chosen.building;
        return new Contact(nearest,ax+(bx-ax)*nearest,ay+(by-ay)*nearest,az+(bz-az)*nearest,new Token(b,b.block.id,b.team.id));
    }
    @Override public boolean hit(Contact contact,Bullet bullet,float damage){
        if(!authority.getAsBoolean()||contact==null||!(contact.token() instanceof Token token)||!Float.isFinite(damage)||damage<0)return false;
        ForceBuild b=token.building;if(!attached(b)||b.block.id!=token.blockId||b.team.id!=token.teamId||volume(b)==null||bullet!=null&&bullet.team==b.team)return false;
        ForceProjector block=(ForceProjector)b.block;
        float multiplier=bullet==null||bullet.type==null?1:bullet.type.shieldDamageMultiplier;
        if(!Float.isFinite(multiplier)||multiplier<0)multiplier=1;
        b.hit=1;b.buildup+=Math.min(Float.MAX_VALUE/4,damage*multiplier);
        if(!Vars.headless&&block.hitSound!=null)block.hitSound.at((float)(contact.x()*Vars.tilesize),(float)(contact.y()*Vars.tilesize),1,block.hitSoundVolume);
        // Native update breaks on this exact threshold. Apply immediately so a later ray this tick sees the collapse.
        if(b.buildup>=block.shieldHealth+block.phaseShieldBoost*b.phaseHeat&&!b.broken){
            b.broken=true;b.buildup=block.shieldHealth;
            if(!Vars.headless){block.shieldBreakEffect.at(b.x,b.y,b.realRadius(),b.team.color,block);block.breakSound.at(b.x,b.y);}
            if(Vars.state!=null&&b.team!=Vars.state.rules.defaultTeam)Events.fire(Trigger.forceProjectorBreak);
        }
        return true;
    }
    /** Last accepted network state on clients; native state on the authority. */
    public Snapshot state(Building building){sync();if(!(building instanceof ForceBuild b)||!attached(b))return null;
        if(!authority.getAsBoolean()&&remoteReceived&&!(b.tile instanceof fpv.construction.LayerTile))return remote.get(b.tile.pos());return nativeState(b);}
    private static Snapshot nativeState(ForceBuild b){return new Snapshot(b.tile.pos(),b.block.id,b.team.id,b.broken,b.buildup,b.radscl,b.hit,b.warmup,b.phaseHeat);}
    public List<Snapshot> snapshot(){sync();var out=new ArrayList<Snapshot>(buildings.size());for(ForceBuild b:buildings.values())if(attached(b))
        out.add(nativeState(b));
        out.sort(Comparator.comparingInt(Snapshot::anchorPos));return List.copyOf(out);}
    public static void validate(List<Snapshot> values,int maximum){
        if(values==null||values.size()>Math.min(maximum,(int)ForceConfig.MAX_SNAPSHOT_ENTRIES))throw new IllegalArgumentException("Force snapshot count");
        var seen=new HashSet<Integer>();for(var s:values)if(s==null||!seen.add(s.anchorPos)||s.blockId<0||s.teamId<0||s.teamId>255||
            !Float.isFinite(s.buildup+s.radscl+s.hit+s.warmup+s.phaseHeat)||s.buildup< -1000||s.buildup>1e12||s.radscl<0||s.radscl>4||s.warmup<0||s.warmup>4||s.phaseHeat<0||s.phaseHeat>1.01||s.hit< -1000||s.hit>1.01)
            throw new IllegalArgumentException("Invalid force snapshot");
    }
    public void applySnapshot(List<Snapshot> values){
        if(authority.getAsBoolean())return;validate(values,Vars.world.width()*Vars.world.height());sync();remote.clear();remoteReceived=true;
        for(var s:values){ForceBuild b=buildings.get(s.anchorPos);if(b==null||b.block.id!=s.blockId||b.team.id!=s.teamId)continue;
            remote.put(s.anchorPos,s);b.broken=s.broken;b.buildup=s.buildup;b.radscl=s.radscl;b.hit=s.hit;b.warmup=s.warmup;b.phaseHeat=s.phaseHeat;}
    }
    public void clear(){buildings.clear();upper.clear();all.clear();lastUpperEntries=null;lastUpperRevision=Long.MIN_VALUE;remote.clear();worldTiles=null;tileChanges=-1;remoteReceived=false;remoteAircraftCollision=false;initialized=false;}
    @Override public void close(){clear();}
}
