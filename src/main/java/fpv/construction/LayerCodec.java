package fpv.construction;

import java.io.*;
import java.util.*;

/** Versioned upper-world payload. Native Z0 world/save data is deliberately absent. */
public final class LayerCodec {
    public static final int VERSION=1,MAX_BYTES=32*1024*1024;
    private static final int MAGIC=0x4650564c;
    private LayerCodec(){}
    public static void write(DataOutput out,LayerWorld world)throws IOException{
        Objects.requireNonNull(out);var snapshot=Objects.requireNonNull(world).snapshot();
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeDouble(snapshot.coordinates().spacing());out.writeInt(snapshot.coordinates().maxLayers());out.writeLong(snapshot.nextId());out.writeInt(snapshot.components().size());
        long total=32;for(var c:snapshot.components()){
            byte[] configuration=c.configuration(),state=c.state();total+=160L+c.blockName().length()*3L+configuration.length+state.length;if(total>MAX_BYTES)throw new IOException("Layer save exceeds size bound");
            out.writeLong(c.id());out.writeByte(c.kind().ordinal());out.writeInt(c.x());out.writeInt(c.y());out.writeShort(c.layer());out.writeUTF(c.blockName());out.writeByte(c.size());out.writeByte(c.rotation());out.writeByte(c.team());out.writeByte(c.mode().ordinal());
            out.writeDouble(c.health());out.writeDouble(c.maxHealth());out.writeDouble(c.mass());out.writeDouble(c.height());out.writeShort(c.support().span());out.writeDouble(c.support().capacity());writeBytes(out,configuration);writeBytes(out,state);
        }
    }
    public static LayerWorld read(DataInput in)throws IOException{
        try{
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported elevated-world save version");
            LayerCoordinates coordinates=new LayerCoordinates(in.readDouble(),in.readInt());long nextId=in.readLong();int count=in.readInt();
            if(count<0||count>LayerWorld.MAX_COMPONENTS)throw new IOException("Elevated-world component count exceeds bound");
            ArrayList<LayerComponent> components=new ArrayList<>(count);long total=32;
            for(int i=0;i<count;i++){
                long id=in.readLong();int kind=in.readUnsignedByte(),x=in.readInt(),y=in.readInt(),layer=in.readUnsignedShort();String block=in.readUTF();
                int size=in.readUnsignedByte(),rotation=in.readUnsignedByte(),team=in.readUnsignedByte(),mode=in.readUnsignedByte();
                if(kind>=LayerComponent.Kind.values().length||mode>=LayerComponent.Mode.values().length)throw new IOException("Unknown elevated component kind/mode");
                double health=in.readDouble(),maxHealth=in.readDouble(),mass=in.readDouble(),height=in.readDouble();int span=in.readUnsignedShort();double capacity=in.readDouble();
                byte[] configuration=readBytes(in,LayerComponent.MAX_CONFIG_BYTES),state=readBytes(in,LayerComponent.MAX_STATE_BYTES);
                total+=160L+block.length()*3L+configuration.length+state.length;if(total>MAX_BYTES)throw new IOException("Layer save exceeds size bound");
                components.add(new LayerComponent(id,LayerComponent.Kind.values()[kind],x,y,layer,block,size,rotation,team,LayerComponent.Mode.values()[mode],health,maxHealth,mass,height,new LayerComponent.SupportProfile(span,capacity),configuration,state));
            }
            LayerWorld world=new LayerWorld(coordinates);world.apply(new LayerWorld.Snapshot(coordinates,nextId,components));return world;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid elevated-world save",invalid);}
    }
    private static void writeBytes(DataOutput out,byte[] data)throws IOException{out.writeInt(data.length);out.write(data);}
    private static byte[] readBytes(DataInput in,int maximum)throws IOException{int length=in.readInt();if(length<0||length>maximum)throw new IOException("Elevated component data exceeds bound");byte[] bytes=new byte[length];in.readFully(bytes);return bytes;}
    public static byte[] encode(LayerWorld world){try{var bytes=new ByteArrayOutputStream();write(new DataOutputStream(bytes),world);return bytes.toByteArray();}catch(IOException bad){throw new IllegalArgumentException("Cannot encode elevated world",bad);}}
    public static LayerWorld decode(byte[] bytes){
        if(bytes==null||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Bounded elevated-world data required");
        try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));LayerWorld world=read(in);if(in.available()!=0)throw new IOException("Trailing elevated-world data");return world;}
        catch(IOException bad){throw new IllegalArgumentException("Invalid elevated-world data",bad);}
    }
}
