package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.FrameBuffer;
import arc.util.ScreenUtils;
import fpv.units.UnitAltitudeSystem;
import mindustry.gen.Unit;

/** Same cached production FPV limb pass, same pose, same pixels: only armour transforms differ. */
public final class LegPlateVisualChecks {
    private LegPlateVisualChecks(){}
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem alt,Unit unit){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated comparison required");
        var pose=alt.unitState(unit);var family=renderer.groundFamily();var camera=new Camera3D();camera.resize(320,240);camera.near=.015f;camera.far=300;camera.fov=95;
        camera.position.set((float)pose.x(),(float)(pose.z()+Math.max(.1,GroundFamilyRenderer.bodyHalfDepth(unit)*.2)),(float)-pose.y());
        double a=Math.toRadians(unit.rotation);camera.direction.set((float)(Math.cos(a)*.45),-.89f,(float)(-Math.sin(a)*.45)).nor();camera.up.set(0,1,0);camera.update();
        float health=unit.health,x=unit.x,y=unit.y;int count=family.visualLegCount(unit);double[] feet=new double[count];for(int i=0;i<count;i++)feet[i]=family.footHeight(unit,i);
        FrameBuffer target=new FrameBuffer(320,240,true);var body=new SpriteExtrusionRenderer();int[] pixels=new int[2];long builds=0;
        try{for(int pass=0;pass<2;pass++){
            LegPlatePose.labEnabled(pass==1);target.begin();
            try{Gl.disable(Gl.scissorTest);Gl.depthMask(true);Gl.clearColor(0,0,0,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
                family.renderLimbs(unit,(float)pose.x(),(float)pose.z(),(float)-pose.y(),(float)pose.yaw(),(float)pose.pitch(),(float)pose.roll(),camera,1,body,(sx,sy)->renderer.surfaceHeight(sx,-sy));
                var image=ScreenUtils.getFrameBufferPixels(0,0,320,240,false);for(int i=0;i<image.length;i+=4)if((image[i]&255)+(image[i+1]&255)+(image[i+2]&255)>15)pixels[pass]++;
                ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(unit.type.name+"-leg-plates-down-"+(pass==0?"before":"after")+".png"),0,0,320,240);
            }finally{target.end();}
            if(pass==0)builds=family.nativeLimbStats().builds();else require(builds==family.nativeLimbStats().builds(),"same original cached limb meshes");
        }
        require(pixels[0]>100&&pixels[1]>50,"armour remains visible");require(pixels[1]<pixels[0],"reduced FPV downward occlusion "+unit.type.name+": "+pixels[0]+" -> "+pixels[1]);
        require(unit.health==health&&unit.x==x&&unit.y==y,"no native gameplay mutation");for(int i=0;i<count;i++)require(family.footHeight(unit,i)==feet[i],"physical foot support unchanged");require(Gl.getError()==0,"valid OpenGL state");
        return unit.type.name+": downward limb pixels "+pixels[0]+" -> "+pixels[1]+"; original mesh reused, feet/HP unchanged\n";
        }finally{LegPlatePose.labEnabled(true);target.dispose();body.close();}
    }
    private static void require(boolean b,String s){if(!b)throw new IllegalStateException("Leg plate GPU: "+s);}
}
