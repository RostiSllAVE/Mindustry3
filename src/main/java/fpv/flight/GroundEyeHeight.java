package fpv.flight;

import arc.Core;
import fpv.render.ProceduralUnitModelProfile;
import fpv.render.SpriteExtrusionRenderer;
import fpv.units.ground.GroundUnitProfiles;
import mindustry.Vars;
import mindustry.gen.Legsc;
import mindustry.gen.Unit;

/** Presentation-only eye offset for ordinary ground units. No pose, velocity or aiming mutation. */
public final class GroundEyeHeight {
    public static final double RADIUS=.08,SKIN=.015;
    private double lift;
    private boolean active;
    public static boolean ordinary(Unit unit){
        if(unit==null||unit.type==null||unit.type.flying||unit.isFlying()||unit instanceof Legsc||unit instanceof mindustry.gen.BlockUnitc)return false;
        var stance=GroundUnitProfiles.profile(unit);return stance==null||!stance.legged()||GroundUnitProfiles.extensibleMech(unit);
    }
    public static String settingKey(Unit unit){return "fpv-ground-eye-"+unit.type.name;}
    public static double desired(Unit unit,double physicalHalfHeight){
        return desired(unit,physicalHalfHeight,0,0);
    }
    public static double desired(Unit unit,double physicalHalfHeight,double pitch,double roll){
        double size=unit.hitSize/(double)Vars.tilesize;
        var region=unit.type.region;
        double width=region==null?size:Math.max(region.width,region.height)*region.scl()/Vars.tilesize;
        int override=Core.settings==null?0:Math.max(0,Math.min(1200,Core.settings.getInt(settingKey(unit),0)));
        return desired(size,width,visualTop(unit,physicalHalfHeight,pitch,roll),override/100.0);
    }
    public static double minimum(Unit unit,double physicalHalfHeight,double pitch,double roll){
        return Math.max(0,visualTop(unit,physicalHalfHeight,pitch,roll))+RADIUS+SKIN;
    }
    private static double visualTop(Unit unit,double physicalHalfHeight,double pitch,double roll){
        double spriteHalf=SpriteExtrusionRenderer.halfDepth(unit),half=Math.max(physicalHalfHeight,spriteHalf);
        return Math.max(half,fpv.render.GroundFamilyRenderer.cameraTop(unit,pitch,roll));
    }
    /** Override is eye lift from canonical body center; always keep the eye above its own hull. */
    public static double desired(double size,double width,double top,double override){
        if(!Double.isFinite(size)||!Double.isFinite(width)||!Double.isFinite(top)||!Double.isFinite(override)
            ||size<0||width<0||override<0)throw new IllegalArgumentException("Finite ground-eye dimensions required");
        double minimum=Math.max(0,top)+RADIUS+SKIN;
        return Math.max(minimum,override>0?Math.min(12,override):Math.max(0,top)+.35+size*.4+width*.12);
    }
    /** Exponential offset transition is independent of framerate and simulation/build time scale. */
    public double advance(double target,double seconds){
        active=true;return ease(target,seconds);
    }
    /** Same size/roof limits without handoff animation when camera smoothing is disabled. */
    public double snap(double target){
        if(!Double.isFinite(target)||target<0)throw new IllegalArgumentException("Finite camera height required");
        active=true;return lift=target;
    }
    private double ease(double target,double seconds){
        if(!Double.isFinite(target)||target<0||!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Finite camera transition required");
        lift+=(target-lift)*-Math.expm1(-Math.min(.1,seconds)/.16);return lift;
    }
    public boolean active(){return active;}
    public double clearHull(double minimum){lift=Math.max(lift,minimum);return lift;}
    /** A ground-unit release eases back to the existing eye offset of the next unit. */
    public double release(double nativeLift,double seconds){
        if(!active)return nativeLift;
        double value=ease(nativeLift,seconds);
        if(Math.abs(value-nativeLift)<.0001){reset();return nativeLift;}return value;
    }
    /** Obstacles win immediately, even while smoothing a higher target. */
    public double constrain(double safe){lift=Math.max(0,Math.min(lift,safe));return lift;}
    public void reset(){lift=0;active=false;}
}
