package fpv.control;

import fpv.units.UnitAltitudeSystem;
import fpv.units.ground.GroundUnitProfiles;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.gen.Unit;

/** Local real support only: never searches another map tile or treats projected defense as a floor. */
public final class NativeBoostLanding {
    private NativeBoostLanding(){}
    public static double height(World3DAdapter world,UnitAltitudeSystem altitudes,Unit unit,double x,double y,double z,double radius,double half){
        if(world==null||unit==null||!Double.isFinite(x+y+z+radius+half)||radius<=0||half<=0)return Double.NaN;
        if(overlaps(world,x,y,z,radius,half))return Double.NaN;
        double clearance=0;
        var profile=GroundUnitProfiles.profile(unit);var stance=altitudes.groundFamily()==null?null:altitudes.groundFamily().stance(unit);
        if(profile!=null){
            var grounded=altitudes.groundFamily()==null?null:altitudes.groundFamily().pose(unit);
            clearance=grounded==null?profile.normalStance():Math.max(profile.minimumClearance(),Math.min(profile.maximumClearance(),grounded.clearance(half)));
            if(stance!=null)clearance=Math.min(clearance,stance.desired(profile));
        }
        double highest=Double.NEGATIVE_INFINITY;
        int extent=(int)Math.ceil(radius+.5);
        for(int oy=-extent;oy<=extent;oy++)for(int ox=-extent;ox<=extent;ox++){
            double dx=Math.max(0,Math.abs(ox)-.5),dy=Math.max(0,Math.abs(oy)-.5);
            if(dx*dx+dy*dy>radius*radius)continue;
            double px=x+ox,py=y+oy;var tile=Vars.world.tile((int)Math.floor(px+.5),(int)Math.floor(py+.5));
            if(tile==null)return Double.NaN;
            double support=Math.max(world.terrainHeight(px,py),world.surfaceHeight(px,py));
            if(support>z-half+.08)return Double.NaN; // underneath/inside a solid native volume
            if(world.construction()!=null)support=world.construction().supportBelow(px,py,z-half+.08,support);
            if(support<=world.terrainHeight(px,py)+.001&&tile.floor().isDeep()&&!unit.type.hovering)return Double.NaN;
            highest=Math.max(highest,support);
        }
        double target=highest+half+clearance+.035;
        // If the pilot descended below the saved standing height, continue to the minimum
        // reachable ground stance rather than declaring an airborne folded mech grounded.
        if(target>z+.02)target=highest+half+(profile==null?0:profile.minimumClearance())+.035;
        // A point overlap sweep uses the same physical collision bounds as flight. Projected walls
        // can obstruct the descent, but they can never contribute to the selected support height.
        var blocked=world.traceUnitBarrier(unit,x,y,z,x,y,target,radius,half);
        if(blocked!=null)return Double.NaN;
        if(overlaps(world,x,y,target,radius,half))return Double.NaN;
        if(world.construction()!=null&&world.construction().ceilingAbove(x,y,target-half,radius)<target+half+.025)return Double.NaN;
        return target;
    }
    private static boolean overlaps(World3DAdapter world,double x,double y,double z,double radius,double half){
        var queries=world.construction();if(queries==null||queries.grid()==null)return false;
        var body=new fpv.construction.LayerCoordinates.Bounds(x-radius,y-radius,z-half,x+radius,y+radius,z+half);
        for(var c:queries.grid().query(body))if(queries.solid(c)&&queries.grid().coordinates().bounds(c).overlaps(body))return true;
        return false;
    }
}
