package fpv.platform;

public final class ArcMouseMotionTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    public static void main(String[] args){
        ArcMouseMotion m=new ArcMouseMotion();
        m.sample(300,200,900,700,true);check(!m.captured()&&m.deltaX()==0,"uncaptured mouse cannot aim");
        m.capture(true);m.sample(300,200,900,700,true);check(m.deltaX()==0&&m.deltaY()==0,"capture starts without jump");
        m.sample(325,180,900,700,true);check(m.deltaX()==25&&m.deltaY()==20,"Arc Y is converted to positive down");
        check(m.deltaX()==25&&m.deltaY()==20,"accessors do not consume sample");
        m.sample(325,180,900,700,true);check(m.deltaX()==0&&m.deltaY()==0,"idle produces no repeated rotation");
        m.sample(890,180,900,700,true);check(m.deltaX()==0,"warp-sized event ignored");
        m.sample(899,180,900,700,true);check(m.deltaX()==9,"edge remains bounded real movement");
        m.sample(900,180,900,700,true);check(!m.captured()&&m.deltaX()==0,"outside window releases logical input");
        m.capture(true);m.sample(500,350,1200,800,true);check(m.deltaX()==0,"reentry seeds instead of jumping");
        m.sample(505,360,1000,750,true);check(m.deltaX()==0&&m.deltaY()==0,"resize discards stale coordinates");
        m.sample(515,350,1000,750,true);check(m.deltaX()==10&&m.deltaY()==10,"movement resumes after resize");
        m.sample(515,350,1000,750,false);check(!m.captured()&&m.deltaX()==0&&m.deltaY()==0,"focus loss stops controls");
        m.sample(520,350,1000,750,true);check(!m.captured(),"focus regain alone does not arm pointer");
        m.capture(true);m.sample(520,350,1000,750,true);m.capture(false);check(!m.captured()&&m.deltaX()==0,"Build/menu release is immediate");
        m.capture(true);m.sample(0,0,0,0,true);check(!m.captured(),"zero-sized/minimized viewport is safe");
        System.out.println("ArcMouseMotionTest: "+checks+" checks passed.");
    }
}
