package fpv.units.ground;

/** Reusable radial leg layout: gameplay XY, angle zero east. No allocation per support sample. */
public final class ProceduralLegLayout {
    private ProceduralLegLayout(){}
    public static double angleRadians(int index,int count){
        if(count<2||count>16||index<0||index>=count)throw new IllegalArgumentException("Valid leg index/count required");
        return (index+.5)*2*Math.PI/count;
    }
    public static double x(int index,int count){return Math.cos(angleRadians(index,count));}
    public static double y(int index,int count){return Math.sin(angleRadians(index,count));}
}
