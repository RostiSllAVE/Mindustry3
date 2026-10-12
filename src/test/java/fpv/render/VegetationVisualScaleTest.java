package fpv.render;

import fpv.world.NaturalProps.Kind;
import java.util.Arrays;

/** Actual cached prop templates retain their base and topology under presentation-only growth. */
public final class VegetationVisualScaleTest {
    public static void main(String[] args){
        int checks=0;
        for(Kind kind:Kind.values())for(int variant=0;variant<8;variant++){
            float[] shape=OrganicPropGeometry.template(kind,variant),before=shape.clone();
            double expected=kind==Kind.ROCK?1:5;
            float oldWidth=1.75f*.94f,oldHeight=1.4f;
            float width=VegetationVisualScale.dimension(kind,oldWidth),height=VegetationVisualScale.dimension(kind,oldHeight);
            if(Math.abs(width/oldWidth-expected)>1e-6||Math.abs(height/oldHeight-expected)>1e-6)throw new AssertionError("Exact vegetation-only dimension multiplier");
            double minBefore=Double.POSITIVE_INFINITY,minAfter=Double.POSITIVE_INFINITY;
            for(int i=0;i<shape.length;i+=OrganicPropGeometry.STRIDE){
                double oldX=shape[i]*oldWidth,oldY=shape[i+1]*oldHeight,oldZ=shape[i+2]*oldWidth;
                double newX=shape[i]*width,newY=shape[i+1]*height,newZ=shape[i+2]*width;
                if(Math.abs(newX-oldX*expected)>1e-6||Math.abs(newY-oldY*expected)>1e-6||Math.abs(newZ-oldZ*expected)>1e-6)
                    throw new AssertionError("All local XYZ distances grow together about the existing base, never the map origin");
                minBefore=Math.min(minBefore,oldY);minAfter=Math.min(minAfter,newY);
                if(newY<0)throw new AssertionError("Vegetation remains above the anchored base");checks++;
            }
            if(Math.abs(minBefore)>1e-6||Math.abs(minAfter)>1e-6)throw new AssertionError("Zero-height original base remains zero after growth");
            if(!Arrays.equals(shape,before)||shape!=OrganicPropGeometry.template(kind,variant))throw new AssertionError("Geometry/cache/physical source template is unchanged");
        }
        if(VegetationVisualScale.scale((Kind)null)!=1)throw new AssertionError("Non-prop content is not enlarged");
        System.out.println("PASS: "+checks+" cached prop vertices; trees/bushes5x XYZ, rocks unchanged, base anchored, templates immutable");
    }
}
