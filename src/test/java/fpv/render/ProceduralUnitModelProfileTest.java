package fpv.render;

/** Geometry attachment math must remain native-yaw compatible without touching simulation. */
public final class ProceduralUnitModelProfileTest {
    private static int checks;
    public static void main(String[] args){
        var profile=new ProceduralUnitModelProfile(.72f,1,.95f,0,0,0,.12f,0,0,0,0);
        near(profile.bodyCenterLift(.12),.24);near(profile.bodyCenterLift(.08),.28);
        near(profile.bodyCenterLift(.12)-profile.bodyThickness()*.5,-.12);
        near(profile.topOffset(.12,1,1.5,0,0),.6);
        require(profile.topOffset(.12,1,1.5,45,0)>.9);
        for(int yaw=0;yaw<360;yaw+=15){
            double[] b=new double[9];SpriteExtrusionGeometry.basis(yaw,0,0,b);
            double x=ProceduralUnitModelProfile.weaponX(12,yaw,4,2);
            double y=ProceduralUnitModelProfile.weaponY(9,yaw,4,2);
            near(x,12+b[0]*4-b[6]*2);near(y,9-b[2]*4+b[8]*2);
            near(ProceduralUnitModelProfile.weaponYaw(yaw,false,71,0),yaw);
            near(ProceduralUnitModelProfile.weaponYaw(yaw,true,71,0),yaw+71);
        }
        boolean rejected=false;try{new ProceduralUnitModelProfile(Float.NaN,1,1,0,0,0,.1f,0,0,0,0);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected);rejected=false;
        try{new ProceduralUnitModelProfile(.2f,0,1,0,0,0,.1f,0,0,0,0);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected);require(ProceduralUnitModelProfile.forType(null)==null);
        System.out.println("ProceduralUnitModelProfileTest: "+checks+" assertions passed");
    }
    private static void near(double actual,double expected){require(Math.abs(actual-expected)<1e-6);}
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("Procedural profile attachment or bounds");}
}
