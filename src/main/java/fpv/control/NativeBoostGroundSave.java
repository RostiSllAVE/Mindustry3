package fpv.control;

import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.io.SaveVersion;
import mindustry.io.SaveFileReader;
import java.io.*;
import java.util.*;

/** Only post-boost physical support height, never resources, health or shared definitions. */
public final class NativeBoostGroundSave {
    private NativeBoostGroundSave(){}
    public record Entry(int id,String type,int team,double x,double y,double z){
        public Entry{if(type==null||type.isBlank()||type.length()>128||team<0||team>255||!Double.isFinite(x+y+z)||Math.abs(x)>100000||Math.abs(y)>100000||z<0||z>100000)throw new IllegalArgumentException("Invalid boost support save");}
    }
    public static void write(DataOutput out,List<Entry> values)throws IOException{
        if(values.size()>16384)throw new IOException("Boost support count");out.writeInt(1);out.writeInt(values.size());
        for(var v:values){out.writeInt(v.id());out.writeUTF(v.type());out.writeByte(v.team());out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());}
    }
    public static List<Entry> read(DataInput in)throws IOException{
        if(in.readInt()!=1)throw new IOException("Boost support version");int count=in.readInt();if(count<0||count>16384)throw new IOException("Boost support count");
        var result=new ArrayList<Entry>(count);var ids=new HashSet<Integer>();
        for(int i=0;i<count;i++)try{var v=new Entry(in.readInt(),in.readUTF(),in.readUnsignedByte(),in.readDouble(),in.readDouble(),in.readDouble());if(!ids.add(v.id()))throw new IOException("Duplicate boost support identity");result.add(v);}catch(IllegalArgumentException bad){throw new IOException("Invalid boost support",bad);}
        return List.copyOf(result);
    }
    static void install(UnitAltitudeSystem alt){SaveVersion.addCustomChunk("fpv-native-boost-ground",new SaveFileReader.CustomChunk(){
        public boolean shouldWrite(){return !alt.boostGroundSnapshot().isEmpty();}public boolean writeNet(){return false;}
        public void write(DataOutput out)throws IOException{NativeBoostGroundSave.write(out,alt.boostGroundSnapshot());}
        public void read(DataInput in)throws IOException{
            var values=NativeBoostGroundSave.read(in);if(Vars.net.client())return;
            for(var v:values){var u=Groups.unit.getByID(v.id());if(!UnitControlCapabilities.boostEligible(u)||!u.type.name.equals(v.type())||u.team.id!=v.team()||Math.hypot(u.x/(double)Vars.tilesize-v.x(),u.y/(double)Vars.tilesize-v.y())>.05)continue;u.elevation=0;alt.seedGroundFromBoost(u,v.z());}
        }
        public void read(DataInput in,int length)throws IOException{if(length<8||length>8*1024*1024)throw new IOException("Boost support chunk length");read(in);}
    });}
}
