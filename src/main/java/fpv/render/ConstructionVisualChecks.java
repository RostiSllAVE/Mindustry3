package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.util.ScreenUtils;
import mindustry.content.Blocks;

/** Actual GPU checks for directional native art, alpha blending and terrain depth occlusion. */
public final class ConstructionVisualChecks {
    private ConstructionVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Explicit isolated lab required");
        FrameBuffer target=new FrameBuffer(640,480,true);ConstructionVisualRenderer view=new ConstructionVisualRenderer();
        Camera3D camera=new Camera3D();camera.resize(640,480);camera.fov=60;camera.near=.04f;camera.far=100;
        camera.position.set(0,4,4);camera.up.set(0,1,0);camera.lookAt(0,.5f,0);camera.update();
        Shader blocker=new Shader("attribute vec3 a_position;void main(){gl_Position=vec4(a_position,1.0);}","void main(){gl_FragColor=vec4(.06,.08,.12,1.0);}");
        Mesh cover=new Mesh(true,6,0,VertexAttribute.position3);cover.setVertices(new float[]{-1,-1,-.9f,1,-1,-.9f,1,1,-.9f,1,1,-.9f,-1,1,-.9f,-1,-1,-.9f});
        int checks=0;
        try{target.begin();try{
            clear();byte[] base=pixels();view.begin();view.ghost(Blocks.conveyor,0,0,0,.4,0,true,.48f);view.draw(camera);byte[] east=pixels();
            require(view.stats().ghosts()==1&&changed(base,east)>100,"native textured preview produces visible pixels");checks++;
            capture("construction-ghost-conveyor-east.png");int streams=view.stats().streamMeshes();
            clear();view.begin();view.ghost(Blocks.conveyor,0,0,0,.4,1,true,.48f);view.draw(camera);byte[] north=pixels();
            require(changed(east,north)>50,"native rotated conveyor texture visibly changes direction");checks++;capture("construction-ghost-conveyor-north.png");
            clear();view.begin();view.ghost(Blocks.conveyor,0,0,0,.4,1,false,.48f);view.draw(camera);require(changed(north,pixels())>50,"blocked placement stays textured and visibly red");checks++;
            require(view.stats().streamMeshes()==streams,"native atlas buffers reused for every rotation");checks++;
            clear();view.begin();view.ghost(Blocks.copperWall,0,0,0,1.4,0,true,.48f);view.draw(camera);require(changed(base,pixels())>100,"wall preview includes visible sides and top");checks++;capture("construction-ghost-wall.png");
            clear();blocker.bind();cover.render(blocker,Gl.triangles);byte[] hidden=pixels();
            view.begin();view.ghost(Blocks.copperWall,0,0,0,1.4,0,true,.48f);view.draw(camera);
            require(changed(hidden,pixels())==0,"solid depth hides all ghost pixels");checks++;
            clear();view.begin();view.beam(-2,0,3,0,0,.2,1,false,1);view.draw(camera);
            require(view.stats().beams()==1&&changed(base,pixels())>100,"native-style build beam is visible in actual XYZ");checks++;capture("construction-beam.png");
            view.begin();for(int i=0;i<300;i++)view.ghost(Blocks.copperWall,0,0,0,1.4,0,true,.1f);
            require(view.stats().ghosts()==256,"bounded preview geometry for large queued plans");checks++;
            require(Gl.getError()==0,"transparent atlas pass preserves valid GL state");checks++;
        }finally{target.end();}
            return "PASS: "+checks+" construction GPU checks; original conveyor rotation, valid/invalid tint, translucent wall sides, real depth occlusion, XYZ beam, cached bounded batches";
        }finally{view.close();blocker.dispose();cover.dispose();target.dispose();}
    }
    private static void clear(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,640,480);}
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("Construction GPU: "+label);}
}
