package fpv.render;

/** Production metadata only: no health, hit size, speed or resource-value inputs exist. */
public final class UnitTierProgressionTest {
    private static int checks;
    public static void main(String[] args){
        var graph=new UnitTierProgression(28);
        graph.seed(0,1);for(int i=0;i<4;i++)graph.upgrade(i,i+1);
        graph.seed(5,1);graph.upgrade(5,6);graph.upgrade(6,7);
        graph.assembly(new int[]{8,9},new int[][]{{5},{6}});
        graph.seed(10,1);graph.upgrade(10,11);graph.upgrade(11,12);
        graph.assembly(new int[]{13,14},new int[][]{{10},{11}});
        graph.seed(15,1);graph.seed(16,2);graph.seed(17,3);
        graph.upgrade(18,19); // orphan chain has no factory/core provenance
        graph.seed(20,1);graph.upgrade(20,21);graph.upgrade(21,20); // invalid cyclic production
        graph.seed(22,1);graph.seed(23,1);graph.upgrade(22,23); // contradictory anchors
        graph.assembly(new int[]{24},new int[][]{{0,5}}); // mixed independent families
        graph.assembly(new int[]{25},new int[][]{{}}); // block-only assembly cannot imply rank
        graph.assembly(new int[]{26},new int[][]{{27}}); // unknown payload provenance
        int[] tiers=graph.resolve();
        for(int i=0;i<5;i++)check(tiers[i]==i+1,"native five-step progression");
        for(int i=5;i<10;i++)check(tiers[i]==i-4,"assembly extends native T3 chain despite T1/T2 payloads");
        for(int i=10;i<15;i++)check(tiers[i]==i-9,"independent assembly family stays independent");
        for(int i=15;i<18;i++)check(tiers[i]==i-14,"core ancestry ranks");
        for(int i=18;i<28;i++)check(tiers[i]==0,"ambiguous and unproven content remains unknown");
        int[] again=graph.resolve();for(int i=0;i<tiers.length;i++)check(tiers[i]==again[i],"resolution does not mutate graph inputs");
        var reversed=new UnitTierProgression(5);reversed.upgrade(3,4);reversed.upgrade(1,2);reversed.upgrade(2,3);reversed.upgrade(0,1);reversed.seed(0,1);
        int[] order=reversed.resolve();for(int i=0;i<5;i++)check(order[i]==i+1,"independent of content iteration order");
        var extra=new UnitTierProgression(7);extra.seed(0,1);for(int i=0;i<6;i++)extra.upgrade(i,i+1);
        int[] beyond=extra.resolve();check(beyond[4]==5&&beyond[5]==0&&beyond[6]==0,"unrecognized higher tiers are not mislabeled T5");
        var fork=new UnitTierProgression(4);fork.seed(0,1);fork.upgrade(0,1);fork.upgrade(0,2);fork.upgrade(1,3);fork.upgrade(2,3);
        check(fork.resolve()[3]==3,"consistent fork and merge has an unambiguous rank");
        check(new UnitTierProgression(0).resolve().length==0,"empty content supported");
        System.out.println("UnitTierProgression: "+checks+" assertions passed.");
    }
    private static void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
}
