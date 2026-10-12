package fpv.defense;

import arc.Core;
import fpv.generated.DefenseConfig;
import fpv.generated.UnitsConfig;
import fpv.units.GroundSurfaceAdapter;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.gen.Crawlc;
import mindustry.gen.Legsc;
import mindustry.gen.Unit;
import java.util.function.DoubleSupplier;

/** Per-unit physical base support, without changing native leg pathfinding or any UnitType. */
public final class LegTraversal implements UnitAltitudeSystem.GroundSupport {
    private final World3DAdapter world;
    private final GroundSurfaceAdapter ordinary,legs;
    private final DoubleSupplier scale;
    private double stepHeight;
    public LegTraversal(World3DAdapter world){this(world,LegTraversal::configuredScale);}
    public LegTraversal(World3DAdapter world,DoubleSupplier scale){
        this.world=world;
        this.scale=scale;
        ordinary=new GroundSurfaceAdapter(world::terrainHeight,UnitsConfig.GROUND_SLOPE_SAMPLE_TILES,
            UnitsConfig.GROUND_MAX_SLOPE_DEGREES,UnitsConfig.GROUND_SUPPORT_STEP_TILES);
        legs=new GroundSurfaceAdapter(this::support,UnitsConfig.GROUND_SLOPE_SAMPLE_TILES,
            UnitsConfig.GROUND_MAX_SLOPE_DEGREES,DefenseConfig.MAX_LEG_STEP_HEIGHT);
    }
    public static boolean capable(Unit unit){return unit!=null&&unit.type!=null&&unit.type.allowLegStep&&
        (unit instanceof Legsc||unit instanceof Crawlc)&&!unit.isFlying();}
    public static double stepHeight(Unit unit){
        return stepHeight(unit,configuredScale());
    }
    private static double configuredScale(){
        return Core.settings==null?DefenseConfig.LEG_STEP_HEIGHT_MULTIPLIER:
            Math.max(50,Math.min(300,Core.settings.getInt("fpv-leg-step-percent",(int)Math.round(DefenseConfig.LEG_STEP_HEIGHT_MULTIPLIER*100))))/100.0;
    }
    private static double stepHeight(Unit unit,double scale){
        if(!capable(unit))return 0;
        return Math.max(0,Math.min(DefenseConfig.MAX_LEG_STEP_HEIGHT,unit.type.legLength*unit.type.legLengthScl/Vars.tilesize*scale));
    }
    @Override public GroundSurfaceAdapter.Contact contact(Unit unit,double x,double y,double yaw,double radius,double halfDepth){
        stepHeight=stepHeight(unit,scale.getAsDouble());
        return (stepHeight>0?legs:ordinary).contact(x,y,yaw,radius,halfDepth);
    }
    private double support(double x,double y){
        double floor=world.terrainHeight(x,y);
        var tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));
        if(tile==null||!ProjectedWallSystem.eligible(tile.build)||(!tile.block().solid&&!tile.build.checkSolid()))return floor;
        double top=world.surfaceHeight(x,y);
        return top-floor<=stepHeight+1e-6?Math.max(floor,top):floor;
    }
}
