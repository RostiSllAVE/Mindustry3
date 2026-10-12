package fpv.combat;

import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import mindustry.Vars;
import mindustry.world.blocks.defense.turrets.Turret;

/** Native barrel/lateral offsets rotated into pitch; construction base, pivot and muzzle stay distinct. */
public final class TurretMuzzle3D {
    private TurretMuzzle3D(){}
    public static WorldCoordinates.Point position(World3DAdapter world,Turret.TurretBuild turret,double nativeX,double nativeY,double radius,WorldCoordinates.Point direction){
        double x=turret.x/(double)Vars.tilesize,y=turret.y/(double)Vars.tilesize,yaw=Math.toRadians(Float.isFinite(turret.rotation)?turret.rotation:0);
        double c=Math.cos(yaw),s=Math.sin(yaw),dx=nativeX-x,dy=nativeY-y,forward=dx*c+dy*s,side=-dx*s+dy*c;
        double planar=Math.hypot(direction.x(),direction.y()),pivot=world.buildingTop(turret)+radius+.06;
        return new WorldCoordinates.Point(x+c*forward*planar-s*side,y+s*forward*planar+c*side,
            Math.max(world.buildingBase(turret)+radius+.06,pivot+forward*direction.z()));
    }
}
