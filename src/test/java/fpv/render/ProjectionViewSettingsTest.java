package fpv.render;

/** Exact camera XYZ bounds and continuous fade; independent of any player's ground position. */
public final class ProjectionViewSettingsTest {
    public static void main(String[] args){
        double d=ProjectionViewSettings.distanceSquared(0,0,120,0,0,10,1,1,5);
        if(d!=105*105)throw new AssertionError("altitude culling");
        if(ProjectionViewSettings.distanceSquared(102,0,0,0,0,0,3,1,1)!=99*99)throw new AssertionError("bounds intersect distance sphere");
        if(ProjectionViewSettings.distanceSquared(0,0,0,0,0,0,1,1,1)!=0)throw new AssertionError("inside bounds");
        if(ProjectionViewSettings.opacity(80,80,100)!=1||ProjectionViewSettings.opacity(90,80,100)!=.5||ProjectionViewSettings.opacity(100,80,100)!=0)throw new AssertionError("smooth fade");
        System.out.println("ProjectionViewSettings: 6 assertions passed.");
    }
}
