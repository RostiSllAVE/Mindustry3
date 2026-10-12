package fpv.net;

public final class ContactLedgerTest{
    private static int checks;
    public static void main(String[] args){
        ContactLedger ledger=new ContactLedger();ledger.begin(.02);
        check(ledger.impulse(1,2),"first response");check(!ledger.impulse(2,1),"second pilot cannot double response");
        check(ledger.damage(1,2,.5),"first trauma");check(!ledger.damage(2,1,.5),"reciprocal trauma suppressed");
        ledger.begin(.02);check(ledger.impulse(2,1),"new frame can resolve continued contact");check(!ledger.damage(2,1,.5),"cooldown shared across pilots");
        for(int i=0;i<26;i++)ledger.begin(.02);check(ledger.damage(2,1,.5),"new impact after cooldown");
        check(ledger.impulse(1,3),"independent target unaffected");check(ledger.damage(1,3,.5),"independent target trauma");
        for(int i=0;i<300;i++)ledger.begin(.02);check(ledger.size()==0,"old pairs pruned");ledger.clear();check(ledger.size()==0,"world reset");
        System.out.println("ContactLedgerTest PASS "+checks+" checks");
    }
    private static void check(boolean okay,String name){checks++;if(!okay)throw new AssertionError(name);}
}
