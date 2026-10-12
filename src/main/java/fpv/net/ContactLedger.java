package fpv.net;

import java.util.HashMap;
import java.util.Map;

/** Shared unordered-pair arbitration: one momentum exchange per frame and one trauma per cooldown. */
public final class ContactLedger {
    private record Pair(long a,long b){}
    private static final class Contact{long impulseFrame=Long.MIN_VALUE;double damageUntil,lastSeen;}
    private final Map<Pair,Contact> contacts=new HashMap<>();
    private long frame;private double time;
    public void begin(double simulationSeconds){
        if(!Double.isFinite(simulationSeconds)||simulationSeconds<0||simulationSeconds>.1)throw new IllegalArgumentException("Bounded simulation delta required");
        frame++;time+=simulationSeconds;contacts.entrySet().removeIf(e->time-e.getValue().lastSeen>5&&time>=e.getValue().damageUntil);
    }
    public boolean impulse(long a,long b){Contact c=contact(a,b);if(c.impulseFrame==frame)return false;c.impulseFrame=frame;return true;}
    public boolean damage(long a,long b,double cooldown){if(!Double.isFinite(cooldown)||cooldown<0||cooldown>10)throw new IllegalArgumentException("Bounded contact cooldown required");Contact c=contact(a,b);if(time<c.damageUntil)return false;c.damageUntil=time+cooldown;return true;}
    private Contact contact(long a,long b){if(a==b)throw new IllegalArgumentException("Two bodies required");Pair key=a<b?new Pair(a,b):new Pair(b,a);Contact c=contacts.computeIfAbsent(key,k->new Contact());c.lastSeen=time;return c;}
    public int size(){return contacts.size();}
    public void clear(){contacts.clear();frame=0;time=0;}
}
