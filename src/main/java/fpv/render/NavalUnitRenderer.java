package fpv.render;

import arc.graphics.g3d.Camera3D;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.gen.WaterMovec;

/** Vanilla naval dispatch in the existing extrusion pass; no separate ship engine or physics. */
public final class NavalUnitRenderer implements AutoCloseable {
    private fpv.combat.NavalWeaponAim3D aim;
    private final MechWeaponSaddle saddles=new MechWeaponSaddle();
    public void setAim(fpv.combat.NavalWeaponAim3D value){aim=value;}
    private final double[] basis=new double[9];
    private long hullDraws,weaponDraws;
    private boolean enabled=true;
    public static boolean supports(Unit unit){return unit instanceof WaterMovec&&NavalHullProfile.supports(unit.type);}
    public NavalHullProfile profile(Unit unit){
        if(!supports(unit))return null;
        return NavalHullProfile.forType(unit.type);
    }
    public long hullDraws(){return hullDraws;}
    public long weaponDraws(){return weaponDraws;}
    void enabled(boolean value){enabled=value;}
    public boolean render(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,double halfHeight,double surface,
            Camera3D camera,float ambient,SpriteExtrusionRenderer extrusion){
        if(!enabled||!supports(unit))return false;
        var profile=profile(unit);var model=profile.body();
        boolean afloat=!unit.isFlying()&&unit.floorOn().isLiquid;
        float line=(float)profile.waterline(y,halfHeight,surface,afloat);
        if(!extrusion.renderNavalProfile(unit,x,line,z,yaw+profile.rotationOffset(),pitch,roll,camera,ambient,profile))return false;
        hullDraws++;
        SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);
        if(unit.mounts!=null)for(var mount:unit.mounts){
            var weapon=mount.weapon;if(weapon==null||weapon.shootOnDeath||!NativeAtlasMesh.valid(weapon.region))continue;
            double gunYaw=ProceduralUnitModelProfile.weaponYaw(yaw,weapon.rotate,mount.rotation,weapon.baseRotation);
            double recoil=Math.pow(mount.recoil,weapon.recoilPow)*weapon.recoil/Vars.tilesize;
            double right=weapon.x/Vars.tilesize+model.weaponRightOffset();
            double forward=weapon.y/Vars.tilesize+model.weaponForwardOffset();
            double lift=profile.mountLift(weapon.x/Vars.tilesize,weapon.y/Vars.tilesize,unit.type.region.width*unit.type.region.scl()/Vars.tilesize,unit.type.region.height*unit.type.region.scl()/Vars.tilesize);
            double up=model.weaponUpOffset()+lift+fpv.combat.WeaponHeightStagger.offset(unit,mount);
            // Same body basis/native rotating mount and recoil. Offset is presentation-only.
            float wx=(float)(x+basis[0]*right-basis[6]*forward+basis[3]*up);
            float wy=(float)(line+basis[1]*right-basis[7]*forward+basis[4]*up);
            float wz=(float)(z+basis[2]*right-basis[8]*forward+basis[5]*up);
            var aimed=aim==null?null:aim.visual(unit,mount,profile);
            float gunPitch=aimed==null?pitch:(float)aimed.pitch(),gunRoll=aimed==null?roll:0;
            double halfLength=weapon.region.height*weapon.region.scl()/Vars.tilesize*.5,rear=halfLength*.65;
            var articulation=NavalWeaponPose.of(gunYaw,gunPitch,halfLength,model.weaponThickness()*.5,profile.weaponGap(),rear,recoil);
            saddles.render(camera,ambient,wx-(float)(Math.cos(Math.toRadians(gunYaw))*rear),wy-model.weaponThickness()*.5+(float)(articulation.lift()*.5),
                wz+(float)(Math.sin(Math.toRadians(gunYaw))*rear),gunYaw,Math.max(.10,unit.hitSize/Vars.tilesize*.10),articulation.lift(),Math.max(.14,unit.hitSize/Vars.tilesize*.14));
            wx+=(float)articulation.x();wy+=(float)articulation.up();wz-=(float)articulation.y();
            if(extrusion.renderComponent(unit,weapon.region,wx,wy,wz,(float)gunYaw,gunPitch,gunRoll,camera,ambient,model.weaponThickness(),weapon.flipSprite,model.sideShade()))weaponDraws++;
        }
        return true;
    }
    public void close(){saddles.close();}
}
