package fpv.combat;
import java.util.*;
public final class MechWeaponPitchCodecTest {
    private static int checks;
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Invalid pitch snapshot accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        float[] original={-20,0,35,70};var entry=new MechWeaponPitchCodec.Entry(7,12,2,original);original[0]=40;assert entry.pitch(0)==-20;checks++;
        var bytes=MechWeaponPitchCodec.encode(List.of(entry));var decoded=MechWeaponPitchCodec.decode(bytes);assert decoded.size()==1&&decoded.get(0).count()==4&&decoded.get(0).pitch(3)==70;checks++;
        float[] leaked=decoded.get(0).pitches();leaked[0]=25;assert decoded.get(0).pitch(0)==-20;checks++;
        assert MechWeaponPitchCodec.decode(MechWeaponPitchCodec.encode(List.of())).isEmpty();checks++;
        for(int i=0;i<bytes.length;i++){int length=i;rejects(()->MechWeaponPitchCodec.decode(Arrays.copyOf(bytes,length)));}
        rejects(()->MechWeaponPitchCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));
        rejects(()->MechWeaponPitchCodec.encode(List.of(entry,entry)));
        rejects(()->new MechWeaponPitchCodec.Entry(1,2,3,new float[]{Float.NaN}));rejects(()->new MechWeaponPitchCodec.Entry(1,2,3,new float[]{86}));rejects(()->new MechWeaponPitchCodec.Entry(1,2,3,new float[33]));
        System.out.println("PASS: "+checks+" mech pitch snapshot immutability, bounds, exact length and duplicate/truncated payload checks");
    }
}
