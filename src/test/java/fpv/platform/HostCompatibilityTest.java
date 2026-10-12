package fpv.platform;

public final class HostCompatibilityTest {
    public static void main(String[] args) {
        int checks=0;
        for(int build : new int[]{-1,0,159,160,161,Integer.MAX_VALUE}) {
            for(int revision=-1;revision<=20;revision++) {
                boolean expected=build==160&&(revision==5||revision==7);
                if(HostCompatibility.supports(build,revision)!=expected)
                    throw new AssertionError("Unexpected compatibility: "+build+"."+revision);
                checks++;
            }
        }
        if(!HostCompatibility.SUPPORTED_VERSIONS.contains("160.5")||
           !HostCompatibility.SUPPORTED_VERSIONS.contains("160.7"))
            throw new AssertionError("Compatibility message omits a supported host");
        System.out.println("HostCompatibilityTest: "+(++checks)+" checks passed");
    }
}
