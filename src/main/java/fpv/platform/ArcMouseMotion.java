package fpv.platform;

/** Bounded host-pointer sampling. No cursor warping, desktop clipping or native dependency. */
public final class ArcMouseMotion {
    private boolean captured, seeded;
    private int previousX,previousY,width,height;
    private float dx,dy;
    public boolean captured(){return captured;}
    public float deltaX(){return dx;}
    public float deltaY(){return dy;}
    public void capture(boolean value){if(value!=captured){captured=value;seeded=false;}if(!value)dx=dy=0;}
    public void sample(int x,int y,int w,int h,boolean focused){
        dx=dy=0;
        if(!captured)return;
        if(!focused||w<=0||h<=0||x<0||y<0||x>=w||y>=h){capture(false);return;}
        boolean resize=w!=width||h!=height;
        if(seeded&&!resize){
            long horizontal=(long)x-previousX,vertical=(long)previousY-y;
            // Re-entry/resize/warp-sized jumps are not physical pilot commands.
            if(Math.abs(horizontal)<=w/2L&&Math.abs(vertical)<=h/2L){dx=horizontal;dy=vertical;}
        }
        previousX=x;previousY=y;width=w;height=h;seeded=true;
    }
}
