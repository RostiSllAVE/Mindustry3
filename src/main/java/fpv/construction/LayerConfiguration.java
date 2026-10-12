package fpv.construction;

import java.io.*;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import arc.math.geom.Point2;

/** Explicit bounded native configuration values; never Java object deserialization. */
public final class LayerConfiguration {
    public static final int MAX_BYTES=16384;
    private LayerConfiguration(){}
    public static byte[] encode(Object value){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            if(value==null)out.writeByte(0);
            else if(value instanceof Boolean v){out.writeByte(1);out.writeBoolean(v);}
            else if(value instanceof Integer v){out.writeByte(2);out.writeInt(v);}
            else if(value instanceof String v){if(v.length()>4096)throw new IllegalArgumentException("Configuration text is too long");out.writeByte(3);out.writeUTF(v);}
            else if(value instanceof Point2 v){if(!validOffset(v))throw new IllegalArgumentException("Configuration link offset exceeds map coordinates");out.writeByte(4);out.writeInt(v.x);out.writeInt(v.y);}
            else if(value instanceof UnlockableContent v){out.writeByte(5);out.writeByte(v.getContentType().ordinal());out.writeUTF(v.name);}
            else if(value instanceof mindustry.ai.UnitCommand v){out.writeByte(6);out.writeUTF(v.name);}
            else if(value instanceof byte[] v){if(v.length>12288)throw new IllegalArgumentException("Compressed configuration limit");out.writeByte(7);out.writeShort(v.length);out.write(v);}
            else if(value instanceof int[] v){if(v.length>32)throw new IllegalArgumentException("Integer configuration limit");out.writeByte(8);out.writeByte(v.length);for(int number:v)out.writeInt(number);}
            else throw new IllegalArgumentException("Unsupported native configuration value");
            if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("Configuration is too large");return bytes.toByteArray();
        }catch(IOException bad){throw new IllegalArgumentException("Invalid configuration",bad);}
    }
    public static Object decode(byte[] bytes){
        if(bytes==null||bytes.length<1||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Invalid configuration size");
        try{var in=new DataInputStream(new ByteArrayInputStream(bytes));Object value=switch(in.readUnsignedByte()){
            case 0->null;case 1->in.readBoolean();case 2->in.readInt();case 3->{String s=in.readUTF();if(s.length()>4096)throw new IOException("Text limit");yield s;}
            case 4->{Point2 point=new Point2(in.readInt(),in.readInt());if(!validOffset(point))throw new IOException("Link offset exceeds map coordinates");yield point;}
            case 5->{int type=in.readUnsignedByte();if(type>=ContentType.values().length)throw new IOException("Content type");var t=ContentType.values()[type];if(t!=ContentType.item&&t!=ContentType.liquid&&t!=ContentType.block&&t!=ContentType.unit)throw new IOException("Configuration content type");var content=Vars.content.getByName(t,in.readUTF());if(content==null)throw new IOException("Unknown content");yield content;}
            case 6->{String name=in.readUTF();Object command=Vars.content.getByName(ContentType.unitCommand,name);if(!(command instanceof mindustry.ai.UnitCommand))throw new IOException("Unknown command");yield command;}
            case 7->{int length=in.readUnsignedShort();if(length>12288)throw new IOException("Compressed configuration limit");byte[] data=new byte[length];in.readFully(data);yield data;}
            case 8->{int length=in.readUnsignedByte();if(length>32)throw new IOException("Integer configuration limit");int[] data=new int[length];for(int i=0;i<length;i++)data[i]=in.readInt();yield data;}
            default->throw new IOException("Unknown configuration type");};
            if(in.available()!=0)throw new IOException("Trailing configuration data");return value;
        }catch(IOException bad){throw new IllegalArgumentException("Invalid configuration",bad);}
    }
    /** Native packed links use signed 16-bit coordinates; reject offsets before narrowing can alias another tile. */
    public static boolean validOffset(Point2 point){return point!=null&&point.x>=-32767&&point.x<=32767&&point.y>=-32767&&point.y<=32767;}
}
