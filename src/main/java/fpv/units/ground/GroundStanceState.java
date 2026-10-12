package fpv.units.ground;

/** Stable, persistent stance intent. The physical solver alone moves the body toward this value. */
public final class GroundStanceState {
    private final double randomOffset;
    private double manualDesired;
    private boolean manual;
    public GroundStanceState(double randomOffset,GroundUnit3DModelProfile profile){
        if(!Double.isFinite(randomOffset)||randomOffset<1||randomOffset>3||profile==null)throw new IllegalArgumentException("Stable stance offset must be 1..3 tiles");
        this.randomOffset=randomOffset;manualDesired=defaultHeight(profile);
    }
    public static double offset(int unitId,int typeId){
        long v=((long)unitId<<32)^Integer.toUnsignedLong(typeId)^0x9e3779b97f4a7c15L;
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;v^=v>>>31;
        return 1+2*(v>>>11)*0x1.0p-53;
    }
    public double randomOffset(){return randomOffset;}
    public double defaultHeight(GroundUnit3DModelProfile profile){
        // Vela is a large bilateral mech. Only its resting stance changes; manual limits stay native.
        if("vela".equals(profile.unitName()))return clamp(1.10,profile);
        return clamp(.5*profile.maximumClearance()+randomOffset,profile);
    }
    public double desired(GroundUnit3DModelProfile profile){return manual?clamp(manualDesired,profile):defaultHeight(profile);}
    public boolean manual(){return manual;}
    public void control(GroundUnit3DModelProfile profile,boolean controlled,double axis,double seconds,double rate){
        if(profile==null||!Double.isFinite(axis+seconds+rate)||Math.abs(axis)>1||seconds<0||rate<=0)throw new IllegalArgumentException("Bounded stance control required");
        if(!profile.legged()){manual=false;return;}
        if(controlled&&!manual)manualDesired=desired(profile);
        manual=controlled;
        if(manual)manualDesired=clamp(manualDesired+axis*Math.min(.1,seconds)*rate,profile);
    }
    /** Restores the chosen value, but ownership is explicitly re-established by the host controller. */
    public void restoreDesired(double desired,boolean manual){
        if(!Double.isFinite(desired)||desired<0||desired>1024)throw new IllegalArgumentException("Finite saved stance target required");
        manualDesired=desired;this.manual=manual;
    }
    private static double clamp(double value,GroundUnit3DModelProfile profile){return Math.max(profile.minimumClearance(),Math.min(profile.maximumClearance(),value));}
}
