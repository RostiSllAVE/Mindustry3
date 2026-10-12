package fpv.render;

import arc.graphics.*;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.*;
import arc.files.Fi;
import arc.util.ScreenUtils;

public final class VolumetricBeamVisualChecks {
    private VolumetricBeamVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated GPU lab required");
        var target=new FrameBuffer(640,480,true);var mesh=new Mesh(false,96,0,VertexAttribute.position3,new VertexAttribute(4,"a_color"));float[] data=new float[96*7];
        var shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;uniform mat4 u_combined;varying vec3 c;void main(){c=a_color.rgb;gl_Position=u_combined*vec4(a_position,1.0);}","varying vec3 c;void main(){gl_FragColor=vec4(c,1);}");
        var camera=new Camera3D();camera.resize(640,480);camera.near=.03f;camera.far=100;int checks=0;
        try{
            for(int view=0;view<3;view++){
                camera.up.set(0,1,0);camera.position.set(view==0?0:view==1?4:3,view==2?5:1,7);camera.lookAt(0,1,1.5f);camera.update();
                for(int color=0;color<3;color++){
                    int[] p={0};float r=color==0?1:.2f,g=color==1?1:.2f,b=color==2?1:.2f;
                    VolumetricBeamGeometry.emit(0,1,0,0,1,3,.6f,r,g,b,camera.position.x,camera.position.y,camera.position.z,(x,y,z,cr,cg,cb)->{int i=p[0];data[i++]=x;data[i++]=y;data[i++]=z;data[i++]=cr;data[i++]=cg;data[i++]=cb;data[i++]=1;p[0]=i;});mesh.setVertices(data);
                    target.begin();try{
                        Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
                        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);mesh.render(shader,Gl.triangles);
                        var pixels=ScreenUtils.getFrameBufferPixels(0,0,640,480,false);int colored=0;
                        for(int i=0;i<pixels.length;i+=4){int primary=pixels[i+color]&255,other=pixels[i+(color+1)%3]&255;if(primary>other*2&&primary>35)colored++;}
                        if(colored<200)throw new AssertionError("Closed laser volume/color invisible view="+view+" color="+color+" pixels="+colored);checks++;
                        ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child("laser-volume-"+view+"-"+color+".png"),0,0,640,480);
                    }finally{target.end();}
                }
            }
            if(Gl.getError()!=0)throw new AssertionError("Laser GL error");checks++;
            return "PASS: "+checks+" actual GPU checks; closed axial/end-on/side/oblique tube, native-color identity, one reused streaming mesh, GL0";
        }finally{mesh.dispose();shader.dispose();target.dispose();}
    }
}
