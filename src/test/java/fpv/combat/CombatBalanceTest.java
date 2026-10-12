package fpv.combat;

public final class CombatBalanceTest {
    public static void main(String[] args){
        int checks=0;double previous=1;
        for(int i=0;i<=1200;i++){
            double value=CombatBalance.spentDamage(i/100.0);
            if(value<0||value>previous+1e-12)throw new AssertionError("Damage must decay continuously");previous=value;checks++;
        }
        if(CombatBalance.spentDamage(0)!=1||Math.abs(CombatBalance.spentDamage(3)-.1)>1e-12||CombatBalance.spentDamage(10)!=0)throw new AssertionError("Spent damage landmarks");
        if(Math.abs(CombatBalance.spentDamage(3-1e-8)-CombatBalance.spentDamage(3+1e-8))>1e-7)throw new AssertionError("Continuous transition");
        previous=1;
        for(int i=0;i<=1800;i++){
            double value=CombatBalance.rangeDamage(i/100.0,10);
            if(value<0||value>previous+1e-12)throw new AssertionError("Horizontal range power must decline");previous=value;checks++;
            if(value*CombatBalance.spentDamage(3)>.100000001||value*CombatBalance.spentDamage(10)!=0)throw new AssertionError("Age and range cannot resurrect spent power");checks++;
        }
        if(CombatBalance.rangeDamage(10,10)!=1||Math.abs(CombatBalance.rangeDamage(12,10)-.1)>1e-12||CombatBalance.rangeDamage(15,10)!=0)throw new AssertionError("Horizontal range landmarks");checks++;
        if(!CombatBalance.inEnvelope(10,50,10,5)||CombatBalance.inEnvelope(10.01,1,10,5)||CombatBalance.inEnvelope(1,50.01,10,5))throw new AssertionError("AA cylinder boundaries");checks++;
        var lead=CombatGeometry.intercept(0,0,0,10,0,10,0,0,2,10,30);
        double time=(lead.z()-10)/2;
        if(time<=0||Math.abs(Math.sqrt(lead.x()*lead.x()+lead.z()*lead.z())-10*time)>1e-8)throw new AssertionError("XYZ moving-altitude intercept");checks++;
        var impossible=CombatGeometry.intercept(0,0,0,10,0,0,20,0,0,10,30);
        if(!Double.isFinite(impossible.x())||impossible.x()!=10)throw new AssertionError("Unreachable target bounded aim");checks++;
        if(CombatBalance.additionalRangeFactor(.1,.1)!=1||Math.abs(CombatBalance.additionalRangeFactor(.1,.05)-.5)>1e-12
            ||CombatBalance.additionalRangeFactor(.1,1)!=1||CombatBalance.additionalRangeFactor(.1,0)!=0)throw new AssertionError("Child rays attenuate range once without resurrecting power");checks++;
        var custom=new CombatBalance.RangeFalloff(1.4,2,.3);
        if(custom.factor(10,10)!=1||Math.abs(custom.factor(14,10)-.3)>1e-12||custom.factor(20,10)!=0)throw new AssertionError("Custom range landmarks");checks++;
        for(int i=0;i<=200;i++){double ratio=i/100.0;
            if(Math.abs(custom.factor(ratio*10,10)-custom.factor(ratio*50,50))>1e-12)throw new AssertionError("Each native weapon R scales the same configured profile");checks++;
        }
        for(double[] invalid:new double[][]{{1,1.5,.1},{1.5,1.5,.1},{1.5,1.2,.1},{1.2,1.5,-.1},{1.2,1.5,1.1},{Double.NaN,1.5,.1},{1.2,Double.POSITIVE_INFINITY,.1}}){
            boolean rejected=false;try{new CombatBalance.RangeFalloff(invalid[0],invalid[1],invalid[2]);}catch(IllegalArgumentException expected){rejected=true;}
            if(!rejected)throw new AssertionError("Invalid authoritative range configuration rejected");checks++;
        }
        System.out.println("CombatBalance: "+(checks+2)+" assertions passed");
    }
}
