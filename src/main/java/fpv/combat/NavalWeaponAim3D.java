package fpv.combat;

import fpv.render.NavalHullProfile;
import fpv.render.NavalUnitRenderer;
import fpv.render.ProceduralUnitModelProfile;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import mindustry.Vars;
import mindustry.entities.bullet.ArtilleryBulletType;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.*;

/** Cosmetic naval traverse. Reads existing XYZ aim/ballistics; never moves a muzzle or changes a shot. */
public final class NavalWeaponAim3D {
    private final Combat3DSystem combat;
    private final World3DAdapter world;
    private final arc.struct.IntMap<MechWeaponPitchCodec.Entry> remote=new arc.struct.IntMap<>();
    public NavalWeaponAim3D(Combat3DSystem combat,World3DAdapter world){this.combat=combat;this.world=world;}
    public record Solution(double yaw,double pitch,WorldCoordinates.Point direction){}
    public Solution visual(Unit unit,WeaponMount mount,NavalHullProfile profile){
        if(!NavalUnitRenderer.supports(unit)||mount==null||mount.weapon==null||profile==null||!combat.coverage().active())return null;
        var pose=world.unitState(unit);if(pose==null)return null;
        var weapon=mount.weapon;
        double yaw=ProceduralUnitModelProfile.weaponYaw(unit.rotation,weapon.rotate,mount.rotation,weapon.baseRotation);
        Double replicated=Vars.net!=null&&Vars.net.client()&&(Vars.player==null||unit!=Vars.player.unit())?remotePitch(unit,mount):null;
        double pitch=replicated==null?pose.pitch():replicated;
        if(replicated==null){
            // Independent repair/point-defense mounts follow their own native target, even when piloted.
            var controlled=weapon.controllable?combat.controlDirection(unit):null;
            if(controlled!=null){
                if(finite(controlled)&&Math.hypot(Math.hypot(controlled.x(),controlled.y()),controlled.z())>1e-9)
                    pitch=elevation(controlled);
            }else{
                var source=combat.originalType(weapon.bullet);
                double mx=ProceduralUnitModelProfile.weaponX(unit.x/Vars.tilesize,unit.rotation,weapon.x/Vars.tilesize,weapon.y/Vars.tilesize);
                double my=ProceduralUnitModelProfile.weaponY(unit.y/Vars.tilesize,unit.rotation,weapon.x/Vars.tilesize,weapon.y/Vars.tilesize);
                double a=Math.toRadians(yaw);mx+=Math.cos(a)*weapon.shootY/Vars.tilesize+Math.sin(a)*weapon.shootX/Vars.tilesize;
                my+=Math.sin(a)*weapon.shootY/Vars.tilesize-Math.cos(a)*weapon.shootX/Vars.tilesize;
                double radius=source==null?.025:Math.max(.025,Math.min(8,source.hitSize/(2.0*Vars.tilesize)));
                double mz=Math.max(pose.z()+WeaponHeightStagger.offset(unit,mount),world.terrainHeight(mx,my)+radius+.025);
                WorldCoordinates.Point target=source==null?null:fpv.construction.LayerNavigationAdapter.aim(unit,source);
                if(target==null&&Combat3DSystem.usableTarget(mount.target)){
                    if(mount.target instanceof Unit other){
                        var body=world.unitState(other);
                        if(body!=null){
                            var lead=weapon.predictTarget&&source!=null?combat.unitRanges().prediction(unit,other,source,mx,my,mz):null;
                            target=lead==null?new WorldCoordinates.Point(body.x(),body.y(),body.z()):new WorldCoordinates.Point(lead.x(),lead.y(),lead.z());
                            if(!body.flying())target=new WorldCoordinates.Point(target.x(),target.y(),Math.max(target.z(),Math.min(world.terrainHeight(target.x(),target.y())+radius+.025,body.z()+body.halfHeight()+radius-1e-4)));
                        }
                    }else if(mount.target instanceof Building b){
                        double base=world.buildingBase(b),top=world.buildingTop(b);
                        target=new WorldCoordinates.Point(b.x/Vars.tilesize,b.y/Vars.tilesize,Math.max((base+top)*.5,Math.min(base+radius+.025,top+radius-1e-4)));
                    }else if(mount.target instanceof Bullet b){
                        var state=combat.projectileState(b);
                        if(state!=null)target=new WorldCoordinates.Point(state.x(),state.y(),state.z());
                    }
                }
                if(target==null&&mount.shoot&&Float.isFinite(mount.aimX)&&Float.isFinite(mount.aimY)&&mount.aimX>=0&&mount.aimY>=0)
                    target=new WorldCoordinates.Point(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize,world.terrainHeight(mount.aimX/Vars.tilesize,mount.aimY/Vars.tilesize)+.15);
                if(target!=null&&finite(target)){
                    double distance=Math.hypot(target.x()-mx,target.y()-my),dz=target.z()-mz;
                    var direction=CombatGeometry.aim(yaw,distance,dz);
                    if(source instanceof ArtilleryBulletType){
                        double life=source.lifetime/60.0;
                        if(source.scaleLife)life*=Math.max(0,Math.min(1,Math.hypot(mount.aimX/Vars.tilesize-mx,mount.aimY/Vars.tilesize-my)/Math.max(.001,source.range/Vars.tilesize)));
                        life=Math.min(120,Math.max(1.0/60,life)*Math.hypot(combat.unitRanges().horizontal(unit.type),combat.unitRanges().vertical(unit.type)));
                        direction=ProjectileDynamics.arc(yaw,distance,dz,life);
                    }
                    pitch=elevation(direction);
                }
            }
        }
        pitch=profile.clampPitch(pitch);
        double a=Math.toRadians(yaw),p=Math.toRadians(pitch),c=Math.cos(p);
        return new Solution(yaw,pitch,new WorldCoordinates.Point(Math.cos(a)*c,Math.sin(a)*c,Math.sin(p)));
    }
    private static boolean finite(WorldCoordinates.Point p){return Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z());}
    private static double elevation(WorldCoordinates.Point p){return Math.toDegrees(Math.atan2(p.z(),Math.hypot(p.x(),p.y())));}
    public void appendSnapshot(java.util.List<MechWeaponPitchCodec.Entry> entries){
        for(Unit unit:Groups.unit){
            if(entries.size()>=MechWeaponPitchCodec.MAX_UNITS)break;
            if(!NavalUnitRenderer.supports(unit)||unit.dead||unit.mounts==null||unit.mounts.length==0)continue;
            var profile=NavalHullProfile.forType(unit.type);float[] angles=new float[Math.min(MechWeaponPitchCodec.MAX_MOUNTS,unit.mounts.length)];
            for(int i=0;i<angles.length;i++){var solution=visual(unit,unit.mounts[i],profile);angles[i]=solution==null?0:(float)solution.pitch();}
            entries.add(new MechWeaponPitchCodec.Entry(unit.id,unit.type.id,unit.team.id,angles));
        }
    }
    public void applySnapshot(java.util.List<MechWeaponPitchCodec.Entry> entries){remote.clear();for(var entry:entries)remote.put(entry.unitId(),entry);}
    public Double remotePitch(Unit unit,WeaponMount mount){
        var entry=remote.get(unit.id);if(entry==null||entry.typeId()!=unit.type.id||entry.teamId()!=unit.team.id||unit.mounts==null)return null;
        for(int i=0;i<Math.min(entry.count(),unit.mounts.length);i++)if(unit.mounts[i]==mount)return (double)entry.pitch(i);
        return null;
    }
    public void clear(){remote.clear();}
}
