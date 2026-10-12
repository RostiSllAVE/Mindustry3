package fpv.physics;

import fpv.generated.CrashConfig;

public final class ImpactModelTest {
    private static int assertions;
    public static void main(String[] args){
        check(ImpactModel.damage(.65,.65,3,3.5,4,false).self()==0,"minor bump");
        var light=ImpactModel.damage(.65,.65,40,3.5,4,false);
        var heavy=ImpactModel.damage(200,200,40,3.5,4,false);
        check(heavy.self()>light.self()*100,"mass matters");
        check(light.other()==light.self()&&light.self()>0,"both participants");
        check(ImpactModel.damage(.65,.65,80,3.5,4,false).other()>light.other()*4,"closing speed squared");
        check(ImpactModel.damage(.65,.65,40,3.5,4,true).self()>light.self(),"immovable obstacle");
        var small=ImpactModel.blast(.65,1,30,2.5,28,30,8000);
        var large=ImpactModel.blast(200,8,100,2.5,28,30,8000);
        check(large.radius()>small.radius()*3&&large.damage()>small.damage()*100,"catastrophe scales");
        heavyTuning();
        for(double mass:new double[]{.01,.65,10,200,1e200})for(double speed:new double[]{0,3,20,200,1e200}){
            var blast=ImpactModel.blast(mass,10,speed,6,100,30,8000);
            check(Double.isFinite(blast.damage())&&blast.damage()<=8000&&blast.radius()<=30,"bounded magnitude");
            var d=ImpactModel.damage(mass,mass,speed,3.5,4,false);
            check(Double.isFinite(d.self())&&d.self()<=200000,"bounded trauma");
        }
        boolean rejected=false;try{ImpactModel.damage(Double.NaN,1,3,3,4,false);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"non-finite rejected");
        // Thin stationary structures cannot be skipped by a very fast segment.
        var hit=SweptBodies.box(0,0,1,1000,0,0,5,-1,0,5.2,1,2);
        check(hit!=null&&Math.abs(hit.time()-.005)<1e-12&&hit.nx()==-1,"continuous building hit");
        check(SweptBodies.box(0,0,4,1000,0,0,5,-1,0,5.2,1,2)==null,"above structure miss");
        check(SweptBodies.box(5.1,0,1,1,0,0,5,-1,0,5.2,1,2)==null,"overlap recovery no new strike");
        var sphere=SweptBodies.ellipsoid(-10,0,0,1000,0,0,2,.5);
        check(sphere!=null&&Math.abs(sphere.time()-.008)<1e-12,"high-speed unit hit");
        check(SweptBodies.ellipsoid(-10,0,2,1000,0,0,2,.5)==null,"XY overlap at different altitude misses");
        check(SweptBodies.ellipsoid(-10,0,0,0,0,0,2,.5)==null,"equal velocity never approaches");
        var headon=SweptBodies.ellipsoid(-10,0,0,20,0,0,2,.5);
        var retreat=SweptBodies.ellipsoid(-10,0,0,5,0,0,2,.5);
        check(headon!=null&&retreat==null,"relative motion determines contact");
        System.out.println("ImpactModelTest: "+assertions+" assertions passed");
    }

    private static void heavyTuning(){
        for(double mass:new double[]{.65,6,12})for(double speed:new double[]{0,10,35,100}){
            var current=ImpactModel.blast(mass,2.5,speed,2.5,28,
                    CrashConfig.MAX_BLAST_RADIUS_TILES,CrashConfig.MAX_BLAST_DAMAGE);
            var previous=legacyBlast(mass,2.5,speed);
            close(current.radius(),previous[0],1e-12,"ordinary radius unchanged below threshold");
            close(current.damage(),previous[1],1e-12,"ordinary blast damage unchanged below threshold");
            var damage=ImpactModel.damage(mass,1000,speed,3.5,4,true);
            close(damage.self(),Math.min(200000,.5*mass*Math.pow(Math.max(0,speed-3.5),2)*4),1e-9,
                    "ordinary immovable trauma unchanged below threshold");
        }
        var lightOnHeavy=ImpactModel.damage(.65,200,20,3.5,4,false);
        close(lightOnHeavy.self(),.5*(.65*200/(.65+200))*16.5*16.5*4,1e-9,
                "heavy target does not amplify a light craft's reduced-mass impact");
        var heavyImpact=ImpactModel.damage(200,200,10,3.5,4,true);
        double oldEnergy=.5*200*6.5*6.5;
        close(heavyImpact.energy(),oldEnergy,0,"heavy gameplay multiplier does not falsify kinetic energy");
        check(heavyImpact.self()>oldEnergy*4*2&&heavyImpact.self()<=200000,
                "slow heavy ram has stronger bounded trauma");
        var heavyBlast=ImpactModel.blast(200,8,35,2.5,28,
                CrashConfig.MAX_BLAST_RADIUS_TILES,CrashConfig.MAX_BLAST_DAMAGE);
        var oldBlast=legacyBlast(200,8,35);
        check(heavyBlast.radius()>oldBlast[0]*2&&heavyBlast.damage()>oldBlast[1]*3,
                "huge same-speed explosion has materially greater radius and damage");
        var extreme=ImpactModel.blast(1e200,1e200,1e200,1e200,1e200,1e200,1e200);
        check(extreme.radius()==40&&extreme.damage()==12000&&Double.isFinite(extreme.visualRadius()),
                "fixed absolute explosion caps survive extreme finite caller values");
        var bounded=ImpactModel.blast(200,8,100,6,100,4,50);
        check(bounded.radius()==4&&bounded.damage()==50,"heavy boost respects stricter caller bounds");
        check(ImpactModel.damage(200,200,3,3.5,4,true).self()==0,
                "heavy multiplier cannot make a safe bump damaging");
        check(ImpactModel.damage(200,200,30,3.5,0,true).self()==0,
                "heavy multiplier respects disabled ram damage");
    }

    private static double[] legacyBlast(double mass,double size,double speed){
        double massScale=Math.max(.15,Math.min(1000,mass/.65));
        double sizeScale=Math.max(.4,Math.min(10,size/1.1));
        double speedScale=Math.max(.5,Math.min(2.5,speed/35));
        return new double[]{Math.max(.2,Math.min(30,2.5*Math.pow(massScale,.23)
                        *Math.pow(sizeScale,.12)*Math.sqrt(speedScale))),
                Math.min(8000,28*Math.pow(massScale,.72)*speedScale*speedScale)};
    }
    private static void close(double actual,double expected,double tolerance,String label){
        check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,
                label+": expected "+expected+", got "+actual);
    }
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
}
