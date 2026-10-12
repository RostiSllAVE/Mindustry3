package fpv.render;

/** T5 exterior hull dimensions, adaptive bounds and exact prior profiles. */
public final class ReignHullGeometryTest {
    public static void main(String[] args){
        var hull=LayeredHullProfile.reign(30f/8,.30f);var scepter=LayeredHullProfile.scepter(22f/8,.30f);
        assert Math.abs(hull.depth()-1.125)<1e-6;
        assert hull.depth()>scepter.depth()*1.3;
        assert hull.bands().get(0).scale()==.84f&&hull.bands().get(1).scale()==.96f;
        assert hull.chassisScale()==.76f&&hull.chassisScale()<hull.bands().get(0).scale();
        assert hull.optimized()&&hull.cameraReferenceDepth()==0;
        assert Math.abs(LayeredHullProfile.reign(.1f,.30f).depth()-.72)<1e-6;
        assert Math.abs(LayeredHullProfile.reign(100,.30f).depth()-1.20)<1e-6;
        for(var band:LayeredHullProfile.reign(100,.30f).bands())assert band.height()<=.45f;
        byte[] art=new byte[64*64];for(int y=3;y<61;y++)for(int x=2;x<62;x++)if(x<24||x>39||y>12)art[x+y*64]=(byte)255;
        var mesh=LayeredHullGeometry.generate(art,64,64,hull,64,8,32,.2,65520);
        var naive=LayeredHullGeometry.generate(art,64,64,new LayeredHullProfile(hull.bands(),hull.chassisScale(),0),64,8,32,.2,65520);
        assert mesh.vertexCount()<naive.vertexCount();int checks=11;
        for(int i=0;i<mesh.vertices().length;i+=8){
            float[] v=mesh.vertices();assert Float.isFinite(v[i+1]);checks++;
            if(v[i+4]>0){assert v[i+1]==.5f;checks++;}
        }
        assert !LayeredHullProfile.dagger().optimized();assert Math.abs(LayeredHullProfile.dagger().depth()-.30)<1e-6;
        assert Math.abs(LayeredHullProfile.mace(1.25f,.34f).depth()-.425)<1e-6;
        assert Math.abs(LayeredHullProfile.fortress(1.625f,.40f).depth()-.65)<1e-6;
        assert Math.abs(scepter.depth()-.825)<1e-6;checks+=5;
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            boolean rejected=false;try{LayeredHullProfile.reign(invalid,.30f);}catch(IllegalArgumentException expected){rejected=true;}
            assert rejected;checks++;
        }
        boolean rejected=false;try{new LayeredHullProfile.Band(1,.451f);}catch(IllegalArgumentException expected){rejected=true;}
        assert rejected;checks++;
        System.out.println("ReignHullGeometryTest: "+checks+" assertions; T5 compact armor, exterior-only roof, bounded plates and unchanged T1-T4");
    }
}
