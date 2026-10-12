package fpv.physics;

/** Hard limits are maxima, directional and shared by direct trauma plus all custom blast damage. */
public final class RamDamageLimitTest {
    private static int checks;
    public static void main(String[] args){
        eq(RamDamageLimit.capped(12,3000),12,"weak damage unchanged");eq(RamDamageLimit.capped(2465,3000),2465,"existing heavy fixture unchanged");
        eq(RamDamageLimit.capped(200000,3000),3000,"strong trauma clamped");eq(RamDamageLimit.capped(200000,0),0,"zero fully suppresses outgoing damage");
        var budget=new RamDamageLimit(100);Object direct=new Object(),nearby=new Object();
        eq(budget.reserve(direct,80,1),80,"direct trauma consumes budget");eq(budget.reserve(direct,90,1),20,"same-victim blast uses remainder");
        eq(budget.reserve(direct,40,1),0,"no further damage after cap");eq(budget.reserve(nearby,90,1),90,"another victim keeps own budget");
        eq(budget.reserve(nearby,40,1),10,"neighbor cannot exceed cap either");
        var scaled=new RamDamageLimit(100);eq(scaled.reserve(direct,90,.25),25,"low health multiplier cannot amplify above HP cap");eq(scaled.remaining(direct),0,"conservative scaled budget exhausted");
        eq(scaled.reserve(nearby,15,0),0,"zero-health rules never receive even a zero API call");eq(scaled.reserve(nearby,15,-1),0,"negative scale rejected");
        var tough=new RamDamageLimit(100);eq(tough.reserve(direct,90,4),90,"tough receiver does not inflate weak raw damage");eq(tough.reserve(direct,90,4),10,"conservative budget never overspends");
        for(double trauma:new double[]{.01,12,100,2465,200000})for(double coreCap:new double[]{0,37,120,1000}){
            double coreReceived=RamDamageLimit.capped(trauma,3000),ordinaryReceived=RamDamageLimit.capped(trauma,coreCap);
            // First claimant merely labels its own receiver as self; the other unit owns that leg's cap.
            eq(new RamDamageLimit(3000).reserve("core",trauma,1),coreReceived,"core-first self leg uses ordinary outgoing limit");
            eq(new RamDamageLimit(coreCap).reserve("normal",trauma,1),ordinaryReceived,"core-first target leg uses core outgoing limit");
            eq(new RamDamageLimit(coreCap).reserve("normal",trauma,1),ordinaryReceived,"normal-first self leg uses core outgoing limit");
            eq(new RamDamageLimit(3000).reserve("core",trauma,1),coreReceived,"normal-first target leg uses ordinary outgoing limit");
        }
        for(int i=0;i<500;i++){
            double cap=i*.33;var many=new RamDamageLimit(cap);double total=0;
            for(int j=0;j<50;j++)total+=many.reserve("one victim",j*.37,1);
            yes(total<=cap+1e-9,"many fragments/callbacks remain bounded");
        }
        System.out.println("RamDamageLimit: "+checks+" assertions passed.");
    }
    private static void eq(double a,double b,String label){yes(Double.isFinite(a)&&Math.abs(a-b)<1e-9,label+": "+a+" vs "+b);}
    private static void yes(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
}
