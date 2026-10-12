package fpv.defense;

/** Top-down collapse, grazing/overflight and regeneration boundary tests. */
public final class ProjectionMathTest {
    private static int checks;
    public static void main(String[] args){
        close(ProjectionMath.top(1.4,8.4,850,850),8.4,"full five-segment volume");
        close(ProjectionMath.top(1.4,8.4,425,850),4.9,"one half pool gives half upper height");
        close(ProjectionMath.top(1.4,8.4,0,850),1.4,"empty projection leaves exact base");
        close(ProjectionMath.regenerated(0,850,8,5,.025),0,"regen waits damage delay");
        close(ProjectionMath.regenerated(0,850,8,10,.025),42.5,"only post-delay partial timestep regenerates");
        close(ProjectionMath.regenerated(800,850,0,10,.025),850,"one pool caps at maximum");
        close(ProjectionMath.regenerated(425,850,0,0,.025),425,"pause freezes regen");
        close(sweep(0,0,4,10,0,4,0),.45,"horizontal wall crossing");
        close(sweep(0,0,9,10,0,9,0),-1,"true XYZ overflight");
        close(sweep(0,0,9,10,0,9,.6),.39,"projectile radius graze");
        close(sweep(5,0,12,5,0,0,0),.3,"vertical shot enters upper cap");
        close(sweep(0,0,1,10,0,1,0),-1,"projection excludes physical base");
        close(sweep(5,0,4,5,0,4,0),0,"initial overlap blocks immediately");
        close(sweep(0,0,4,0,0,4,0),-1,"stationary outside misses");
        close(sweep(0,0,4,10,0,4,-1),-1,"negative radius rejected");
        close(sweep(Double.NaN,0,4,10,0,4,0),-1,"nonfinite input rejected");
        for(int i=0;i<100;i++){
            double hp=i*8.5,top=ProjectionMath.top(1.4,8.4,hp,850);
            yes(top>=1.4&&top<=8.4,"collapse stays bounded");
            if(i>0)yes(ProjectionMath.sweep(0,0,top-.001,10,0,top-.001,4.5,-.5,1.4,5.5,.5,top,0)>=0,"inside surviving height remains solid");
            yes(ProjectionMath.sweep(0,0,top+.001,10,0,top+.001,4.5,-.5,1.4,5.5,.5,top,0)<0,"removed top becomes traversable");
        }
        System.out.println("ProjectionMath: "+checks+" assertions passed.");
    }
    private static double sweep(double ax,double ay,double az,double bx,double by,double bz,double radius){return ProjectionMath.sweep(ax,ay,az,bx,by,bz,4.5,-.5,1.4,5.5,.5,8.4,radius);}
    private static void close(double a,double b,String label){yes(Double.isFinite(a)&&Math.abs(a-b)<1e-9,label+": "+a+" != "+b);}
    private static void yes(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
}
