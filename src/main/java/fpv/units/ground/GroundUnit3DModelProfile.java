package fpv.units.ground;

/** Data-only family definition. Distances are tiles; spread is relative to native hit radius. */
public record GroundUnit3DModelProfile(String unitName,BodyStyle bodyStyle,int wheelCount,int legCount,
        double thicknessMultiplier,double normalStance,double maxExtension,double minSpeedFraction,double footprintScale) {
    public enum BodyStyle { WHEELED, LEGGED }
    public GroundUnit3DModelProfile {
        if(unitName==null||unitName.isBlank()||bodyStyle==null||wheelCount<0||wheelCount>16||legCount<0||legCount>8||
           !Double.isFinite(thicknessMultiplier+normalStance+maxExtension+minSpeedFraction+footprintScale)||
           thicknessMultiplier<=0||normalStance<0||maxExtension<0||maxExtension>512||
           minSpeedFraction<=0||minSpeedFraction>1||footprintScale<=0||footprintScale>8||
           (bodyStyle==BodyStyle.WHEELED&&(wheelCount!=4||legCount!=0||maxExtension!=0))||
           (bodyStyle==BodyStyle.LEGGED&&(wheelCount!=0||legCount<2||legCount%2!=0)))throw new IllegalArgumentException("Invalid grounded family profile");
    }
    public boolean legged(){return bodyStyle==BodyStyle.LEGGED;}
    public double minimumClearance(){return normalStance;}
    public double maximumClearance(){return normalStance+maxExtension;}
    public double speedFraction(double extension){
        if(!Double.isFinite(extension))throw new IllegalArgumentException("Finite extension required");
        double t=maxExtension>0?Math.max(0,Math.min(1,extension/maxExtension)):0;
        return 1-(1-minSpeedFraction)*t*t;
    }
}
