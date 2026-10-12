package fpv.physics;

import arc.Core;
import arc.struct.IntMap;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.flight.FlightSettings;
import fpv.generated.CrashConfig;
import fpv.world.World3DAdapter;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import java.util.function.DoubleBinaryOperator;

/** Continuous collision and deferred native damage. New entity queries use canonical Z-up XYZ. */
public final class CrashSystem {
    /** Optional shared server-pilot arbitration. Contact clamping always happens before these claims. */
    public interface ContactPolicy {
        boolean claimImpulse(Unit source,Unit target);
        boolean claimDamage(Unit source,Unit target,double cooldownSeconds);
    }
    private final DoubleBinaryOperator surface;
    private SweptCollision collision;
    private final RadialExplosion effects;
    private final double metersPerTile;
    private World3DAdapter world;
    private ContactPolicy contactPolicy;
    private RammingCollision bodies;
    private double radius=CrashConfig.RADIUS_TILES,halfHeight=CrashConfig.RADIUS_TILES;
    private double cooldown,controlledMass=.65;
    private Unit tracked;
    private int deaths;
    private Unit pendingUnit,targetUnit;
    private Building targetBuilding;
    private FlightController.State pendingState;
    private SweptCollision.Impact pendingImpact;
    private ImpactModel.Damage pendingDamage;
    private double pendingMass,pendingSpeed;
    private double pendingOwnCap,pendingOtherCap;
    private int pendingUnitId,pendingTargetId;
    private long feedbackSequence;
    private ImpactFeedback lastFeedback;
    public record ImpactFeedback(long sequence,String targetName,int targetId,int targetTeam,double speedMps,
                                 double attemptedDamage,float healthBefore,float healthAfter,float shieldBefore,float shieldAfter,
                                 boolean friendlyProtected,boolean killed){
        public float healthLoss(){return Math.max(0,healthBefore-Math.max(0,healthAfter));}
        public float shieldLoss(){return Math.max(0,Math.max(0,shieldBefore)-Math.max(0,shieldAfter));}
    }
    private final IntMap<TargetImpulses> targetImpulses=new IntMap<>();
    private SweptCollision.Impact lastImpact=SweptCollision.Impact.NONE;
    public CrashSystem(DoubleBinaryOperator surface,RadialExplosion effects,double metersPerTile){
        this.surface=surface;collision=new SweptCollision(surface,radius,halfHeight,CrashConfig.SWEEP_STEP_TILES);
        this.effects=effects;this.metersPerTile=metersPerTile;
    }
    public void setWorld(World3DAdapter value){world=value;bodies=value==null?null:new RammingCollision(value,this::predictTarget);targetImpulses.clear();}
    public void setControlledMass(double value){if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("Positive mass required");controlledMass=value;}
    public void setContactPolicy(ContactPolicy value){contactPolicy=value;}
    public void reset(){cooldown=0;tracked=null;clearPending();targetImpulses.clear();lastImpact=SweptCollision.Impact.NONE;lastFeedback=null;}
    public ImpactFeedback lastFeedback(){return lastFeedback;}
    public int impactDeaths(){return deaths;}
    public SweptCollision.Impact lastImpact(){return lastImpact;}
    public double colliderRadius(){return radius;}
    private void clearPending(){pendingUnit=null;pendingState=null;pendingImpact=null;targetUnit=null;targetBuilding=null;pendingDamage=null;}

    private void predictTarget(Unit unit,Unit3DState state,double elapsed,CollisionResponse.Motion output){
        TargetImpulses queued=targetImpulses.get(unit.id);
        if(queued!=null&&queued.unit==unit)queued.timeline.apply(elapsed,output);
    }

    private void queueImpulse(Unit unit,double time,CollisionResponse.Delta delta){
        if(!delta.changesTarget())return;
        TargetImpulses queued=targetImpulses.get(unit.id);
        if(queued==null||queued.unit!=unit){queued=new TargetImpulses(unit);targetImpulses.put(unit.id,queued);}
        queued.timeline.add(time,delta.targetX(),delta.targetY(),delta.targetZ());
    }

    private void applyPhysicalImpulses(){
        try{
            if(world==null)return;
            for(var entry:targetImpulses){
                TargetImpulses queued=entry.value;
                if(queued.unit.id!=entry.key||queued.unit.dead||!queued.unit.isAdded())continue;
                for(int i=0;i<queued.timeline.size();i++){
                    var kick=queued.timeline.kick(i);
                    world.addVelocity(queued.unit,kick.x(),kick.y(),kick.z());
                }
            }
        }finally{targetImpulses.clear();}
    }

    private static final class TargetImpulses {
        final Unit unit;
        final CollisionResponse.Timeline timeline=new CollisionResponse.Timeline();
        TargetImpulses(Unit unit){this.unit=unit;}
    }

    public SweptCollision.Impact resolve(Unit unit,FlightController.State state,double ox,double oy,double oz,double seconds,boolean allowDamage){
        return resolve(unit,state,ox,oy,oz,seconds,allowDamage,0);
    }
    public SweptCollision.Impact resolve(Unit unit,FlightController.State state,double ox,double oy,double oz,double seconds,boolean allowDamage,double elapsed){
        if(tracked!=unit){tracked=unit;cooldown=0;clearPending();targetImpulses.clear();}
        cooldown=Math.max(0,cooldown-seconds);
        if(world!=null&&unit!=null){
            var pose=world.unitState(unit);
            if(pose!=null){
                double r=clamp(pose.radius(),.22,CrashConfig.MAX_COLLIDER_RADIUS_TILES);
                double h=clamp(pose.halfHeight(),.12,CrashConfig.MAX_COLLIDER_HALF_HEIGHT);
                if(r!=radius||h!=halfHeight){radius=r;halfHeight=h;collision=new SweptCollision(surface,radius,halfHeight,CrashConfig.SWEEP_STEP_TILES);}
            }
        }
        RammingCollision.Contact body=bodies==null?null:bodies.first(unit,ox,-oz,oy,state.x,-state.z,state.y,
            state.vx,-state.vz,state.vy,radius,halfHeight,Math.max(0,seconds),Math.max(0,elapsed));
        if(body!=null){
            double t=Math.max(0,body.time()-1e-6);
            state.x=ox+(state.x-ox)*t;state.y=oy+(state.y-oy)*t;state.z=oz+(state.z-oz)*t;
        }
        var impact=collision.resolve(state,ox,oy,oz);
        Unit hitUnit=null;Building hitBuilding=null;
        if(!impact.hit()&&body!=null){
            hitUnit=body.unit();hitBuilding=body.building();
            impact=new SweptCollision.Impact(true,body.x(),body.z(),-body.y(),body.nx(),body.nz(),-body.ny(),body.closingSpeed());
            if(hitUnit!=null&&(contactPolicy==null||contactPolicy.claimImpulse(unit,hitUnit))){
                double otherMass=FlightProfile.baseMassKg(FlightSettings.metadata(hitUnit));
                var target=world.unitState(hitUnit);
                var response=CollisionResponse.inelastic(controlledMass,otherMass,body.nx(),body.ny(),body.nz(),
                    state.vx-body.targetVx(),-state.vz-body.targetVy(),state.vy-body.targetVz(),body.targetVz(),
                    target!=null&&target.flying(),World3DAdapter.MAX_HORIZONTAL_DELTA_VELOCITY,World3DAdapter.MAX_VERTICAL_DELTA_VELOCITY);
                state.vx+=response.ownX();state.vz-=response.ownY();state.vy+=response.ownZ();
                queueImpulse(hitUnit,Math.max(0,elapsed)+body.time()*Math.max(0,seconds),response);
            }else if(hitUnit==null){
                double dot=state.vx*body.nx()-state.vz*body.ny()+state.vy*body.nz();
                if(dot<0){state.vx-=dot*body.nx();state.vz+=dot*body.ny();state.vy-=dot*body.nz();}
            }
            state.x+=impact.nx()*.0001;state.y+=impact.ny()*.0001;state.z+=impact.nz()*.0001;
        }else if(impact.hit()&&world!=null){
            var tile=Vars.world.tile((int)Math.floor(impact.x()+.5),(int)Math.floor(-impact.z()+.5));
            if(tile!=null)hitBuilding=tile.build;
        }
        lastImpact=impact;
        if(!impact.hit()||!allowDamage||unit==null||unit.dead||cooldown>0)return impact;
        double otherMass=hitUnit==null?controlledMass:FlightProfile.baseMassKg(FlightSettings.metadata(hitUnit));
        double coefficient=CrashConfig.RAM_DAMAGE_COEFFICIENT*setting("fpv-ram-strength",100,0,300)/100.0;
        var trauma=ImpactModel.damage(controlledMass,otherMass,impact.normalSpeed()*metersPerTile,
            CrashConfig.SAFE_SPEED_MPS,coefficient,hitUnit==null);
        if(trauma.self()<=0)return impact;
        if(hitUnit!=null&&contactPolicy!=null&&!contactPolicy.claimDamage(unit,hitUnit,CrashConfig.DAMAGE_COOLDOWN_SECONDS))return impact;
        cooldown=CrashConfig.DAMAGE_COOLDOWN_SECONDS;
        pendingUnit=unit;pendingState=state;pendingImpact=impact;pendingDamage=trauma;
        pendingUnitId=unit.id;pendingTargetId=hitUnit!=null?hitUnit.id:hitBuilding!=null?hitBuilding.id:-1;
        pendingMass=controlledMass;pendingSpeed=impact.normalSpeed()*metersPerTile;
        pendingOwnCap=RamDamageSettings.cap(unit);pendingOtherCap=hitUnit==null?0:RamDamageSettings.cap(hitUnit);
        targetUnit=hitUnit;targetBuilding=hitBuilding;
        return impact;
    }

    /** Commit after UnitMotionBridge/native Unit.update has reached the resolved contact XY. */
    public boolean applyPending(){
        if(Vars.net.client()){clearPending();targetImpulses.clear();return false;}
        // Safe bumps and cooldown contacts still exchange momentum; damage is a separate policy.
        applyPhysicalImpulses();
        if(pendingUnit==null)return false;
        Unit unit=pendingUnit,other=targetUnit;Building building=targetBuilding;
        var impact=pendingImpact;var state=pendingState;var damage=pendingDamage;
        int sourceId=pendingUnitId,targetId=pendingTargetId;
        double mass=pendingMass,speed=pendingSpeed,ownCap=pendingOwnCap,otherCap=pendingOtherCap;clearPending();
        if(unit!=tracked||unit.id!=sourceId)return false;
        boolean sourceAlreadyDead=unit.dead;
        boolean validOther=other!=null&&other.id==targetId&&!other.dead&&other.isAdded();
        boolean validBuilding=building!=null&&building.id==targetId&&!building.dead&&building.tile.build==building;
        float beforeHealth=validOther?other.health:validBuilding?building.health:0,beforeShield=validOther?other.shield:0;
        var outgoing=new RamDamageLimit(ownCap);
        // The once-per-unordered-pair claimant settles both outgoing legs. Native armor applies
        // to both receivers, so swapping solver ownership cannot change either participant's loss.
        boolean enemyPair=other!=null&&other.id==targetId&&other.team!=unit.team;
        double incoming=enemyPair?new RamDamageLimit(otherCap).reserve(unit,damage.self(),RamDamageSettings.healthScale(unit)):damage.self();
        if(!sourceAlreadyDead)RamDamageSettings.damage(unit,(float)incoming,!enemyPair);
        // Friendly units/buildings remain obstacles, but team protection applies to outgoing damage.
        // Capture before source damage too: its native death payload may already destroy the target.
        if(validOther&&other.id==targetId){
            boolean friendly=other.team==unit.team;
            double applied=!friendly?outgoing.reserve(other,damage.other(),RamDamageSettings.healthScale(other)):0;
            if(!friendly&&!other.dead&&other.isAdded())RamDamageSettings.damage(other,(float)applied,false);
            lastFeedback=new ImpactFeedback(++feedbackSequence,other.type.localizedName,other.id,other.team.id,speed,applied,
                beforeHealth,other.health,beforeShield,other.shield,friendly,other.dead);
        }
        if(validBuilding&&building.id==targetId){
            boolean friendly=building.team==unit.team;
            double applied=!friendly?outgoing.reserve(building,damage.other(),RamDamageSettings.healthScale(building)):0;
            if(applied>0&&!friendly&&!building.dead&&building.tile.build==building)building.damage(unit.team,(float)applied);
            lastFeedback=new ImpactFeedback(++feedbackSequence,building.block.localizedName,building.id,building.team.id,speed,applied,
                beforeHealth,building.health,0,0,friendly,building.dead);
        }
        if(unit.dead&&!sourceAlreadyDead){
            deaths++;
            var magnitude=ImpactModel.blast(mass,unit.hitSize/(double)Vars.tilesize,speed,
                setting("fpv-blast-radius",(int)(CrashConfig.EXPLOSION_RADIUS_TILES*10),5,60)/10.0,
                setting("fpv-blast-damage",(int)CrashConfig.EXPLOSION_DAMAGE,0,100),
                CrashConfig.MAX_BLAST_RADIUS_TILES,CrashConfig.MAX_BLAST_DAMAGE);
            var spec=new RadialExplosion.Spec(magnitude.radius(),Math.min(magnitude.damage(),ownCap),magnitude.visualRadius(),magnitude.duration(),
                setting("fpv-blast-style",0,0,1)==0?RadialExplosion.Style.sparks:RadialExplosion.Style.pulse,
                Core.settings.getBool("fpv-blast-sound",true));
            effects.detonateXYZ(unit.team,impact.x(),-impact.z(),impact.y(),spec,new RadialExplosion.DamageLimiter(){
                public double unit(Unit victim,double proposed){return outgoing.reserve(victim,proposed,RamDamageSettings.healthScale(victim));}
                public double building(Building victim,double proposed){return outgoing.reserve(victim,proposed,RamDamageSettings.healthScale(victim));}
                public double elevated(fpv.construction.LayerComponent victim,double proposed){
                    double scale=victim.kind()==fpv.construction.LayerComponent.Kind.PLATFORM?1:Vars.state.rules.blockHealth(mindustry.game.Team.get(victim.team()));
                    return outgoing.reserve(Long.valueOf(victim.id()),proposed,Math.abs(scale)<.000001?0:scale);
                }
                public void damageUnit(Unit victim,float accepted){RamDamageSettings.damage(victim,accepted,false);}
            });
            if(lastFeedback!=null){
                var f=lastFeedback;
                if(validOther&&other.id==targetId)lastFeedback=new ImpactFeedback(f.sequence(),f.targetName(),f.targetId(),f.targetTeam(),f.speedMps(),f.attemptedDamage(),
                    f.healthBefore(),other.health,f.shieldBefore(),other.shield,f.friendlyProtected(),other.dead);
                else if(validBuilding&&building.id==targetId)lastFeedback=new ImpactFeedback(f.sequence(),f.targetName(),f.targetId(),f.targetTeam(),f.speedMps(),f.attemptedDamage(),
                    f.healthBefore(),building.health,0,0,f.friendlyProtected(),building.dead);
            }
            state.vx=state.vy=state.vz=0;
            return true;
        }
        return false;
    }
    private static int setting(String key,int fallback,int low,int high){return (int)clamp(Core.settings.getInt(key,fallback),low,high);}
    private static double clamp(double v,double low,double high){return Math.max(low,Math.min(high,v));}
}
