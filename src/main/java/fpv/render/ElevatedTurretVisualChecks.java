package fpv.render;

import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.util.ScreenUtils;
import mindustry.content.Blocks;
import mindustry.world.Block;

/** Actual atlas pixels: pitched gun orientation and zero-pitch compatibility with the original renderer. */
public final class ElevatedTurretVisualChecks {
    private ElevatedTurretVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.elevatedTurrets.smoke"))throw new IllegalStateException("Explicit turret lab required");
        var target=new FrameBuffer(640,480,true);var builder=new NativeAtlasMesh.Builder(false);var camera=new Camera3D();camera.resize(640,480);camera.position.set(3,5,7);camera.up.set(0,1,0);camera.lookAt(0,1.4f,0);camera.near=.04f;camera.far=100;camera.update();
        var shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec3 c;varying vec2 uv;void main(){c=a_color.rgb;uv=a_texCoord0;gl_Position=u_combined*vec4(a_position,1.0);}","uniform sampler2D u_texture;varying vec3 c;varying vec2 uv;void main(){vec4 t=texture2D(u_texture,uv);if(t.a<.1)discard;gl_FragColor=vec4(t.rgb*c,t.a);}");int checks=0;
        try{target.begin();try{
            for(Block b:new Block[]{Blocks.duo,Blocks.salvo,Blocks.meltdown}){
                var region=NativeRegions.turret(b);check(NativeAtlasMesh.valid(region),b.name+" native gun preview/fallback region exists");checks++;
                clear();byte[] blank=pixels();builder.clear();builder.top(region,0,1.4f,0,2,2,45,1,1,1,false);draw(builder,shader,camera);byte[] original=pixels();check(changed(blank,original)>50,b.name+" actual native art is visible");checks++;
                clear();builder.clear();builder.pitchedTop(region,0,1.4f,0,2,45,0);draw(builder,shader,camera);byte[] flat=pixels();check(changed(original,flat)<5,b.name+" zero pitch preserves original native texture orientation");checks++;
                clear();builder.clear();builder.pitchedTop(region,0,1.4f,0,2,45,50);draw(builder,shader,camera);byte[] up=pixels();check(changed(flat,up)>50,b.name+" upward pitch produces real perspective geometry");checks++;
                ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child("elevated-turret-"+b.name+"-up.png"),0,0,640,480);
                clear();builder.clear();builder.pitchedTop(region,0,1.4f,0,2,45,-50);draw(builder,shader,camera);check(changed(up,pixels())>50,b.name+" downward pitch is visibly different");checks++;
            }
            check(Gl.getError()==0,"valid OpenGL state after actual turret meshes");checks++;
        }finally{target.end();}return "PASS: "+checks+" GPU pixel checks; original native gun texture/yaw at zero pitch and true up/down perspective, GL0";
        }finally{builder.dispose();shader.dispose();target.dispose();}
    }
    private static void draw(NativeAtlasMesh.Builder b,Shader shader,Camera3D camera){var mesh=b.upload();try{shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);mesh.draw(shader);}finally{mesh.dispose();}}
    private static void clear(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError("Turret GPU: "+text);}
}
