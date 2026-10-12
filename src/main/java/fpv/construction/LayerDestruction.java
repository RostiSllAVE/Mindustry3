package fpv.construction;

import fpv.effects.RadialExplosion;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGenerator;

/** Snapshot only: the host drains queued blasts after its current building update completes. */
public final class LayerDestruction {
    public record Blast(double x,double y,double z,RadialExplosion.Spec spec){}
    private LayerDestruction(){}
    public static Blast generator(Building building,double altitude){
        if(building==null||!(building.block instanceof PowerGenerator type)||!(building instanceof PowerGenerator.GeneratorBuild generator)||
            Vars.state==null||!Vars.state.rules.reactorExplosions||!Double.isFinite(altitude)||!generator.shouldExplode()||type.explosionRadius<=0||type.explosionDamage<=0)return null;
        double radius=Math.min(40,type.explosionRadius),damage=Math.min(12000,type.explosionDamage);
        return new Blast(building.x/(double)Vars.tilesize,building.y/(double)Vars.tilesize,altitude,
            new RadialExplosion.Spec(radius,damage,Math.min(radius,12),1.25,RadialExplosion.Style.pulse,true));
    }
}
