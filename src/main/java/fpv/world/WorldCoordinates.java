package fpv.world;

/** Canonical gameplay XYZ uses native horizontal X/Y in tiles and altitude Z. */
public final class WorldCoordinates {
    private WorldCoordinates() {}
    public record Point(double x,double y,double z) {
        public Point { requireFinite(x,y,z); }
        public double distanceSquared(Point other) { return WorldCoordinates.distanceSquared(x,y,z,other.x,other.y,other.z); }
    }
    public static Point toRender(double x,double y,double z) { return new Point(x,z,-y); }
    public static Point fromRender(double renderX,double renderY,double renderZ) { return new Point(renderX,-renderZ,renderY); }
    public static double distanceSquared(double ax,double ay,double az,double bx,double by,double bz) {
        double x=ax-bx,y=ay-by,z=az-bz;return x*x+y*y+z*z;
    }
    public static void requireFinite(double x,double y,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Finite XYZ required");
    }
}
