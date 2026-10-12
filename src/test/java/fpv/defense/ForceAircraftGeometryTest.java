package fpv.defense;

/** Independent body extents and swept side/cap normals for aircraft-only collision. */
public final class ForceAircraftGeometryTest {
    private static int checks;
    public static void main(String[] args){
        var side=body(-20,0,5,20,0,5,1,.2,0);yes(side!=null&&side.nx()<-.8&&Math.abs(side.nz())<1e-9,"side normal");
        var roof=body(0,0,20,0,0,5,.1,2,0);close(roof.time(),.4,"large vertical extent hits roof at altitude14");close(roof.nz(),1,"roof upward normal");
        yes(body(-20,0,12.3,20,0,12.3,3,.2,0)==null,"wide thin aircraft still clears roof");
        yes(body(-20,0,13,20,0,13,.1,2,0)!=null,"tall narrow body contacts roof-side extent");
        yes(body(0,0,5,30,0,5,1,1,0)==null,"already inside can exit");yes(body(0,0,5,0,0,5,1,1,0)==null,"existing overlap has no new impact");
        yes(body(-20,0,5,-15,0,5,1,1,0)==null,"movement entirely outside");yes(body(Double.NaN,0,5,20,0,5,1,1,0)==null,"nonfinite safely rejected");
        for(int i=0;i<72;i++){
            double angle=i*Math.PI/36,c=Math.cos(angle),s=Math.sin(angle);
            var h=ForceGeometry.sweepBody(-20*c,-20*s,5,20*c,20*s,5,0,0,2,12,10,6,angle,1,.2);
            close(h.time(),side.time(),"rotated body sweep fraction");close(h.nx()*c+h.ny()*s,side.nx(),"rotated normal");
        }
        System.out.println("ForceAircraftGeometry: "+checks+" assertions passed.");
    }
    private static fpv.physics.SweptBodies.Hit body(double ax,double ay,double az,double bx,double by,double bz,double r,double h,double turn){return ForceGeometry.sweepBody(ax,ay,az,bx,by,bz,0,0,2,12,10,6,turn,r,h);}
    private static void close(double a,double b,String text){yes(Math.abs(a-b)<1e-9,text+": "+a+" / "+b);}
    private static void yes(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
}
