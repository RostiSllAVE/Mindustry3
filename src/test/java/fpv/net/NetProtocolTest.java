package fpv.net;

import fpv.flight.FlightController;
import fpv.generated.FlightProfiles;
import fpv.world.Unit3DState;
import java.util.*;

/** Actual encoded bytes, adversarial bounds, atomic snapshot replacement and display isolation. */
public final class NetProtocolTest {
    private static int checks;
    public static void main(String[] args){
        NetworkControls input=new NetworkControls(true,0,.6,.3,-.2,1,-1,0,.9,0,Math.PI/2,.6,.8,0,true);
        byte[] encoded=NetProtocol.encode(NetProtocol.INPUT,17,42,out->NetProtocol.controls(out,input));
        var envelope=NetProtocol.decode(encoded);check(envelope.epoch()==17&&envelope.sequence()==42,"wire epoch/sequence");
        check(NetProtocol.read(envelope.payload(),NetProtocol::controls).equals(input),"all controls round-trip");
        NetworkControls creator=new NetworkControls(true,3,0,0,0,0,.2,-.3,.5,0,.4,0,1,0,false,true);
        check(NetProtocol.read(NetProtocol.bytes(out->NetProtocol.controls(out,creator)),NetProtocol::controls).equals(creator),"explicit creator request survives wire separately from Build/ground movement mode");
        for(int i=0;i<encoded.length;i++){byte[] truncated=Arrays.copyOf(encoded,i);reject(()->{var e=NetProtocol.decode(truncated);NetProtocol.read(e.payload(),NetProtocol::controls);},"truncation rejected");}
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);reject(()->{var e=NetProtocol.decode(trailing);NetProtocol.read(e.payload(),NetProtocol::controls);},"trailing bytes rejected");
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.01,1.01})reject(()->new NetworkControls(true,0,value,0,0,0,0,0,0,0,0,0,1,0,false),"invalid throttle");
        reject(()->new NetworkControls(true,4,0,0,0,0,0,0,0,0,0,0,1,0,false),"invalid mode");
        reject(()->new NetworkControls(true,1,0,0,0,0,0,0,0,0,0,0,0,0,false),"zero aim");
        new NetworkControls(false,3,0,0,0,0,1,1,0,0,0,0,1,0,true);checks++;
        var controller=new FlightController(FlightProfiles.create());controller.reset(3,7,-9,80);controller.state().vx=13;
        var own=new NetProtocol.Flight(5,71,controller.profile(),controller.state());
        Unit3DState unit=new Unit3DState(71,4,2,3,9,7,13,0,0,10,0,0,7,.6,.2,true);
        var snapshot=new NetProtocol.Snapshot(List.of(unit),own);byte[] state=NetProtocol.snapshot(snapshot);var decoded=NetProtocol.snapshot(state);
        check(decoded.units().equals(snapshot.units()),"canonical world axes survive");check(decoded.local().profile().massKg==own.profile().massKg,"server tuning survives");
        check(decoded.local().state().z==-9&&decoded.local().state().y==7,"render flight axes survive separately");
        reject(()->NetProtocol.snapshot(NetProtocol.bytes(out->{out.writeInt(-1);})),"negative count");
        byte[] invalidState=state.clone();java.nio.ByteBuffer.wrap(invalidState).putDouble(4+4+4+1,Double.NaN);reject(()->NetProtocol.snapshot(invalidState),"NaN world state");
        reject(()->NetProtocol.snapshot(NetProtocol.snapshot(new NetProtocol.Snapshot(List.of(unit,unit),null))),"duplicate unit ID");
        byte[] message=new byte[29017];for(int i=0;i<message.length;i++)message[i]=(byte)(i*31);
        NetProtocol.Assembly assembly=new NetProtocol.Assembly();
        var chunks=new ArrayList<NetProtocol.Chunk>();for(int i=0;i<3;i++)chunks.add(new NetProtocol.Chunk("poses",7,i,3,message.length,Arrays.copyOfRange(message,i*12000,Math.min(message.length,(i+1)*12000))));
        for(var chunk:chunks){var round=NetProtocol.chunk(NetProtocol.bytes(out->NetProtocol.chunk(out,chunk)));check(Arrays.equals(chunk.data(),round.data()),"chunk wire roundtrip");}
        check(assembly.accept(chunks.get(2),1)==null,"out-of-order partial hidden");check(assembly.accept(chunks.get(0),1.1)==null,"still incomplete hidden");
        check(assembly.accept(chunks.get(0),1.2)==null,"duplicate chunk not completion");check(Arrays.equals(message,assembly.accept(chunks.get(1),1.3)),"atomic full reconstruction");
        check(assembly.accept(chunks.get(1),1.4)==null,"completed replay ignored");
        reject(()->NetProtocol.chunk(NetProtocol.bytes(out->NetProtocol.chunk(out,new NetProtocol.Chunk("poses",1,9,3,29017,new byte[17])))),"invalid chunk index");
        reject(()->NetProtocol.chunk(NetProtocol.bytes(out->NetProtocol.chunk(out,new NetProtocol.Chunk("../escape",1,0,1,0,new byte[0])))),"channel cannot become path");
        RemotePoses poses=new RemotePoses();poses.accept(snapshot,1);
        FlightController.State second=new FlightController.State();NetProtocol.copy(controller.state(),second);second.x=13;second.qx=-second.qx;second.qy=-second.qy;second.qz=-second.qz;second.qw=-second.qw;
        poses.accept(new NetProtocol.Snapshot(List.of(new Unit3DState(71,4,2,13,9,7,13,0,0,350,0,0,7,.6,.2,true)),new NetProtocol.Flight(5,71,controller.profile(),second)),1.1);
        FlightController display=new FlightController(FlightProfiles.create());check(poses.copyFlight(display,1.15),"display available");
        close(display.state().x,8,"interpolation midpoint");close(poses.display(71,1.15).x(),8,"NPC interpolation agrees");
        close(poses.latest(71).x(),13,"server sample not mutated by rendering");close(second.x,13,"source physical state immutable to display");
        close(display.state().qx*display.state().qx+display.state().qy*display.state().qy+display.state().qz*display.state().qz+display.state().qw*display.state().qw,1,"antipodal quaternions normalized");
        poses.copyFlight(display,10);close(display.state().x,13,"no unbounded prediction on outage");poses.clear();check(!poses.copyFlight(display,11),"world reset clears authority samples");
        System.out.println("NetProtocolTest PASS "+checks+" checks");
    }
    private static void close(double a,double b,String label){check(Math.abs(a-b)<1e-8,label+": "+a);}
    private static void reject(Runnable call,String label){try{call.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
