package fpv.render;

import mindustry.world.blocks.defense.turrets.*;

/** Presentation dimensions only; never used for targeting, hitboxes or muzzle simulation. */
public record TurretModelProfile(float thicknessFactor,float minimumThickness,float maximumThickness) {
    public static final TurretModelProfile CONVENTIONAL=new TurretModelProfile(.105f,.08f,.42f);
    public static final TurretModelProfile ARTILLERY=new TurretModelProfile(.125f,.09f,.48f);
    public static final TurretModelProfile ENERGY=new TurretModelProfile(.09f,.07f,.36f);
    public static final TurretModelProfile LIQUID=new TurretModelProfile(.085f,.07f,.34f);
    public static TurretModelProfile forTurret(Turret turret){
        if(turret instanceof LiquidTurret)return LIQUID;
        if(turret instanceof PowerTurret||turret instanceof LaserTurret)return ENERGY;
        if(turret.name.equals("hail")||turret.name.equals("ripple"))return ARTILLERY;
        return CONVENTIONAL;
    }
    public float thickness(int size){return Math.max(minimumThickness,Math.min(maximumThickness,size*thicknessFactor));}
    /** Salvo's warmup/recoil panels must clear the sibling barrel's entire extruded slab.
     * Offset is along the assembly normal, so native planar pivots/animation survive every pitch/yaw. */
    public static float partLift(Turret type,mindustry.entities.part.DrawPart part,float thickness){
        float lift=part.under?-thickness*.38f:thickness*.52f;
        if(type.name.equals("salvo")&&part instanceof mindustry.entities.part.RegionPart region&&region.suffix.equals("-side"))
            lift+=thickness*.65f+.018f;
        return lift;
    }
}
