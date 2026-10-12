package fpv.flight;

import arc.graphics.g3d.Camera3D;
import arc.math.geom.Vec3;
import fpv.ui.CameraView;

/** Presentation only. Its bounded boom never writes pilot pose, sight or weapon state. */
public final class ObserverCamera {
    @FunctionalInterface public interface Trace { double fraction(Vec3 from,Vec3 to,double radius); }
    public static final double PROBE=.12, SKIN=.03;
    private final Vec3 offset=new Vec3(),desired=new Vec3(),candidate=new Vec3(),previous=new Vec3(),localOffset=new Vec3();
    private final Vec3 targetDirection=new Vec3(),axis=new Vec3(),up=new Vec3(),right=new Vec3(),targetFocus=new Vec3();
    private boolean active,previousValid;
    private double orbit,weight,zoomDistance,allowedDistance;
    public static double distance(double radius,double halfHeight,int percent){
        double base=Math.max(2.5,Math.max(radius*3.6,halfHeight*3.2));
        return Math.max(Math.max(.9,radius*1.6),base*Math.max(25,Math.min(400,percent))/100.0);
    }
    public void reset(){active=previousValid=false;weight=zoomDistance=allowedDistance=0;offset.setZero();}
    public double actualDistance(){return offset.len();}
    public boolean active(){return active;}
    public void apply(Camera3D camera,Vec3 focus,double radius,double halfHeight,int mode,int percent,double seconds,boolean smoothing,Trace trace){
        double dt=Math.max(0,Math.min(.1,seconds)),blend=smoothing?1-Math.exp(-dt/.18):1;
        boolean third=mode!=CameraView.FIRST;
        if(!smoothing&&!third){reset();return;}
        if(!active&&!third){previousValid=false;return;}
        double wantedOrbit=mode==CameraView.FRONT?Math.PI:0;
        if(!active){active=true;orbit=smoothing?0:wantedOrbit;allowedDistance=0;zoomDistance=distance(radius,halfHeight,percent);}
        double difference=Math.atan2(Math.sin(wantedOrbit-orbit),Math.cos(wantedOrbit-orbit));
        orbit=smoothing?orbit+difference*blend:wantedOrbit;
        weight+=( (third?1:0)-weight)*blend;
        zoomDistance+=(distance(radius,halfHeight,percent)-zoomDistance)*blend;
        if(!third&&weight<.0001){reset();return;}
        up.set(camera.up).nor();
        right.set(camera.direction).crs(up).nor();
        desired.set(camera.direction).scl(-1).rotate(up,(float)Math.toDegrees(orbit)).scl((float)(zoomDistance*weight));
        desired.y+=(float)(Math.max(.6,radius*.45)*weight*weight);
        candidate.set(camera.position).add(desired);
        double length=desired.len();
        double fraction=trace.fraction(camera.position,candidate,PROBE);
        double safe=fraction<1?Math.max(0,length*fraction-SKIN):length;
        // Obstacle ingress is immediate; recovery is eased without changing the stored preference.
        allowedDistance=!smoothing||safe<allowedDistance?safe:allowedDistance+(safe-allowedDistance)*(1-Math.exp(-dt/.24));
        if(length>1e-7)desired.scl((float)(Math.min(length,allowedDistance)/length));
        candidate.set(camera.position).add(desired);
        // Sweep animation in the CURRENT pilot frame. Never hold the rig at an old world position
        // or orientation when its owner moves/turns. The anchor-to-camera trace still wins instantly.
        if(smoothing&&previousValid){
            previous.set(camera.position).mulAdd(right,localOffset.x).mulAdd(up,localOffset.y).mulAdd(camera.direction,localOffset.z);
            double travel=previous.dst(candidate),f=trace.fraction(previous,candidate,PROBE);
            if(f<1&&travel>1e-7)candidate.set(previous).lerp(candidate,(float)Math.max(0,f-SKIN/travel));
        }
        double finalLength=camera.position.dst(candidate),f=trace.fraction(camera.position,candidate,PROBE);
        if(f<1&&finalLength>1e-7)candidate.set(camera.position).lerp(candidate,(float)Math.max(0,f-SKIN/finalLength));
        offset.set(candidate).sub(camera.position);
        localOffset.set(offset.dot(right),offset.dot(up),offset.dot(camera.direction));
        targetFocus.set(camera.position).lerp(focus,(float)(weight*weight));
        targetDirection.set(targetFocus).sub(candidate);
        if(targetDirection.len2()<1e-8)targetDirection.set(camera.direction);
        targetDirection.nor();
        // Orientation belongs to this frame's actual camera/focus, not an independently lagging
        // world-direction filter. Orbit animation therefore moves AND turns around the same anchor.
        camera.position.set(candidate);camera.direction.set(targetDirection);
        // Orthonormalize without losing the pilot's bank, including straight-up/down sights.
        axis.set(targetDirection).crs(up);
        if(axis.len2()<1e-8)axis.set(targetDirection).crs(Math.abs(targetDirection.y)<.9?Vec3.Y:Vec3.X);
        camera.up.set(axis.nor()).crs(targetDirection).nor();
        previousValid=true;
    }
}
