package fpv.combat;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntSet;
import arc.util.Time;
import arc.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import mindustry.Vars;
import mindustry.entities.Mover;
import mindustry.entities.bullet.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.Turret;
import fpv.world.Unit3DState;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;

/**
 * Incremental local XYZ weapon adapter. Native weapon creation, reload and ammo remain untouched.
 * Adapted per-content proxies intercept init before instant native lasers can deal XY damage.
 */
public final class Combat3DSystem implements AutoCloseable {
    @FunctionalInterface public interface AimProvider { WorldCoordinates.Point direction(Unit unit); }
    public record Coverage(int boundSlots,int adaptedTypes,int fallbackTypes,int activeShots,boolean active) {}
    private enum Family { PROJECTILE, LASER, CONTINUOUS, LIGHTNING, POINT_CONTINUOUS, EXPLOSION }
    private static final double EVENT_LIFETIME=.16, MUZZLE_CLEARANCE=.025;
    private static final int MAX_EVENTS=1024;
    private final World3DAdapter world;
    private final BooleanSupplier enabled;
    private BooleanSupplier authority=()->!Vars.net.client();
    private final AimProvider aim;
    private final CombatBindings bindings;
    private final CombatTargetIndex targets;
    private final CombatPayloads payloads;
    private final Lightning3D lightning;
    private final TurretDefense3D defense;
    private final TurretTargeting3D turretTargeting;
    private final UnitWeaponRanges unitRanges;
    private final MechWeaponAim3D mechWeapons;
    private final NavalWeaponAim3D navalWeapons;
    private final AircraftWeapons aircraftWeapons;
    public AircraftWeapons aircraftWeapons(){return aircraftWeapons;}
    public byte[] weaponAimReplication(){return AircraftWeaponCodec.encode(weaponPitchSnapshot(),aircraftWeapons.snapshot(),aircraftWeapons.limits());}
    public void applyWeaponAimReplication(byte[] bytes){var s=AircraftWeaponCodec.decode(bytes);applyWeaponPitchSnapshot(s.existing());aircraftWeapons.applySnapshot(s.aircraft());aircraftWeapons.applyLimits(s.limits());}
    public NavalWeaponAim3D navalWeapons(){return navalWeapons;}
    public List<MechWeaponPitchCodec.Entry> weaponPitchSnapshot(){var entries=new ArrayList<>(mechWeapons.snapshot());navalWeapons.appendSnapshot(entries);return entries;}
    public void applyWeaponPitchSnapshot(List<MechWeaponPitchCodec.Entry> entries){mechWeapons.applySnapshot(entries);navalWeapons.applySnapshot(entries);}
    public MechWeaponAim3D mechWeapons(){return mechWeapons;}
    private java.util.function.Supplier<java.util.Collection<fpv.construction.LayerBuildingAdapter.Entry>> upperBuildings=java.util.List::of;
    public void setUpperBuildings(java.util.function.Supplier<java.util.Collection<fpv.construction.LayerBuildingAdapter.Entry>> source){upperBuildings=source==null?java.util.List::of:source;}
    java.util.Collection<fpv.construction.LayerBuildingAdapter.Entry> upperBuildings(){return upperBuildings.get();}
    public fpv.construction.LayerBuildingAdapter.TurretAiming layerTurretAiming(){return turretTargeting.layerAdapter();}
    WorldCoordinates.Point controlDirection(Unit unit){return aim.direction(unit);}
    public void updateUpperDefense(Building building){defense.updateUpper(building);}
    public UnitWeaponRanges unitRanges(){return unitRanges;}
    private ProjectedDefense projectedDefense;
    private double antiAirVerticalMultiplier=CombatBalance.DEFAULT_AA_VERTICAL_MULTIPLIER;
    private CombatBalance.RangeFalloff rangeFalloff=CombatBalance.DEFAULT_RANGE_FALLOFF;
    private final IdentityHashMap<BulletType,Proxy> proxies=new IdentityHashMap<>();
    private final IdentityHashMap<BulletType,String> fallback=new IdentityHashMap<>();
    private final IdentityHashMap<BulletType,String> replacements=new IdentityHashMap<>();
    private final IdentityHashMap<BulletType,Boolean> checking=new IdentityHashMap<>();
    private final IdentityHashMap<Bullet,Shot> shots=new IdentityHashMap<>();
    private final ArrayList<Event> events=new ArrayList<>();
    private final java.util.ArrayDeque<Event> eventPool=new java.util.ArrayDeque<>();
    private final ArrayList<CombatVisual> visualBuffer=new ArrayList<>();
    private final List<CombatVisual> visualView=Collections.unmodifiableList(visualBuffer);
    private List<CombatVisual> remoteVisuals=List.of();
    private Object tiles;
    private boolean installed,active;
    private long serial;
    private SpawnContext childContext;
    private int childDepth;
    private int invalidAimEvents;
    public int invalidAimEvents(){return invalidAimEvents;}
    private void invalidAim(Shot shot,String reason){
        invalidAimEvents++;
        if(invalidAimEvents<=8||(invalidAimEvents&(invalidAimEvents-1))==0)
            Log.warn("[FPV-AIM] Cancelled invalid shot @: @; type=@ owner=@ angle=@ aim=(@,@) delta=@ (count=@)",
                shot.nativeId,reason,shot.proxy.original.getClass().getSimpleName(),shot.bullet.owner,
                shot.bullet.rotation(),shot.bullet.aimX,shot.bullet.aimY,Time.delta,invalidAimEvents);
    }
    private static boolean finiteXY(double x,double y){return Double.isFinite(x)&&Double.isFinite(y);}
    private static boolean finiteOwner(Bullet bullet){
        return !(bullet.owner instanceof Unit unit)||finiteXY(unit.x,unit.y)&&Float.isFinite(unit.rotation)&&finiteXY(unit.vel.x,unit.vel.y);
    }
    static boolean usableTarget(Teamc target){
        return target!=null&&finiteXY(target.x(),target.y())&&
            (!(target instanceof Unit unit)||!unit.dead&&unit.isAdded()&&Float.isFinite(unit.rotation)&&finiteXY(unit.vel.x,unit.vel.y))&&
            // Native static/sleeping buildings are deliberately absent from the update group.
            // Tile identity is also the native isValid contract, including elevated LayerTiles.
            (!(target instanceof Building build)||build.tile!=null&&build.isValid());
    }

    public Combat3DSystem(World3DAdapter world,BooleanSupplier enabled,AimProvider aim){
        if(world==null||enabled==null||aim==null)throw new IllegalArgumentException("Combat world, activation and aim providers required");
        this.world=world;this.enabled=enabled;this.aim=aim;bindings=new CombatBindings(this::adapt);targets=new CombatTargetIndex(world);payloads=new CombatPayloads(world,targets,this::healingVisual);lightning=new Lightning3D(world,targets,this::addEvent);defense=new TurretDefense3D(world,this);turretTargeting=new TurretTargeting3D(world,this);unitRanges=new UnitWeaponRanges(this,world);mechWeapons=new MechWeaponAim3D(this,world);navalWeapons=new NavalWeaponAim3D(this,world);aircraftWeapons=new AircraftWeapons(this,world);
    }
    /** Call after native content initialization. Appends mod proxy types without mutating original types. */
    public void install(){unitRanges.install();aircraftWeapons.install();bindings.discover();defense.install();turretTargeting.install();installed=true;syncActive();}
    /** Run before native entity updates; Build Mode remains active and pause does not switch combat rules. */
    public void syncActive(){
        if(!installed)return;
        Object current=Vars.world==null?null:Vars.world.tiles;
        if(tiles!=current){clear();tiles=current;bindings.discover();}
        boolean next=enabled.getAsBoolean();
        if(next){bindings.apply();unitRanges.apply(true);aircraftWeapons.apply(true);active=true;}
        else if(active){bindings.restore();unitRanges.apply(false);aircraftWeapons.apply(false);active=false;clear();}
    }
    /** Only presentation aging and pooled-identity pruning. Native proxy.update advances physical shots. */
    public void update(double simulationSeconds){
        if(!Double.isFinite(simulationSeconds)||simulationSeconds<0)throw new IllegalArgumentException("Nonnegative simulated time required");
        syncActive();
        if(active&&authority.getAsBoolean())targets.capture();
        aircraftWeapons.prune();
        for(int i=events.size()-1;i>=0;i--){Event event=events.get(i);event.age+=simulationSeconds;if(event.age>=event.life){events.remove(i);eventPool.addLast(event);}}
        shots.entrySet().removeIf(entry->!entry.getKey().isAdded()||entry.getKey().id!=entry.getValue().nativeId||entry.getKey().type!=entry.getValue().proxy);
    }
    public Coverage coverage(){return new Coverage(bindings.size(),proxies.size(),fallback.size(),shots.size(),active);}
    public void setAuthority(BooleanSupplier provider){if(provider==null)throw new IllegalArgumentException("Authority provider required");authority=provider;}
    public boolean authoritative(){return active&&authority.getAsBoolean();}
    public void acceptRemoteVisuals(List<CombatVisual> values){if(authority.getAsBoolean())return;remoteVisuals=List.copyOf(values.subList(0,Math.min(4096,values.size())));}
    public List<CombatVisual> networkVisuals(){return authoritative()?List.copyOf(visuals()):List.of();}
    public int replacementTypes(){return replacements.size();}
    public void setProjectedDefense(ProjectedDefense defense){projectedDefense=defense;lightning.setProjectedDefense(defense);}
    public double antiAirVerticalMultiplier(){return antiAirVerticalMultiplier;}
    public void setAntiAirVerticalMultiplier(double value){if(!Double.isFinite(value)||value<1||value>20)throw new IllegalArgumentException("AA multiplier must be between 1 and 20");antiAirVerticalMultiplier=value;}
    public CombatBalance.RangeFalloff rangeFalloff(){return rangeFalloff;}
    /** Host/session configuration; native weapon R remains the distance scale. */
    public void setRangeFalloff(double kneeRatio,double endRatio,double kneePower){
        var next=new CombatBalance.RangeFalloff(kneeRatio,endRatio,kneePower);
        if(!next.equals(rangeFalloff)){rangeFalloff=next;lightning.setRangeFalloff(next);}
    }
    /** Authoritative launch boundary for explicit spatial emitters; never accepts a client hit decision. */
    public Bullet launchPhysical(BulletType type,Entityc owner,Team team,WorldCoordinates.Point position,WorldCoordinates.Point direction,float damage){
        if(!authoritative())return null;
        var normalized=CombatGeometry.direction(direction.x(),direction.y(),direction.z());
        if(!Double.isFinite(position.x())||!Double.isFinite(position.y())||!Double.isFinite(position.z()))throw new IllegalArgumentException("Finite launch position required");
        SpawnContext previous=childContext;childContext=new SpawnContext(position.x(),position.y(),position.z(),normalized,1,position.x(),position.y(),0,false,0);
        try{return adapt(type,true).create(owner,owner,team,(float)(position.x()*Vars.tilesize),(float)(position.y()*Vars.tilesize),
            (float)Math.toDegrees(Math.atan2(normalized.y(),normalized.x())),damage,1,1,null,null,-1,-1,null);}
        finally{childContext=previous;}
    }
    /** Read-only original metadata for diagnostics and visual fallback classification. */
    public BulletType originalType(BulletType type){return type instanceof Proxy proxy?proxy.original:type;}
    public Map<String,Integer> fallbackReasons(){
        Map<String,Integer> result=new HashMap<>();for(String reason:fallback.values())result.merge(reason,1,Integer::sum);
        return Collections.unmodifiableMap(result);
    }
    public boolean managed(Bullet bullet){Shot shot=shots.get(bullet);return shot!=null&&shot.nativeId==bullet.id&&bullet.type==shot.proxy;}
    /** Physical diagnostics for integration tests and UI; all values use canonical XYZ tiles/seconds. */
    public record ProjectileState(double x,double y,double z,double vx,double vy,double vz,boolean falling,double poweredRange,double traveled) {}
    public ProjectileState projectileState(Bullet bullet){Shot s=shots.get(bullet);return s==null?null:new ProjectileState(s.x,s.y,s.z,s.vx,s.vy,s.vz,s.falling,s.range,s.traveled);}
    public record DefensiveTarget(Bullet bullet,int id,Team team,double x,double y,double z,double radius,float damage) {}
    public DefensiveTarget nearestDefensiveTarget(Team team,double x,double y,double z,double range){
        DefensiveTarget best=null;double bestDistance=Double.POSITIVE_INFINITY;
        for(Shot shot:shots.values()){
            if(shot.proxy.family!=Family.PROJECTILE||!shot.proxy.original.hittable||shot.bullet.team==team||!shot.bullet.isAdded()||shot.bullet.id!=shot.nativeId)continue;
            double distance=sq(x-shot.x)+sq(y-shot.y)+sq(z-shot.z);
            if(!CombatBalance.inEnvelope(Math.hypot(x-shot.x,y-shot.y),shot.z-z,range,antiAirVerticalMultiplier))continue;
            if(distance<bestDistance){bestDistance=distance;best=new DefensiveTarget(shot.bullet,shot.nativeId,shot.bullet.team,shot.x,shot.y,shot.z,shot.radius,shot.bullet.damage);}
        }
        return best;
    }
    public List<DefensiveTarget> defensiveTargets(Team team,double x,double y,double z,double range){
        var result=new ArrayList<DefensiveTarget>();
        for(Shot shot:shots.values())if(shot.proxy.family==Family.PROJECTILE&&shot.proxy.original.hittable&&shot.bullet.team!=team&&shot.bullet.isAdded()&&shot.bullet.id==shot.nativeId&&sq(x-shot.x)+sq(y-shot.y)+sq(z-shot.z)<=range*range)
            result.add(new DefensiveTarget(shot.bullet,shot.nativeId,shot.bullet.team,shot.x,shot.y,shot.z,shot.radius,shot.bullet.damage));
        return result;
    }
    public void mineLightning(Building source,mindustry.world.blocks.defense.ShockMine type,double z){
        if(!authoritative())return;Bullet carrier=Bullet.create();carrier.owner=source;carrier.team=source.team;carrier.type=mindustry.content.Bullets.damageLightningGround;carrier.damage=type.damage;
        double x=source.x/(double)Vars.tilesize,y=source.y/(double)Vars.tilesize;
        for(int i=0;i<Math.min(32,type.tendrils);i++){double angle=Math.PI*2*i/Math.max(1,type.tendrils);lightning.emit(carrier,carrier.type,x,y,z,CombatGeometry.direction(Math.cos(angle),Math.sin(angle),0),type.damage,type.length,type.lightningColor,x,y,Math.max(1,type.length*6.0/Vars.tilesize));}
    }
    public boolean intercept(DefensiveTarget target,float damage){
        if(!authoritative()||target==null||damage<=0)return false;
        Shot shot=shots.get(target.bullet);if(shot==null||shot.nativeId!=target.id||!target.bullet.isAdded())return false;
        if(target.bullet.damage>damage)target.bullet.damage-=damage;else{impactVisual(shot,shot.x,shot.y,shot.z);retire(shot);}
        return true;
    }
    public void defensiveBeam(Team team,double ax,double ay,double az,double bx,double by,double bz,Color color){
        if(authoritative())addEvent(new CombatVisual(0,CombatVisual.Kind.BEAM,team.id,ax,ay,az,bx,by,bz,.07,color.r,color.g,color.b,1));
    }
    /** Snapshot values are immutable; list contents are rebuilt on each call. Do not retain the list. */
    public List<CombatVisual> visuals(){
        if(active&&!authority.getAsBoolean())return remoteVisuals;
        visualBuffer.clear();
        for(Shot shot:shots.values())if(shot.bullet.isAdded()&&shot.bullet.id==shot.nativeId){
            if(shot.proxy.family==Family.PROJECTILE)visualBuffer.add(visual(shot,CombatVisual.Kind.PROJECTILE,
                shot.previousX,shot.previousY,shot.previousZ,shot.x,shot.y,shot.z,1));
            else if(shot.beamVisible)visualBuffer.add(visual(shot,CombatVisual.Kind.BEAM,
                shot.x,shot.y,shot.z,shot.endX,shot.endY,shot.endZ,(float)Math.max(.12,1-shot.bullet.fin())));
        }
        for(Event event:events){CombatVisual v=event.visual;
            visualBuffer.add(new CombatVisual(v.id(),v.kind(),v.teamId(),v.ax(),v.ay(),v.az(),v.bx(),v.by(),v.bz(),v.radius(),
                v.r(),v.g(),v.b(),v.alpha()*(float)(1-event.age/event.life)));
        }
        return visualView;
    }
    /** World/mode reset retires managed shots without native despawn damage or map mutations. */
    public void clear(){
        for(Shot shot:new ArrayList<>(shots.values()))if(shot.bullet.id==shot.nativeId&&shot.bullet.isAdded()){
            shot.bullet.hit=true;shot.bullet.remove();
        }
        shots.clear();eventPool.addAll(events);events.clear();visualBuffer.clear();remoteVisuals=List.of();targets.invalidate();mechWeapons.clear();navalWeapons.clear();aircraftWeapons.clear();
    }
    @Override public void close(){bindings.restore();unitRanges.apply(false);aircraftWeapons.apply(false);defense.close();turretTargeting.close();active=false;clear();installed=false;}

    BulletType unitProxy(BulletType original){
        Proxy shared=(Proxy)adapt(original,true);
        return new Proxy(original,shared.family,true);
    }

    BulletType adapt(BulletType original){return adapt(original,false);}
    BulletType adapt(BulletType original,boolean force){
        if(original==null||original instanceof Proxy)return original;
        Proxy existing=proxies.get(original);if(existing!=null){if(force)existing.forced=true;return existing;}
        Family family=family(original);
        if(family==null&&!force)return original;
        if(family==null){
            replacements.put(original,fallback.remove(original));
            family=replacementFamily(original);
        }
        Proxy proxy=new Proxy(original,family,force);proxies.put(original,proxy);
        // Deterministic prewarming gives server/client the same appended content identifiers.
        if(proxy.source.fragBullet!=null)adapt(proxy.source.fragBullet,force);
        if(proxy.source.intervalBullet!=null)adapt(proxy.source.intervalBullet,force);
        if(force)for(var spawn:proxy.source.spawnBullets)adapt(spawn,true);
        return proxy;
    }
    private Family replacementFamily(BulletType type){
        if(type.spawnUnit!=null)return Family.PROJECTILE;
        if(type instanceof LightningBulletType)return Family.LIGHTNING;
        if(type instanceof PointLaserBulletType)return Family.POINT_CONTINUOUS;
        if(type instanceof ContinuousBulletType)return Family.CONTINUOUS;
        if(type instanceof LaserBulletType||type instanceof ShrapnelBulletType||type instanceof RailBulletType||type instanceof PointBulletType||type instanceof SapBulletType)return Family.LASER;
        if(type instanceof ExplosionBulletType||type.instantDisappear&&type.speed<=0)return Family.EXPLOSION;
        return Family.PROJECTILE;
    }
    private Family family(BulletType type){
        if(checking.put(type,true)!=null){fallback.put(type,"cyclic projectile payload");return null;}
        try{return checkedFamily(type);}finally{checking.remove(type);}
    }
    private Family checkedFamily(BulletType type){
        String reason=null;Family result=null;
        if(type instanceof LightningBulletType)result=Family.LIGHTNING;
        else if(type instanceof ContinuousLaserBulletType||type.getClass()==ContinuousBulletType.class)result=Family.CONTINUOUS;
        else if(type instanceof LaserBulletType)result=Family.LASER;
        else result=Family.PROJECTILE;
        if(reason==null&&(type.spawnUnit!=null||type.despawnUnit!=null||type.spawnBullets.size>0))reason="unit/spawn payload";
        if(reason==null&&type instanceof LaserBulletType laser&&laser.lightningSpacing>0)reason="scheduled laser side-lightning";
        if(reason==null&&(type.lightning>0||result==Family.LIGHTNING)&&!safeLightningPayload(type.lightningType))reason="custom lightning payload";
        if(reason==null&&result==Family.LIGHTNING&&(type.fragBullet!=null||type.intervalBullet!=null||type.splashDamage>0))reason="custom instant lightning payload";
        if(reason==null&&(type.sticky||type.killShooter||type.instantDisappear||type.suppressionRange>0||type.circleShooter||(type.puddleLiquid!=null&&type.puddles>0)))reason="sticky/suppression/puddle/special payload";
        if(reason==null&&!CombatBindings.supportedPattern(type.shootPattern))reason="custom mover pattern";
        if(reason==null&&result==Family.PROJECTILE&&type.speed<=0)reason="stationary or instant special projectile";
        if(reason==null&&!standardLifecycle(type,result))reason="custom lifecycle override";
        if(reason==null&&type.fragBullet!=null&&family(type.fragBullet)==null)reason="unsupported fragment child";
        if(reason==null&&type.intervalBullet!=null&&family(type.intervalBullet)==null)reason="unsupported interval child";
        if(reason!=null){fallback.put(type,reason);return null;}
        return result;
    }
    private static boolean safeLightningPayload(BulletType type){
        return type==null||standardLifecycle(type,Family.PROJECTILE)&&type.spawnUnit==null&&type.despawnUnit==null&&type.spawnBullets.size==0
            &&type.fragBullet==null&&type.intervalBullet==null&&type.lightning==0&&type.splashDamage<=0&&type.suppressionRange<=0&&type.puddles==0&&!type.sticky;
    }
    private static boolean standardLifecycle(BulletType type,Family family){
        try{
            for(Class<?> owner=type.getClass();owner!=BulletType.class;owner=owner.getSuperclass()){
                if(owner==null)return false;
                if(owner==BasicBulletType.class||owner==MissileBulletType.class||owner==LaserBoltBulletType.class||owner==ArtilleryBulletType.class||owner==BombBulletType.class||owner==FlakBulletType.class||owner==LaserBulletType.class||owner==ContinuousBulletType.class||owner==ContinuousLaserBulletType.class||owner==LightningBulletType.class)continue;
                for(var method:owner.getDeclaredMethods()){
                    String name=method.getName();
                    if(!name.equals("draw")&&!name.equals("drawLight")&&!name.equals("load")&&!name.equals("calculateRange")&&!name.equals("estimateDPS")
                        &&!name.equals("continuousDamage")&&!(name.equals("init")&&method.getParameterCount()==0))return false;
                }
            }
            Class<?> init=type.getClass().getMethod("init",Bullet.class).getDeclaringClass();
            Class<?> update=type.getClass().getMethod("update",Bullet.class).getDeclaringClass();
            if(init!=BulletType.class&&!(family==Family.LASER&&init==LaserBulletType.class)&&!(family==Family.CONTINUOUS&&init==ContinuousBulletType.class)&&!(family==Family.LIGHTNING&&init==LightningBulletType.class))return false;
            if(update!=BulletType.class&&update!=ArtilleryBulletType.class&&update!=FlakBulletType.class&&!(family==Family.CONTINUOUS&&update==ContinuousBulletType.class))return false;
            return type.getClass().getMethod("hitEntity",Bullet.class,Hitboxc.class,float.class).getDeclaringClass()==BulletType.class
                &&type.getClass().getMethod("hit",Bullet.class,float.class,float.class,boolean.class).getDeclaringClass()==BulletType.class
                &&type.getClass().getMethod("despawned",Bullet.class).getDeclaringClass()==BulletType.class
                &&type.getClass().getMethod("removed",Bullet.class).getDeclaringClass()==BulletType.class;
        }catch(ReflectiveOperationException exception){return false;}
    }
    private final class Proxy extends BulletType {
        final BulletType original,source;final Family family;boolean forced;Teamc creatingTarget;Mover creatingMover;
        Proxy(BulletType original,Family family,boolean forced){
            this.original=original;this.source=forced?TurretProjectileAdapter.missile(original):original;this.family=family;this.forced=forced;
            TurretProjectileAdapter.copyMetadata(source,this);
            // These belong only to the newly created adapter, never the shared original content.
            collides=false;collidesTiles=false;hittable=false;absorbable=false;reflectable=false;
            drag=0;accel=0;keepVelocity=false;spawnUnit=null;instantDisappear=false;
            if(family==Family.PROJECTILE&&speed<=0)speed=.5f;
            if(source instanceof SapBulletType sap)lifesteal=sap.sapStrength;
        }
        @Override public Bullet create(Entityc owner,Entityc shooter,Team team,float x,float y,float angle,float damage,float velocityScl,
                                       float lifetimeScl,Object data,Mover mover,float aimX,float aimY,Teamc target){
            syncActive();
            if(!active)return original.create(owner,shooter,team,x,y,angle,damage,velocityScl,lifetimeScl,data,mover,aimX,aimY,target);
            // Arbitrary mover callbacks can encode custom physics; leave them native rather than discard it.
            if(mover!=null&&!forced)return original.create(owner,shooter,team,x,y,angle,damage,velocityScl,lifetimeScl,data,mover,aimX,aimY,target);
            Teamc previous=creatingTarget;Mover previousMover=creatingMover;creatingTarget=target;creatingMover=mover;
            try{
                Bullet bullet=super.create(owner,shooter,team,x,y,angle,damage,velocityScl,lifetimeScl,data,null,aimX,aimY,target);
                if(bullet!=null){Shot shot=shots.get(bullet);if(shot!=null){
                    if(!shot.suppressed)publishNative(shot);
                    if(!shot.suppressed&&source.killShooter&&authority.getAsBoolean()&&owner instanceof Healthc health&&!health.dead()){
                        // Retain killShooter metadata: native death weapons use it with totalShots
                        // to prevent a second explosion. Damage already ran at the captured XYZ pose.
                        if(owner instanceof Unit unit)for(var mount:unit.mounts)
                            if(mount.weapon.bullet==this)mount.totalShots=Math.max(1,mount.totalShots);
                        health.kill();
                    }
                    // Native add marks 'added' only after init. Depleted init hits can request
                    // removal before that flag exists; complete that request once add returns.
                    if(shot.suppressed||family==Family.EXPLOSION||bullet.hit&&source.removeAfterPierce)retire(shot);
                }}
                return bullet;
            }finally{creatingTarget=previous;creatingMover=previousMover;}
        }
        @Override public void init(Bullet bullet){spawn(bullet,this,creatingTarget);}
        @Override public void update(Bullet bullet){step(bullet);}
        @Override public void draw(Bullet bullet){source.draw(bullet);}
        @Override public void drawLight(Bullet bullet){source.drawLight(bullet);}
        void physicalHitEntity(Bullet bullet,Hitboxc target,float health){super.hitEntity(bullet,target,health);}
        void physicalPierce(Bullet bullet,float health,float x,float y){super.handlePierce(bullet,health,x,y);}
        boolean physicalTestCollision(Bullet bullet,Building build){return super.testCollision(bullet,build);}
        @Override public void despawned(Bullet bullet){/* Hard cleanup has no phantom native XY payload. */}
        @Override public void removed(Bullet bullet){
            Shot shot=shots.remove(bullet);
            if(shot!=null&&shot.beamVisible)addEvent(visual(shot,CombatVisual.Kind.BEAM,shot.x,shot.y,shot.z,shot.endX,shot.endY,shot.endZ,.8f));
        }
    }
    private void spawn(Bullet bullet,Proxy proxy,Teamc target){
        Shot shot=new Shot(bullet,proxy,++serial);shots.put(bullet,shot);
        shot.mountedLaunch=childContext==null;
        shot.target=target;
        shot.mover=proxy.creatingMover;
        if(!finiteOwner(bullet)||!finiteXY(bullet.x,bullet.y)||!finiteXY(bullet.vel.x,bullet.vel.y)){
            invalidAim(shot,"invalid native muzzle or projectile velocity");shot.suppressed=true;bullet.hit=true;return;
        }
        locateMuzzle(shot);
        var direction=direction(shot);
        if(direction==null){shot.suppressed=true;bullet.hit=true;return;}
        if(childContext==null&&bullet.owner instanceof Turret.TurretBuild turret&&turret.tile instanceof fpv.construction.LayerTile){
            var muzzle=TurretMuzzle3D.position(world,turret,shot.x,shot.y,shot.radius,direction);shot.x=muzzle.x();shot.y=muzzle.y();shot.z=muzzle.z();
            direction=direction(shot);if(direction==null){shot.suppressed=true;bullet.hit=true;return;}
        }
        if(childContext!=null){shot.x=childContext.x;shot.y=childContext.y;shot.z=childContext.z;direction=childContext.direction;shot.target=null;shot.inheritedEffectiveness=childContext.effectiveness;shot.effectiveness=childContext.effectiveness;shot.falling=childContext.falling;shot.spentAge=childContext.spentAge;bullet.damage*=shot.effectiveness;}
        shot.lastDirection=direction;
        shot.originX=childContext==null?shot.x:childContext.originX;shot.originY=childContext==null?shot.y:childContext.originY;
        shot.effectiveRange=childContext!=null&&childContext.range>0?childContext.range:shot.range;
        if(childContext==null&&bullet.owner instanceof Turret.TurretBuild turret)shot.effectiveRange=Math.max(.05,turret.range()/Vars.tilesize);
        if(childContext==null&&bullet.owner instanceof Unit unit)shot.effectiveRange=shot.range*unitRanges.horizontal(unit.type);
        double speed=shot.airAim!=null&&shot.airAim.bomber()&&proxy.source.speed==0?0:bullet.vel.len()*60.0/Vars.tilesize;
        shot.vx=direction.x()*speed;shot.vy=direction.y()*speed;shot.vz=direction.z()*speed;
        if(shot.airAim!=null&&shot.airAim.launchSpeed()>0){double launch=shot.airAim.launchSpeed();shot.vx=direction.x()*launch;shot.vy=direction.y()*launch;shot.vz=direction.z()*launch;}
        if(shot.mechAim!=null&&shot.mechAim.launchSpeed()>0){
            double launch=shot.mechAim.launchSpeed();shot.vx=direction.x()*launch;shot.vy=direction.y()*launch;shot.vz=direction.z()*launch;
        }
        if(proxy.family==Family.PROJECTILE&&proxy.source.keepVelocity&&bullet.owner instanceof Unit owner){
            var pose=world.unitState(owner);
            if(pose!=null){shot.vx+=pose.vx();shot.vy+=pose.vy();shot.vz+=pose.vz();
                double total=Math.sqrt(shot.vx*shot.vx+shot.vy*shot.vy+shot.vz*shot.vz);
                if(proxy.source.scaleKeepVelocity&&total>0)bullet.lifetime*=Math.min(1,speed/total);
            }
        }
        if(proxy.family==Family.PROJECTILE){
            shot.poweredLife=Math.max(1.0/60,bullet.lifetime/60.0);
            if(bullet.owner instanceof Turret.TurretBuild turret&&((Turret)turret.block).targetAir)
                shot.poweredLife*=Math.hypot(1,antiAirVerticalMultiplier);
            else if(childContext==null&&bullet.owner instanceof Unit unit&&!unit.type.flying)
                shot.poweredLife*=Math.hypot(unitRanges.horizontal(unit.type),unitRanges.vertical(unit.type));
            shot.poweredLife=Math.min(120,shot.poweredLife);
            shot.gravity=proxy.source instanceof ArtilleryBulletType||proxy.source instanceof BombBulletType||shot.airAim!=null&&shot.airAim.bomber();
            if(proxy.source instanceof ArtilleryBulletType&&childContext==null&&controlledEmitter(bullet)==null&&shot.mechAim==null&&shot.airAim==null){
                double distance=Math.hypot(bullet.aimX/Vars.tilesize-shot.x,bullet.aimY/Vars.tilesize-shot.y);
                if(bullet.aimX>=0&&bullet.aimY>=0){
                    double dz=direction.z()*distance/Math.max(1e-6,Math.hypot(direction.x(),direction.y()));
                    var arc=ProjectileDynamics.arc(bullet.rotation(),distance,dz,shot.poweredLife);
                    shot.vx=arc.x();shot.vy=arc.y();shot.vz=arc.z();
                }
            }
            // TimedComp still owns the native object; reserve a finite falling lifetime now.
            bullet.lifetime=(float)((shot.poweredLife+ProjectileDynamics.MAX_FALL_SECONDS)*60);
        }
        shot.previousX=shot.x;shot.previousY=shot.y;shot.previousZ=shot.z;
        if(!authority.getAsBoolean())return;
        if(shot.suppressed){bullet.hit=true;bullet.damage=0;return;}
        if(shot.airAim!=null){
            var color=proxy.source instanceof BasicBulletType basic?basic.frontColor:proxy.source.hitColor;
            addEvent(new CombatVisual(0,CombatVisual.Kind.HIT,bullet.team.id,shot.x,shot.y,shot.z,shot.x,shot.y,shot.z,Math.max(.045,Math.min(.16,shot.radius)),color.r,color.g,color.b,.85f));
        }
        if(proxy.family==Family.EXPLOSION){payload(shot,shot.x,shot.y,shot.z);bullet.hit=true;return;}
        if(proxy.family==Family.LIGHTNING)lightning.emit(bullet,proxy.source,shot.x,shot.y,shot.z,direction,(float)(proxy.source.damage*shot.effectiveness),
            proxy.source.lightningLength+Mathf.random(proxy.source.lightningLengthRand),proxy.source.lightningColor,shot.originX,shot.originY,shot.effectiveRange);
        if(proxy.family==Family.LASER||(proxy.family==Family.CONTINUOUS&&!((ContinuousBulletType)proxy.source).continuous))beam(shot,true);
        if(proxy.forced)for(var spawn:proxy.source.spawnBullets)child(shot,spawn,shot.x,shot.y,shot.z,Mathf.range(proxy.source.spawnBulletRandomSpread),1,1);
    }
    private void locateMuzzle(Shot shot){
        Bullet bullet=shot.bullet;
        shot.x=bullet.x/(double)Vars.tilesize;shot.y=bullet.y/(double)Vars.tilesize;
        if(bullet.owner instanceof Unit unit){
            Unit3DState pose=targets.ownerPose(unit);
            shot.suppressed=pose==null&&(unit.dead||!unit.isAdded());
            shot.z=pose==null?world.surfaceHeight(shot.x,shot.y)+.4:pose.z();
            if(shot.mountedLaunch)shot.z+=WeaponHeightStagger.offset(unit,WeaponHeightStagger.emitting(unit,bullet));
        }else if(bullet.owner instanceof Building build){
            shot.z=world.buildingTop(build)+shot.radius+MUZZLE_CLEARANCE;
        }else shot.z=world.surfaceHeight(shot.x,shot.y)+shot.radius+MUZZLE_CLEARANCE;
        shot.z=Math.max(shot.z,world.terrainHeight(shot.x,shot.y)+shot.radius+MUZZLE_CLEARANCE);
        if(shot.mountedLaunch&&bullet.owner instanceof Unit unit){
            var mount=mechWeapons.emittingMount(unit,bullet);
            shot.mechAim=mount==null?null:mechWeapons.solve(unit,mount,bullet);
            if(shot.mechAim!=null){var muzzle=shot.mechAim.muzzle();shot.x=muzzle.x();shot.y=muzzle.y();shot.z=muzzle.z();}
            shot.airAim=aircraftWeapons.emitting(unit,bullet);
            if(shot.airAim!=null){var muzzle=shot.airAim.muzzle();shot.x=muzzle.x();shot.y=muzzle.y();shot.z=muzzle.z();if(!shot.airAim.allowed())shot.suppressed=true;}
        }
    }
    private WorldCoordinates.Point direction(Shot shot){
        if(shot.airAim!=null)return shot.airAim.allowed()?shot.airAim.direction():null;
        if(shot.mechAim!=null)return shot.mechAim.direction();
        Bullet bullet=shot.bullet;
        double azimuth=bullet.rotation();
        if(!Double.isFinite(azimuth)){
            invalidAim(shot,"nonfinite native azimuth");return null;
        }
        Unit emitter=controlledEmitter(bullet);
        if(emitter!=null){
            Unit unit=emitter;
            WorldCoordinates.Point supplied=aim.direction(unit);
            if(supplied!=null){
                if(!finiteXY(unit.aimX,unit.aimY)||Math.max(Math.abs(supplied.x()),Math.max(Math.abs(supplied.y()),Math.abs(supplied.z())))<1e-12){
                    invalidAim(shot,"missing controlled aim direction");return null;
                }
                double nativeAim=Math.toDegrees(Math.atan2(unit.aimY-bullet.y,unit.aimX-bullet.x));
                double spread=((bullet.rotation()-nativeAim+540)%360)-180;
                if(!(unit instanceof BlockUnitc)&&fpv.render.NavalHullProfile.supports(unit.type)){
                    double horizontal=Math.hypot(supplied.x(),supplied.y());double yaw=horizontal<1e-9?azimuth:Math.toDegrees(Math.atan2(supplied.y(),supplied.x()));
                    double pitch=fpv.render.NavalHullProfile.forType(unit.type).clampPitch(Math.toDegrees(Math.atan2(supplied.z(),horizontal))),a=Math.toRadians(yaw),p=Math.toRadians(pitch);
                    supplied=new WorldCoordinates.Point(Math.cos(a)*Math.cos(p),Math.sin(a)*Math.cos(p),Math.sin(p));
                }
                return CombatGeometry.rotateAzimuth(CombatGeometry.direction(supplied.x(),supplied.y(),supplied.z()),spread);
            }
        }
        Teamc target=shot.target;
        if(bullet.owner instanceof Unit owner){
            var obstacle=fpv.construction.LayerNavigationAdapter.aim(owner,shot.proxy.source);
            if(obstacle!=null)return CombatGeometry.aim(azimuth,Math.hypot(obstacle.x()-shot.x,obstacle.y()-shot.y),obstacle.z()-shot.z);
        }
        if(bullet.owner instanceof Turret.TurretBuild turret&&turret.target instanceof Teamc nativeTarget)target=nativeTarget;
        if(!usableTarget(target)){if(target!=null)shot.target=null;target=null;}
        double tx=bullet.aimX/(double)Vars.tilesize,ty=bullet.aimY/(double)Vars.tilesize;
        // Native no-target (-1,-1) is a valid straight shot. Nonfinite native aim is
        // recoverable from a live physical target, otherwise cancel it rather than invent a hit.
        if(!finiteXY(tx,ty)&&target==null){invalidAim(shot,"nonfinite native aim without live target");return null;}
        double tz=finiteXY(tx,ty)?world.terrainHeight(tx,ty)+.15:shot.z;
        if(target instanceof Unit unit){
            var pose=world.unitState(unit);
            if(pose!=null){
                tx=pose.x();ty=pose.y();tz=pose.z();
                if(bullet.owner instanceof Turret.TurretBuild turret){var predicted=turretTargeting.prediction(turret,unit,shot.x,shot.y,shot.z);tx=predicted.x();ty=predicted.y();tz=predicted.z();}
                else if(bullet.owner instanceof Unit owner&&!owner.type.flying){
                    var predicted=unitRanges.prediction(owner,unit,shot.proxy.source,shot.x,shot.y,shot.z);
                    if(predicted!=null){
                        tx=predicted.x();ty=predicted.y();tz=predicted.z();
                        if(finiteXY(bullet.aimX,bullet.aimY)&&Math.hypot(tx-shot.x,ty-shot.y)>1e-9){
                            double commanded=Math.toDegrees(Math.atan2(bullet.aimY/Vars.tilesize-shot.y,bullet.aimX/Vars.tilesize-shot.x));
                            double lead=Math.toDegrees(Math.atan2(ty-shot.y,tx-shot.x));
                            azimuth+=((lead-commanded+540)%360)-180;
                        }
                    }
                }
                // Thin ground sprites can have centers lower than the physical projectile radius.
                // Aim within the expanded body above the floor, keeping the terrain sweep authoritative.
                if(!pose.flying())tz=Math.max(tz,Math.min(world.terrainHeight(tx,ty)+shot.radius+MUZZLE_CLEARANCE,
                    pose.z()+pose.halfHeight()+shot.radius-1e-4));
            }
        }
        else if(target instanceof Building building){tx=building.x/(double)Vars.tilesize;ty=building.y/(double)Vars.tilesize;tz=buildingAimHeight(shot,building);}
        else if(bullet.aimTile!=null&&usableTarget(bullet.aimTile.build))tz=buildingAimHeight(shot,bullet.aimTile.build);
        if(bullet.aimX==-1&&bullet.aimY==-1&&target==null)tz=shot.z;
        return CombatGeometry.aim(azimuth,Math.hypot(tx-shot.x,ty-shot.y),tz-shot.z);
    }
    private Unit controlledEmitter(Bullet bullet){
        Unit unit=bullet.owner instanceof Unit u?u:
            bullet.owner instanceof Turret.TurretBuild t&&t.controlled()?t.unit():null;
        return unit!=null&&aim.direction(unit)!=null?unit:null;
    }
    private double buildingAimHeight(Shot shot,Building building){
        double x=building.x/(double)Vars.tilesize,y=building.y/(double)Vars.tilesize;
        double base=world.buildingBase(building),top=world.buildingTop(building);
        return Math.max((base+top)*.5,Math.min(base+shot.radius+MUZZLE_CLEARANCE,top+shot.radius-1e-4));
    }
    private void steer(Shot shot,double dt){
        BulletType type=shot.proxy.source;Bullet bullet=shot.bullet;
        double rate=type.homingPower*3000;
        WorldCoordinates.Point destination=null;
        if(rate>0&&shot.age*60>=type.homingDelay){
            Teamc target=shot.target;
            if(!usableTarget(target))target=null;
            if(target==null){
                final Unit[] chosen={null};final double[] best={Math.max(0,type.homingRange/Vars.tilesize)};
                targets.nearby(bullet.team,shot.x-best[0],shot.y-best[0],shot.x+best[0],shot.y+best[0],0,dt,unit->{
                    if(unit.dead||!unit.isAdded()||!unit.checkTarget(type.collidesAir,type.collidesGround)||shot.hitUnits.contains(unit.id))return;
                    var body=world.unitState(unit);if(body==null)return;
                    double distance=Math.sqrt(sq(body.x()-shot.x)+sq(body.y()-shot.y)+sq(body.z()-shot.z));
                    if(distance<best[0]){best[0]=distance;chosen[0]=unit;}
                });
                target=chosen[0];
                if(target==null&&bullet.aimTile!=null&&bullet.aimTile.build!=null)target=bullet.aimTile.build;
            }
            if(target instanceof Unit unit){var pose=world.unitState(unit);if(pose!=null){
                double z=pose.flying()?pose.z():Math.max(pose.z(),Math.min(world.terrainHeight(pose.x(),pose.y())+shot.radius+MUZZLE_CLEARANCE,pose.z()+pose.halfHeight()+shot.radius-1e-4));
                destination=new WorldCoordinates.Point(pose.x(),pose.y(),z);
            }}
            else if(target instanceof Building build)destination=new WorldCoordinates.Point(build.x/Vars.tilesize,build.y/Vars.tilesize,buildingAimHeight(shot,build));
            if(destination!=null&&Math.sqrt(sq(destination.x()-shot.x)+sq(destination.y()-shot.y)+sq(destination.z()-shot.z))>type.homingRange/Vars.tilesize)destination=null;
        }
        if(type.followAimSpeed>0&&bullet.shooter instanceof Unit unit){
            var supplied=aim.direction(unit);
            if(supplied!=null)destination=new WorldCoordinates.Point(shot.x+supplied.x()*100,shot.y+supplied.y()*100,shot.z+supplied.z()*100);
            else if(finiteXY(unit.aimX,unit.aimY))destination=new WorldCoordinates.Point(unit.aimX/Vars.tilesize,unit.aimY/Vars.tilesize,world.surfaceHeight(unit.aimX/Vars.tilesize,unit.aimY/Vars.tilesize));
            else {invalidAim(shot,"nonfinite follow-aim target");retire(shot);return;}
            rate=type.followAimSpeed*60;
        }
        if(destination!=null){
            var velocity=ProjectileDynamics.steer(shot.vx,shot.vy,shot.vz,destination.x()-shot.x,destination.y()-shot.y,destination.z()-shot.z,Math.toRadians(rate)*dt);
            shot.vx=velocity.x();shot.vy=velocity.y();shot.vz=velocity.z();
        }
        double weave=type.weaveMag==0?0:Math.sin((shot.age*60+Math.PI*type.weaveScale*.5)/Math.max(.001,type.weaveScale))*type.weaveMag*(type.weaveRandom&&(bullet.id&1)==1?-1:1);
        double angle=Math.toRadians((weave+type.rotateSpeed)*60*dt),c=Math.cos(angle),s=Math.sin(angle),vx=shot.vx;
        shot.vx=vx*c-shot.vy*s;shot.vy=vx*s+shot.vy*c;
    }
    private void interval(Shot shot,double dt){
        BulletType type=shot.proxy.source;
        if(type.intervalBullet==null||shot.falling||shot.age*60<type.intervalDelay)return;
        shot.intervalClock+=dt;
        double period=Math.max(1.0/60,type.bulletInterval/60.0);
        if(shot.intervalClock<period)return;
        shot.intervalClock%=period;
        for(int i=0;i<Math.min(64,type.intervalBullets);i++){
            double angle=Mathf.range(type.intervalRandomSpread)+type.intervalAngle+(i-(type.intervalBullets-1)*.5)*type.intervalSpread;
            child(shot,type.intervalBullet,shot.x,shot.y,shot.z,angle,1,1);
        }
    }
    private void fragments(Shot shot,double x,double y,double z){
        BulletType type=shot.proxy.source;
        if(type.fragBullet==null||(!type.fragOnAbsorb&&shot.bullet.absorbed)||(type.pierceFragCap>=0&&shot.bullet.frags>=type.pierceFragCap))return;
        shot.bullet.frags++;
        for(int i=0;i<Math.min(64,type.fragBullets);i++){
            double angle=Mathf.range(type.fragRandomSpread*.5f)+type.fragAngle+(i-(type.fragBullets-1)*.5)*type.fragSpread;
            child(shot,type.fragBullet,x,y,z,angle,Mathf.random(type.fragVelocityMin,type.fragVelocityMax),Mathf.random(type.fragLifeMin,type.fragLifeMax));
        }
    }
    private void child(Shot parent,BulletType original,double x,double y,double z,double angle,float velocity,float lifetime){
        if(childDepth>=8||shots.size()>=16384)return;
        BulletType adapted=adapt(original,parent.proxy.forced);if(!(adapted instanceof Proxy))throw new IllegalStateException("Unvalidated native child would leak XY combat");
        var d=shotDirection(parent);
        if(d==null)return;
        d=CombatGeometry.rotateAzimuth(d,angle);
        SpawnContext previous=childContext;childContext=new SpawnContext(x,y,z,d,parent.effectiveness,parent.originX,parent.originY,parent.effectiveRange,parent.falling,parent.spentAge);childDepth++;
        try{
            adapted.create(parent.bullet.owner,parent.bullet.shooter,parent.bullet.team,(float)(x*Vars.tilesize),(float)(y*Vars.tilesize),
                (float)Math.toDegrees(Math.atan2(d.y(),d.x())),-1,velocity,lifetime,null,null,-1,-1,null);
        }finally{childDepth--;childContext=previous;}
    }
    private void flak(Shot shot,double dt){
        if(!(shot.proxy.source instanceof FlakBulletType type))return;
        if(shot.fuse>=0){shot.fuse-=dt;if(shot.fuse<=0){payload(shot,shot.x,shot.y,shot.z);finish(shot);}return;}
        if(shot.age*60<type.flakDelay)return;
        shot.flakClock+=dt;if(shot.flakClock<Math.max(1.0/60,type.flakInterval/60.0))return;shot.flakClock=0;
        double radius=type.explodeRange/Vars.tilesize;
        targets.nearby(shot.bullet.team,shot.x-radius,shot.y-radius,shot.x+radius,shot.y+radius,0,dt,unit->{
            if(unit.dead||!unit.isAdded()||!unit.checkTarget(type.collidesAir,type.collidesGround)||!unit.targetable(shot.bullet.team))return;
            var body=world.unitState(unit);if(body!=null&&ProjectileDynamics.cylinderDistance(shot.x,shot.y,shot.z,body.x(),body.y(),body.z(),body.radius(),body.halfHeight())<=radius)
                shot.fuse=Math.max(0,type.explodeDelay/60.0);
        });
    }
    private void payload(Shot shot,double x,double y,double z){
        payloads.area(shot.bullet,shot.proxy.source,x,y,z,shot.effectiveness);ignite(shot,x,y,z);
        payloads.surface(shot.proxy.source,x,y,z,shot.radius,shot.effectiveness);
        if(shot.proxy.source.fragOnHit)fragments(shot,x,y,z);
        BulletType type=shot.proxy.source;
        if(type.lightning>0){
            var direction=shotDirection(shot);
            for(int i=0;direction!=null&&i<Math.min(32,type.lightning);i++)lightning.emit(shot.bullet,type,x,y,z,
                CombatGeometry.rotateAzimuth(direction,Mathf.range(type.lightningCone*.5f)+type.lightningAngle),
                (float)((type.lightningDamage<0?type.damage:type.lightningDamage)*shot.effectiveness),type.lightningLength+Mathf.random(type.lightningLengthRand),type.lightningColor,shot.originX,shot.originY,shot.effectiveRange);
        }
        impactVisual(shot,x,y,z);
    }
    private WorldCoordinates.Point shotDirection(Shot shot){return sq(shot.vx)+sq(shot.vy)+sq(shot.vz)>1e-12?CombatGeometry.direction(shot.vx,shot.vy,shot.vz):shot.lastDirection;}
    private void step(Bullet bullet){
        Shot shot=shots.get(bullet);if(shot==null||shot.nativeId!=bullet.id)return;
        if(!authority.getAsBoolean())return;
        double dt=Math.max(0,Time.delta/60.0);
        if(dt==0){publishNative(shot);return;}
        if(shot.proxy.family!=Family.PROJECTILE){
            shot.age+=dt;
            if(shot.proxy.family==Family.CONTINUOUS||shot.proxy.family==Family.POINT_CONTINUOUS){
                if(!finiteOwner(bullet)||!finiteXY(bullet.x,bullet.y)||!finiteXY(bullet.vel.x,bullet.vel.y)){
                    invalidAim(shot,"invalid updated native beam muzzle/velocity");retire(shot);return;
                }
                locateMuzzle(shot);
                if(bullet.owner instanceof Turret.TurretBuild turret&&turret.tile instanceof fpv.construction.LayerTile){
                    var direction=direction(shot);if(direction==null){retire(shot);return;}
                    var muzzle=TurretMuzzle3D.position(world,turret,shot.x,shot.y,shot.radius,direction);shot.x=muzzle.x();shot.y=muzzle.y();shot.z=muzzle.z();
                }
                BulletType type=shot.proxy.source;
                float interval=type instanceof ContinuousBulletType continuous?continuous.damageInterval:((PointLaserBulletType)type).damageInterval;
                boolean pulse=(!(type instanceof ContinuousBulletType continuous)||continuous.continuous)&&bullet.timer(1,Math.max(.01f,interval));
                beam(shot,pulse);
            }
            publishNative(shot);return;
        }
        if(shot.firstUpdate){shot.firstUpdate=false;publishNative(shot);return;}
        shot.previousX=shot.x;shot.previousY=shot.y;shot.previousZ=shot.z;
        double moverDx=0,moverDy=0;
        if(shot.mover!=null){
            publishNative(shot);double ox=bullet.x,oy=bullet.y;shot.mover.move(bullet);
            if(!finiteXY(bullet.x,bullet.y)||!finiteXY(bullet.vel.x,bullet.vel.y)){invalidAim(shot,"nonfinite native projectile mover result");retire(shot);return;}
            moverDx=(bullet.x-ox)/Vars.tilesize;moverDy=(bullet.y-oy)/Vars.tilesize;
        }
        // Small physical substeps make curved trajectories continuous even at low frame rates.
        int steps=Math.max(1,(int)Math.ceil(dt*120));double h=dt/steps;
        for(int i=0;i<steps&&bullet.isAdded();i++){
            if(!shot.falling&&(shot.age>=shot.poweredLife||Math.hypot(shot.x-shot.originX,shot.y-shot.originY)>=shot.effectiveRange))shot.falling=true;
            if(shot.falling)shot.spentAge+=h;
            applyEffectiveness(shot);
            if(shot.effectiveness<=0){impactVisual(shot,shot.x,shot.y,shot.z);retire(shot);return;}
            if(!shot.falling)steer(shot,h);
            if(!bullet.isAdded())return;
            var motion=ProjectileDynamics.integrate(shot.vx,shot.vy,shot.vz,h,shot.gravity||shot.falling,
                Math.max(0,shot.proxy.source.drag)*60+(shot.falling?.025:0));
            if(shot.falling){double extra=ProjectileDynamics.GRAVITY*(CombatBalance.SPENT_GRAVITY_MULTIPLIER-1);
                motion=new ProjectileDynamics.Step(motion.dx(),motion.dy(),motion.dz()-.5*extra*h*h,motion.vx(),motion.vy(),motion.vz()-extra*h);
            }
            double bx=shot.x+motion.dx()+(i==0?moverDx:0),by=shot.y+motion.dy()+(i==0?moverDy:0),bz=shot.z+motion.dz();
            double distance=Math.sqrt(sq(motion.dx())+sq(motion.dy())+sq(motion.dz()));
            double fraction=trace(shot,bx,by,bz,true,false,h,Math.max(0,dt-(i+1)*h));
            shot.x+=(bx-shot.x)*fraction;shot.y+=(by-shot.y)*fraction;shot.z+=(bz-shot.z)*fraction;
            shot.traveled+=distance*fraction;shot.age+=h;
            shot.vx=motion.vx();shot.vy=motion.vy();shot.vz=motion.vz();publishNative(shot);
            if(shot.blocked||fraction<1){finish(shot);return;}
            if(!shot.falling){
                double speed=Math.sqrt(sq(shot.vx)+sq(shot.vy)+sq(shot.vz));
                if(speed>0){double scale=Math.max(0,speed+shot.proxy.source.accel*3600/Vars.tilesize*h)/speed;shot.vx*=scale;shot.vy*=scale;shot.vz*=scale;}
            }
        }
        if(!bullet.isAdded())return;
        interval(shot,dt);flak(shot,dt);
        if(shot.age>shot.poweredLife+ProjectileDynamics.MAX_FALL_SECONDS||shot.x<-64||shot.y<-64||shot.x>Vars.world.width()+64||shot.y>Vars.world.height()+64||shot.z < -8||shot.z>4096){retire(shot);return;}
        publishNative(shot);
    }
    private void beam(Shot shot,boolean damage){
        var direction=direction(shot);BulletType source=shot.proxy.source;
        if(direction==null){shot.suppressed=true;retire(shot);return;}
        shot.lastDirection=direction;
        double length=beamLength(shot);
        // Native point lasers apply damage only at their aimed endpoint. Their
        // connecting line remains visible, but must not become a piercing laser.
        if(source instanceof PointLaserBulletType){
            Teamc target=shot.target;
            if(shot.bullet.owner instanceof Turret.TurretBuild turret&&turret.target instanceof Teamc current)target=current;
            if(usableTarget(target)){
                double tx=target.x()/Vars.tilesize,ty=target.y()/Vars.tilesize,tz=world.terrainHeight(tx,ty);
                if(target instanceof Unit unit){var pose=world.unitState(unit);if(pose!=null)tz=pose.z();}
                else if(target instanceof Building build)tz=buildingAimHeight(shot,build);
                length=Math.min(length,Math.sqrt(sq(tx-shot.x)+sq(ty-shot.y)+sq(tz-shot.z)));
            }else if(shot.bullet.aimX>=0&&shot.bullet.aimY>=0){
                double horizontal=Math.hypot(direction.x(),direction.y());
                if(horizontal>1e-9)length=Math.min(length,Math.hypot(shot.bullet.aimX/Vars.tilesize-shot.x,shot.bullet.aimY/Vars.tilesize-shot.y)/horizontal);
            }
        }
        if(!Double.isFinite(length)){invalidAim(shot,"nonfinite native beam length");shot.suppressed=true;retire(shot);return;}
        length=Math.max(0,Math.min(8192,length));
        double bx=shot.x+direction.x()*length,by=shot.y+direction.y()*length,bz=shot.z+direction.z()*length;
        float originalDamage=shot.bullet.damage;
        double originalEffectiveness=shot.effectiveness;
        if(source instanceof ContinuousBulletType continuous&&continuous.timescaleDamage&&shot.bullet.owner instanceof Building owner)shot.bullet.damage*=owner.timeScale();
        double fraction=trace(shot,bx,by,bz,damage,true,0,0);
        shot.bullet.damage=originalDamage;
        shot.effectiveness=originalEffectiveness;
        shot.endX=shot.x+(bx-shot.x)*fraction;shot.endY=shot.y+(by-shot.y)*fraction;shot.endZ=shot.z+(bz-shot.z)*fraction;
        shot.bullet.fdata=(float)(length*fraction*Vars.tilesize);shot.beamVisible=true;
    }
    private double beamLength(Shot shot){
        double length=nativeBeamLength(shot);
        if(shot.bullet.owner instanceof Unit unit&&!unit.type.flying){
            var d=shot.lastDirection;double horizontal=d==null?1:Math.hypot(d.x(),d.y()),vertical=d==null?0:Math.abs(d.z());
            length*=Math.min(horizontal<1e-9?Double.POSITIVE_INFINITY:unitRanges.horizontal(unit.type)/horizontal,
                vertical<1e-9?Double.POSITIVE_INFINITY:unitRanges.vertical(unit.type)/vertical);
        }
        if(shot.bullet.owner instanceof Turret.TurretBuild turret&&turret.target instanceof Unit unit&&unit.isFlying()&&((Turret)turret.block).targetAir)
            length=Math.max(length,turret.range()/Vars.tilesize*Math.hypot(1,antiAirVerticalMultiplier));
        return length;
    }
    private double nativeBeamLength(Shot shot){
        BulletType source=shot.proxy.source;
        if(source instanceof LaserBulletType laser)return laser.length/Vars.tilesize;
        if(source instanceof ShrapnelBulletType shrapnel)return shrapnel.length/Vars.tilesize;
        if(source instanceof RailBulletType rail)return rail.length/Vars.tilesize;
        if(source instanceof SapBulletType sap)return sap.length/Vars.tilesize;
        if(source instanceof ContinuousBulletType continuous)return continuous.currentLength(shot.bullet)/Vars.tilesize;
        if(source instanceof PointBulletType)return source.speed*shot.bullet.lifetime/Vars.tilesize;
        if(shot.bullet.owner instanceof Turret.TurretBuild turret)return turret.range()/Vars.tilesize;
        return Math.max(1,shot.range);
    }
    private void applyEffectiveness(Shot shot){
        applyEffectiveness(shot,shot.x,shot.y);
    }
    private void applyEffectiveness(Shot shot,double x,double y){
        double next=shot.inheritedEffectiveness*(shot.falling?CombatBalance.spentDamage(shot.spentAge):1)
            *rangeFalloff.factor(Math.hypot(x-shot.originX,y-shot.originY),shot.effectiveRange);
        if(shot.effectiveness>1e-12)shot.bullet.damage*=next/shot.effectiveness;
        else shot.bullet.damage=0;
        shot.effectiveness=next;
    }
    /** Continuous geometry sorts all target contacts ahead of the nearest natural terrain hit. */
    private double trace(Shot shot,double bx,double by,double bz,boolean damage,boolean beam,double targetStep,double targetEndLag){
        Bullet bullet=shot.bullet;BulletType type=shot.proxy.source;
        boolean pointBeam=beam&&(type instanceof PointLaserBulletType||type instanceof PointBulletType);
        boolean absorbLaserWalls=type.laserAbsorb&&!(type instanceof RailBulletType)&&!pointBeam;
        boolean addedAtStart=bullet.isAdded();
        var terrain=world.traceTerrainSegment(shot.x,shot.y,shot.z,bx,by,bz,shot.radius);
        shot.blocked=terrain.hit();
        double stop=terrain.hit()?terrain.fraction():1;
        var projection=projectedDefense==null?null:beam&&!(type instanceof SapBulletType)?
            projectedDefense.traceLaser(bullet.team,shot.x,shot.y,shot.z,bx,by,bz,shot.radius,absorbLaserWalls,bullet):
            projectedDefense.trace(bullet.team,shot.x,shot.y,shot.z,bx,by,bz,shot.radius,bullet);
        if(projection!=null&&projection.fraction()>=0&&projection.fraction()<=stop)stop=projection.fraction();else projection=null;
        var hits=new ArrayList<Contact>();
        final double terrainStop=stop;
        targets.nearby(bullet.team,shot.x,shot.y,bx,by,shot.radius,targetStep,unit->{
            if(unit==bullet.owner||unit.team==bullet.team||unit.dead||!unit.isAdded()||!unit.hittable()||!unit.checkTarget(type.collidesAir,type.collidesGround)||(!beam&&shot.hitUnits.contains(unit.id)))return;
            var body=world.unitState(unit);if(body==null)return;
            double fraction=CombatGeometry.movingCylinder(shot.x,shot.y,shot.z,bx,by,bz,
                body.x(),body.y(),body.z(),body.vx(),body.vy(),body.vz(),body.radius()+shot.radius,body.halfHeight()+shot.radius,targetStep,targetEndLag);
            if(fraction<=terrainStop)hits.add(new Contact(fraction,unit,null));
        });
        if(type.collidesGround&&(type.collidesTiles||shot.gravity)){
            IntSet visited=new IntSet();
            int minX=Math.max(0,(int)Math.floor(Math.min(shot.x,bx)-shot.radius-.5)),maxX=Math.min(Vars.world.width()-1,(int)Math.ceil(Math.max(shot.x,bx)+shot.radius+.5));
            int minY=Math.max(0,(int)Math.floor(Math.min(shot.y,by)-shot.radius-.5)),maxY=Math.min(Vars.world.height()-1,(int)Math.ceil(Math.max(shot.y,by)+shot.radius+.5));
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++){
                var tile=Vars.world.tile(x,y);Building build=tile==null?null:tile.build;
                boolean friendly=build!=null&&build.team==bullet.team;
                if(build==null||build==bullet.owner||friendly&&!(type.heals()&&type.collidesTeam)||build.dead||!visited.add(build.id)||(!beam&&shot.hitBuildings.contains(build.id))||!(shot.proxy.forced?shot.proxy.physicalTestCollision(bullet,build):type.testCollision(bullet,build))||!friendly&&!build.collide(bullet))continue;
                double cx=build.x/(double)Vars.tilesize,cy=build.y/(double)Vars.tilesize,half=build.block.size*.5+shot.radius;
                double fraction=CombatGeometry.box(shot.x,shot.y,shot.z,bx,by,bz,cx-half,cy-half,world.terrainHeight(cx,cy)-shot.radius,cx+half,cy+half,world.surfaceHeight(cx,cy)+shot.radius);
                if(fraction<=stop&&bullet.checkUnderBuild(build,(float)((shot.x+(bx-shot.x)*fraction)*Vars.tilesize),(float)((shot.y+(by-shot.y)*fraction)*Vars.tilesize)))hits.add(new Contact(fraction,null,build));
            }
        }
        hits.sort(Comparator.comparingDouble(Contact::fraction));
        if(pointBeam&&absorbLaserWalls)for(Contact contact:hits){
            if(contact.fraction>stop)break;
            if(contact.building!=null&&contact.building.absorbLasers()){stop=contact.fraction;shot.blocked=true;break;}
        }
        int pierced=beam?0:shot.hitUnits.size+shot.hitBuildings.size;
        for(Contact contact:hits){
            if(contact.fraction>stop)break;
            double fraction=pointBeam?stop:contact.fraction;
            double x=shot.x+(bx-shot.x)*fraction,y=shot.y+(by-shot.y)*fraction,z=shot.z+(bz-shot.z)*fraction;
            if(pointBeam&&!containsEndpoint(contact,x,y,z,shot.radius))continue;
            if(damage){
                applyEffectiveness(shot,x,y);
                if(shot.effectiveness<=0){stop=contact.fraction;shot.blocked=true;break;}
                bullet.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
                if(contact.unit!=null&&!contact.unit.dead&&contact.unit.isAdded()){
                    contact.unit.collision(bullet,bullet.x,bullet.y);
                    if(!shot.proxy.forced&&type instanceof SapBulletType sap&&bullet.owner instanceof Healthc owner)
                        owner.heal(Math.max(0,Math.min(contact.unit.health,bullet.damage))*sap.sapStrength);
                    if(shot.proxy.forced)shot.proxy.physicalHitEntity(bullet,contact.unit,contact.unit.health);else type.hitEntity(bullet,contact.unit,contact.unit.health);
                    shot.hitUnits.add(contact.unit.id);
                }else if(contact.building!=null&&!contact.building.dead){
                    float health=contact.building.health;
                    if(contact.building.team==bullet.team)payloads.healing(contact.building,type);
                    else{
                        contact.building.collision(bullet);
                        if(shot.proxy.lifesteal>0&&bullet.owner instanceof Healthc owner)owner.heal(Math.max(0,Math.min(health,bullet.damage))*shot.proxy.lifesteal);
                        if(type.makeFire)mindustry.entities.Fires.create(contact.building.tile);
                    }
                    if(shot.proxy.forced)shot.proxy.physicalPierce(bullet,health,bullet.x,bullet.y);else type.handlePierce(bullet,health,bullet.x,bullet.y);shot.hitBuildings.add(contact.building.id);
                }
                payload(shot,x,y,z);
            }
            pierced++;
            boolean stopHere=beam&&type instanceof SapBulletType
                ||contact.building!=null&&(beam?absorbLaserWalls&&contact.building.absorbLasers():!type.pierceBuilding)
                ||contact.unit!=null&&!type.pierce||(beam?type.pierceCap>0:type.pierceCap>=0)&& (beam||type.removeAfterPierce)&&pierced>=type.pierceCap||bullet.damage<=0||(addedAtStart&&!bullet.isAdded());
            if(stopHere){stop=fraction;shot.blocked=true;break;}
        }
        if(projection!=null&&stop==projection.fraction()){
            if(!damage){shot.blocked=true;return stop;}
            applyEffectiveness(shot,projection.x(),projection.y());
            if(projectedDefense.hit(projection,bullet,Math.max(0,bullet.damage))){
                boolean material=projectedDefense.physicalSurface(projection);
                shot.blocked=true;shot.defenseAbsorbed=!material;if(material)payload(shot,projection.x(),projection.y(),projection.z());
                impactVisual(shot,projection.x(),projection.y(),projection.z());return stop;
            }
            // A stale token is safe to pass; other contacts are reconsidered in the next physical step.
            stop=terrain.hit()?terrain.fraction():1;shot.blocked=terrain.hit();
        }
        if(damage&&terrain.hit()&&stop==terrain.fraction()){
            double x=shot.x+(bx-shot.x)*stop,y=shot.y+(by-shot.y)*stop,z=shot.z+(bz-shot.z)*stop;
            applyEffectiveness(shot,x,y);if(shot.effectiveness>0)payload(shot,x,y,z);
        }
        return stop;
    }
    private boolean containsEndpoint(Contact contact,double x,double y,double z,double radius){
        if(contact.unit!=null){
            var body=world.unitState(contact.unit);
            return body!=null&&ProjectileDynamics.cylinderDistance(x,y,z,body.x(),body.y(),body.z(),body.radius(),body.halfHeight())<=radius+1e-6;
        }
        var build=contact.building;double cx=build.x/(double)Vars.tilesize,cy=build.y/(double)Vars.tilesize,half=build.block.size*.5;
        return ProjectileDynamics.boxDistance(x,y,z,cx-half,cy-half,world.terrainHeight(cx,cy),cx+half,cy+half,world.surfaceHeight(cx,cy))<=radius+1e-6;
    }
    /** Native chance/count and candidate spread; only surfaces actually reached in XYZ ignite. */
    private void ignite(Shot shot,double x,double y,double z){
        BulletType type=shot.proxy.source;
        if(type.incendAmount<=0||type.incendChance<=0||!Mathf.chance(type.incendChance))return;
        double spread=Math.max(0,type.incendSpread/Vars.tilesize),reach=spread+shot.radius;
        for(int i=0;i<type.incendAmount;i++){
            double px=x+Mathf.range((float)spread),py=y+Mathf.range((float)spread);
            var tile=Vars.world.tileWorld((float)(px*Vars.tilesize),(float)(py*Vars.tilesize));
            if(tile==null)continue;
            double top=world.surfaceHeight(tile.x,tile.y);
            if(sq(px-x)+sq(py-y)+sq(top-z)<=reach*reach)mindustry.entities.Fires.create(tile);
        }
    }
    private void publishNative(Shot shot){
        shot.bullet.set((float)(shot.x*Vars.tilesize),(float)(shot.y*Vars.tilesize));
        if(shot.proxy.family==Family.PROJECTILE)shot.bullet.vel.set((float)(shot.vx*Vars.tilesize/60),(float)(shot.vy*Vars.tilesize/60));
    }
    private void finish(Shot shot){
        if(!shot.defenseAbsorbed&&shot.bullet.frags==0&&shot.proxy.source.fragOnDespawn)fragments(shot,shot.x,shot.y,shot.z);
        retire(shot);
    }
    private void retire(Shot shot){if(shot.bullet.isAdded()){shot.bullet.hit=true;shot.bullet.remove();}}
    private void impactVisual(Shot shot,double x,double y,double z){
        boolean beam=shot.proxy.family==Family.CONTINUOUS||shot.proxy.family==Family.POINT_CONTINUOUS;
        if(shot.impactShown&&(!beam||shot.age-shot.lastImpactAge<.08))return;
        shot.impactShown=true;shot.lastImpactAge=shot.age;
        boolean explosive=shot.proxy.source.splashDamage>0&&shot.proxy.source.splashDamageRadius>0;
        addEvent(visual(shot,explosive?CombatVisual.Kind.EXPLOSION:CombatVisual.Kind.HIT,x,y,z,x,y,z,1));
        if(!Vars.headless&&shot.proxy.source.hitSound!=Sounds.none)shot.proxy.source.hitSound.at((float)(x*Vars.tilesize),(float)(y*Vars.tilesize),shot.proxy.source.hitSoundPitch,shot.proxy.source.hitSoundVolume);
    }
    private CombatVisual visual(Shot shot,CombatVisual.Kind kind,double ax,double ay,double az,double bx,double by,double bz,float alpha){
        Color color=shot.proxy.source.hitColor;
        if(shot.proxy.source instanceof BasicBulletType basic)color=basic.frontColor;
        else if(shot.proxy.source instanceof LaserBulletType laser&&laser.colors.length>1)color=laser.colors[laser.colors.length-2];
        else if(shot.proxy.source instanceof ContinuousLaserBulletType laser&&laser.colors.length>1)color=laser.colors[laser.colors.length-2];
        double radius=kind==CombatVisual.Kind.EXPLOSION?Math.max(.2,Math.min(20,shot.proxy.source.splashDamageRadius/Vars.tilesize*Math.sqrt(Math.max(0,shot.effectiveness)))):shot.radius;
        return new CombatVisual(shot.id,kind,shot.bullet.team.id,ax,ay,az,bx,by,bz,radius,color.r,color.g,color.b,alpha);
    }
    private void healingVisual(Building build){
        double x=build.x/(double)Vars.tilesize,y=build.y/(double)Vars.tilesize;
        addEvent(new CombatVisual(0,CombatVisual.Kind.HEAL,build.team.id,x,y,world.buildingBase(build),x,y,world.buildingTop(build),build.block.size*.5,.36f,1,.52f,1));
    }
    private void addEvent(CombatVisual visual){
        if(events.size()>=MAX_EVENTS)eventPool.addLast(events.remove(0));
        var event=new CombatVisual(++serial,visual.kind(),visual.teamId(),visual.ax(),visual.ay(),visual.az(),visual.bx(),visual.by(),visual.bz(),visual.radius(),visual.r(),visual.g(),visual.b(),visual.alpha());
        Event pooled=eventPool.pollFirst();if(pooled==null)pooled=new Event();
        pooled.visual=event;pooled.life=visual.kind()==CombatVisual.Kind.EXPLOSION?.45:EVENT_LIFETIME;pooled.age=0;events.add(pooled);
    }
    private static double sq(double x){return x*x;}
    private record Contact(double fraction,Unit unit,Building building) {}
    private record SpawnContext(double x,double y,double z,WorldCoordinates.Point direction,double effectiveness,double originX,double originY,double range,boolean falling,double spentAge) {}
    private static final class Event {CombatVisual visual;double life,age;}
    private static final class Shot {
        MechWeaponAim3D.Solution mechAim;
        AircraftWeapons.Solution airAim;
        boolean mountedLaunch;
        final Bullet bullet;final Proxy proxy;final long id;final int nativeId;final double radius,range;
        final IntSet hitUnits=new IntSet(),hitBuildings=new IntSet();
        Teamc target;Mover mover;WorldCoordinates.Point lastDirection;double x,y,z,vx,vy,vz,previousX,previousY,previousZ,endX,endY,endZ,traveled,poweredLife,age,intervalClock,flakClock,fuse=-1,spentAge,effectiveness=1,inheritedEffectiveness=1,originX,originY,effectiveRange;
        double lastImpactAge;boolean impactShown;
        boolean firstUpdate=true,beamVisible,blocked,falling,gravity,suppressed,defenseAbsorbed;
        Shot(Bullet bullet,Proxy proxy,long id){
            this.bullet=bullet;this.proxy=proxy;this.id=id;nativeId=bullet.id;
            radius=Math.max(.025,Math.min(8,proxy.source.hitSize/(2.0*Vars.tilesize)));
            double nativeRange=proxy.source.range/Vars.tilesize;
            range=Math.max(.05,Math.min(8192,nativeRange>0?nativeRange:proxy.source.speed*bullet.lifetime/Vars.tilesize));
        }
    }
}
