package fpv.render;

import java.util.Arrays;
import java.util.List;

/** Exactly one textured roof per occupied alpha cell: raised areas do not hide duplicate deck caps. */
public final class NavalBodyGeometryTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    public static void main(String[] args){
        var profile=NavalHullProfile.risso(1.25f);byte[] alpha=new byte[40*48];
        for(int y=3;y<45;y++)for(int x=3;x<37;x++)if((x+y)%13!=0)alpha[x+y*40]=(byte)255;
        var base=LayeredHullGeometry.generate(alpha,40,48,profile.body().hull(),48,8,32,.13,65520);float[] before=base.vertices().clone();
        var mesh=NavalBodyGeometry.generate(base,profile.sections(),profile.body().bodyThickness(),65520);
        check(Arrays.equals(before,base.vertices()),"cached source hull never mutated");
        int w=base.gridWidth(),h=base.gridHeight(),raised=0;double area=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double u=(x+.5)/w,v=(y+.5)/h;int original=0,roofs=0;double actual=-1;
            for(int k=0;k<before.length;k+=48)if(before[k+4]>.9&&inside(before,k,u-.5,v-.5)){original++;}
            for(int k=0;k<mesh.vertices().length;k+=48)if(mesh.vertices()[k+4]>.9&&inside(mesh.vertices(),k,u-.5,v-.5)){roofs++;actual=mesh.vertices()[k+1];}
            check(original==roofs&&roofs<=1,"no lost, duplicated or hidden horizontal caps");
            if(roofs>0){double offset=0;for(var s:profile.sections())if(s.contains(u,v))offset=Math.max(offset,s.height()/profile.body().bodyThickness());
                check(Math.abs(actual-.5-offset)<1e-6,"only selected native regions rise");if(offset>0)raised++;area++;
            }
        }
        check(raised>0&&raised<area*.25,"small raised areas; no full-sprite upward extrusion");
        check(mesh.bottomQuads()==base.bottomQuads()&&mesh.sideQuads()>base.sideQuads(),"lower hull retained, exterior risers added");
        for(int k=0;k<mesh.vertices().length;k+=8){var a=mesh.vertices();check(Float.isFinite(a[k]+a[k+1]+a[k+2]),"finite mesh");
            if(a[k+4]>.9){check(Math.abs(a[k+6]-(a[k]+.5))<1e-6&&Math.abs(a[k+7]-(a[k+2]+.5))<1e-6,"original top UVs, no repeated textures");}
        }
        check(NavalBodyGeometry.generate(base,List.of(),profile.body().bodyThickness(),65520)==base,"empty pattern is original cached hull");
        var changed=NavalBodyGeometry.generate(base,List.of(new NavalBodyGeometry.Section(.2f,.3f,.20f,.2f,.12f,.15f)),profile.body().bodyThickness(),65520);
        check(!Arrays.equals(changed.vertices(),mesh.vertices()),"generator accepts different unit profiles");
        boolean bounded=false;try{NavalBodyGeometry.generate(base,profile.sections(),profile.body().bodyThickness(),36);}catch(IllegalArgumentException expected){bounded=true;}
        check(bounded,"vertex budget enforced");
        check(profile.mountLift(0,-.625,66/32.0,78/32.0)>.2&&profile.mountLift(.5,.1875,66/32.0,78/32.0)==0,"rear mount follows roof; front mounts keep deck");
        System.out.println("NavalBodyGeometryTest: "+checks+" selective relief/exterior-only roofs/UVs/immutability/reusable profiles/budget checks passed");
    }
    private static boolean inside(float[] a,int start,double x,double z){
        float minX=1,maxX=-1,minZ=1,maxZ=-1;for(int i=start;i<start+48;i+=8){minX=Math.min(minX,a[i]);maxX=Math.max(maxX,a[i]);minZ=Math.min(minZ,a[i+2]);maxZ=Math.max(maxZ,a[i+2]);}
        return x>minX&&x<maxX&&z>minZ&&z<maxZ;
    }
}
