package fpv.render;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real wire round trips and malformed length/count/identity/numeric rejection. */
public final class SupportEffectsCodecTest {
    private static int checks;
    public static void main(String[] args){
        var turret=new SupportEffectsState.Effect(0,65537,82,1,0,123,4,-1,.75f,135,0);
        var unit=new SupportEffectsState.Effect(1,222,9,1,1,196611,44,2,.3f,25,0);
        var tower=new SupportEffectsState.Effect(2,65542,87,1,2,-1,-1,-1,.7f,0,.45f);
        var upperTurret=new SupportEffectsState.Effect(4,37,82,1,0,123,4,-1,.8f,135,0);
        var upperTower=new SupportEffectsState.Effect(5,41,87,1,2,-1,-1,-1,.4f,0,.1f);
        var upperTowerTarget=new SupportEffectsState.Effect(6,41,87,1,0,123,4,-1,.4f,0,.1f);
        var upperTarget=new SupportEffectsState.Effect(1,222,9,1,3,47,44,2,.3f,25,0);
        var effects=List.of(turret,unit,tower,upperTurret,upperTower,upperTowerTarget,upperTarget);byte[] bytes=SupportEffectsCodec.encode(effects);
        check(SupportEffectsCodec.decode(bytes).equals(effects),"all source/target/mount/native visual fields survive");
        check(SupportEffectsCodec.decode(SupportEffectsCodec.encode(List.of())).isEmpty(),"empty authoritative snapshot clears visuals");
        for(int i=0;i<bytes.length;i++){byte[] cut=Arrays.copyOf(bytes,i);reject(()->SupportEffectsCodec.decode(cut),"truncated payload");}
        reject(()->SupportEffectsCodec.decode(Arrays.copyOf(bytes,bytes.length+1)),"trailing byte");
        byte[] invalid=bytes.clone();ByteBuffer.wrap(invalid).putInt(4,SupportEffectsState.MAX_EFFECTS+1);byte[] oversized=invalid;reject(()->SupportEffectsCodec.decode(oversized),"oversized row count");
        invalid=bytes.clone();ByteBuffer.wrap(invalid).putInt(4,-1);byte[] negative=invalid;reject(()->SupportEffectsCodec.decode(negative),"negative count");
        invalid=bytes.clone();ByteBuffer.wrap(invalid).putFloat(8+32,Float.NaN);byte[] nan=invalid;reject(()->SupportEffectsCodec.decode(nan),"NaN strength");
        invalid=bytes.clone();ByteBuffer.wrap(invalid).putInt(8+12,256);byte[] team=invalid;reject(()->SupportEffectsCodec.decode(team),"invalid team");
        invalid=bytes.clone();ByteBuffer.wrap(invalid).putInt(8,9);byte[] kind=invalid;reject(()->SupportEffectsCodec.decode(kind),"unknown visual kind");
        var maximum=new ArrayList<SupportEffectsState.Effect>();for(int i=0;i<SupportEffectsState.MAX_EFFECTS;i++)maximum.add(turret);
        check(SupportEffectsCodec.decode(SupportEffectsCodec.encode(maximum)).size()==SupportEffectsState.MAX_EFFECTS,"bounded maximum populated packet");
        maximum.add(turret);reject(()->SupportEffectsCodec.encode(maximum),"encode count cap");
        System.out.println("SupportEffectsCodecTest PASS "+checks+" checks");
    }
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void reject(Runnable code,String label){try{code.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
}
