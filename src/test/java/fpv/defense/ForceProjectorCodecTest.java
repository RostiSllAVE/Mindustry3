package fpv.defense;

import java.util.*;

public final class ForceProjectorCodecTest {
    private static int checks;
    public static void main(String[] args){
        var state=List.of(new ForceProjectorSystem.Snapshot(42,102,2,false,320,.88f,1,.99f,.25f),new ForceProjectorSystem.Snapshot(76,102,1,true,749,.001f,-.1f,0,0));
        byte[] bytes=ForceProjectorCodec.encode(state,100);
        yes(ForceProjectorCodec.decode(bytes,100).equals(state),"all native field values preserve exact float bits");
        expect(()->ForceProjectorCodec.decode(bytes,1));
        expect(()->ForceProjectorCodec.decode(Arrays.copyOf(bytes,bytes.length-4),100));
        expect(()->ForceProjectorCodec.encode(List.of(state.get(0),state.get(0)),100));
        expect(()->ForceProjectorCodec.encode(List.of(new ForceProjectorSystem.Snapshot(42,102,2,false,Float.NaN,1,0,1,0)),100));
        expect(()->ForceProjectorCodec.encode(List.of(new ForceProjectorSystem.Snapshot(42,102,2,false,1,100,0,1,0)),100));
        expect(()->ForceProjectorCodec.decode(null,100));
        yes(ForceProjectorCodec.decode(ForceProjectorCodec.encode(List.of(),0),0).isEmpty(),"empty world clears remote shells");
        System.out.println("ForceProjectorCodec: "+checks+" assertions passed.");
    }
    private static void yes(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
    private static void expect(Runnable test){checks++;try{test.run();throw new AssertionError("Invalid force packet accepted");}catch(IllegalArgumentException expected){}}
}
