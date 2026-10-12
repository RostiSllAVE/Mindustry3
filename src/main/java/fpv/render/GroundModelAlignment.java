package fpv.render;

/** Common vertical attachment for native whole-leg mechs. Canonical body height/thickness never change. */
public final class GroundModelAlignment {
    private GroundModelAlignment(){}
    public static double limbDepth(double nativeDepth,double bodyHalfDepth){
        return Math.max(.001,Math.min(nativeDepth,bodyHalfDepth*.9));
    }
    /** Fit the existing limb into the space below the torso, instead of forcing its lower face into terrain. */
    public static double supportedLimbDepth(double nativeDepth,double bodyHalfDepth,double bodyCenter,double support){
        double available=Math.max(0,bodyCenter-bodyHalfDepth*.25-support);
        return Math.min(limbDepth(nativeDepth,bodyHalfDepth),available);
    }
    public static double limbCenter(double bodyCenter,double bodyHalfDepth,double depth,double support,double lift){
        // The original 2D art is already the complete leg. Keep its top below the torso center,
        // rather than adding a thick separate slab on top of the body as the old adapter did.
        double top=bodyCenter-bodyHalfDepth*.25;
        return Math.min(top-depth*.5,Math.max(support+depth*.5,bodyCenter-bodyHalfDepth*.65-depth*.5)+Math.max(0,lift));
    }
    public static double baseDepth(double nativeDepth,double bodyHalfDepth){return Math.max(.001,Math.min(nativeDepth,bodyHalfDepth*.65));}
    public static double baseCenter(double bodyCenter,double bodyHalfDepth,double depth){return bodyCenter-bodyHalfDepth*.1-depth*.5;}
}
