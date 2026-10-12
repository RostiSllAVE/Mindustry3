package fpv.render;

/** T4 compact armor, exterior efficiency and independent T1/T2/T3 profiles. */
public final class ScepterHullGeometryTest {
    public static void main(String[] args){
        var hull=LayeredHullProfile.scepter(22f/8,.30f);var fortress=LayeredHullProfile.fortress(13f/8,.40f);
        assert Math.abs(hull.depth()-.825)<1e-6;
        assert hull.depth()>fortress.depth()*1.2;
        assert hull.bands().get(0).scale()==.78f&&hull.bands().get(1).scale()==.94f;
        assert hull.chassisScale()==.70f&&hull.chassisScale()<hull.bands().get(0).scale();
        assert hull.optimized()&&hull.cameraReferenceDepth()==0;
        assert Math.abs(LayeredHullProfile.scepter(.1f,.30f).depth()-.54)<1e-6;
        assert Math.abs(LayeredHullProfile.scepter(100,.30f).depth()-.88)<1e-6;
        byte[] art=new byte[64*64];for(int y=4;y<60;y++)for(int x=3;x<61;x++)if(x<24||x>39||y>12)art[x+y*64]=(byte)255;
        var mesh=LayeredHullGeometry.generate(art,64,64,hull,64,8,32,.2,65520);
        var naive=LayeredHullGeometry.generate(art,64,64,new LayeredHullProfile(hull.bands(),hull.chassisScale(),0),64,8,32,.2,65520);
        assert mesh.vertexCount()<naive.vertexCount();int checks=8;
        for(int i=0;i<mesh.vertices().length;i+=8){
            float[] v=mesh.vertices();assert Float.isFinite(v[i+1]);checks++;
            if(v[i+4]>0){assert v[i+1]==.5f;checks++;}
        }
        assert !LayeredHullProfile.dagger().optimized();assert Math.abs(fortress.depth()-.65)<1e-6;
        assert Math.abs(LayeredHullProfile.mace(1.25f,.34f).depth()-.425)<1e-6;checks+=3;
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            boolean rejected=false;try{LayeredHullProfile.scepter(invalid,.30f);}catch(IllegalArgumentException expected){rejected=true;}
            assert rejected;checks++;
        }
        System.out.println("ScepterHullGeometryTest: "+checks+" assertions; T4 broad armor, adaptive clamps, exterior roof and unchanged prior profiles");
    }
}
