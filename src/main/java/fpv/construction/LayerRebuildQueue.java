package fpv.construction;

import java.io.*;
import java.util.*;

/** Host-owned destroyed-building plans; never carries an old inventory or writes native XY tiles. */
public final class LayerRebuildQueue {
    public static final int MAX_JOBS=2048,MAX_BYTES=2*1024*1024;
    private static final int VERSION=1;
    private record Cell(int x,int y,int layer,LayerComponent.Kind kind){}
    public record Job(LayerComponent component,double progress){}
    public record Tower(double x,double y,double z,int team,double range,double work){
        public Tower{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||team<0||team>255||!Double.isFinite(range)||range<0||!Double.isFinite(work)||work<0)throw new IllegalArgumentException("Finite rebuild tower state required");}
    }
    public interface Callback {
        /** Must include current geometry, structural support, server permissions and available recipe resources. */
        boolean canPlace(LayerComponent component);
        /** Original block buildTime, in native ticks before tower effort and team speed multipliers. */
        double buildTime(LayerComponent component);
        /** Atomic revalidation, recipe payment and creation of a fresh empty building. False remains retryable. */
        boolean complete(LayerComponent component);
    }
    public record Work(LayerComponent component,double progress,boolean completed){}
    private LayerCoordinates coordinates;
    private final LinkedHashMap<Cell,Job> jobs=new LinkedHashMap<>();
    private long revision;
    private int bytes=8;
    public LayerRebuildQueue(LayerCoordinates coordinates){this.coordinates=Objects.requireNonNull(coordinates);}
    public long revision(){return revision;}
    public int size(){return jobs.size();}
    public int byteBudget(){return bytes;}
    public void setCoordinates(LayerCoordinates value){Objects.requireNonNull(value);if(value.spacing()!=coordinates.spacing())throw new IllegalArgumentException("Cannot move pending rebuild floors");for(Job job:jobs.values())value.plane(job.component().layer());coordinates=value;}
    public List<Job> snapshot(){return List.copyOf(jobs.values());}
    /** Call only for destruction, not a voluntary remove/deconstruct or snapshot replacement. */
    public boolean remember(LayerComponent destroyed){
        Objects.requireNonNull(destroyed);coordinates.plane(destroyed.layer());
        LayerComponent fresh=new LayerComponent(destroyed.id(),destroyed.kind(),destroyed.x(),destroyed.y(),destroyed.layer(),destroyed.blockName(),destroyed.size(),destroyed.rotation(),destroyed.team(),destroyed.mode(),destroyed.maxHealth(),destroyed.maxHealth(),destroyed.mass(),destroyed.height(),destroyed.support(),destroyed.configuration(),null);
        Cell cell=cell(fresh);Job old=jobs.get(cell);int next=bytes-budget(old)+budget(fresh);
        if(old==null&&jobs.size()>=MAX_JOBS||next>MAX_BYTES)return false;
        jobs.put(cell,new Job(fresh,0));bytes=next;revision++;return true;
    }
    public boolean removeAt(int x,int y,int layer,LayerComponent.Kind kind){Job old=jobs.remove(new Cell(x,y,layer,kind));if(old==null)return false;bytes-=budget(old);revision++;return true;}
    public void clear(){if(jobs.isEmpty())return;jobs.clear();bytes=8;revision++;}
    /** Pure query used by native shouldConsume; even an unpowered tower can discover pending work. */
    public Job target(Tower tower,Callback callback){
        Objects.requireNonNull(tower);Objects.requireNonNull(callback);Job best=null;double distance=Double.POSITIVE_INFINITY;
        for(Job job:jobs.values()){
            var c=job.component();if(c.team()!=tower.team())continue;
            double dx=LayerCoordinates.center(c.x(),c.size())-tower.x(),dy=LayerCoordinates.center(c.y(),c.size())-tower.y(),dz=coordinates.plane(c.layer())-tower.z();
            double next=dx*dx+dy*dy+dz*dz;if(next>tower.range()*tower.range()||next>=distance||!callback.canPlace(c))continue;
            best=job;distance=next;
        }return best;
    }
    public Work update(Tower tower,Callback callback){
        Job job=target(tower,callback);if(job==null)return null;
        if(tower.work()==0)return new Work(job.component(),job.progress(),false);
        double duration=callback.buildTime(job.component());if(!Double.isFinite(duration)||duration<=0)duration=60;
        double progress=Math.min(1,job.progress()+tower.work()/duration);boolean complete=progress>=1&&callback.complete(job.component());
        Cell key=cell(job.component());
        if(jobs.get(key)==job){if(complete){jobs.remove(key);bytes-=budget(job);}else jobs.put(key,new Job(job.component(),progress));revision++;}
        return new Work(job.component(),progress,complete);
    }
    public void write(DataOutput out)throws IOException{
        out.writeInt(VERSION);out.writeInt(jobs.size());
        for(Job job:jobs.values()){
            LayerComponent c=job.component();out.writeLong(c.id());out.writeByte(c.kind().ordinal());out.writeInt(c.x());out.writeInt(c.y());out.writeShort(c.layer());out.writeUTF(c.blockName());out.writeByte(c.size());out.writeByte(c.rotation());out.writeByte(c.team());out.writeByte(c.mode().ordinal());
            out.writeDouble(c.maxHealth());out.writeDouble(c.mass());out.writeDouble(c.height());out.writeShort(c.support().span());out.writeDouble(c.support().capacity());byte[] config=c.configuration();out.writeInt(config.length);out.write(config);out.writeDouble(job.progress());
        }
    }
    /** Reads into an isolated queue; the caller publishes it only after the entire world payload validates. */
    public static LayerRebuildQueue read(DataInput in,LayerCoordinates coordinates)throws IOException{
        try{
            if(in.readInt()!=VERSION)throw new IOException("Unsupported upper rebuild queue");int count=in.readInt();if(count<0||count>MAX_JOBS)throw new IOException("Rebuild job limit");
            LayerRebuildQueue result=new LayerRebuildQueue(coordinates);
            for(int i=0;i<count;i++){
                long id=in.readLong();int kind=in.readUnsignedByte(),x=in.readInt(),y=in.readInt(),layer=in.readUnsignedShort();String block=in.readUTF();int size=in.readUnsignedByte(),rotation=in.readUnsignedByte(),team=in.readUnsignedByte(),mode=in.readUnsignedByte();
                if(kind>=LayerComponent.Kind.values().length||mode>=LayerComponent.Mode.values().length)throw new IOException("Invalid rebuild kind/mode");
                double health=in.readDouble(),mass=in.readDouble(),height=in.readDouble();int span=in.readUnsignedShort();double capacity=in.readDouble();int length=in.readInt();
                if(length<0||length>LayerComponent.MAX_CONFIG_BYTES||result.bytes+length+160L+block.length()*3L>MAX_BYTES)throw new IOException("Rebuild configuration limit");byte[] config=new byte[length];in.readFully(config);double progress=in.readDouble();
                if(!Double.isFinite(progress)||progress<0||progress>1)throw new IOException("Invalid rebuild progress");
                var component=new LayerComponent(id,LayerComponent.Kind.values()[kind],x,y,layer,block,size,rotation,team,LayerComponent.Mode.values()[mode],health,health,mass,height,new LayerComponent.SupportProfile(span,capacity),config,null);
                if(result.jobs.containsKey(cell(component))||!result.remember(component))throw new IOException("Duplicate or excessive rebuild job");result.jobs.put(cell(component),new Job(component,progress));
            }result.revision=0;return result;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid upper rebuild queue",invalid);}
    }
    private static Cell cell(LayerComponent c){return new Cell(c.x(),c.y(),c.layer(),c.kind());}
    private static int budget(Job job){return job==null?0:budget(job.component());}
    private static int budget(LayerComponent c){return 160+c.blockName().length()*3+c.configuration().length;}
}
