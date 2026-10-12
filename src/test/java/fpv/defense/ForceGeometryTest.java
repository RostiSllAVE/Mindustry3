package fpv.defense;

/** Finite-height contact, polygon corners and sweep invariance for the actual shell shape. */
public final class ForceGeometryTest {
    private static int checks;
    public static void main(String[] args){
        close(hit(-20,0,3,20,0,3,6,0,0),.25,"native hex vertex edge");
        close(hit(0,-20,3,0,20,3,6,0,0),(20-10*Math.cos(Math.PI/6))/40,"native hex flat edge");
        close(hit(-20,0,13,20,0,13,6,0,0),-1,"overflight passes");
        close(hit(-20,0,-1,20,0,-1,6,0,0),-1,"underflight passes");
        close(hit(0,0,20,0,0,0,6,0,0),.4,"top cap stops downward laser");
        close(hit(0,0,-8,0,0,12,6,0,0),.5,"bottom cap stops upward laser");
        close(hit(0,0,3,20,0,3,6,0,0),0,"hostile born inside absorbed before escape");
        close(hit(0,0,3,0,0,3,6,0,0),0,"stationary hostile inside");
        close(hit(20,0,3,20,0,3,6,0,0),-1,"stationary outside");
        close(hit(-20,0,12.1,20,0,12.1,6,0,0),-1,"point outside cap");
        yes(hit(-20,0,12.1,20,0,12.1,6,0,.11)>=0,"body radius grazing cap");
        close(hit(Double.NaN,0,0,20,0,0,6,0,0),-1,"nonfinite rejects");
        close(hit(-20,0,3,20,0,3,2,0,0),-1,"degenerate polygon rejects");
        close(hit(-20,0,3,20,0,3,6,0,-1),-1,"negative body radius rejects");
        for(int sides=3;sides<=64;sides++)for(int turn=0;turn<24;turn++){
            double angle=turn*Math.PI/12,c=Math.cos(angle),s=Math.sin(angle);
            double rotated=hit(-20*c,-20*s,3,20*c,20*s,3,sides,angle,0);
            double base=hit(-20,0,3,20,0,3,sides,0,0);close(rotated,base,"rotational invariance");
            yes(rotated>=0&&rotated<.5,"entry in first half");
            double moved=ForceGeometry.sweep(-20*c+100,-20*s-40,8,20*c+100,20*s-40,8,100,-40,7,17,10,sides,angle,0);
            close(moved,base,"translation in all three coordinates");
        }
        System.out.println("ForceGeometry: "+checks+" assertions passed.");
    }
    private static double hit(double ax,double ay,double az,double bx,double by,double bz,int sides,double rotation,double padding){return ForceGeometry.sweep(ax,ay,az,bx,by,bz,0,0,2,12,10,sides,rotation,padding);}
    private static void close(double a,double b,String text){yes(Double.isFinite(a)&&Math.abs(a-b)<1e-9,text+": "+a+" / "+b);}
    private static void yes(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
}
