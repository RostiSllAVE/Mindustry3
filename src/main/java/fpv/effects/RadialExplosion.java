package fpv.effects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;
import arc.struct.IntSet;
import fpv.generated.CrashConfig;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;

/** Original scalable XYZ blast, bounded independently of caller input; native team/health APIs. */
public final class RadialExplosion {
    /** Optional additional caller-owned cap; returned amount must never exceed proposed damage. */
    public interface DamageLimiter {
        double unit(Unit unit,double proposed);
        double building(Building building,double proposed);
        default double elevated(fpv.construction.LayerComponent component,double proposed){return 0;}
        default void damageUnit(Unit unit,float accepted){unit.damage(accepted);}
    }
    public enum Style { sparks, pulse }
    public record Spec(double radius,double damage,double visualRadius,double duration,Style style,boolean sound){
        public Spec {
            if(!Double.isFinite(radius)||radius<=0||radius>40||!Double.isFinite(damage)||damage<0||damage>12000||
               !Double.isFinite(visualRadius)||visualRadius<=0||visualRadius>40||!Double.isFinite(duration)||duration<=0||duration>5||style==null)
                throw new IllegalArgumentException("Invalid bounded radial explosion");
        }
    }
    public static final class Burst {
        public final double x,y,z;public final Spec spec;public final int seed;private double age;
        Burst(double x,double y,double z,Spec spec,int seed){this.x=x;this.y=y;this.z=z;this.spec=spec;this.seed=seed;}
        public double age(){return age;}
        public double fraction(){return Math.min(1,age/spec.duration);}
    }
    /** Network/render-only snapshot in canonical XYZ. Never carries a callable damage action. */
    public record VisualBurst(int id,double x,double y,double z,Spec spec,double age){}
    private final ArrayList<Burst> bursts=new ArrayList<>();
    private final List<Burst> view=Collections.unmodifiableList(bursts);
    private final ToDoubleFunction<Unit> unitHeight;
    private final ToDoubleFunction<Building> buildingHeight;
    private World3DAdapter world;
    private int serial;
    public RadialExplosion(ToDoubleFunction<Unit> unitHeight,ToDoubleFunction<Building> buildingHeight){this.unitHeight=unitHeight;this.buildingHeight=buildingHeight;}
    public RadialExplosion(World3DAdapter world){
        this(u->{var state=world.unitState(u);return state==null?0:state.z();},
            b->world.surfaceHeight(b.x/(double)Vars.tilesize,b.y/(double)Vars.tilesize));
        this.world=world;
    }
    public List<Burst> bursts(){return view;}
    public List<VisualBurst> visualSnapshot(){
        var result=new ArrayList<VisualBurst>();
        for(var b:bursts)result.add(new VisualBurst(b.seed,b.x,-b.z,b.y,b.spec,b.age));
        return result;
    }
    public void acceptVisuals(List<VisualBurst> snapshots){
        if(!Vars.net.client())return;
        if(snapshots.size()>(int)CrashConfig.MAX_EFFECTS)throw new IllegalArgumentException("Too many blast visuals");
        var replacement=new ArrayList<Burst>();
        for(var v:snapshots){
            if(!Double.isFinite(v.x)||!Double.isFinite(v.y)||!Double.isFinite(v.z)||!Double.isFinite(v.age)||v.age<0||v.age>v.spec.duration)
                throw new IllegalArgumentException("Invalid blast visual snapshot");
            // Damage stays in the server-owned detonateXYZ call; replay cannot touch health.
            var b=new Burst(v.x,v.z,-v.y,v.spec,v.id);b.age=v.age;replacement.add(b);
        }
        bursts.clear();bursts.addAll(replacement);
    }
    public boolean active(){return !bursts.isEmpty();}
    public void clear(){bursts.clear();}
    public void update(double seconds){
        if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Finite elapsed time required");
        for(int i=bursts.size()-1;i>=0;i--){Burst b=bursts.get(i);b.age+=seconds;if(b.age>=b.spec.duration)bursts.remove(i);}
    }
    /** Compatibility boundary for the existing Y-up renderer/tests. New gameplay uses detonateXYZ. */
    @Deprecated public void detonate(Team source,double x,double y,double z,Spec spec){detonateXYZ(source,x,-z,y,spec);}
    /** X/Y are native horizontal tiles, Z is altitude; no map writes and no reactor hooks. */
    public void detonateXYZ(Team source,double gx,double gy,double gz,Spec spec){
        detonateXYZ(source,gx,gy,gz,spec,null);
    }
    public void detonateXYZ(Team source,double gx,double gy,double gz,Spec spec,DamageLimiter limiter){
        double x=gx,y=gz,z=-gy;
        if(source==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Vars.net.client())return;
        if(bursts.size()>=(int)CrashConfig.MAX_EFFECTS)bursts.remove(0);
        bursts.add(new Burst(x,y,z,spec,++serial));
        float wx=(float)x*Vars.tilesize,wy=(float)-z*Vars.tilesize;
        if(spec.damage>0){
            // Native spatial queries bound native hitboxes, which can be smaller than the physical
            // sprite envelope. Snapshot once per blast; native damage/death may remove other units.
            var candidates=new ArrayList<Unit>();
            for(Unit unit:Groups.unit)if(unit.team!=source&&!unit.dead&&unit.hittable())candidates.add(unit);
            for(Unit u:candidates){
                if(u.team==source||u.dead||!u.isAdded()||!u.hittable())continue;
                var physical=world==null?null:world.unitState(u);
                double dx=(physical==null?u.x/(double)Vars.tilesize:physical.x())-x;
                double dz=-(physical==null?u.y/(double)Vars.tilesize:physical.y())-z;
                double dy=(physical==null?unitHeight.applyAsDouble(u):physical.z())-y;
                double radius=physical==null?u.hitSize/(2.0*Vars.tilesize):physical.radius();
                double halfHeight=physical==null?radius:physical.halfHeight();
                double horizontalGap=Math.max(0,Math.hypot(dx,dz)-radius);
                double verticalGap=Math.max(0,Math.abs(dy)-halfHeight);
                double distance=Math.hypot(horizontalGap,verticalGap);
                if(distance<spec.radius){
                    double raw=spec.damage*(1-distance/spec.radius),amount=limited(raw,limiter==null?raw:limiter.unit(u,raw));
                    if(amount>0){if(limiter==null)u.damage((float)amount);else limiter.damageUnit(u,(float)amount);}
                }
            }
            // Every point in a native building footprint belongs to one of its linked tiles.
            // This rectangular pass includes square corners rejected by nearbyBuildings' circle.
            var visited=new IntSet();
            int minX=Math.max(0,(int)Math.floor(gx-spec.radius-.5)),maxX=Math.min(Vars.world.width()-1,(int)Math.ceil(gx+spec.radius+.5));
            int minY=Math.max(0,(int)Math.floor(gy-spec.radius-.5)),maxY=Math.min(Vars.world.height()-1,(int)Math.ceil(gy+spec.radius+.5));
            for(int tx=minX;tx<=maxX;tx++)for(int ty=minY;ty<=maxY;ty++){
                var tile=Vars.world.tile(tx,ty);
                Building b=tile==null?null:tile.build;
                if(b==null||b.team==source||b.dead||!visited.add(b.id)||b.tile.build!=b)continue;
                double half=b.block.size*.5,bx=b.x/(double)Vars.tilesize,bz=-b.y/(double)Vars.tilesize;
                double dx=Math.max(0,Math.abs(x-bx)-half),dz=Math.max(0,Math.abs(z-bz)-half);
                double base=world==null?0:world.terrainHeight(bx,-bz);
                double top=Math.max(base,buildingHeight.applyAsDouble(b)),dy=Math.max(0,Math.max(base-y,y-top));
                double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
                if(distance<spec.radius){
                    double raw=spec.damage*(1-distance/spec.radius),amount=limited(raw,limiter==null?raw:limiter.building(b,raw));
                    if(amount>0)b.damage(source,(float)amount);
                }
            }
            if(world!=null&&world.construction()!=null)world.construction().area(source,gx,gy,gz,spec.radius,spec.damage,0,
                limiter==null?null:limiter::elevated);
        }
        if(spec.sound&&!Vars.headless)Sounds.explosion.at(wx,wy,1.25f,(float)CrashConfig.EXPLOSION_SOUND_VOLUME);
    }
    private static double limited(double proposed,double accepted){return Double.isFinite(accepted)?Math.max(0,Math.min(proposed,accepted)):0;}
}
