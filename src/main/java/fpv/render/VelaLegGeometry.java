package fpv.render;

/** Cache-time subdivision of Vela's original whole-leg artwork, including its off-centre padding.
 * The native bilateral feet and standing solver remain the sole source of physical endpoints. */
public final class VelaLegGeometry {
    private VelaLegGeometry(){}
    public record Piece(float[] vertices,int x,int y,int width,int height){}
    public static Piece[] split(byte[] alpha,int width,int height,boolean coarse){
        if(alpha==null||width<1||height<1||(long)width*height!=alpha.length)
            throw new IllegalArgumentException("Whole native leg alpha required");
        int low=height,high=-1;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((alpha[x+y*width]&255)>=32){low=Math.min(low,y);high=Math.max(high,y);}
        if(high<low)throw new IllegalArgumentException("Empty leg silhouette");
        int span=high-low+1;
        // Upper armour, shin, knee socket and planted toe, all sampled from the SAME native leg.
        return new Piece[]{crop(alpha,width,height,low,low+(int)Math.ceil(span*.57),coarse),
            crop(alpha,width,height,low+(int)(span*.50),low+(int)Math.ceil(span*.86),coarse),
            crop(alpha,width,height,low+(int)(span*.43),low+(int)Math.ceil(span*.69),coarse),
            crop(alpha,width,height,low+(int)(span*.79),high+1,coarse)};
    }
    private static Piece crop(byte[] alpha,int width,int height,int start,int end,boolean coarse){
        int left=width,right=-1,top=height,bottom=-1;
        for(int y=start;y<Math.min(height,end);y++)for(int x=0;x<width;x++)if((alpha[x+y*width]&255)>=32){
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        if(right<left)throw new IllegalArgumentException("Missing native leg segment");
        int w=right-left+1,h=bottom-top+1;byte[] cropped=new byte[w*h];
        for(int y=0;y<h;y++)System.arraycopy(alpha,(y+top)*width+left,cropped,y*w,w);
        float[] vertices=SpriteExtrusionGeometry.generate(cropped,w,h,coarse?12:28,6,32,.13,4096).vertices();
        for(int i=0;i<vertices.length;i+=8){
            // Atlas coordinates stay in the full original region; no cloned texture or new atlas entry.
            vertices[i+6]=(left+vertices[i+6]*w)/width;
            vertices[i+7]=(top+vertices[i+7]*h)/height;
        }
        return new Piece(vertices,left,top,w,h);
    }
    /** Same two-link solver as native walkers, with a stable forward bend on both Vela flanks. */
    public static void knee(double hx,double hy,double hz,double fx,double fy,double fz,
                            double length,double yaw,int side,double[] out){
        if(side!=-1&&side!=1)throw new IllegalArgumentException("Mirrored leg side required");
        double a=Math.toRadians(yaw);
        double distance=Math.sqrt(Math.pow(fx-hx,2)+Math.pow(fy-hy,2)+Math.pow(fz-hz,2));
        // Retract at low stance instead of folding a fixed-length thigh through the body.
        double retracted=Math.min(length,Math.max(.001,distance/.86));
        ArticulatedLegIK.knee(hx,hy,hz,fx,fy,fz,retracted,-Math.sin(a)*side,Math.cos(a)*side,-side,out);
    }
    /** Rear knee stays in the rear-center sagittal plane instead of borrowing a left/right bend. */
    public static void rearKnee(double hx,double hy,double hz,double fx,double fy,double fz,double length,double yaw,double[] out){
        double a=Math.toRadians(yaw),distance=Math.sqrt(Math.pow(fx-hx,2)+Math.pow(fy-hy,2)+Math.pow(fz-hz,2));
        double retracted=Math.min(length,Math.max(.001,distance/.86));
        ArticulatedLegIK.knee(hx,hy,hz,fx,fy,fz,retracted,-Math.cos(a),-Math.sin(a),0,out);
    }
}
