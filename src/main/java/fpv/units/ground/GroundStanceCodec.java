package fpv.units.ground;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Small bounded save payload independent of game entities and current camera/control mode. */
public final class GroundStanceCodec {
    public static final int VERSION=1,MAX_UNITS=32768,MAX_BYTES=6*1024*1024;
    public record Entry(int unitId,String typeName,int teamId,double x,double y,double offset,double desired,double clearance,boolean manual){
        public Entry{
            if(typeName==null||typeName.isBlank()||typeName.length()>128||teamId<0||teamId>255||
                !Double.isFinite(x+y+offset+desired+clearance)||Math.abs(x)>100000||Math.abs(y)>100000||
                offset<1||offset>3||desired<0||desired>1024||clearance<0||clearance>4096)
                throw new IllegalArgumentException("Invalid saved walker stance");
        }
    }
    private GroundStanceCodec(){}
    public static void write(DataOutput out,List<Entry> entries)throws IOException{
        if(entries==null||entries.size()>MAX_UNITS)throw new IllegalArgumentException("Bounded stance entry count required");
        out.writeInt(VERSION);out.writeInt(entries.size());
        for(Entry e:entries){out.writeInt(e.unitId);out.writeUTF(e.typeName);out.writeByte(e.teamId);
            out.writeDouble(e.x);out.writeDouble(e.y);out.writeDouble(e.offset);out.writeDouble(e.desired);out.writeDouble(e.clearance);out.writeBoolean(e.manual);}
    }
    public static List<Entry> read(DataInput in)throws IOException{
        int version=in.readInt(),count=in.readInt();
        if(version!=VERSION||count<0||count>MAX_UNITS)throw new IOException("Invalid walker stance chunk version/count");
        ArrayList<Entry> entries=new ArrayList<>(count);java.util.HashSet<Integer> ids=new java.util.HashSet<>();
        for(int i=0;i<count;i++)try{
            Entry e=new Entry(in.readInt(),in.readUTF(),in.readUnsignedByte(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readBoolean());
            if(!ids.add(e.unitId))throw new IOException("Duplicate walker stance id");entries.add(e);
        }catch(IllegalArgumentException bad){throw new IOException("Invalid walker stance entry",bad);}
        return List.copyOf(entries);
    }
    public static byte[] encode(List<Entry> entries){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();write(new DataOutputStream(bytes),entries);return bytes.toByteArray();}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    public static List<Entry> decode(byte[] bytes){
        if(bytes==null||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Bounded walker stance data required");
        try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));var result=read(in);if(in.available()!=0)throw new IOException("Trailing walker stance data");return result;}
        catch(IOException bad){throw new IllegalArgumentException("Invalid walker stance data",bad);}
    }
}
