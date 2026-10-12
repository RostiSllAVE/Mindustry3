package fpv.construction;

import java.io.*;
import java.util.Arrays;

/** Bounded authoritative rebuild progression, fresh inventory, exact completion and persisted retries. */
public final class LayerRebuildQueueTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        var queue=new LayerRebuildQueue(LayerCoordinates.DEFAULT);LayerComponent broken=component(1,10,10,10,1);
        require(queue.remember(broken),"destruction is remembered");var fresh=queue.snapshot().get(0).component();require(fresh.state().length==0&&fresh.health()==fresh.maxHealth()&&Arrays.equals(fresh.configuration(),broken.configuration()),"rebuild restores full health/config but never copies destroyed contents");
        int[] resources={5},paid={0};boolean[] supported={true},race={false};
        var callback=new LayerRebuildQueue.Callback(){public boolean canPlace(LayerComponent c){return supported[0]&&resources[0]>=5;}public double buildTime(LayerComponent c){return 100;}public boolean complete(LayerComponent c){if(race[0]||!canPlace(c))return false;resources[0]-=5;paid[0]++;return true;}};
        require(queue.target(new LayerRebuildQueue.Tower(10,10,0,1,10,1),callback)==null,"same XY outside vertical range cannot be rebuilt");
        require(queue.target(new LayerRebuildQueue.Tower(10,10,14,2,10,1),callback)==null,"enemy destroyed plan is not selected");
        var tower=new LayerRebuildQueue.Tower(10,10,14,1,10,25);supported[0]=false;require(queue.update(tower,callback)==null&&queue.snapshot().get(0).progress()==0,"unsupported geometry does not accumulate construction");supported[0]=true;
        queue.update(new LayerRebuildQueue.Tower(10,10,14,1,10,0),callback);require(queue.snapshot().get(0).progress()==0&&resources[0]==5,"unpowered tower neither progresses nor charges resources");
        for(int i=0;i<3;i++)queue.update(tower,callback);require(queue.snapshot().get(0).progress()==.75&&paid[0]==0,"native build effort accumulates without early charge");
        byte[] save=bytes(queue);queue=LayerRebuildQueue.read(new DataInputStream(new ByteArrayInputStream(save)),LayerCoordinates.DEFAULT);require(queue.snapshot().get(0).progress()==.75&&queue.snapshot().get(0).component().state().length==0,"in-progress save restores an empty future building");
        race[0]=true;require(!queue.update(tower,callback).completed()&&queue.size()==1&&paid[0]==0,"completion race remains queued without losing resources");race[0]=false;
        require(queue.update(tower,callback).completed()&&queue.size()==0&&paid[0]==1&&resources[0]==0,"one atomic successful completion charges exactly once");require(queue.update(tower,callback)==null&&paid[0]==1,"second tower cannot duplicate completed job");
        queue.remember(broken);queue.remember(component(2,10,10,10,2));require(queue.size()==1&&queue.snapshot().get(0).component().team()==2,"replacement destruction at same cell replaces stale ownership");
        require(queue.removeAt(10,10,10,LayerComponent.Kind.BLOCK)&&queue.size()==0,"manual construction can dismiss the old destroyed plan");
        for(int i=0;i<LayerRebuildQueue.MAX_JOBS;i++)require(queue.remember(component(i+10,i,0,1,1)),"bounded queue accepts capacity");require(!queue.remember(component(9999,3000,0,1,1))&&queue.size()==LayerRebuildQueue.MAX_JOBS,"queue rejects overflow without dropping existing plans");
        var huge=new LayerRebuildQueue(LayerCoordinates.DEFAULT);int accepted=0;for(int i=0;i<2048;i++){var c=component(i+1,i,0,1,1).withConfiguration(new byte[LayerComponent.MAX_CONFIG_BYTES]);if(!huge.remember(c))break;accepted++;}require(accepted<2048&&huge.byteBudget()<=LayerRebuildQueue.MAX_BYTES,"combined configuration memory remains bounded");
        reject(new byte[]{0,0,0,1,0,0,16,0});var invalid=bytes(huge);Arrays.fill(invalid,invalid.length-8,invalid.length,(byte)0xff);reject(invalid);
        var raised=new LayerRebuildQueue(LayerCoordinates.DEFAULT);raised.setCoordinates(new LayerCoordinates(1.4,128));require(raised.remember(component(5000,10,10,100,1)),"host may raise layer limit before future destruction");
        try{raised.setCoordinates(LayerCoordinates.DEFAULT);throw new AssertionError("Pending high-floor plan hidden");}catch(IllegalArgumentException expected){checks++;}
        require(LayerRebuildQueue.read(new DataInputStream(new ByteArrayInputStream(bytes(raised))),new LayerCoordinates(1.4,128)).size()==1,"raised-limit rebuild plan survives save");
        System.out.println("LayerRebuildQueue: "+checks+" assertions passed.");
    }
    private static LayerComponent component(long id,int x,int y,int layer,int team){return new LayerComponent(id,LayerComponent.Kind.BLOCK,x,y,layer,"copper-wall",1,0,team,LayerComponent.Mode.STRUCTURAL,0,100,1,1.4,LayerComponent.SupportProfile.NONE,new byte[]{1},new byte[]{99,99,99});}
    private static byte[] bytes(LayerRebuildQueue queue)throws IOException{var bytes=new ByteArrayOutputStream();queue.write(new DataOutputStream(bytes));return bytes.toByteArray();}
    private static void reject(byte[] data){try{LayerRebuildQueue.read(new DataInputStream(new ByteArrayInputStream(data)),LayerCoordinates.DEFAULT);throw new AssertionError("Bad queue accepted");}catch(IOException expected){checks++;}}
    private static void require(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
}
