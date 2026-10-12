package fpv.combat;

import fpv.render.ProceduralUnitModelProfile;
import fpv.units.ground.GroundUnitProfiles;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import mindustry.Vars;
import mindustry.entities.bullet.ArtilleryBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.*;

/** One mount solution consumed by world guns, FPV viewmodels and authoritative projectiles. */
public final class MechWeaponAim3D {
    private final Combat3DSystem combat;
    private final World3DAdapter world;
    private final double[] basis=new double[9];
    private final arc.struct.IntMap<MechWeaponPitchCodec.Entry> remote=new arc.struct.IntMap<>();
    public void clear(){remote.clear();}
    public java.util.List<MechWeaponPitchCodec.Entry> snapshot(){
        var entries=new java.util.ArrayList<MechWeaponPitchCodec.Entry>();
        for(Unit unit:Groups.unit){
            if(entries.size()>=MechWeaponPitchCodec.MAX_UNITS)break;
            if(!supports(unit)||unit.dead||unit.mounts==null||unit.mounts.length==0)continue;
            float[] angles=new float[Math.min(MechWeaponPitchCodec.MAX_MOUNTS,unit.mounts.length)];
            for(int i=0;i<angles.length;i++){var pose=solve(unit,unit.mounts[i],null);angles[i]=pose==null?0:(float)pose.pitch();}
            entries.add(new MechWeaponPitchCodec.Entry(unit.id,unit.type.id,unit.team.id,angles));
        }
        return entries;
    }
    public void applySnapshot(java.util.List<MechWeaponPitchCodec.Entry> entries){
        remote.clear();for(var entry:entries)remote.put(entry.unitId(),entry);
    }
    public Double remotePitch(Unit unit,WeaponMount mount){
        var entry=remote.get(unit.id);if(entry==null||entry.typeId()!=unit.type.id||entry.teamId()!=unit.team.id)return null;
        for(int i=0;i<Math.min(entry.count(),unit.mounts.length);i++)if(unit.mounts[i]==mount)return (double)entry.pitch(i);
        return null;
    }
    public MechWeaponAim3D(Combat3DSystem combat,World3DAdapter world){this.combat=combat;this.world=world;}
    public fpv.world.Unit3DState origin(Unit unit){return world.unitState(unit);}
    public static boolean supports(Unit unit){return unit instanceof Mechc&&!unit.type.flying&&!GroundUnitProfiles.stanceCapable(unit);}
    /** Conservative render-only upper bound; steep barrels must not vanish with an off-screen hull. */
    public static double upperEnvelope(Unit unit){
        if(!supports(unit)||unit.mounts==null)return 0;double extra=0;
        for(var mount:unit.mounts)extra=Math.max(extra,2*MechWeaponPitch.halfLength(mount.weapon.name,unit.hitSize/Vars.tilesize,mount.weapon.shootY/Vars.tilesize));
        return extra;
    }
    public record Solution(double yaw,double pitch,double lift,double depth,WorldCoordinates.Point pivot,WorldCoordinates.Point center,
                           WorldCoordinates.Point muzzle,WorldCoordinates.Point direction,double launchSpeed){}
    /** Match the unique per-weapon proxy, not the shared vanilla bullet type (mirrored guns matter). */
    public WeaponMount emittingMount(Unit unit,Bullet bullet){
        if(!supports(unit)||unit.mounts==null)return null;
        for(var mount:unit.mounts)if(mount.weapon.bullet==bullet.type)return mount;
        return null;
    }
    public Solution visual(Unit unit,WeaponMount mount){
        if(!combat.coverage().active()||!supports(unit))return null;
        Double pitch=Vars.net!=null&&Vars.net.client()&&(Vars.player==null||unit!=Vars.player.unit())?remotePitch(unit,mount):null;
        return solve(unit,mount,null,pitch);
    }
    public Solution solve(Unit unit,WeaponMount mount,Bullet bullet){
        return solve(unit,mount,bullet,null);
    }
    private Solution solve(Unit unit,WeaponMount mount,Bullet bullet,Double replicatedPitch){
        if(!supports(unit)||mount==null)return null;
        var pose=world.unitState(unit);if(pose==null)return null;
        var weapon=mount.weapon;BulletType source=combat.originalType(weapon.bullet);
        if(source==null||weapon.shootOnDeath||source.killShooter)return null;
        var profile=ProceduralUnitModelProfile.aimingProfile(unit.type);
        double yaw=ProceduralUnitModelProfile.weaponYaw(unit.rotation,weapon.rotate,mount.rotation,weapon.baseRotation);
        double right=weapon.x/Vars.tilesize+(profile==null?0:profile.weaponRightOffset());
        double forward=weapon.y/Vars.tilesize+(profile==null?0:profile.weaponForwardOffset());
        double canonicalSupport=Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,
            unit.hitSize/(double)Vars.tilesize*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE));
        double modelSupport=Math.max(fpv.render.SpriteExtrusionRenderer.halfDepth(unit),canonicalSupport);
        double up=profile==null?canonicalSupport+.1:profile.weaponUpOffset()+modelSupport-canonicalSupport;
        up+=WeaponHeightStagger.offset(unit,mount);
        double depth=profile==null?.14:profile.weaponThickness(),gap=profile==null?.025:
            Math.max(0,profile.weaponUpOffset()-depth*.5-profile.topOffset(modelSupport,1,1,0,0));
        // Content metadata works identically on SDL clients and a server without an atlas.
        double halfLength=MechWeaponPitch.halfLength(weapon.name,unit.hitSize/Vars.tilesize,weapon.shootY/Vars.tilesize);
        double rearPivot=halfLength*.75;
        fpv.render.SpriteExtrusionGeometry.basis(unit.rotation,pose.pitch(),pose.roll(),basis);
        double px=pose.x()+basis[0]*right-basis[6]*forward+basis[3]*up;
        double py=pose.y()-basis[2]*right+basis[8]*forward-basis[5]*up,pz=pose.z()+basis[1]*right-basis[7]*forward+basis[4]*up;
        double side=weapon.shootX/Vars.tilesize,fore=weapon.shootY/Vars.tilesize;
        if(bullet!=null){
            // Recover random barrels/spread from the actual native spawn, before XYZ conversion.
            double mx=ProceduralUnitModelProfile.weaponX(unit.x/Vars.tilesize,unit.rotation,weapon.x/Vars.tilesize,weapon.y/Vars.tilesize);
            double my=ProceduralUnitModelProfile.weaponY(unit.y/Vars.tilesize,unit.rotation,weapon.x/Vars.tilesize,weapon.y/Vars.tilesize);
            double dx=bullet.x/Vars.tilesize-mx,dy=bullet.y/Vars.tilesize-my,a=Math.toRadians(yaw);
            side=dx*Math.sin(a)-dy*Math.cos(a);fore=dx*Math.cos(a)+dy*Math.sin(a);
        }
        var controlled=replicatedPitch==null?combat.controlDirection(unit):MechWeaponPitch.direction(yaw,replicatedPitch);
        if(bullet!=null&&!Float.isFinite(bullet.rotation()))return null;
        if(controlled!=null&&(!Double.isFinite(controlled.x()+controlled.y()+controlled.z())||Math.sqrt(controlled.x()*controlled.x()+controlled.y()*controlled.y()+controlled.z()*controlled.z())<1e-9))return null;
        WorldCoordinates.Point target=null;
        if(controlled==null){
            target=fpv.construction.LayerNavigationAdapter.aim(unit,source);
            Teamc nativeTarget=bullet==null?mount.target:bullet.aimX==-1&&bullet.aimY==-1?null:mount.target;
            if(!Combat3DSystem.usableTarget(nativeTarget))nativeTarget=null;
            if(bullet!=null&&nativeTarget==null&&(!Float.isFinite(bullet.aimX)||!Float.isFinite(bullet.aimY)))return null;
            if(target==null&&nativeTarget instanceof Unit other&&!other.dead&&other.isAdded()){
                var predicted=combat.unitRanges().prediction(unit,other,source,px,py,pz);
                if(predicted!=null){
                    var body=world.unitState(other);double height=predicted.z(),radius=Math.max(.025,Math.min(8,source.hitSize/(2.0*Vars.tilesize)));
                    if(body!=null&&!body.flying())height=Math.max(height,Math.min(world.terrainHeight(predicted.x(),predicted.y())+radius+.025,body.z()+body.halfHeight()+radius-1e-4));
                    target=new WorldCoordinates.Point(predicted.x(),predicted.y(),height);
                }
            }else if(target==null&&nativeTarget instanceof Building building&&!building.dead){
                target=new WorldCoordinates.Point(building.x/Vars.tilesize,building.y/Vars.tilesize,(world.buildingBase(building)+world.buildingTop(building))*.5);
            }
            if(target==null&&mount.shoot&&Float.isFinite(mount.aimX)&&Float.isFinite(mount.aimY)&&mount.aimX>=0&&mount.aimY>=0)
                target=new WorldCoordinates.Point(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize,world.terrainHeight(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize)+.15);
        }
        double life=bullet==null?source.lifetime/60.0:bullet.lifetime/60.0;
        if(bullet==null&&source.scaleLife){
            double nativeMuzzleX=ProceduralUnitModelProfile.weaponX(unit.x,unit.rotation,weapon.x,weapon.y);
            double nativeMuzzleY=ProceduralUnitModelProfile.weaponY(unit.y,unit.rotation,weapon.x,weapon.y);
            double a=Math.toRadians(yaw);nativeMuzzleX+=Math.cos(a)*weapon.shootY+Math.sin(a)*weapon.shootX;nativeMuzzleY+=Math.sin(a)*weapon.shootY-Math.cos(a)*weapon.shootX;
            life*=Math.max(0,Math.min(1,Math.hypot(mount.aimX-nativeMuzzleX,mount.aimY-nativeMuzzleY)/Math.max(.001,weapon.bullet.range)));
        }
        life=Math.min(120,Math.max(1.0/60,life)*Math.hypot(combat.unitRanges().horizontal(unit.type),combat.unitRanges().vertical(unit.type)));
        boolean artillery=controlled==null&&target!=null&&source instanceof ArtilleryBulletType;
        double pitch=controlled==null?(target==null?MechWeaponPitch.clamp(pose.pitch()):0):MechWeaponPitch.elevation(controlled),lift=0,speed=0;
        double a=Math.toRadians(yaw),hingeX=px-Math.cos(a)*rearPivot,hingeY=py-Math.sin(a)*rearPivot;
        WorldCoordinates.Point pivot=new WorldCoordinates.Point(hingeX,hingeY,pz),center=pivot,muzzle=pivot;
        // Fixed-point solve accounts for the pitched muzzle height without a second target rule.
        for(int i=0;i<(controlled==null&&target!=null?8:1);i++){
            lift=MechWeaponPitch.lift(pitch,halfLength,depth*.5,gap,rearPivot);
            pivot=new WorldCoordinates.Point(hingeX,hingeY,pz+lift);center=MechWeaponPitch.muzzle(pivot,yaw,pitch,0,rearPivot);
            muzzle=MechWeaponPitch.muzzle(center,yaw,pitch,side,fore);
            if(controlled==null&&target!=null){
                double distance=Math.hypot(target.x()-muzzle.x(),target.y()-muzzle.y()),dz=target.z()-muzzle.z();
                var launch=artillery?ProjectileDynamics.arc(yaw,distance,dz,life):CombatGeometry.aim(yaw,distance,dz);
                pitch=MechWeaponPitch.elevation(launch);speed=artillery?Math.sqrt(launch.x()*launch.x()+launch.y()*launch.y()+launch.z()*launch.z()):0;
            }
        }
        lift=MechWeaponPitch.lift(pitch,halfLength,depth*.5,gap,rearPivot);pivot=new WorldCoordinates.Point(hingeX,hingeY,pz+lift);
        center=MechWeaponPitch.muzzle(pivot,yaw,pitch,0,rearPivot);muzzle=MechWeaponPitch.muzzle(center,yaw,pitch,side,fore);
        double actualYaw=bullet==null?yaw:bullet.rotation();
        return new Solution(yaw,pitch,lift,depth,pivot,center,muzzle,MechWeaponPitch.direction(actualYaw,pitch),speed);
    }
}
