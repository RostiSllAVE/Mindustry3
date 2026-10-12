package fpv.units.ground;

/** Restore only the rejected part of an already-commanded component; never create input. */
public final class GroundIntentMath {
    private GroundIntentMath(){}
    public static double restore(double start,double nativeEnd,double intendedDelta,boolean nativeTileBlocked){
        if(!Double.isFinite(start+nativeEnd+intendedDelta))throw new IllegalArgumentException("Finite native movement intent required");
        if(!nativeTileBlocked||Math.abs(intendedDelta)<1e-8)return nativeEnd;
        double actual=nativeEnd-start;
        if(actual*intendedDelta<-.0001||Math.abs(actual)>=Math.abs(intendedDelta)*.8)return nativeEnd;
        return start+intendedDelta;
    }
}
