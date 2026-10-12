package fpv.net;

import fpv.defense.ProjectedWallSystem;
import fpv.defense.ProjectionSnapshot;
import fpv.generated.DefenseConfig;
import fpv.generated.NetworkConfig;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Sparse, compressed authoritative pools with an explicit settings header and bounded expansion. */
public final class ProjectionCodec {
    private ProjectionCodec(){}
    public static byte[] encode(ProjectedWallSystem.Replication value,int mapTiles){
        int max=maxRows(mapTiles);if(value.damaged().size()>max)throw new IllegalArgumentException("Damaged projection count exceeds map/config bound");
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){
            out.writeInt(2);out.writeLong(value.epoch());var s=value.settings();out.writeDouble(s.healthMultiplier());out.writeInt(s.segments());out.writeDouble(s.regenDelay());out.writeDouble(s.regenRate());out.writeBoolean(s.blocksUnits());out.writeDouble(s.legStepMultiplier());out.writeInt(value.damaged().size());
            for(var p:value.damaged()){out.writeInt(p.anchorPos());out.writeInt(p.blockId());out.writeByte(p.teamId());out.writeFloat(p.hp());out.writeFloat(p.maxHp());out.writeFloat(p.cooldown());out.writeFloat(p.baseZ());out.writeFloat(p.topZ());out.writeLong(p.revision());}
        }if(bytes.size()>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Compressed projection state exceeds transport bound");return bytes.toByteArray();
        }catch(IOException error){throw new IllegalArgumentException("Projection encoding failed",error);}
    }
    public static ProjectedWallSystem.Replication decode(byte[] bytes,int mapTiles){
        if(bytes==null||bytes.length>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalArgumentException("Projection packet size");
        int max=maxRows(mapTiles);
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes));var in=new DataInputStream(gzip)){
            if(in.readInt()!=2)throw new IOException("Projection schema");long epoch=in.readLong();
            var settings=new ProjectedWallSystem.Settings(in.readDouble(),in.readInt(),in.readDouble(),in.readDouble(),in.readBoolean(),in.readDouble());
            int count=in.readInt();if(count<0||count>max)throw new IOException("Projection count");var rows=new ArrayList<ProjectionSnapshot>(count);var seen=new HashSet<Integer>();
            // Read only count fixed-size rows, then one byte. A gzip expansion bomb cannot request further allocation.
            for(int i=0;i<count;i++){int anchor=in.readInt(),block=in.readInt(),team=in.readUnsignedByte();float hp=in.readFloat(),maxHp=in.readFloat(),cooldown=in.readFloat(),base=in.readFloat(),top=in.readFloat();long revision=in.readLong();
                if(!seen.add(anchor)||!Float.isFinite(hp+maxHp+cooldown+base+top)||hp<0||maxHp<=0||hp>maxHp||cooldown<0||top<base||revision<0)throw new IOException("Projection row");
                rows.add(new ProjectionSnapshot(anchor,block,team,hp,maxHp,cooldown,base,top,revision));}
            if(in.read()!=-1)throw new IOException("Trailing projection expansion");return new ProjectedWallSystem.Replication(settings,rows,epoch);
        }catch(IOException|RuntimeException error){throw new IllegalArgumentException("Invalid compressed projection state",error);}
    }
    private static int maxRows(int mapTiles){if(mapTiles<0)throw new IllegalArgumentException("Map size");return Math.min(mapTiles,(int)DefenseConfig.MAX_SNAPSHOT_ENTRIES);}
}
