package fpv.units;

import java.util.function.DoubleBinaryOperator;

/** Pure ground contact in canonical native-horizontal XY, Z-up coordinates. */
public final class GroundSurfaceAdapter {
    public record Contact(double centerHeight,double pitch,double roll,double slopeX,double slopeY) {}
    private final DoubleBinaryOperator terrain;
    private final double sample,maxSlope,maxSupportStep;
    public GroundSurfaceAdapter(DoubleBinaryOperator terrainHeight,double sampleDistance,double maxSlopeDegrees,double maxSupportStep){
        if(terrainHeight==null||!Double.isFinite(sampleDistance)||sampleDistance<=0||
            !Double.isFinite(maxSlopeDegrees)||maxSlopeDegrees<=0||maxSlopeDegrees>=85||
            !Double.isFinite(maxSupportStep)||maxSupportStep<0)
            throw new IllegalArgumentException("Valid ground support profile required");
        terrain=terrainHeight;sample=sampleDistance;maxSlope=Math.tan(Math.toRadians(maxSlopeDegrees));this.maxSupportStep=maxSupportStep;
    }
    public Contact contact(double x,double y,double yawDegrees,double radius,double halfDepth){
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(yawDegrees)||
            !Double.isFinite(radius)||radius<0||!Double.isFinite(halfDepth)||halfDepth<0)
            throw new IllegalArgumentException("Finite ground pose/dimensions required");
        double floor=height(x,y);
        double gx=gradient(floor,height(x+sample,y),height(x-sample,y));
        double gy=gradient(floor,height(x,y+sample),height(x,y-sample));
        double slope=Math.hypot(gx,gy);
        if(slope>maxSlope){gx*=maxSlope/slope;gy*=maxSlope/slope;}
        double yaw=Math.toRadians(yawDegrees),ca=Math.cos(yaw),sa=Math.sin(yaw);
        double forwardSlope=gx*ca+gy*sa,rightSlope=gx*sa-gy*ca;
        double pitch=Math.atan(forwardSlope),roll=Math.atan2(-rightSlope,Math.sqrt(1+forwardSlope*forwardSlope));
        double cp=Math.cos(pitch),sp=Math.sin(pitch),cr=Math.cos(roll),sr=Math.sin(roll);
        // Same basis as sprite extrusion, expressed in public native XY/Z rather than render X/Y/Z.
        double rx=sa*cr+ca*sp*sr,ry=-ca*cr+sa*sp*sr,rz=-cp*sr;
        double bx=-ca*cp,by=-sa*cp,bz=-sp;
        double ux=-ca*sp*cr+sa*sr,uy=-sa*sp*cr-ca*sr,uz=cp*cr;
        double support=floor+halfDepth/Math.max(.1,uz);
        for(int i=0;i<9;i++){
            double angle=(i-1)*Math.PI/4,a=i==0?0:Math.cos(angle)*radius,b=i==0?0:Math.sin(angle)*radius;
            double dx=rx*a+bx*b-ux*halfDepth,dy=ry*a+by*b-uy*halfDepth;
            double localZ=rz*a+bz*b-uz*halfDepth;
            double sampleHeight=height(x+dx,y+dy),expected=floor+gx*dx+gy*dy;
            // A blocked neighboring wall is not a floor under the native unit's center.
            if(sampleHeight>expected+maxSupportStep)continue;
            support=Math.max(support,sampleHeight-localZ);
        }
        return new Contact(support,Math.toDegrees(pitch),Math.toDegrees(roll),gx,gy);
    }
    private double gradient(double center,double positive,double negative){
        double forward=(positive-center)/sample,backward=(center-negative)/sample;
        if(Math.abs(forward)>maxSlope*1.5||Math.abs(backward)>maxSlope*1.5)
            return Math.abs(forward)<Math.abs(backward)?forward:backward;
        return (forward+backward)*.5;
    }
    private double height(double x,double y){
        double value=terrain.applyAsDouble(x,y);
        if(!Double.isFinite(value))throw new IllegalArgumentException("Ground height must be finite");
        return value;
    }
}
