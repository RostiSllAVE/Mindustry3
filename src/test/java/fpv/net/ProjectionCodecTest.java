package fpv.net;
import fpv.defense.*;
import java.util.*;
public final class ProjectionCodecTest{
    public static void main(String[] args){
        var settings=new ProjectedWallSystem.Settings(.85,5,5,.03,false);var row=new ProjectionSnapshot(123,8,2,60,100,3,1,6,41);
        var value=new ProjectedWallSystem.Replication(settings,List.of(row),45);var bytes=ProjectionCodec.encode(value,100);
        var decoded=ProjectionCodec.decode(bytes,100);if(!decoded.equals(value))throw new AssertionError("Sparse header/bounds/HP exact roundtrip");
        expect(()->ProjectionCodec.decode(bytes,0));byte[] cut=Arrays.copyOf(bytes,bytes.length-3);expect(()->ProjectionCodec.decode(cut,100));
        var full=new ProjectedWallSystem.Replication(settings,List.of(),46);if(!ProjectionCodec.decode(ProjectionCodec.encode(full,100),100).damaged().isEmpty())throw new AssertionError("Implicit full-health map");
        var duplicate=new ProjectedWallSystem.Replication(settings,List.of(row,row),47);expect(()->ProjectionCodec.decode(ProjectionCodec.encode(duplicate,100),100));
        System.out.println("ProjectionCodecTest PASS 5 checks");
    }
    private static void expect(Runnable work){try{work.run();throw new AssertionError("Malformed projection accepted");}catch(IllegalArgumentException expected){}}
}
