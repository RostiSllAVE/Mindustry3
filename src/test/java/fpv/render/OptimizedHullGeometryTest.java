package fpv.render;

import java.util.Arrays;

/** Exterior topology, original roof and bounded cache data; no host art or simulation mutation. */
public final class OptimizedHullGeometryTest {
    public static void main(String[] args){
        byte[] alpha=new byte[48*64];for(int y=3;y<61;y++)for(int x=3;x<45;x++)
            if(!(x>19&&x<28&&y>24&&y<40))alpha[x+y*48]=(byte)255;
        var profile=LayeredHullProfile.mace(1.25f,.34f);
        var legacyProfile=new LayeredHullProfile(profile.bands(),profile.chassisScale(),profile.cameraReferenceDepth());
        var result=LayeredHullGeometry.generate(alpha,48,64,profile,64,8,32,.2,65520);
        var legacy=LayeredHullGeometry.generate(alpha,48,64,legacyProfile,64,8,32,.2,65520);
        var flat=SpriteExtrusionGeometry.generate(alpha,48,64,64,8,32,.2,65520);
        assert result.topQuads()==flat.topQuads():"one original roof, not three sprites";
        assert result.vertexCount()<legacy.vertexCount():"merged exterior is smaller";
        assert Math.abs(profile.depth()-.425)<1e-6;
        assert LayeredHullProfile.mace(.1f,.34f).depth()>.239;
        assert LayeredHullProfile.mace(10,.34f).depth()<.601;
        int checks=5;
        for(int q=0;q<result.vertices().length;q+=48){
            float[] v=result.vertices();float ny=v[q+4],height=v[q+1];
            if(ny>0){assert height==.5f:"no hidden upward caps";checks++;}
            else if(ny<0){assert height<.499f;for(int i=8;i<48;i+=8){assert v[q+6]==v[q+i+6]&&v[q+7]==v[q+i+7]:"undersides use a sampled material, not full sprite";checks++;}}
            for(int i=0;i<48;i++){assert Float.isFinite(v[q+i]);checks++;}
            for(int i=0;i<48;i+=8){assert v[q+i+1]>=-.500001&&v[q+i+1]<=.500001;checks++;}
        }
        // Deterministic output and tight budgets, including checkerboards/empty/disconnected silhouettes.
        assert Arrays.equals(result.vertices(),LayeredHullGeometry.generate(alpha,48,64,profile,64,8,32,.2,65520).vertices());checks++;
        byte[] checker=new byte[64*64];for(int i=0;i<checker.length;i++)if((i%64+i/64)%2==0)checker[i]=(byte)255;
        var bounded=LayeredHullGeometry.generate(checker,64,64,profile,64,8,32,.2,5000);assert bounded.vertexCount()<=5000;checks++;
        assert LayeredHullGeometry.generate(new byte[16],4,4,profile,64,1,32,.2,1000).vertexCount()==0;checks++;
        // Dagger is still dispatched through its exact original full-band generator.
        var dagger=LayeredHullProfile.dagger();assert !dagger.optimized();
        var old=LayeredHullGeometry.generate(alpha,48,64,dagger,64,8,32,.2,65520);
        assert old.topQuads()==old.bottomQuads();checks+=2;
        System.out.println("OptimizedHullGeometryTest: "+checks+" assertions; original roof, exterior-only caps, nested contours, merged sides, memory reduction, adaptive thickness, budgets and legacy Dagger path");
    }
}
