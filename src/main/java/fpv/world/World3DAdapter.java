package fpv.world;

import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import java.util.function.DoubleBinaryOperator;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/** Shared physical world queries and bounded native impulse boundary. Public coordinates are gameplay XYZ. */
public final class World3DAdapter {
    @FunctionalInterface public interface UnitBarrier {
        fpv.physics.SweptBodies.Hit trace(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight);
    }
    public static final double MAX_HORIZONTAL_DELTA_VELOCITY=200;
    public static final double MAX_VERTICAL_DELTA_VELOCITY=30;
    private final TerrainElevation terrain;
    private final DoubleBinaryOperator renderSurface;
    private final UnitAltitudeSystem units;
    private final HeightRaycast.Surface corners=this::corner;
    private final HeightRaycast.Surface terrainCorners=this::terrainCorner;
    private UnitBarrier unitBarrier;
    private fpv.construction.LayerPhysicalQueries construction;
    public void setConstruction(fpv.construction.LayerPhysicalQueries value){construction=value;}
    public fpv.construction.LayerPhysicalQueries construction(){return construction;}
    public World3DAdapter(TerrainElevation terrain,DoubleBinaryOperator renderSurfaceHeight,UnitAltitudeSystem units){
        if(terrain==null||renderSurfaceHeight==null||units==null)throw new IllegalArgumentException("World adapters required");
        this.terrain=terrain;this.renderSurface=renderSurfaceHeight;this.units=units;
    }
    public double terrainHeight(double x,double y){return terrain.heightAt(x,-y);}
    public double maximumTerrainHeight(){return terrain.maxHeight();}
    public double surfaceHeight(double x,double y){return renderSurface.applyAsDouble(x,-y);}
    public double buildingBase(mindustry.gen.Building building){
        var b=construction==null?null:construction.buildingBounds(building);
        return b==null?terrainHeight(building.x/Vars.tilesize,building.y/Vars.tilesize):b.minZ();
    }
    public double buildingTop(mindustry.gen.Building building){
        var b=construction==null?null:construction.buildingBounds(building);
        return b==null?surfaceHeight(building.x/Vars.tilesize,building.y/Vars.tilesize):b.maxZ();
    }
    public Unit3DState unitState(Unit unit){return unit instanceof mindustry.gen.BlockUnitc?fpv.control.GroundedPilot.fixedPose(unit,this):units.unitState(unit);}
    public Unit3DState unit(Unit unit){return unitState(unit);}
    public boolean nativeBoostGrounded(Unit unit){return units.nativeBoostGrounded(unit);}
    public void setUnitBarrier(UnitBarrier value){unitBarrier=value;}
    /** Factory exit already swept every upper volume except its own enclosure. */
    public fpv.physics.SweptBodies.Hit traceExternalUnitBarrier(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){return unitBarrier==null?null:unitBarrier.trace(unit,ax,ay,az,bx,by,bz,radius,halfHeight);}
    public fpv.physics.SweptBodies.Hit traceUnitBarrier(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        var original=unitBarrier==null?null:unitBarrier.trace(unit,ax,ay,az,bx,by,bz,radius,halfHeight);
        var upper=construction==null?null:construction.sweepUnit(unit,ax,ay,az,bx,by,bz,radius,halfHeight);
        return upper!=null&&(original==null||upper.time()<original.time())?upper:original;
    }
    /** Apply delta-velocity, not force: preserve native horizontal momentum/bridge ownership. */
    public boolean addVelocity(Unit unit,double dvx,double dvy,double dvz){
        WorldCoordinates.requireFinite(dvx,dvy,dvz);
        if(unit==null||unit.dead||!unit.isAdded()||unit.type==null)return false;
        double horizontal=Math.hypot(dvx,dvy);
        if(horizontal>MAX_HORIZONTAL_DELTA_VELOCITY){dvx*=MAX_HORIZONTAL_DELTA_VELOCITY/horizontal;dvy*=MAX_HORIZONTAL_DELTA_VELOCITY/horizontal;}
        float mass=unit.mass();
        if(!Float.isFinite(mass)||mass<=0)return false;
        double ix=dvx*Vars.tilesize/60.0*mass,iy=dvy*Vars.tilesize/60.0*mass;
        if(!Double.isFinite(ix)||!Double.isFinite(iy)||Math.abs(ix)>Float.MAX_VALUE||Math.abs(iy)>Float.MAX_VALUE)return false;
        if(dvx!=0||dvy!=0)unit.impulse((float)ix,(float)iy);
        boolean vertical=dvz!=0&&units.addVerticalVelocity(unit,Math.max(-MAX_VERTICAL_DELTA_VELOCITY,Math.min(MAX_VERTICAL_DELTA_VELOCITY,dvz)));
        return dvx!=0||dvy!=0||vertical;
    }
    public static double distanceSquared(Unit3DState a,Unit3DState b){return a.distanceSquared(b);}
    public static double distance(Unit3DState a,Unit3DState b){return Math.sqrt(distanceSquared(a,b));}
    public HeightRaycast.Hit traceSegment(double ax,double ay,double az,double bx,double by,double bz,double radius){
        var original=HeightRaycast.trace(corners,ax,ay,az,bx,by,bz,radius);
        var upper=construction==null?HeightRaycast.Hit.NONE:construction.ray(ax,ay,az,bx,by,bz,radius);
        return upper.hit()&&(!original.hit()||upper.fraction()<original.fraction())?upper:original;
    }
    /** Terrain/environment-only trace; combat applies native team rules to placed structures separately. */
    public HeightRaycast.Hit traceTerrainSegment(double ax,double ay,double az,double bx,double by,double bz,double radius){
        return HeightRaycast.trace(terrainCorners,ax,ay,az,bx,by,bz,radius);
    }
    private double terrainCorner(int x,int y,int corner){
        Tile tile=Vars.world==null?null:Vars.world.tile(x,y);
        if(tile==null)return 0;
        if(terrain.naturalWall(tile))return terrain.tileCornerHeight(x,y,corner);
        return tile.build==null&&tile.block().solid?surfaceHeight(x,y):0;
    }
    public boolean lineOfSight(double ax,double ay,double az,double bx,double by,double bz){
        return !traceSegment(ax,ay,az,bx,by,bz,0).hit();
    }
    public boolean lineOfSight(Unit3DState a,Unit3DState b){return lineOfSight(a.x(),a.y(),a.z(),b.x(),b.y(),b.z());}
    public HeightRaycast.Hit traceProjectile(Projectile3DState projectile,double seconds){
        var end=projectile.advance(seconds);
        return traceSegment(projectile.x(),projectile.y(),projectile.z(),end.x(),end.y(),end.z(),projectile.radius());
    }
    private double corner(int x,int y,int corner){
        Tile tile=Vars.world==null?null:Vars.world.tile(x,y);
        if(tile==null)return 0;
        if(terrain.naturalWall(tile))return terrain.tileCornerHeight(x,y,corner);
        // Includes sleeping/disabled buildings because tile membership, not update membership, is truth.
        return tile.build!=null||tile.block().solid?surfaceHeight(x,y):0;
    }
}
