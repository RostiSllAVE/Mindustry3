package fpv.defense;

import fpv.generated.ForceConfig;
import fpv.generated.NetworkConfig;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Complete native shield state, bounded by both map size and compressed transport limits. */
public final class ForceProjectorCodec {
    private ForceProjectorCodec(){}
    public static byte[] encode(List<ForceProjectorSystem.Snapshot> state,int mapTiles){
        ForceProjectorSystem.validate(state,maxRows(mapTiles));
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){
            out.writeInt(1);out.writeInt(state.size());
            for(var s:state){out.writeInt(s.anchorPos());out.writeInt(s.blockId());out.writeByte(s.teamId());out.writeBoolean(s.broken());out.writeFloat(s.buildup());out.writeFloat(s.radscl());out.writeFloat(s.hit());out.writeFloat(s.warmup());out.writeFloat(s.phaseHeat());}
        }if(bytes.size()>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Force state exceeds transport bound");return bytes.toByteArray();
        }catch(IOException e){throw new IllegalArgumentException("Force encoding failed",e);}
    }
    public static List<ForceProjectorSystem.Snapshot> decode(byte[] bytes,int mapTiles){
        if(bytes==null||bytes.length>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Force packet size");int max=maxRows(mapTiles);
        try(var in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))){
            if(in.readInt()!=1)throw new IOException("Force schema");int count=in.readInt();if(count<0||count>max)throw new IOException("Force count");
            var out=new ArrayList<ForceProjectorSystem.Snapshot>(count);
            // Fixed-size records followed by exactly one read prevent unbounded decompression.
            for(int i=0;i<count;i++)out.add(new ForceProjectorSystem.Snapshot(in.readInt(),in.readInt(),in.readUnsignedByte(),in.readBoolean(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
            if(in.read()!=-1)throw new IOException("Trailing force bytes");ForceProjectorSystem.validate(out,max);return List.copyOf(out);
        }catch(IOException|RuntimeException e){throw new IllegalArgumentException("Invalid force state",e);}
    }
    private static int maxRows(int n){if(n<0)throw new IllegalArgumentException("Map size");return Math.min(n,(int)ForceConfig.MAX_SNAPSHOT_ENTRIES);}
}
