package fpv.render;

/** Regression for small native bodies being visually buried underneath a much thicker leg sprite. */
public final class GroundModelAlignmentTest {
    public static void main(String[] args){
        int checked=0;
        for(double half:new double[]{.06,.09,.2325,.8})for(double nativeDepth:new double[]{.035,.1,.32})for(double support:new double[]{0,1.4,4})for(double lift:new double[]{0,.02,.3}){
            double body=support+half,depth=GroundModelAlignment.supportedLimbDepth(nativeDepth,half,body,support);
            double leg=GroundModelAlignment.limbCenter(body,half,depth,support,lift);
            double baseDepth=GroundModelAlignment.baseDepth(nativeDepth,half),base=GroundModelAlignment.baseCenter(body,half,baseDepth);
            if(leg+depth*.5>=body||base+baseDepth*.5>=body)throw new AssertionError("Legs and chassis must remain below the torso center, including small Dagger art");
            if(leg-depth*.5<support-1e-9)throw new AssertionError("Shared Mech feet must not sink below their sampled surface");
            if(depth>nativeDepth+1e-9||!Double.isFinite(leg+base))throw new AssertionError("Stable shared attachment without inflated native depth");checked++;
        }
        // Native atlas/body values and metadata support heights from the real 160.5 gallery.
        String[] names={"dagger","fortress","scepter","reign"};
        double[] half={.06,.12,.18,.2325},height={.12,.12,.1925,.2625};
        double[] expectedDepth={.054,.09,.1475,.204375};
        for(int i=0;i<names.length;i++)for(double ground:new double[]{0,1.4,4})for(double lift:new double[]{0,.02,.3}){
            double d=GroundModelAlignment.supportedLimbDepth(.32,half[i],height[i]+ground,ground);
            double center=GroundModelAlignment.limbCenter(height[i]+ground,half[i],d,ground,lift);
            if(Math.abs(d-expectedDepth[i])>1e-9||center-d*.5<ground-1e-9||center+d*.5>=ground+height[i])
                throw new AssertionError(names[i]+" actual native attachment must fit above floor and below torso");
            if(i==0){
                double oldDepth=GroundModelAlignment.limbDepth(.32,half[i]);
                double oldCenter=GroundModelAlignment.limbCenter(height[i]+ground,half[i],oldDepth,ground,lift);
                if(d!=oldDepth||center!=oldCenter)throw new AssertionError("Dagger geometry must remain exactly unchanged");
            }
            checked++;
        }
        if(GroundModelAlignment.supportedLimbDepth(.32,.12,.12,.2)!=0)throw new AssertionError("No-space support collapses the limb instead of burying it");
        System.out.println("PASS: "+checked+" shared Mech layer envelopes; native Fortress/Scepter/Reign floor fit; Dagger unchanged");
    }
}
