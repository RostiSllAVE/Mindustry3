package fpv.render;

/** Section topology, waterline placement, airborne escape and bounded hull parameters. */
public final class NavalHullProfileTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Naval hull/profile invariant");}
    public static void main(String[] args){
        var p=NavalHullProfile.risso(10f/8);check(Math.abs(p.body().bodyThickness()-.44)<1e-6);
        check(p.keel()<0&&p.deck()>0&&Math.abs(p.deck()-p.keel()-.44)<1e-6);
        check(Math.abs(p.body().weaponUpOffset()-p.body().weaponThickness()*.5-p.deck()-p.weaponGap())<1e-6);
        for(double water:new double[]{0,2,40})for(double half:new double[]{.075,.12,.4}){
            check(p.waterline(water+half,half,water,true)==water);
            check(Math.abs(p.waterline(water+half+5,half,water,true)-water-5)<1e-6);
            check(Math.abs(p.waterline(water+half+.04,half,water,false)-water-.04)<1e-6);
        }
        byte[] alpha=new byte[40*64];for(int y=4;y<60;y++)for(int x=4;x<36;x++)if(y>12||Math.abs(x-20)<y-3)alpha[x+y*40]=(byte)255;
        var h=p.body().hull();var shape=LayeredHullGeometry.generate(alpha,40,64,h,64,8,32,.13,65520);
        var stacked=LayeredHullGeometry.generate(alpha,40,64,new LayeredHullProfile(h.bands(),h.chassisScale(),0),64,8,32,.13,65520);
        check(shape.vertexCount()<stacked.vertexCount()&&shape.sideQuads()>4);
        for(int i=0;i<shape.vertices().length;i+=8){var v=shape.vertices();check(Float.isFinite(v[i]+v[i+1]+v[i+2]));if(v[i+4]>0)check(v[i+1]==.5f);}
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            boolean failed=false;try{NavalHullProfile.risso(invalid);}catch(IllegalArgumentException expected){failed=true;}check(failed);
        }
        check(NavalHullProfile.risso(.1f).body().bodyThickness()>=.34f&&NavalHullProfile.risso(50).body().bodyThickness()<=.60f);
        System.out.println("NavalHullProfileTest: "+checks+" exterior geometry/waterline/weapon gap/finite bounds checks passed");
    }
}
