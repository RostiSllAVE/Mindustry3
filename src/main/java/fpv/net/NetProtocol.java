package fpv.net;

import fpv.flight.FlightController;
import fpv.world.Unit3DState;
import fpv.generated.NetworkConfig;
import java.io.*;
import java.util.*;

/** Bounded wire values only. Deserialization never creates native entities or accepts client damage. */
public final class NetProtocol {
    private NetProtocol(){}
    public static final int MAGIC=0x46505637;
    public static final byte HELLO=1,ACK=2,INPUT=3,STATE=4,CHANNEL=5;
    public record Envelope(byte kind,long epoch,long sequence,byte[] payload){}
    public record Flight(int playerId,int unitId,FlightController.Profile profile,FlightController.State state){}
    public record Snapshot(List<Unit3DState> units,Flight local){}
    public record Chunk(String channel,long revision,int index,int count,int total,byte[] data){}
    @FunctionalInterface public interface Writer{void write(DataOutputStream out)throws IOException;}
    @FunctionalInterface public interface Reader<T>{T read(DataInputStream in)throws IOException;}
    public static byte[] encode(byte kind,long epoch,long sequence,Writer writer){
        return bytes(out->{out.writeInt(MAGIC);out.writeShort((int)NetworkConfig.PROTOCOL_VERSION);out.writeByte(kind);out.writeLong(epoch);out.writeLong(sequence);writer.write(out);});
    }
    public static Envelope decode(byte[] data){
        return read(data,in->{if(in.readInt()!=MAGIC||in.readUnsignedShort()!=(int)NetworkConfig.PROTOCOL_VERSION)throw new IOException("Protocol mismatch");
            byte kind=in.readByte();if(kind<HELLO||kind>CHANNEL)throw new IOException("Packet kind");
            long epoch=in.readLong(),sequence=in.readLong();if(sequence<0)throw new IOException("Sequence");
            return new Envelope(kind,epoch,sequence,in.readAllBytes());});
    }
    public static byte[] bytes(Writer writer){
        try{var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){writer.write(out);}
            if(buffer.size()>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Message too large");return buffer.toByteArray();
        }catch(IOException error){throw new IllegalArgumentException("Cannot encode packet",error);}
    }
    public static <T>T read(byte[] bytes,Reader<T> reader){
        if(bytes==null||bytes.length>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Packet size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){T value=reader.read(in);if(in.available()!=0)throw new IOException("Trailing bytes");return value;
        }catch(IOException|RuntimeException error){throw new IllegalArgumentException("Invalid FPV packet",error);}
    }
    public static void controls(DataOutputStream out,NetworkControls c)throws IOException{
        out.writeBoolean(c.active());out.writeByte(c.mode());
        for(double value:new double[]{c.throttle(),c.pitch(),c.roll(),c.yaw(),c.forward(),c.right(),c.climb(),c.turn(),c.headingRadians(),c.aimX(),c.aimY(),c.aimZ()})out.writeDouble(value);
        out.writeBoolean(c.shooting());out.writeBoolean(c.creatorFlight());out.writeBoolean(c.boostFlight());
    }
    public static NetworkControls controls(DataInputStream in)throws IOException{
        return new NetworkControls(in.readBoolean(),in.readUnsignedByte(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readBoolean(),in.readBoolean(),in.readBoolean());
    }
    public static byte[] snapshot(Snapshot snapshot){return bytes(out->{
        if(snapshot.units.size()>NetworkConfig.MAX_UNIT_STATES)throw new IOException("Too many units");out.writeInt(snapshot.units.size());
        for(Unit3DState s:snapshot.units){out.writeInt(s.unitId());out.writeInt(s.typeId());out.writeByte(s.teamId());
            for(double n:new double[]{s.x(),s.y(),s.z(),s.vx(),s.vy(),s.vz(),s.yaw(),s.pitch(),s.roll(),s.desiredAltitude(),s.radius(),s.halfHeight()})out.writeDouble(n);out.writeBoolean(s.flying());}
        out.writeBoolean(snapshot.local!=null);if(snapshot.local!=null){Flight f=snapshot.local;out.writeInt(f.playerId);out.writeInt(f.unitId);profile(out,f.profile);state(out,f.state);}
    });}
    public static Snapshot snapshot(byte[] bytes){return read(bytes,in->{
        int count=in.readInt();if(count<0||count>NetworkConfig.MAX_UNIT_STATES)throw new IOException("Unit count");var units=new ArrayList<Unit3DState>(count);var ids=new HashSet<Integer>();
        for(int i=0;i<count;i++){int id=in.readInt(),type=in.readInt(),team=in.readUnsignedByte();if(!ids.add(id))throw new IOException("Duplicate unit");
            units.add(new Unit3DState(id,type,team,number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),in.readBoolean()));}
        Flight local=in.readBoolean()?new Flight(in.readInt(),in.readInt(),profile(in),state(in)):null;
        return new Snapshot(List.copyOf(units),local);
    });}
    public static void profile(DataOutputStream out,FlightController.Profile p)throws IOException{
        for(double v:new double[]{p.metersPerTile,p.massKg,p.inertiaXKgM2,p.inertiaYKgM2,p.inertiaZKgM2,p.maxThrustNewtons,p.gravityMps2,p.linearDragPerSecond,p.quadraticDragPerMeter,p.angularDragPerSecond,p.motorTimeConstantSeconds,p.rateResponseSeconds,p.maxTorqueNewtonMeters,p.pitchRateDegreesPerSecond,p.rollRateDegreesPerSecond,p.yawRateDegreesPerSecond,p.rateExpo,p.fixedStepSeconds,p.maxFrameSeconds})out.writeDouble(v);
    }
    public static FlightController.Profile profile(DataInputStream in)throws IOException{return new FlightController.Profile(number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in),number(in));}
    public static void state(DataOutputStream out,FlightController.State s)throws IOException{for(double v:new double[]{s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz})out.writeDouble(v);}
    public static FlightController.State state(DataInputStream in)throws IOException{
        FlightController.State s=new FlightController.State();s.x=number(in);s.y=number(in);s.z=number(in);s.vx=number(in);s.vy=number(in);s.vz=number(in);s.qx=number(in);s.qy=number(in);s.qz=number(in);s.qw=number(in);s.wx=number(in);s.wy=number(in);s.wz=number(in);
        double norm=s.qx*s.qx+s.qy*s.qy+s.qz*s.qz+s.qw*s.qw;if(norm<.9||norm>1.1)throw new IOException("Quaternion norm");return s;
    }
    public static void copy(FlightController.State a,FlightController.State b){b.x=a.x;b.y=a.y;b.z=a.z;b.vx=a.vx;b.vy=a.vy;b.vz=a.vz;b.qx=a.qx;b.qy=a.qy;b.qz=a.qz;b.qw=a.qw;b.wx=a.wx;b.wy=a.wy;b.wz=a.wz;}
    public static double number(DataInputStream in)throws IOException{double n=in.readDouble();if(!Double.isFinite(n)||Math.abs(n)>1e12)throw new IOException("Nonfinite or unbounded value");return n;}
    public static void chunk(DataOutputStream out,Chunk c)throws IOException{out.writeUTF(c.channel);out.writeLong(c.revision);out.writeInt(c.index);out.writeInt(c.count);out.writeInt(c.total);out.writeInt(c.data.length);out.write(c.data);}
    public static Chunk chunk(byte[] data){return read(data,in->{String name=in.readUTF();long rev=in.readLong();int index=in.readInt(),count=in.readInt(),total=in.readInt(),length=in.readInt();
        if(!name.matches("[a-z0-9-]{1,32}")||rev<0||total<0||total>NetworkConfig.MAX_MESSAGE_BYTES||count<1||count>(int)Math.ceil(NetworkConfig.MAX_MESSAGE_BYTES/NetworkConfig.CHUNK_BYTES)||index<0||index>=count||length<0||length>NetworkConfig.CHUNK_BYTES||length>in.available()||count!=Math.max(1,(int)Math.ceil(total/NetworkConfig.CHUNK_BYTES)))throw new IOException("Chunk bounds");
        if(length!=Math.min((int)NetworkConfig.CHUNK_BYTES,total-index*(int)NetworkConfig.CHUNK_BYTES))throw new IOException("Chunk length");return new Chunk(name,rev,index,count,total,in.readNBytes(length));});}
    /** One bounded in-flight full snapshot per channel. Apply only after all chunks arrive. */
    public static final class Assembly{
        private long revision=-1,completed=-1;private byte[][] parts;private int remaining,total;private double started;
        public byte[] accept(Chunk c,double now){
            if(c.revision<=completed||c.revision<revision)return null;
            if(c.revision!=revision||parts==null||now-started>5){revision=c.revision;parts=new byte[c.count][];remaining=c.count;total=c.total;started=now;}
            if(parts.length!=c.count||total!=c.total)throw new IllegalArgumentException("Chunk conflict");
            if(parts[c.index]==null){parts[c.index]=c.data.clone();remaining--;}
            if(remaining!=0)return null;byte[] result=new byte[total];int at=0;for(byte[] part:parts){System.arraycopy(part,0,result,at,part.length);at+=part.length;}completed=revision;parts=null;return result;
        }
    }
}
