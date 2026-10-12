package fpv.physics;

import fpv.generated.CrashConfig;

/** Pure bounded gameplay impact energy. A mass proxy is not a claim about real unit kilograms. */
public final class ImpactModel {
    private ImpactModel() {}
    public record Damage(double self,double other,double energy) {}
    public record Blast(double radius,double damage,double visualRadius,double duration) {}

    public static Damage damage(double ownMass,double otherMass,double closingMetersPerSecond,
                                double safeSpeed,double coefficient,boolean immovable){
        finite(ownMass,otherMass,closingMetersPerSecond,safeSpeed,coefficient);
        double a=clamp(ownMass,.05,5000),b=clamp(otherMass,.05,5000);
        double speed=clamp(closingMetersPerSecond-safeSpeed,0,800);
        double reduced=immovable?a:a*b/(a+b);
        double energy=.5*reduced*speed*speed;
        double trauma=clamp(energy*clamp(coefficient,0,20)
                * heavyMultiplier(reduced,CrashConfig.HEAVY_TRAUMA_EXPONENT,
                    CrashConfig.HEAVY_MAX_TRAUMA_MULTIPLIER),0,200000);
        return new Damage(trauma,trauma,energy);
    }

    public static Blast blast(double mass,double hitSizeTiles,double closingMetersPerSecond,
                              double baseRadius,double baseDamage,double maxRadius,double maxDamage){
        finite(mass,hitSizeTiles,closingMetersPerSecond,baseRadius,baseDamage,maxRadius,maxDamage);
        double massScale=clamp(mass/.65,.15,1000);
        double sizeScale=clamp(hitSizeTiles/1.1,.4,10);
        double speedScale=clamp(closingMetersPerSecond/35,.5,2.5);
        double scale=Math.pow(massScale,.23)*Math.pow(sizeScale,.12);
        double radius=clamp(baseRadius*scale*Math.sqrt(speedScale)
                * heavyMultiplier(mass,CrashConfig.HEAVY_BLAST_RADIUS_EXPONENT,
                    CrashConfig.HEAVY_MAX_BLAST_RADIUS_MULTIPLIER),.2,clamp(maxRadius,.2,40));
        double damage=clamp(baseDamage*Math.pow(massScale,.72)*speedScale*speedScale
                * heavyMultiplier(mass,CrashConfig.HEAVY_BLAST_DAMAGE_EXPONENT,
                    CrashConfig.HEAVY_MAX_BLAST_DAMAGE_MULTIPLIER),0,clamp(maxDamage,0,12000));
        return new Blast(radius,damage,clamp(radius*.56,.2,22),clamp(.5+radius*.06,.5,2.5));
    }
    private static double heavyMultiplier(double mass,double exponent,double maximum){
        return Math.min(maximum,Math.pow(Math.max(1,mass/CrashConfig.HEAVY_IMPACT_MASS_KG),exponent));
    }
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static void finite(double... values){for(double v:values)if(!Double.isFinite(v))throw new IllegalArgumentException("Finite impact parameters required");}
}
