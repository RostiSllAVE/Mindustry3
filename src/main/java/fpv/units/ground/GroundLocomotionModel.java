package fpv.units.ground;

/** Bounded grounded motion solver; all inputs/outputs use canonical gameplay XY with Z up. */
public final class GroundLocomotionModel {
    public interface Surface {
        /** Physical foot support. Holograms must never be returned here. */
        double support(double x,double y);
        /** Physical or enabled projected obstruction; zero/ground when absent. */
        double obstruction(double x,double y);
        /** Optional true-volume query; roofs must never be converted into ground height. */
        default fpv.physics.SweptBodies.Hit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){return null;}
        default double footX(int index,int count,double yawDegrees,double spread){return Math.cos(Math.toRadians(yawDegrees)+ProceduralLegLayout.angleRadians(index,count))*spread;}
        default double footY(int index,int count,double yawDegrees,double spread){return Math.sin(Math.toRadians(yawDegrees)+ProceduralLegLayout.angleRadians(index,count))*spread;}
    }
    public static final class State {
        private double x,y,z,desiredZ,extension,speedFraction=1,pitch,roll,verticalVelocity,velocityX,velocityY,distance,supportHeight,physicalFloor;
        private boolean initialized,blocked;
        public double x(){return x;} public double y(){return y;} public double z(){return z;}
        public double supportHeight(){return supportHeight;} public double clearance(double halfDepth){return z-halfDepth-supportHeight;}
        public double desiredZ(){return desiredZ;} public double extension(){return extension;}
        public double speedFraction(){return speedFraction;} public double pitch(){return pitch;}
        public double roll(){return roll;} public double verticalVelocity(){return verticalVelocity;}
        public double velocityX(){return velocityX;} public double velocityY(){return velocityY;}
        public double distance(){return distance;} public boolean blocked(){return blocked;}
    }
    private static final class Sample {double minFoot,maxFoot,meanFoot,physicalObstacle,obstacle,target,pitch,roll;boolean reachable;}
    private final Sample sample=new Sample(),current=new Sample();
    private final double climbSpeed,descentSpeed,sweepStep,crawlerStep;
    public GroundLocomotionModel(double climbSpeed,double descentSpeed,double sweepStep,double crawlerStep){
        if(!Double.isFinite(climbSpeed+descentSpeed+sweepStep+crawlerStep)||climbSpeed<=0||descentSpeed<=0||sweepStep<=0||crawlerStep<0)
            throw new IllegalArgumentException("Valid grounded integration profile required");
        this.climbSpeed=climbSpeed;this.descentSpeed=descentSpeed;this.sweepStep=sweepStep;this.crawlerStep=crawlerStep;
    }
    public State create(GroundUnit3DModelProfile profile,double x,double y,double yaw,double radius,double halfDepth,Surface surface){
        State s=new State();reset(s,profile,x,y,yaw,radius,halfDepth,surface);return s;
    }
    public void reset(State s,GroundUnit3DModelProfile p,double x,double y,double yaw,double radius,double halfDepth,Surface surface){
        validate(p,x,y,yaw,radius,halfDepth,surface);sample(p,x,y,yaw,radius,halfDepth,surface,current);
        s.x=x;s.y=y;s.supportHeight=current.minFoot;s.physicalFloor=current.physicalObstacle;
        s.z=s.desiredZ=allowedTarget(p,current,halfDepth,p.normalStance());
        s.extension=extension(p,s.z,halfDepth,current.minFoot);
        s.speedFraction=p.speedFraction(s.extension);s.pitch=current.pitch;s.roll=current.roll;s.verticalVelocity=0;
        s.distance=0;s.velocityX=s.velocityY=0;s.blocked=false;s.initialized=true;
    }
    public void step(State s,GroundUnit3DModelProfile p,double targetX,double targetY,double yaw,double radius,double halfDepth,double dt,Surface surface){
        step(s,p,targetX,targetY,yaw,radius,halfDepth,dt,surface,p.normalStance());
    }
    public void seedHeight(State s,GroundUnit3DModelProfile profile,double height,double halfDepth){
        if(s==null||profile==null||!Double.isFinite(height+halfDepth)||halfDepth<=0)throw new IllegalArgumentException("Valid resumed ground height required");
        s.z=Math.max(s.physicalFloor+halfDepth+.015,height);s.verticalVelocity=s.velocityX=s.velocityY=0;
        s.extension=extension(profile,s.z,halfDepth,s.supportHeight);s.speedFraction=profile.speedFraction(s.extension);
    }
    public void step(State s,GroundUnit3DModelProfile p,double targetX,double targetY,double yaw,double radius,double halfDepth,double dt,Surface surface,double desiredClearance){
        validate(p,targetX,targetY,yaw,radius,halfDepth,surface);
        if(s==null||!s.initialized||!Double.isFinite(dt)||dt<0)throw new IllegalArgumentException("Initialized state and finite nonnegative delta required");
        if(!Double.isFinite(desiredClearance))throw new IllegalArgumentException("Finite stance target required");
        desiredClearance=Math.max(p.minimumClearance(),Math.min(p.maximumClearance(),desiredClearance));
        if(dt==0)return;
        dt=Math.min(.1,dt);double oldZ=s.z,oldX=s.x,oldY=s.y;
        sample(p,s.x,s.y,yaw,radius,halfDepth,surface,current);
        // Terrain edits and spawned units recover onto physical support without inventing flight.
        if(s.z-halfDepth<current.physicalObstacle+.01)s.z=current.physicalObstacle+halfDepth+.01;
        double dx=(targetX-s.x)*s.speedFraction,dy=(targetY-s.y)*s.speedFraction;
        double length=Math.hypot(dx,dy),heightTarget=allowedTarget(p,current,halfDepth,desiredClearance);
        int steps=Math.max(1,Math.min(128,(int)Math.ceil(length/sweepStep)));
        s.blocked=false;
        for(int i=1;i<=steps;i++){
            double x=oldX+dx*i/steps,y=oldY+dy*i/steps;
            sample(p,x,y,yaw,radius,halfDepth,surface,sample);
            // The unit cannot adopt a high platform as its own baseline to bypass its reach limit.
            double rise=sample.obstacle-current.minFoot;
            double reach=p.normalStance()+(p.legged()?p.maxExtension():crawlerStep);
            if(!sample.reachable||rise>reach+1e-6){
                s.blocked=true;
                heightTarget=Math.max(heightTarget,current.minFoot+reach+halfDepth);
                break;
            }
            heightTarget=Math.max(heightTarget,allowedTarget(p,sample,halfDepth,desiredClearance));
            double stepTolerance=p.legged()?.025:crawlerStep;
            if(sample.obstacle>s.z-halfDepth+stepTolerance){s.blocked=true;break;}
            if(surface.sweep(s.x,s.y,s.z,x,y,s.z,radius,halfDepth)!=null){s.blocked=true;break;}
            s.x=x;s.y=y;
        }
        sample(p,s.x,s.y,yaw,radius,halfDepth,surface,current);
        if(!s.blocked)heightTarget=allowedTarget(p,current,halfDepth,desiredClearance);
        double maximum=current.minFoot+p.normalStance()+(p.legged()?p.maxExtension():crawlerStep)+halfDepth;
        heightTarget=Math.min(heightTarget,Math.max(current.physicalObstacle+halfDepth+.015,maximum));
        s.desiredZ=heightTarget;
        double rate=heightTarget>s.z?climbSpeed:descentSpeed;
        double proposedZ=Math.max(current.physicalObstacle+halfDepth+.01,s.z+Math.max(-rate*dt,Math.min(rate*dt,heightTarget-s.z)));
        var ceiling=surface.sweep(s.x,s.y,s.z,s.x,s.y,proposedZ,radius,halfDepth);
        if(ceiling!=null){s.z+=(proposedZ-s.z)*Math.max(0,ceiling.time()-1e-5);s.blocked=true;}
        else s.z=proposedZ;
        s.supportHeight=current.minFoot;s.physicalFloor=current.physicalObstacle;
        s.extension=extension(p,s.z,halfDepth,current.minFoot);
        s.speedFraction=p.speedFraction(s.extension);
        s.pitch=current.pitch;s.roll=current.roll;s.verticalVelocity=(s.z-oldZ)/dt;
        s.velocityX=(s.x-oldX)/dt;s.velocityY=(s.y-oldY)/dt;
        s.distance+=Math.hypot(s.x-oldX,s.y-oldY);
    }
    private void sample(GroundUnit3DModelProfile p,double x,double y,double yaw,double radius,double halfDepth,Surface surface,Sample out){
        double angle=Math.toRadians(yaw),c=Math.cos(angle),s=Math.sin(angle),spread=radius*p.footprintScale();
        int count=p.legged()?p.legCount():4;
        out.minFoot=Double.POSITIVE_INFINITY;out.maxFoot=Double.NEGATIVE_INFINITY;out.meanFoot=0;
        for(int i=0;i<count;i++){
            double fx=surface.footX(i,count,yaw,spread),fy=surface.footY(i,count,yaw,spread);
            double h=height(surface.support(x+fx,y+fy));
            out.minFoot=Math.min(out.minFoot,h);out.maxFoot=Math.max(out.maxFoot,h);out.meanFoot+=h/count;
        }
        out.physicalObstacle=height(surface.support(x,y));
        out.obstacle=Math.max(out.physicalObstacle,height(surface.obstruction(x,y)));
        // Body footprint is the actual collision hull, not the much wider leg spread.
        for(int i=0;i<8;i++){
            double a=i*Math.PI/4;
            double sx=x+Math.cos(a)*radius*.8,sy=y+Math.sin(a)*radius*.8;
            out.physicalObstacle=Math.max(out.physicalObstacle,height(surface.support(sx,sy)));
            out.obstacle=Math.max(out.obstacle,Math.max(out.physicalObstacle,height(surface.obstruction(sx,sy))));
        }
        double stance=p.normalStance(),reach=stance+(p.legged()?p.maxExtension():crawlerStep);
        out.reachable=out.obstacle-out.minFoot<=reach+1e-6;
        out.target=Math.max(out.obstacle+.015,out.meanFoot+stance)+halfDepth;
        double distance=Math.max(.2,radius*.5),limit=Math.tan(Math.toRadians(12));
        double gx=(height(surface.support(x+distance,y))-height(surface.support(x-distance,y)))/(2*distance);
        double gy=(height(surface.support(x,y+distance))-height(surface.support(x,y-distance)))/(2*distance);
        // Cliff edges change extension, not body pitch; smooth slopes retain a modest grounded tilt.
        if(Math.abs(gx)>limit*2)gx=0;if(Math.abs(gy)>limit*2)gy=0;
        double forward=Math.max(-limit,Math.min(limit,gx*c+gy*s));
        double right=Math.max(-limit,Math.min(limit,gx*s-gy*c));
        out.pitch=Math.toDegrees(Math.atan(forward));out.roll=-Math.toDegrees(Math.atan(right));
    }
    private double allowedTarget(GroundUnit3DModelProfile p,Sample s,double halfDepth,double desiredClearance){
        double reach=p.normalStance()+(p.legged()?p.maxExtension():crawlerStep);
        // A new/regenerating hologram may obstruct a body, but may never launch it above tier reach.
        return Math.max(s.physicalObstacle+halfDepth+.015,Math.min(Math.max(s.target,s.minFoot+desiredClearance+halfDepth),s.minFoot+reach+halfDepth));
    }
    private static double extension(GroundUnit3DModelProfile p,double z,double halfDepth,double minFoot){return p.legged()?Math.max(0,Math.min(p.maxExtension(),z-halfDepth-minFoot-p.normalStance())):0;}
    private static double height(double h){if(!Double.isFinite(h))throw new IllegalArgumentException("Finite support/obstruction height required");return h;}
    private static void validate(GroundUnit3DModelProfile p,double x,double y,double yaw,double radius,double halfDepth,Surface surface){
        if(p==null||surface==null||!Double.isFinite(x+y+yaw+radius+halfDepth)||radius<=0||halfDepth<=0)
            throw new IllegalArgumentException("Valid grounded unit geometry required");
    }
}

