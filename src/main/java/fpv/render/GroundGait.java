package fpv.render;

import fpv.units.ground.ProceduralLegLayout;
import fpv.units.ground.NativeLegLayout;
import java.util.function.DoubleBinaryOperator;

/** Small persistent foot state in canonical XYZ. Planted feet remain fixed in world XY until their alternating step. */
public final class GroundGait {
    private final int count;
    private final boolean tripod;
    private final double[] feet,from,to;
    private boolean initialized,stepping;
    private int group,groups=2;
    private double progress,lastX,lastY,distance;
    public GroundGait(int legCount){
        this(legCount,false);
    }
    /** Explicit opt-in: native paired rigs keep exactly their existing layout and scheduling. */
    public static GroundGait velaTripod(){return new GroundGait(VelaTripodLayout.LEGS,true);}
    private GroundGait(int legCount,boolean tripod){
        if(tripod?legCount!=3:legCount<2||legCount>16||legCount%2!=0)throw new IllegalArgumentException("Paired legs or explicit Vela tripod required");
        this.tripod=tripod;groups=tripod?3:2;
        count=legCount;feet=new double[count*3];from=new double[count*3];to=new double[count*3];
    }
    public int count(){return count;}
    public double x(int index){return feet[index*3];}
    public double y(int index){return feet[index*3+1];}
    public double z(int index){return feet[index*3+2];}
    public boolean planted(int index){return !stepping||index%groups!=group;}
    public double traveled(){return distance;}
    public void reset(){initialized=false;stepping=false;progress=0;distance=0;group=0;}
    public static double angle(int index,int count){return ProceduralLegLayout.angleRadians(index,count);}

    public void update(double x,double y,double yaw,double footprint,double bodyRadius,double seconds,DoubleBinaryOperator surface){
        update(x,y,yaw,footprint,bodyRadius,seconds,surface,null);
    }
    public void update(double x,double y,double yaw,double footprint,double bodyRadius,double seconds,DoubleBinaryOperator surface,NativeLegLayout.Config layout){
        if(surface==null||!Double.isFinite(x+y+yaw+footprint+bodyRadius+seconds)||footprint<=0||bodyRadius<=0||seconds<0)
            throw new IllegalArgumentException("Finite ground pose and surface required");
        double moved=initialized?Math.hypot(x-lastX,y-lastY):0;
        if(tripod&&layout!=null)throw new IllegalArgumentException("Tripod uses its visual layout only");
        int nextGroups=tripod?3:layout==null?2:Math.min(count,Math.max(count/layout.groupSize(),2));
        if(nextGroups!=groups){reset();groups=nextGroups;}
        if(!initialized||moved>Math.max(8,footprint*5)){
            for(int i=0;i<count;i++){
                int o=i*3;
                feet[o]=x+offsetX(i,yaw,footprint,layout);feet[o+1]=y+offsetY(i,yaw,footprint,layout);
                feet[o+2]=surface.applyAsDouble(feet[o],feet[o+1]);
            }
            initialized=true;stepping=false;progress=0;lastX=x;lastY=y;return;
        }
        distance+=moved;double dt=Math.min(.1,seconds);
        if(!stepping&&dt>0){
            boolean needed=false;
            double threshold=layout==null?bodyRadius*.36:Math.min(bodyRadius*.8,Math.max(bodyRadius*.36,layout.length()*.10));
            // The tripod may service another overdue foot when the next phase is already planted.
            // This avoids a stale rear foot after stopping/reversing; only one foot can swing.
            if(tripod)for(int candidate=0;candidate<count;candidate++){
                int i=(group+candidate)%count,o=i*3;
                if(Math.hypot(x+offsetX(i,yaw,footprint,null)-feet[o],y+offsetY(i,yaw,footprint,null)-feet[o+1])>threshold){group=i;needed=true;break;}
            }
            else for(int i=group;i<count;i+=groups){
                int o=i*3;
                if(Math.hypot(x+offsetX(i,yaw,footprint,layout)-feet[o],y+offsetY(i,yaw,footprint,layout)-feet[o+1])>threshold){needed=true;break;}
            }
            if(needed){
                double speed=seconds>1e-8?moved/seconds:0;
                double lead=moved>1e-8?Math.min(bodyRadius*.4,speed*.10)/moved:0;
                for(int i=group;i<count;i+=groups){
                    int o=i*3;
                    System.arraycopy(feet,o,from,o,3);
                    to[o]=x+offsetX(i,yaw,footprint,layout)+(x-lastX)*lead;
                    to[o+1]=y+offsetY(i,yaw,footprint,layout)+(y-lastY)*lead;
                    to[o+2]=surface.applyAsDouble(to[o],to[o+1]);
                }
                progress=0;stepping=true;
            }
        }
        if(stepping){
            double speed=seconds>1e-8?moved/seconds:0;
            double duration=Math.max(.12,Math.min(.32,bodyRadius*.40/Math.max(.1,speed)));
            progress=Math.min(1,progress+dt/duration);double eased=progress*progress*(3-2*progress);
            for(int i=group;i<count;i+=groups){
                int o=i*3;
                feet[o]=from[o]+(to[o]-from[o])*eased;feet[o+1]=from[o+1]+(to[o+1]-from[o+1])*eased;
                feet[o+2]=from[o+2]+(to[o+2]-from[o+2])*eased+Math.sin(progress*Math.PI)*bodyRadius*.26;
                feet[o+2]=Math.max(feet[o+2],surface.applyAsDouble(feet[o],feet[o+1]));
            }
            if(progress>=1){stepping=false;group=(group+1)%groups;}
        }
        for(int i=0;i<count;i++)if(planted(i))feet[i*3+2]=surface.applyAsDouble(feet[i*3],feet[i*3+1]);
        lastX=x;lastY=y;
    }
    private double offsetX(int i,double yaw,double footprint,NativeLegLayout.Config layout){return tripod?VelaTripodLayout.footX(i,yaw,footprint):layout==null?Math.cos(yaw+angle(i,count))*footprint:NativeLegLayout.footX(layout,i,count,Math.toDegrees(yaw));}
    private double offsetY(int i,double yaw,double footprint,NativeLegLayout.Config layout){return tripod?VelaTripodLayout.footY(i,yaw,footprint):layout==null?Math.sin(yaw+angle(i,count))*footprint:NativeLegLayout.footY(layout,i,count,Math.toDegrees(yaw));}
}
