package fpv.render;

import fpv.units.ground.GroundStanceState;
import fpv.units.ground.GroundUnit3DModelProfile;

/** Vela-only compact mech proportions and default stance; manual limits and other profiles stay intact. */
public final class VelaMechHullTest {
    public static void main(String[] args){
        int checks=0;var hull=LayeredHullProfile.vela(3,.32f);
        assert Math.abs(hull.depth()-.96)<1e-6;checks++;
        assert hull.optimized()&&hull.bands().get(2).scale()==1;checks++;
        assert hull.depth()>LayeredHullProfile.scepter(2.75f,.30f).depth();checks++;
        assert hull.chassisScale()==.72f&&hull.bands().get(0).scale()==.82f;checks++;
        assert Math.abs(LayeredHullProfile.vela(.1f,.32f).depth()-.70)<1e-6;checks++;
        assert Math.abs(LayeredHullProfile.vela(100,.32f).depth()-1.12)<1e-6;checks++;
        byte[] alpha=new byte[48*40];for(int y=2;y<38;y++)for(int x=2;x<46;x++)if(y>10||x<15||x>32)alpha[x+y*48]=(byte)255;
        var mesh=LayeredHullGeometry.generate(alpha,48,40,hull,48,8,32,.13,65520);
        var naive=LayeredHullGeometry.generate(alpha,48,40,new LayeredHullProfile(hull.bands(),hull.chassisScale(),0),48,8,32,.13,65520);
        assert mesh.vertexCount()<naive.vertexCount()&&mesh.sideQuads()>4;checks++;
        for(int i=0;i<mesh.vertices().length;i+=8){
            float[] v=mesh.vertices();assert Float.isFinite(v[i])&&Float.isFinite(v[i+1])&&Float.isFinite(v[i+2]);checks++;
            if(v[i+4]>.9f){assert v[i+1]==.5f;checks++;}
        }
        var vela=new GroundUnit3DModelProfile("vela",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,2,1,.3,10,.2,1);
        var corvus=new GroundUnit3DModelProfile("corvus",GroundUnit3DModelProfile.BodyStyle.LEGGED,0,4,1,.3,10,.2,1);
        for(double offset:new double[]{1,2,3}){
            var stance=new GroundStanceState(offset,vela);assert Math.abs(stance.defaultHeight(vela)-1.10)<1e-12;checks++;
            assert new GroundStanceState(offset,corvus).defaultHeight(corvus)==.5*corvus.maximumClearance()+offset;checks++;
            for(int i=0;i<500;i++)stance.control(vela,true,1,.1,4);
            assert stance.desired(vela)==vela.maximumClearance();checks++;
            for(int i=0;i<500;i++)stance.control(vela,true,-1,.1,4);
            assert stance.desired(vela)==vela.minimumClearance();checks++;
            stance.control(vela,false,0,.1,4);assert stance.desired(vela)==1.10;checks++;
            assert stance.randomOffset()==offset;checks++;
        }
        assert Math.abs(LayeredHullProfile.dagger().depth()-.30)<1e-6;checks++;
        assert Math.abs(LayeredHullProfile.mace(1.25f,.34f).depth()-.425)<1e-6;checks++;
        assert Math.abs(LayeredHullProfile.fortress(1.625f,.40f).depth()-.65)<1e-6;checks++;
        assert Math.abs(LayeredHullProfile.scepter(2.75f,.30f).depth()-.825)<1e-6;checks++;
        assert Math.abs(LayeredHullProfile.reign(3.75f,.30f).depth()-1.125)<1e-6;checks++;
        System.out.println("VelaMechHullTest: "+checks+" compact exterior, native roof, baseline/manual maximum and unchanged other profiles checks passed");
    }
}
