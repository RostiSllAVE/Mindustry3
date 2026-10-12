package fpv.render;

/** Fortress-specific proportions and exterior hull dispatch; existing unit profiles are retained. */
public final class FortressHullGeometryTest {
    public static void main(String[] args){
        var hull=LayeredHullProfile.fortress(13f/8,.40f);var mace=LayeredHullProfile.mace(10f/8,.34f);
        assert Math.abs(hull.depth()-.65)<1e-6;
        assert hull.depth()>mace.depth()*1.4;
        assert hull.bands().get(0).scale()==.72f&&hull.bands().get(1).scale()==.90f;
        assert hull.chassisScale()==.64f&&hull.chassisScale()<hull.bands().get(0).scale();
        assert hull.optimized()&&hull.cameraReferenceDepth()==0;
        assert Math.abs(LayeredHullProfile.fortress(.1f,.40f).depth()-.36)<1e-6;
        assert Math.abs(LayeredHullProfile.fortress(100,.40f).depth()-.75)<1e-6;
        byte[] art=new byte[64*64];
        for(int y=5;y<59;y++)for(int x=3;x<61;x++)if(x<24||x>39||y>12)art[x+y*64]=(byte)255;
        var mesh=LayeredHullGeometry.generate(art,64,64,hull,64,8,32,.2,65520);
        var naive=LayeredHullGeometry.generate(art,64,64,new LayeredHullProfile(hull.bands(),hull.chassisScale(),0),64,8,32,.2,65520);
        assert mesh.vertexCount()<naive.vertexCount();
        int checks=8;
        for(int i=0;i<mesh.vertices().length;i+=8){
            float[] v=mesh.vertices();assert Float.isFinite(v[i+1]);checks++;
            if(v[i+4]>0){assert v[i+1]==.5f;checks++;}
        }
        assert !LayeredHullProfile.dagger().optimized();assert Math.abs(mace.depth()-.425)<1e-6;checks+=2;
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            boolean rejected=false;try{LayeredHullProfile.fortress(invalid,.40f);}catch(IllegalArgumentException expected){rejected=true;}
            assert rejected;checks++;
        }
        System.out.println("FortressHullGeometryTest: "+checks+" assertions; heavier armor, adaptive clamps, exterior-only roof and preserved previous profiles");
    }
}
