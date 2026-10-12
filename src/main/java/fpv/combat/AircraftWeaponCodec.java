package fpv.combat;

import java.io.*;
import java.util.*;

/** Existing weapon-state channel envelope. Ground/naval pitch codec remains untouched. */
public final class AircraftWeaponCodec {
    public static final int MAX_UNITS=1024;
    public record Limits(int fixedPitch,int gimbalPitch,int hysteresis){
        public static final Limits DEFAULT=new Limits(35,45,5);
        public Limits{if(fixedPitch<5||fixedPitch>60||gimbalPitch<5||gimbalPitch>60||hysteresis<0||hysteresis>15)throw new IllegalArgumentException("Aircraft limits");}
    }
    public record Entry(int id,int type,int team,float[] yaw,float[] pitch,byte[] side){
        public Entry{
            if(id<0||type<0||team<0||team>255||yaw==null||pitch==null||side==null||yaw.length<1||yaw.length>32||pitch.length!=yaw.length||side.length!=yaw.length)throw new IllegalArgumentException("Aircraft snapshot identity");
            yaw=yaw.clone();pitch=pitch.clone();side=side.clone();for(int i=0;i<yaw.length;i++)if(!Float.isFinite(yaw[i]+pitch[i])||Math.abs(yaw[i])>180||Math.abs(pitch[i])>90||Math.abs(side[i])>1)throw new IllegalArgumentException("Aircraft snapshot angles");
        }
        @Override public float[] yaw(){return yaw.clone();}
        @Override public float[] pitch(){return pitch.clone();}
        @Override public byte[] side(){return side.clone();}
        public int count(){return yaw.length;}
        public float yaw(int mount){return yaw[mount];}
        public float pitch(int mount){return pitch[mount];}
        public byte side(int mount){return side[mount];}
    }
    public record Snapshot(List<MechWeaponPitchCodec.Entry> existing,List<Entry> aircraft,Limits limits){}
    public static byte[] encode(List<MechWeaponPitchCodec.Entry> existing,List<Entry> aircraft){
        return encode(existing,aircraft,Limits.DEFAULT);
    }
    public static byte[] encode(List<MechWeaponPitchCodec.Entry> existing,List<Entry> aircraft,Limits limits){
        if(aircraft.size()>MAX_UNITS)throw new IllegalArgumentException("Aircraft snapshot count");
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);var old=MechWeaponPitchCodec.encode(existing);out.writeInt(2);out.writeByte(limits.fixedPitch());out.writeByte(limits.gimbalPitch());out.writeByte(limits.hysteresis());out.writeInt(old.length);out.write(old);out.writeInt(aircraft.size());var ids=new HashSet<Integer>();
            for(var e:aircraft){if(!ids.add(e.id()))throw new IllegalArgumentException("Duplicate aircraft");out.writeInt(e.id());out.writeInt(e.type());out.writeByte(e.team());out.writeByte(e.count());for(int i=0;i<e.count();i++){out.writeFloat(e.yaw(i));out.writeFloat(e.pitch(i));out.writeByte(e.side(i));}}return bytes.toByteArray();
        }catch(IOException e){throw new IllegalArgumentException(e);}
    }
    public static Snapshot decode(byte[] bytes){
        if(bytes==null||bytes.length>MechWeaponPitchCodec.MAX_BYTES+MAX_UNITS*302+15)throw new IllegalArgumentException("Aircraft state size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=2)throw new IOException("Weapon state envelope");var limits=new Limits(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte());int size=in.readInt();if(size<8||size>MechWeaponPitchCodec.MAX_BYTES||size>in.available())throw new IOException("Pitch size");var old=MechWeaponPitchCodec.decode(in.readNBytes(size));int n=in.readInt();if(n<0||n>MAX_UNITS)throw new IOException("Aircraft count");var entries=new ArrayList<Entry>();var ids=new HashSet<Integer>();
            for(int a=0;a<n;a++){int id=in.readInt(),type=in.readInt(),team=in.readUnsignedByte(),count=in.readUnsignedByte();if(count<1||count>32||!ids.add(id))throw new IOException("Aircraft mount count");float[] y=new float[count],p=new float[count];byte[] s=new byte[count];for(int m=0;m<count;m++){y[m]=in.readFloat();p[m]=in.readFloat();s[m]=in.readByte();}entries.add(new Entry(id,type,team,y,p,s));}if(in.available()!=0)throw new IOException("Trailing aircraft data");return new Snapshot(old,List.copyOf(entries),limits);
        }catch(IOException e){throw new IllegalArgumentException("Aircraft state packet",e);}
    }
}
