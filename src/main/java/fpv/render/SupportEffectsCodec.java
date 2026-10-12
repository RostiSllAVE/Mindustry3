package fpv.render;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Fixed-size, count-bounded payload; transport is already chunked by the matching-mod protocol. */
public final class SupportEffectsCodec {
    private static final int RECORD_BYTES=44,MAX_BYTES=8+SupportEffectsState.MAX_EFFECTS*RECORD_BYTES;
    private SupportEffectsCodec(){}
    public static byte[] encode(List<SupportEffectsState.Effect> effects){
        SupportEffectsState.validate(effects);
        try{
            var bytes=new ByteArrayOutputStream(8+effects.size()*RECORD_BYTES);var out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeInt(effects.size());
            for(var e:effects){out.writeInt(e.kind());out.writeInt(e.sourceRef());out.writeInt(e.sourceType());out.writeInt(e.teamId());
                out.writeInt(e.targetKind());out.writeInt(e.targetRef());out.writeInt(e.targetType());out.writeInt(e.mount());
                out.writeFloat(e.strength());out.writeFloat(e.rotation());out.writeFloat(e.progress());}
            out.flush();return bytes.toByteArray();
        }catch(IOException e){throw new IllegalArgumentException("Support encoding failed",e);}
    }
    public static List<SupportEffectsState.Effect> decode(byte[] bytes){
        if(bytes==null||bytes.length<8||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Support payload size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=1)throw new IOException("Support schema");int count=in.readInt();
            if(count<0||count>SupportEffectsState.MAX_EFFECTS||bytes.length!=8+count*RECORD_BYTES)throw new IOException("Support count/length");
            var out=new ArrayList<SupportEffectsState.Effect>(count);
            for(int i=0;i<count;i++)out.add(new SupportEffectsState.Effect(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readFloat(),in.readFloat(),in.readFloat()));
            SupportEffectsState.validate(out);return List.copyOf(out);
        }catch(IOException|RuntimeException e){throw new IllegalArgumentException("Invalid support payload",e);}
    }
}
