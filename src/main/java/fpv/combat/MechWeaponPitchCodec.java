package fpv.combat;

import java.io.*;
import java.util.*;

/** Server-to-observer visual state only. It never accepts a client hit, launch or damage decision. */
public final class MechWeaponPitchCodec {
    public static final int MAX_UNITS=4096,MAX_MOUNTS=32,MAX_BYTES=8+MAX_UNITS*(13+4*MAX_MOUNTS);
    public record Entry(int unitId,int typeId,int teamId,float[] pitches){
        public Entry{
            if(unitId<0||typeId<0||teamId<0||teamId>255||pitches==null||pitches.length<1||pitches.length>MAX_MOUNTS)throw new IllegalArgumentException("Mech pitch identity/count");
            pitches=pitches.clone();for(float pitch:pitches)if(!Float.isFinite(pitch)||pitch<MechWeaponPitch.MIN||pitch>MechWeaponPitch.MAX)throw new IllegalArgumentException("Mech pitch limits");
        }
        @Override public float[] pitches(){return pitches.clone();}
        public int count(){return pitches.length;}
        public float pitch(int mount){return pitches[mount];}
    }
    private MechWeaponPitchCodec(){}
    public static byte[] encode(List<Entry> entries){
        if(entries==null||entries.size()>MAX_UNITS)throw new IllegalArgumentException("Mech pitch count");
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeInt(entries.size());var ids=new HashSet<Integer>();
            for(var entry:entries){if(entry==null||!ids.add(entry.unitId()))throw new IllegalArgumentException("Duplicate mech pitch identity");out.writeInt(entry.unitId());out.writeInt(entry.typeId());out.writeInt(entry.teamId());out.writeByte(entry.count());for(int m=0;m<entry.count();m++)out.writeFloat(entry.pitch(m));}
            out.flush();return bytes.toByteArray();
        }catch(IOException error){throw new IllegalArgumentException("Mech pitch encoding",error);}
    }
    public static List<Entry> decode(byte[] bytes){
        if(bytes==null||bytes.length<8||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Mech pitch payload length");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=1)throw new IOException("Mech pitch version");int count=in.readInt();if(count<0||count>MAX_UNITS)throw new IOException("Mech pitch count");
            var entries=new ArrayList<Entry>(count);var ids=new HashSet<Integer>();
            for(int i=0;i<count;i++){int id=in.readInt(),type=in.readInt(),team=in.readInt(),mounts=in.readUnsignedByte();if(mounts<1||mounts>MAX_MOUNTS||!ids.add(id))throw new IOException("Mech pitch mounts/identity");float[] pitches=new float[mounts];for(int m=0;m<mounts;m++)pitches[m]=in.readFloat();entries.add(new Entry(id,type,team,pitches));}
            if(in.available()!=0)throw new IOException("Trailing mech pitch bytes");return List.copyOf(entries);
        }catch(IOException error){throw new IllegalArgumentException("Invalid mech pitch snapshot",error);}
    }
}
