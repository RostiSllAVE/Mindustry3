package fpv.flight;

public final class GroundEyeHeightTest {
    public static void main(String[] args){
        double small=GroundEyeHeight.desired(1,1.5,.6,0),medium=GroundEyeHeight.desired(2,2,.2,0),large=GroundEyeHeight.desired(4,4,.3,0);
        assert small>.6+GroundEyeHeight.RADIUS;assert large>medium;
        assert GroundEyeHeight.desired(1,1,.6,.1)>=.6+GroundEyeHeight.RADIUS;
        assert GroundEyeHeight.desired(1,1,.6,3)==3;
        var a=new GroundEyeHeight();var b=new GroundEyeHeight();
        double va=0,vb=0;for(int i=0;i<60;i++)va=a.advance(3,1/60.0);for(int i=0;i<120;i++)vb=b.advance(3,1/120.0);
        assert Math.abs(va-vb)<1e-8;assert va>2.99;
        double blocked=a.constrain(.3);assert blocked==.3;
        assert a.advance(3,1/60.0)<1;assert a.constrain(.1)==.1;
        a.reset();assert a.advance(3,0)==0;
        double first=a.advance(3,1/60.0);assert first>0&&first<.4;
        double released=a.release(0,1/60.0);assert released>0&&released<first;
        for(int i=0;i<180;i++)released=a.release(0,1/60.0);
        assert released==0&&!a.active();assert a.release(.72,1/60.0)==.72;
        boolean rejected=false;try{GroundEyeHeight.desired(Double.NaN,1,.2,0);}catch(IllegalArgumentException expected){rejected=true;}assert rejected;
        System.out.println("GroundEyeHeightTest: sizing, own hull, override, framerate-independent handoff, instant collision clamp and finite bounds passed");
    }
}
