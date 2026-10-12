package fpv.render;

/** Bounded render-only fold progress; independent of authoritative boost and stance state. */
public final class BoostLegPose {
    private double amount;
    public BoostLegPose(boolean airborne){amount=airborne?1:0;}
    public double amount(){return amount;}
    public void advance(boolean airborne,double dt){
        if(!Double.isFinite(dt)||dt<0)throw new IllegalArgumentException("Finite pose time required");
        double step=Math.min(.1,dt)/.35,target=airborne?1:0;
        amount=target>amount?Math.min(target,amount+step):Math.max(target,amount-step);
    }
    public double eased(){return amount*amount*(3-2*amount);}
}
