package fpv.control;

import java.io.*;
import java.util.List;

/** Save payload safety without creating a game world or graphics context. */
public final class CreatorSaveTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        var saved=new CreatorFlightSystem.Saved(37,"atrax",1,40,20,0,.8,.4,40,18,-20,2,-3,4,0,0,0,1,0,0,0);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();CreatorFlightSystem.writeSaved(new DataOutputStream(bytes),List.of(saved));
        var copy=CreatorFlightSystem.readSaved(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        yes(copy.equals(List.of(saved)),"immutable creator XYZ, momentum and original grounded elevation round trip");
        ByteArrayOutputStream duplicate=new ByteArrayOutputStream();CreatorFlightSystem.writeSaved(new DataOutputStream(duplicate),List.of(saved,saved));
        bad(()->CreatorFlightSystem.readSaved(new DataInputStream(new ByteArrayInputStream(duplicate.toByteArray()))),"duplicate identity rejected");
        bad(()->CreatorFlightSystem.readSaved(new DataInputStream(new ByteArrayInputStream(new byte[]{1,2,3}))),"truncated chunk rejected");
        bad(()->new CreatorFlightSystem.Saved(1,"atrax",1,0,0,1,1,1,0,1,0,0,0,0,0,0,0,1,0,0,0),"temporary airborne elevation never becomes landed elevation");
        bad(()->new CreatorFlightSystem.Saved(1,"atrax",1,0,0,0,1,1,0,Double.NaN,0,0,0,0,0,0,0,1,0,0,0),"nonfinite height rejected");
        bad(()->new CreatorFlightSystem.Saved(1,"atrax",1,0,0,0,1,1,0,1,0,0,0,0,0,0,0,0,0,0,0),"invalid attitude rejected");
        ByteArrayOutputStream count=new ByteArrayOutputStream();var out=new DataOutputStream(count);out.writeInt(1);out.writeInt(16385);
        bad(()->CreatorFlightSystem.readSaved(new DataInputStream(new ByteArrayInputStream(count.toByteArray()))),"bounded saved unit count");
        System.out.println("CreatorSave: "+checks+" assertions passed.");
    }
    private interface Throwing{void run()throws Exception;}
    private static void bad(Throwing action,String label){boolean rejected=false;try{action.run();}catch(IOException|IllegalArgumentException expected){rejected=true;}catch(Exception e){throw new AssertionError(e);}yes(rejected,label);}
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
