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
import fpv.construction.LayerRuntime;

/** Actual cached wall/conveyor meshes on an isolated SDL context, with depth and pixel comparisons. */
public final class LayerWallVisualChecks {
    private LayerWallVisualChecks(){}
    public static String run(LayerRuntime runtime,long conveyorId){
        if(!Boolean.getBoolean("fpv.placement.smoke"))throw new IllegalStateException("Explicit construction lab required");
        var conveyor=runtime.grid().get(conveyorId);var bounds=runtime.grid().coordinates().bounds(conveyor);
        var view=new LayerWorldRenderer();view.setRuntime(runtime);var target=new FrameBuffer(640,480,true);
        var camera=new Camera3D();camera.resize(640,480);camera.near=.04f;camera.far=100;camera.fov=60;camera.up.set(0,1,0);
        var shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;uniform vec3 u_offset;varying vec4 c;varying vec2 uv;void main(){c=a_color;uv=a_texCoord0;gl_Position=u_combined*vec4(a_position+u_offset,1.0);}",
            "uniform sampler2D u_texture;varying vec4 c;varying vec2 uv;void main(){vec4 t=texture2D(u_texture,uv);if(t.a<.1)discard;gl_FragColor=vec4(t.rgb*c.rgb,1.0);}");
        var blocker=new Shader("attribute vec3 a_position;void main(){gl_Position=vec4(a_position,1.0);}","void main(){gl_FragColor=vec4(.02,.03,.04,1.0);}");
        var cover=new Mesh(true,6,0,VertexAttribute.position3);cover.setVertices(new float[]{-1,-1,-.9f,1,-1,-.9f,1,1,-.9f,1,1,-.9f,-1,1,-.9f,-1,-1,-.9f});int checks=0;
        try{target.begin();try{
            for(int angle=0;angle<4;angle++){
                double a=angle*Math.PI/2;
                camera.up.set(0,1,0);
                camera.position.set((float)(bounds.centerX()+Math.cos(a)*5),(float)(bounds.minZ()+4),(float)(-bounds.centerY()+Math.sin(a)*5));
                camera.lookAt((float)bounds.centerX(),(float)(bounds.minZ()-.6),(float)-bounds.centerY());camera.update();
                runtime.grid().remove(conveyorId);clear();draw(view,camera,shader);byte[] wall=pixels();
                runtime.grid().add(conveyor);clear();draw(view,camera,shader);byte[] placed=pixels();
                require(changed(wall,placed)>100,"conveyor is visible above the tall wall from angle "+angle);checks++;
                new Fi(System.getProperty("fpv.smoke.output")).mkdirs();ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child("wall-top-conveyor-"+angle+".png"),0,0,640,480);
                long builds=view.meshBuilds();clear();draw(view,camera,shader);require(changed(placed,pixels())==0&&view.meshBuilds()==builds,"stationary view is stable and reuses cached meshes at angle "+angle);checks++;
            }
            clear();draw(view,camera,shader);byte[] north=pixels();
            long builds=view.meshBuilds();require(runtime.requestRotate(conveyorId,0).accepted(),"rotation uses authority");
            clear();draw(view,camera,shader);require(changed(north,pixels())>50,"rotated native conveyor artwork changes visible pixels");checks++;
            require(view.meshBuilds()>builds,"rotation invalidates cached oriented solid faces");checks++;
            require(runtime.requestRotate(conveyorId,conveyor.rotation()).accepted(),"original direction restored");
            clear();blocker.bind();cover.render(blocker,Gl.triangles);byte[] hidden=pixels();draw(view,camera,shader);
            require(changed(hidden,pixels())==0,"opaque depth occludes real wall and conveyor geometry");checks++;
            var tile=mindustry.Vars.world.tile(54,46);boolean permission=Core.settings.getBool(LayerRuntime.FREE_PERMISSION,false);
            if(tile.block()!=mindustry.content.Blocks.air)throw new IllegalStateException("Empty ground joint fixture required");
            long slabId=0;
            try{
                Core.settings.put(LayerRuntime.FREE_PERMISSION,true);tile.setBlock(mindustry.content.Blocks.titaniumWall,mindustry.game.Team.sharded,0);
                var placed=runtime.requestPlace(54,46,1,"",true,0,fpv.construction.LayerComponent.Mode.FREE);require(placed.accepted(),"authorized ground-joint fixture");slabId=placed.componentId();
                camera.up.set(0,1,0);camera.position.set(54,6,-42);camera.lookAt(54,1.4f,-46);camera.update();
                clear();draw(view,camera,shader);byte[] embedded=pixels();long cached=view.meshBuilds();long revision=runtime.grid().geometryRevision();
                tile.setAir();clear();draw(view,camera,shader);
                require(runtime.grid().geometryRevision()==revision&&view.meshBuilds()>cached&&changed(embedded,pixels())>100,"native ground removal exposes the slab without waiting for an upper-grid mutation");checks++;
            }finally{if(slabId!=0)runtime.requestRemove(slabId);tile.setAir();Core.settings.put(LayerRuntime.FREE_PERMISSION,permission);}
            require(Gl.getError()==0,"no GL error after wall/depth/cache passes");checks++;
        }finally{target.end();}
            return checks+" wall/conveyor GPU pixel checks (four angles, stable frames, rotation, opaque occlusion, native ground-joint invalidation)";
        }finally{if(runtime.grid().get(conveyorId)==null)runtime.grid().add(conveyor);view.close();target.dispose();shader.dispose();blocker.dispose();cover.dispose();}
    }
    private static void draw(LayerWorldRenderer view,Camera3D camera,Shader shader){shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformf("u_offset",0,0,0);shader.setUniformi("u_texture",0);view.render(camera,shader);}
    private static void clear(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.depthFunc(Gl.lequal);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError("Wall GPU: "+label);}
}
