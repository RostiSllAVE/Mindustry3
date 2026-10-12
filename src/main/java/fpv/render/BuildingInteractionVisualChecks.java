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
import fpv.construction.BuildingMousePicker;
import fpv.construction.LayerCoordinates;

/** Real GPU evidence for green/red preview and scene-depth occlusion, not just compilation. */
public final class BuildingInteractionVisualChecks {
    private BuildingInteractionVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.mouse.smoke"))throw new IllegalStateException("Explicit mouse lab required");
        var view=new BuildingInteractionRenderer();var target=new FrameBuffer(640,480,true);Camera3D camera=new Camera3D();camera.resize(640,480);camera.position.set(0,4,7);camera.lookAt(0,1,0);camera.up.set(0,1,0);camera.near=.04f;camera.far=100;camera.update();
        var left=new BuildingMousePicker.Hit(1,null,1,-2,0,1.4,new LayerCoordinates.Bounds(-2.5,-.5,1.4,-1.5,.5,2.1),true);
        var right=new BuildingMousePicker.Hit(2,null,3,2,0,4.2,new LayerCoordinates.Bounds(1.5,-.5,4.2,2.5,.5,4.9),true);
        Shader blocker=new Shader("attribute vec3 a_position;void main(){gl_Position=vec4(a_position,1.0);}","void main(){gl_FragColor=vec4(.06,.08,.12,1.0);}");
        Mesh cover=new Mesh(true,6,0,VertexAttribute.position3);cover.setVertices(new float[]{-1,-1,-.9f,1,-1,-.9f,1,1,-.9f,1,1,-.9f,-1,1,-.9f,-1,-1,-.9f});int checks=0;
        try{target.begin();try{
            clear();byte[] base=pixels();view.set(left,null,null,false);view.render(camera);require(changed(base,pixels())>30,"floating object's full boundaries create visible pixels");checks++;
            clear();view.set(right,left,right,true);view.render(camera);byte[] green=pixels();require(colored(green,true)>20,"valid XYZ connection is green");checks++;capture("mouse-link-valid.png");
            clear();view.set(right,left,right,false);view.render(camera);byte[] red=pixels();require(colored(red,false)>20&&changed(green,red)>20,"invalid XYZ connection is red");checks++;capture("mouse-link-invalid.png");
            clear();blocker.bind();cover.render(blocker,Gl.triangles);byte[] hidden=pixels();view.render(camera);require(changed(hidden,pixels())==0,"solid depth hides hover and entire preview line");checks++;
            view.set(null,null,null,false);clear();view.render(camera);require(changed(base,pixels())==0,"cancel clears all interaction graphics");checks++;
            require(Gl.getError()==0,"interaction pass leaves valid GL state");checks++;
        }finally{target.end();}return "PASS: "+checks+" GPU pixel checks; 3D boundaries, green/red link preview, opaque depth occlusion and cancellation";}
        finally{view.close();target.dispose();cover.dispose();blocker.dispose();}
    }
    private static void clear(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static int colored(byte[] data,boolean green){int n=0;for(int i=0;i<data.length;i+=4){int r=data[i]&255,g=data[i+1]&255,b=data[i+2]&255;if(green?g>r+70&&g>b+70:r>g+70&&r>b+70)n++;}return n;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,640,480);}
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError("Mouse GPU: "+text);}
}
