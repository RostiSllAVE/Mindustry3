package fpv.combat;

import arc.Core;
import arc.math.Angles;
import arc.struct.IntMap;
import arc.util.Time;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates.Point;
import mindustry.Vars;
import mindustry.entities.bullet.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import java.lang.reflect.Modifier;
import java.util.*;
import static fpv.combat.AircraftWeaponGeometry.*;

/** Shared aircraft/walker dual-mount geometry. One native weapon/event per mount. */
public final class AircraftWeapons {
    public record Solution(boolean bomber,int side,boolean allowed,double yaw,double pitch,Point pivot,Point muzzle,Point direction,Point right,Point up,double thickness,Point oppositePivot,Frame frame,double launchSpeed){}
    private static final class Pose {final Unit owner;final IdentityHashMap<WeaponMount,Joint> joints=new IdentityHashMap<>();double yaw=Double.NaN,pitch,roll;Frame frame;Pose(Unit u){owner=u;}}
    private static final class Joint {int side=1;double yaw;float tick=Float.NaN;Solution cached;double x,y,z,byaw,bpitch,broll,ayaw,apitch,jyaw;Profile profile;}
    private final IntMap<Pose> poses=new IntMap<>();
    private final IntMap<AircraftWeaponCodec.Entry> remote=new IntMap<>();
    private final ArrayList<AirWeapon> wrappers=new ArrayList<>();
    private final IdentityHashMap<Weapon,Profile> overrides=new IdentityHashMap<>();
    private final Combat3DSystem combat;private final World3DAdapter world;
    private boolean active;
    private AircraftWeaponCodec.Limits hostLimits;
    AircraftWeapons(Combat3DSystem combat,World3DAdapter world){this.combat=combat;this.world=world;}
    public static boolean owns(Weapon w){return w instanceof AircraftWeapons.AirWeapon;}
    public static boolean walker(UnitType t){return t==mindustry.content.UnitTypes.atrax||t==mindustry.content.UnitTypes.spiroct||t==mindustry.content.UnitTypes.arkyid||t==mindustry.content.UnitTypes.toxopid;}
    public static boolean dual(Weapon w){return owns(w)||w instanceof UnitWeaponRanges.RangedWeapon r&&r.dualMount;}
    public boolean supports(Unit u){return u!=null&&u.type!=null&&(u.type.flying||walker(u.type))&&!(u instanceof BlockUnitc)&&u.mounts!=null;}
    static boolean walkerEligible(UnitType type,Weapon w,BulletType b){return walker(type)&&b!=null&&!w.noAttack&&w.useAttackRange&&!w.shootOnDeath&&!b.killShooter&&!w.continuous&&!w.alwaysContinuous&&b.spawnUnit==null&&CombatBindings.supportedPattern(w.shoot)&&standard(w);}
    private static boolean bomber(Weapon w){return w instanceof AirWeapon a&&a.bomber;}
    private static float nativeCone(Weapon w){return w instanceof AirWeapon a?a.nativeCone:((UnitWeaponRanges.RangedWeapon)w).nativeCone;}
    private static Emission emission(Weapon w){return w instanceof AirWeapon a?a.emission:((UnitWeaponRanges.RangedWeapon)w).emission;}
    static final class Emission {float angle;double side,forward;boolean firing;}
    void advance(Unit unit,WeaponMount mount){solve(unit,mount,true,0,null);}
    boolean canFire(Unit unit,WeaponMount mount){var s=visual(unit,mount);return s!=null&&s.allowed();}
    public void override(Weapon weapon,Profile profile){overrides.put(weapon,Objects.requireNonNull(profile));}
    public AircraftWeaponCodec.Limits limits(){
        if(Vars.net!=null&&Vars.net.client()&&hostLimits!=null)return hostLimits;
        if(Core.settings==null)return AircraftWeaponCodec.Limits.DEFAULT;
        return new AircraftWeaponCodec.Limits(Math.max(5,Math.min(60,Core.settings.getInt("fpv-aircraft-fixed-pitch",35))),Math.max(5,Math.min(60,Core.settings.getInt("fpv-aircraft-gimbal-pitch",45))),Math.max(0,Math.min(15,Core.settings.getInt("fpv-aircraft-switch-degrees",5))));
    }
    public void applyLimits(AircraftWeaponCodec.Limits value){hostLimits=Objects.requireNonNull(value);}
    public Profile profile(Unit unit,WeaponMount mount){
        if(!supports(unit)||mount==null||!dual(mount.weapon))return null;
        Weapon a=mount.weapon;boolean bomb=bomber(a);
        var override=overrides.get(mount.weapon);if(override!=null)return override;
        var limits=limits();int dead=limits.hysteresis();
        double yaw=bomb?180:a.rotate?Math.min(90,a.rotationLimit*.5):walker(unit.type)?45:Math.min(12,Math.max(1,nativeCone(a)));
        double pitch=bomb?90:WeaponPitchLimits.expanded(a.rotate?limits.gimbalPitch():limits.fixedPitch());
        Profile p=a instanceof AirWeapon air?air.profile:((UnitWeaponRanges.RangedWeapon)a).profile;
        if(p==null||p.yawLimit()!=yaw||p.pitchLimit()!=pitch||p.hysteresis()!=dead)p=new Profile(bomb,yaw,pitch,dead);
        if(a instanceof AirWeapon air)air.profile=p;else ((UnitWeaponRanges.RangedWeapon)a).profile=p;
        return p;
    }
    void install(){
        if(!wrappers.isEmpty())return;
        for(UnitType type:Vars.content.units())if(type.flying&&type.weapons.size<=32){WeaponHeightStagger.register(type,combat::originalType);for(int i=0;i<type.weapons.size;i++){
            Weapon w=type.weapons.get(i);if(w instanceof AircraftWeapons.AirWeapon previous)w=previous.template;BulletType b=combat.originalType(w.bullet);
            if(b==null||w.noAttack||!w.useAttackRange||w.shootOnDeath||b.killShooter||w.continuous||w.alwaysContinuous||b.spawnUnit!=null||!CombatBindings.supportedPattern(w.shoot)||!standard(w))continue;
            if(!(b instanceof BasicBulletType||b instanceof LaserBulletType))continue;
            var wrapper=new AirWeapon(type,w,b);type.weapons.set(i,wrapper);wrappers.add(wrapper);
        }}
    }
    static boolean standard(Weapon w){
        for(Class<?> c=w.getClass();c!=Weapon.class&&c!=null;c=c.getSuperclass())for(var m:c.getDeclaredMethods())if(Set.of("update","shoot","bullet","bulletRotation","handleBullet").contains(m.getName()))return false;
        return true;
    }
    void apply(boolean enabled){active=enabled;for(var a:wrappers){a.bullet=enabled?a.physical:a.nativeBullet;a.physical.shootEffect=mindustry.content.Fx.none;a.physical.smokeEffect=mindustry.content.Fx.none;a.ejectEffect=enabled?mindustry.content.Fx.none:a.template.ejectEffect;}if(!enabled)clear();}
    public void clear(){poses.clear();remote.clear();hostLimits=null;}
    void prune(){var iterator=poses.entries();while(iterator.hasNext()){var e=iterator.next();if(!e.value.owner.isAdded()||e.value.owner.dead||e.key!=e.value.owner.id)iterator.remove();}}
    public int supportedWeapons(){return wrappers.size();}
    public fpv.world.Unit3DState body(Unit unit){return world.unitState(unit);}
    private Joint joint(Unit unit,WeaponMount mount){
        var p=poses.get(unit.id);if(p==null||p.owner!=unit){p=new Pose(unit);poses.put(unit.id,p);}return p.joints.computeIfAbsent(mount,m->{var j=new Joint();j.yaw=-mount.weapon.baseRotation;return j;});
    }
    private Frame frameFor(Unit unit,fpv.world.Unit3DState body){
        var p=poses.get(unit.id);if(p==null||p.owner!=unit){p=new Pose(unit);poses.put(unit.id,p);}
        if(p.yaw!=body.yaw()||p.pitch!=body.pitch()||p.roll!=body.roll()){p.frame=frame(body.yaw(),body.pitch(),body.roll());p.yaw=body.yaw();p.pitch=body.pitch();p.roll=body.roll();}return p.frame;
    }
    private Point target(Unit unit,WeaponMount mount,boolean bomber,Point origin){
        var obstacle=fpv.construction.LayerNavigationAdapter.aim(unit,combat.originalType(mount.weapon.bullet));if(walker(unit.type)&&obstacle!=null)return obstacle;
        Teamc target=mount.target;
        if(Combat3DSystem.usableTarget(target)){
            if(target instanceof Unit u){
                var state=world.unitState(u);if(state==null)return null;
                var source=combat.originalType(mount.weapon.bullet);
                if(!bomber&&mount.weapon.predictTarget&&source!=null){var lead=combat.unitRanges().prediction(unit,u,source,origin.x(),origin.y(),origin.z());if(lead!=null)return new Point(lead.x(),lead.y(),lead.z());}
                return new Point(state.x(),state.y(),state.z());
            }
            if(target instanceof Building b)return new Point(b.x/Vars.tilesize,b.y/Vars.tilesize,(world.buildingBase(b)+world.buildingTop(b))*.5);
        }
        if(mount.shoot&&Float.isFinite(mount.aimX)&&Float.isFinite(mount.aimY)&&mount.aimX>=0&&mount.aimY>=0)return new Point(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize,world.terrainHeight(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize)+.15);
        return null;
    }
    public Solution visual(Unit unit,WeaponMount mount){return solve(unit,mount,false,0,null);}
    private Solution solve(Unit unit,WeaponMount mount,boolean advance,double spread,Bullet bullet){
        var profile=profile(unit,mount);if(profile==null||!active)return null;var body=world.unitState(unit);if(body==null)return null;
        var frame=frameFor(unit,body);var center=new Point(body.x(),body.y(),body.z());var j=joint(unit,mount);
        Point control=mount.weapon.controllable?combat.controlDirection(unit):null,end=control==null?target(unit,mount,profile.bomber(),center):null;
        var w=mount.weapon;double size=unit.hitSize/(double)Vars.tilesize;
        double thick=Math.max(.09,Math.min(.4,size*.07)),half=walker(unit.type)?body.halfHeight():Math.max(.15,Math.min(.75,size*.1)),length=Math.max(.25,Math.abs(w.shootY)/Vars.tilesize);
        if(walker(unit.type))length=Math.max(length,MechWeaponPitch.halfLength(w.name,size,w.shootY/Vars.tilesize));
        var source=combat.originalType(w.bullet);boolean artillery=walker(unit.type)&&control==null&&end!=null&&source instanceof ArtilleryBulletType;
        double life=source==null?1:source.lifetime/60.0;
        if(artillery){
            if(bullet!=null)life=bullet.lifetime/60.0;
            else if(source.scaleLife){double a=Math.toRadians(unit.rotation+(w.rotate?mount.rotation:w.baseRotation));double b=Math.toRadians(unit.rotation-90);double nx=unit.x+Math.cos(b)*w.x-Math.sin(b)*w.y+Math.cos(a)*w.shootY+Math.sin(a)*w.shootX,ny=unit.y+Math.sin(b)*w.x+Math.cos(b)*w.y+Math.sin(a)*w.shootY-Math.cos(a)*w.shootX;life*=Math.max(0,Math.min(1,Math.hypot(mount.aimX-nx,mount.aimY-ny)/Math.max(.001,w.bullet.range)));}
            life=Math.min(120,Math.max(1.0/60,life)*Math.hypot(combat.unitRanges().horizontal(unit.type),combat.unitRanges().vertical(unit.type)));
        }
        double ballisticSpeed=source==null?1:Math.max(.01,source.speed*60.0/Vars.tilesize);
        Point launch=end==null?null:trajectory(center,end,artillery,life,ballisticSpeed);
        Point local=frame.local(control!=null?control:launch==null?frame.forward():launch);
        double bodyAimYaw=yaw(frame.local(control!=null?control:end==null?frame.forward():difference(end,center)));
        if(control!=null){var bodyLocal=frame.local(control);if(Math.hypot(bodyLocal.x(),bodyLocal.z())<1e-9)bodyAimYaw=-w.baseRotation;}
        int side=profile.bomber()?-1:side(pitch(local),j.side,profile.hysteresis());
        double stagger=WeaponHeightStagger.offset(unit,mount);
        // AI aims from the physical hinge, not from the fuselage centre. The muzzle lies on
        // that ray (apart from native shootX), so wing mounts converge on the same XYZ target.
        if(end!=null&&!profile.bomber())for(int i=0;i<(artillery?8:4);i++){
            double p=Math.max(-profile.pitchLimit(),Math.min(profile.pitchLimit(),pitch(local)));
            double lift=half+thick*.5+.04+stagger+length*Math.abs(Math.sin(Math.toRadians(p)));
            Point hinge=add(center,frame.world(w.x/Vars.tilesize,side*lift,w.y/Vars.tilesize));
            Point barrelRight=direction(frame,yaw(local)+90,0);
            Point origin=add(hinge,scale(barrelRight,w.shootX/Vars.tilesize));
            if(artillery)origin=add(origin,scale(direction(frame,yaw(local),p),Math.max(.15,w.shootY/Vars.tilesize)));
            launch=trajectory(origin,end,artillery,life,ballisticSpeed);local=frame.local(launch);
        }
        double desiredYaw=Math.hypot(local.x(),local.z())<1e-9?(w.rotate?j.yaw:-w.baseRotation):yaw(local),desiredPitch=pitch(local);
        double baseYaw=-w.baseRotation;
        double sectorPitch=control!=null&&!profile.bomber()?Math.max(-profile.pitchLimit(),Math.min(profile.pitchLimit(),desiredPitch)):desiredPitch;
        boolean allowed=Math.hypot(Math.hypot(local.x(),local.y()),local.z())>1e-9&&allowed(profile,angleDifference(desiredYaw,baseYaw),sectorPitch);
        // Native fixed guns converge from an offset barrel while the BODY must face its
        // target. Keep that original cone; it is not a hinge traverse limit at close range.
        if(walker(unit.type)&&!w.rotate&&Math.abs(angleDifference(bodyAimYaw,baseYaw))>nativeCone(w)+1e-6)allowed=false;
        if(advance&&j.tick!=Time.time){j.tick=Time.time;j.yaw=mount.weapon.rotate?Angles.moveToward((float)j.yaw,(float)desiredYaw,mount.weapon.rotateSpeed*Time.delta):desiredYaw;}
        double gunYaw=mount.weapon.rotate?j.yaw:desiredYaw;
        double gunPitch=profile.bomber()?-90:Math.max(-profile.pitchLimit(),Math.min(profile.pitchLimit(),desiredPitch));
        gunYaw=angleDifference(baseYaw+Math.max(-profile.yawLimit(),Math.min(profile.yawLimit(),angleDifference(gunYaw,baseYaw))),0);
        if(!profile.bomber()&&mount.weapon.rotate&&Angles.angleDist((float)gunYaw,(float)desiredYaw)>Math.max(1,nativeCone(mount.weapon)))allowed=false;
        if(walker(unit.type)&&mount.target instanceof Unit target){var b=combat.originalType(w.bullet);if(!target.checkTarget(b.collidesAir&&unit.type.targetAir,b.collidesGround&&unit.type.targetGround))allowed=false;}
        if(Vars.net.client()&&(Vars.player==null||unit!=Vars.player.unit())){
            var e=remote.get(unit.id);int index=index(unit,mount);
            if(e!=null&&e.type()==unit.type.id&&e.team()==unit.team.id&&index>=0&&index<e.count()){gunYaw=e.yaw(index);gunPitch=e.pitch(index);side=e.side(index);}
        }else j.side=side;
        if(bullet==null&&j.cached!=null&&j.profile==profile&&j.x==body.x()&&j.y==body.y()&&j.z==body.z()&&j.byaw==body.yaw()&&j.bpitch==body.pitch()&&j.broll==body.roll()&&j.ayaw==desiredYaw&&j.apitch==desiredPitch&&j.jyaw==gunYaw&&j.cached.side()==side&&j.cached.pitch()==gunPitch&&j.cached.allowed()==allowed&&(!allowed||clearMuzzle(center,j.cached.muzzle())))return j.cached;
        double lift=half+thick*.5+.04+stagger+(profile.bomber()?0:length*Math.abs(Math.sin(Math.toRadians(gunPitch))));
        Point pivot=add(center,frame.world(w.x/Vars.tilesize,side*lift,w.y/Vars.tilesize));
        Point dir=profile.bomber()?frame.forward():direction(frame,gunYaw,gunPitch);
        Point right=profile.bomber()?frame.right():direction(frame,gunYaw+90,0);
        Point up=profile.bomber()?scale(frame.up(),-1):cross(right,dir);
        var nativeMount=emission(w);
        double muzzleRight=bullet==null?w.shootX/Vars.tilesize:nativeMount.side,muzzleForward=bullet==null?Math.max(.15,w.shootY/Vars.tilesize):nativeMount.forward;
        Point muzzle=profile.bomber()?add(pivot,scale(frame.up(),-.1)):add(pivot,add(scale(right,muzzleRight),scale(dir,muzzleForward)));
        if(bullet!=null){
            if(profile.bomber())dir=direction(frame,-(((bullet.rotation()-unit.rotation+540)%360)-180),0);
            else dir=direction(frame,gunYaw-spread,gunPitch);
        }
        if(intersectsBody(frame.local(difference(muzzle,center)),frame.local(dir),body.radius(),half))allowed=false;
        if(allowed&&!clearMuzzle(center,muzzle))allowed=false;
        var result=new Solution(profile.bomber(),side,allowed,gunYaw,gunPitch,pivot,muzzle,dir,right,up,thick,add(center,frame.world(w.x/Vars.tilesize,-side*lift,w.y/Vars.tilesize)),frame,artillery?Math.sqrt(dot(launch,launch)):0);
        if(bullet==null){j.cached=result;j.profile=profile;j.x=body.x();j.y=body.y();j.z=body.z();j.byaw=body.yaw();j.bpitch=body.pitch();j.broll=body.roll();j.ayaw=desiredYaw;j.apitch=desiredPitch;j.jyaw=gunYaw;}return result;
    }
    private static Point trajectory(Point origin,Point end,boolean artillery,double life,double speed){var d=difference(end,origin);return artillery?ProjectileDynamics.arc(Math.toDegrees(Math.atan2(d.y(),d.x())),Math.hypot(d.x(),d.y()),d.z(),Math.min(life,Math.sqrt(dot(d,d))/speed)):d;}
    private static Point cross(Point a,Point b){return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private boolean clearMuzzle(Point body,Point muzzle){return world.lineOfSight(body.x(),body.y(),body.z(),muzzle.x(),muzzle.y(),muzzle.z());}
    private static double angleDifference(double angle,double center){return ((angle-center)%360+540)%360-180;}
    private static int index(Unit u,WeaponMount m){for(int i=0;i<u.mounts.length;i++)if(u.mounts[i]==m)return i;return -1;}
    static void capture(Unit unit,WeaponMount mount,float x,float y,float nativeAngle){
        Weapon w=mount.weapon;Emission e=emission(w);double bodyAngle=Math.toRadians(unit.rotation-90),gunAngle=Math.toRadians(unit.rotation+(w.rotate?mount.rotation:w.baseRotation));
        double mx=unit.x+Math.cos(bodyAngle)*w.x-Math.sin(bodyAngle)*w.y,my=unit.y+Math.sin(bodyAngle)*w.x+Math.cos(bodyAngle)*w.y;
        double dx=(x-mx)/Vars.tilesize,dy=(y-my)/Vars.tilesize;e.side=dx*Math.sin(gunAngle)-dy*Math.cos(gunAngle);e.forward=Math.max(.15,dx*Math.cos(gunAngle)+dy*Math.sin(gunAngle));e.angle=nativeAngle;
    }
    Solution emitting(Unit unit,Bullet bullet){
        for(var m:unit.mounts)if(dual(m.weapon)&&emission(m.weapon).firing&&m.weapon.bullet==bullet.type){
            double spread=((bullet.rotation()-emission(m.weapon).angle+540)%360)-180;return solve(unit,m,false,spread,bullet);
        }return null;
    }
    public List<AircraftWeaponCodec.Entry> snapshot(){
        var list=new ArrayList<AircraftWeaponCodec.Entry>();
        for(Unit u:Groups.unit)if(supports(u)&&!u.dead&&u.isAdded()&&list.size()<AircraftWeaponCodec.MAX_UNITS){
            int n=Math.min(32,u.mounts.length);float[] ys=new float[n],ps=new float[n];byte[] ss=new byte[n];boolean any=false;
            for(int i=0;i<n;i++){var s=visual(u,u.mounts[i]);if(s!=null){any=true;ys[i]=(float)s.yaw();ps[i]=(float)s.pitch();ss[i]=(byte)s.side();}}
            if(any)list.add(new AircraftWeaponCodec.Entry(u.id,u.type.id,u.team.id,ys,ps,ss));
        }return list;
    }
    public void applySnapshot(List<AircraftWeaponCodec.Entry> entries){remote.clear();for(var e:entries)remote.put(e.id(),e);}
    final class AirWeapon extends Weapon {
        final BulletType nativeBullet,physical;final Weapon template;final boolean bomber;final float nativeCone;float nativeAngle,blockedReload;double nativeSide,nativeForward;boolean blocked,firing;Profile profile;final Emission emission=new Emission();
        AirWeapon(UnitType type,Weapon original,BulletType b){
            try{for(var f:Weapon.class.getFields())if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isFinal(f.getModifiers()))f.set(this,f.get(original));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            template=original;nativeBullet=b;physical=combat.unitProxy(b);nativeCone=original.shootCone;bomber=b instanceof BombBulletType||type.autoDropBombs&&b.speed==0&&b.splashDamage>0;
        }
        @Override public void update(Unit unit,WeaponMount mount){
            if(!active){super.update(unit,mount);return;}
            solve(unit,mount,true,0,null);blocked=false;float cone=shootCone;shootCone=360;
            try{super.update(unit,mount);if(blocked)mount.reload=blockedReload;}finally{shootCone=cone;}
        }
        @Override protected void shoot(Unit unit,WeaponMount mount,float x,float y,float rotation){
            if(active){var s=solve(unit,mount,false,0,null);if(s==null||!s.allowed()){blocked=true;blockedReload=mount.reload;return;}}
            super.shoot(unit,mount,x,y,rotation);
        }
        @Override protected void bullet(Unit unit,WeaponMount mount,float x,float y,float angle,mindustry.entities.Mover mover){
            if(active){var s=solve(unit,mount,false,0,null);if(s==null||!s.allowed())return;}
            firing=emission.firing=true;try{super.bullet(unit,mount,x,y,angle,mover);}finally{firing=emission.firing=false;}
        }
        @Override protected float bulletRotation(Unit unit,WeaponMount mount,float x,float y){
            nativeAngle=super.bulletRotation(unit,mount,x,y);capture(unit,mount,x,y,nativeAngle);nativeSide=emission.side;nativeForward=emission.forward;return nativeAngle;
        }
    }
}
