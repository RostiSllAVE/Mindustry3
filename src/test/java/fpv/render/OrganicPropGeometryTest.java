package fpv.render;

import fpv.world.NaturalProps.Kind;
import java.util.Arrays;

public final class OrganicPropGeometryTest {
    private static int checks;
    public static void main(String[] args){
        for(Kind kind:Kind.values())for(int variant=0;variant<8;variant++){
            float[] mesh=OrganicPropGeometry.template(kind,variant);
            check(mesh==OrganicPropGeometry.template(kind,variant+8),"one cached deterministic template per kind/variant");
            check(mesh.length%18==0&&mesh.length/6<512,"bounded triangle count per object");
            boolean middle=false;double minY=1,maxY=0;
            for(int i=0;i<mesh.length;i+=6){
                for(int j=0;j<6;j++)check(Float.isFinite(mesh[i+j]),"finite cached vertex");
                check(Math.hypot(mesh[i],mesh[i+2])<=.496,"rotated geometry stays within original tile collider envelope");
                check(mesh[i+1]>=-1e-6&&mesh[i+1]<=1.00001,"preserved physical height envelope");
                minY=Math.min(minY,mesh[i+1]);maxY=Math.max(maxY,mesh[i+1]);middle|=mesh[i+1]>.1&&mesh[i+1]<.9;
            }
            check(maxY>.95&&middle,"recognizable faceted shape spans vertical model, not flat sprite/cube");
            if(kind!=Kind.LEAF_TREE)check(minY<.01,"ground anchored trunk or natural prop");
        }
        check(!Arrays.equals(OrganicPropGeometry.template(Kind.ROCK,0),OrganicPropGeometry.template(Kind.ROCK,1)),"deterministic rock shape variation");
        System.out.println("OrganicPropGeometry: "+checks+" assertions passed (bounded cached faceting, height/footprint envelope, stable variation).");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
