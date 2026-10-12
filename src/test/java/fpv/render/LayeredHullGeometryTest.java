package fpv.render;

public final class LayeredHullGeometryTest {
    public static void main(String[] args){
        byte[] mask=new byte[32*48];
        for(int y=4;y<44;y++)for(int x=3;x<29;x++)if(!(x>=13&&x<19&&y>=19&&y<29))mask[x+y*32]=(byte)255;
        var profile=LayeredHullProfile.dagger();var result=LayeredHullGeometry.generate(mask,32,48,profile,64,8,32,.2,65520);
        assert result.vertexCount()>0&&result.vertexCount()<65520;assert result.topQuads()==result.bottomQuads();
        int checks=2;
        for(var band:profile.bands()){
            var inset=LayeredHullGeometry.inset(mask,32,48,band.scale(),32);int occupied=0;
            for(int i=0;i<mask.length;i++){assert (inset[i]&255)==0||(mask[i]&255)>=32;if((inset[i]&255)>0)occupied++;checks++;}
            assert occupied>0;checks++;
        }
        for(int i=0;i<result.vertices().length;i+=8){
            assert Float.isFinite(result.vertices()[i])&&result.vertices()[i+1]>=-.500001&&result.vertices()[i+1]<=.500001;
            assert result.vertices()[i+6]>=0&&result.vertices()[i+6]<=1&&result.vertices()[i+7]>=0&&result.vertices()[i+7]<=1;checks+=2;
        }
        boolean rejected=false;try{new LayeredHullProfile(java.util.List.of(new LayeredHullProfile.Band(1,.1f),new LayeredHullProfile.Band(.5f,.1f)),.3f,.72f);}catch(IllegalArgumentException expected){rejected=true;}
        assert rejected;checks++;
        var model=new ProceduralUnitModelProfile(.30f,1,.95f,0,0,.10f,.12f,0,0,.36f,0,profile);
        assert Math.abs(model.topOffset(.12,1,1.5,0,0)-.28)<1e-6;
        assert Math.abs(model.weaponUpOffset()-model.weaponThickness()/2-model.topOffset(.12,1,1.5,0,0)-.02)<1e-6;
        assert Math.abs(model.cameraTopOffset(.12,1,1.5,0,0)-.6)<1e-6;
        assert Math.abs(profile.chassisTopOffset(.12,.10,0,0)+.012)<1e-6;checks+=4;
        System.out.println("LayeredHullGeometryTest: "+checks+" assertions; inset silhouette/holes, compact bands, caps, budget, mounted guns, chassis and unchanged eye reference passed");
    }
}
