package fpv.render;

/** Six immutable procedural silhouettes. Rasterization happens only when the renderer creates its atlas. */
public final class ThreatMarkerSymbols {
    public static final int CELL=64, COUNT=6;
    private ThreatMarkerSymbols(){}
    public static boolean fill(int tier,double x,double y){
        return switch(tier){
            case 1 -> x*x+y*y<.19*.19;
            case 2 -> Math.pow(Math.abs(x)-.21,2)+y*y<.145*.145;
            case 3 -> y>=-.30&&y<=.36&&Math.abs(x)<(.36-y)*.515;
            case 4 -> Math.abs(x)<.29&&Math.abs(y)<.29;
            case 5 -> skull(x,y);
            default -> Math.abs(x)+Math.abs(y)<.39&&Math.abs(x)+Math.abs(y)>.23;
        };
    }
    private static boolean skull(double x,double y){
        boolean body=x*x/(.35*.35)+(y-.10)*(y-.10)/(.29*.29)<1 || Math.abs(x)<.245&&y>-.29&&y<.08;
        boolean eye=Math.pow((Math.abs(x)-.15)/.085,2)+Math.pow((y-.105)/.075,2)<1;
        boolean nose=y>-.14&&y<-.025&&Math.abs(x)<(y+.14)*.40;
        boolean teeth=y<-.19&&(Math.abs(x-.085)<.023||Math.abs(x+.085)<.023);
        return body&&!eye&&!nose&&!teeth;
    }
    /** RGBA8888: red fill, green dark edge, blue controlled-unit white edge. */
    public static int[] pixels(){
        int width=CELL*COUNT;int[] pixels=new int[width*CELL];
        for(int tier=0;tier<COUNT;tier++){
            boolean[] inside=new boolean[CELL*CELL];
            for(int y=0;y<CELL;y++)for(int x=0;x<CELL;x++)inside[x+y*CELL]=fill(tier,(x+.5)/CELL-.5,.5-(y+.5)/CELL);
            for(int y=0;y<CELL;y++)for(int x=0;x<CELL;x++){
                double nearest=100;
                for(int dy=-6;dy<=6;dy++)for(int dx=-6;dx<=6;dx++){
                    int sx=x+dx,sy=y+dy;
                    if(sx>=0&&sx<CELL&&sy>=0&&sy<CELL&&inside[sx+sy*CELL])nearest=Math.min(nearest,Math.sqrt(dx*dx+dy*dy));
                }
                int r=inside[x+y*CELL]?255:0,g=(int)(255*Math.max(0,Math.min(1,3-nearest))),b=(int)(255*Math.max(0,Math.min(1,6-nearest)));
                pixels[tier*CELL+x+y*width]=(r<<24)|(g<<16)|(b<<8)|255;
            }
        }
        return pixels;
    }
}
