package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.*;
import arc.util.ScreenUtils;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.draw.DrawTurret;

/** Opt-in production renderer, real loaded atlas, native part animations and depth-buffer evidence. */
public final class TurretModelVisualChecks {
    private TurretModelVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.elevatedTurrets.smoke"))throw new IllegalStateException("Isolated turret lab required");
        var model=new TurretModelRenderer();var batch=new NativeAtlasMesh.Builder(true);var camera=new Camera3D();
        var buffer=new FrameBuffer(640,480,true);camera.resize(640,480);camera.near=.04f;camera.far=100;
        var shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec3 c;varying vec2 uv;void main(){c=a_color.rgb;uv=a_texCoord0;gl_Position=u_combined*vec4(a_position,1.0);}","uniform sampler2D u_texture;varying vec3 c;varying vec2 uv;void main(){vec4 t=texture2D(u_texture,uv);if(t.a<.1)discard;gl_FragColor=vec4(t.rgb*c,1.0);}");
        var tile=Vars.world.tile(82,32);var output=new Fi(System.getProperty("fpv.smoke.output"));int checks=0;
        var report=new StringBuilder("# Native turret geometry\n\nCached CPU vertex bytes; transformed into shared native-atlas stream. No FPS measurement.\n\n| Turret | Cached vertices after type | Cached bytes after type | Streams |\n|---|---:|---:|---:|\n");
        try{
            for(Block block:new Block[]{Blocks.duo,Blocks.salvo,Blocks.scatter,Blocks.hail,Blocks.lancer,Blocks.cyclone,Blocks.meltdown}){
                tile.setBlock(block,Team.sharded,0);var build=(Turret.TurretBuild)tile.build;var type=(Turret)block;
                require(model.supports(type),block.name+" supported native parts");checks++;
                var drawer=(DrawTurret)type.drawer;float health=build.health,reload=build.reloadCounter;int bullets=mindustry.gen.Groups.bullet.size();
                if(block==Blocks.salvo){
                    float h=fpv.net.HeadlessGeometry.heightFor(block);camera.up.set(0,1,0);camera.position.set(3.6f,h+2.8f,4.9f);camera.lookAt(0,h,0);camera.update();
                    for(float yaw:new float[]{0,90,180,270})for(float pitch:new float[]{-55,0,55,85}){
                        build.rotation=yaw;build.shootWarmup=0;build.curRecoil=0;byte[] idle=draw(buffer,shader,camera,batch,model,build,0,pitch);
                        build.shootWarmup=1;build.curRecoil=1;byte[] firing=draw(buffer,shader,camera,batch,model,build,0,pitch);
                        require(changed(idle,firing)>20,"Salvo mirrored warmup+recoil wings animate at yaw "+yaw+" pitch "+pitch);checks++;
                    }
                    build.rotation=35;build.shootWarmup=1;build.curRecoil=1;draw(buffer,shader,camera,batch,model,build,0,55);capture(buffer,output,"salvo-panels-firing-pitch.png");
                    build.shootWarmup=0;build.curRecoil=0;
                }
                build.rotation=35;build.curRecoil=0;
                for(int floor:new int[]{0,2}){
                    float base=floor*2.5f,h=fpv.net.HeadlessGeometry.heightFor(block),distance=Math.max(1,type.size*.7f);
                    camera.up.set(0,1,0);camera.position.set(2.6f*distance,base+h+2.4f*distance,3.5f*distance);camera.lookAt(0,base+h*.7f,0);camera.update();
                    byte[] oblique=draw(buffer,shader,camera,batch,model,build,base,0);require(visible(oblique)>200,block.name+" Z"+floor+" visible full model");checks++;capture(buffer,output,block.name+"-z"+floor+"-oblique.png");
                    long builds=model.builds();int streams=batch.streamMeshCount();draw(buffer,shader,camera,batch,model,build,base,0);
                    require(model.builds()==builds&&batch.streamMeshCount()==streams,block.name+" instances reuse cached geometry/stream");checks++;
                    byte[] pitched=draw(buffer,shader,camera,batch,model,build,base,55);require(changed(oblique,pitched)>80,block.name+" true upward pitch");checks++;capture(buffer,output,block.name+"-z"+floor+"-pitch.png");
                    require(changed(pitched,draw(buffer,shader,camera,batch,model,build,base,-35))>80,block.name+" downward pitch");checks++;
                    build.rotation=125;require(changed(oblique,draw(buffer,shader,camera,batch,model,build,base,0))>80,block.name+" native horizontal yaw retained");checks++;build.rotation=35;
                    build.recoilOffset.trns(215,type.recoil);build.curRecoil=1;
                    if(build.curRecoils!=null)java.util.Arrays.fill(build.curRecoils,1);
                    require(changed(oblique,draw(buffer,shader,camera,batch,model,build,base,0))>10,block.name+" native body/independent barrel recoil visible");checks++;
                    build.recoilOffset.setZero();build.curRecoil=0;if(build.curRecoils!=null)java.util.Arrays.fill(build.curRecoils,0);
                    camera.up.set(0,0,-1);camera.position.set(0,base+h+5*distance,.01f);camera.lookAt(0,base+h,0);camera.update();require(visible(draw(buffer,shader,camera,batch,model,build,base,0))>100,block.name+" complete top art");checks++;capture(buffer,output,block.name+"-z"+floor+"-top.png");
                    camera.up.set(0,1,0);camera.position.set(2.6f*distance,base+h+.08f*distance,3.5f*distance);camera.lookAt(0,base+h*.85f,0);camera.update();require(visible(draw(buffer,shader,camera,batch,model,build,base,0))>100,block.name+" actual side volume");checks++;capture(buffer,output,block.name+"-z"+floor+"-side.png");
                    camera.up.set(0,1,0);camera.position.set(2.6f*distance,base+h+2.4f*distance,3.5f*distance);camera.lookAt(0,base+h*.7f,0);camera.update();
                    byte[] clear=draw(buffer,shader,camera,batch,model,build,base,0);
                    buffer.begin();try{reset();batch.clear();batch.top(Core.atlas.white(),0,base+h+2,0,18,18,0,.025f,.035f,.055f,false);shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);batch.drawAndClear(shader);model.render(build,batch,0,base,0,h,0);batch.drawAndClear(shader);require(visible(ScreenUtils.getFrameBufferPixels(0,0,640,480,false))<visible(clear)/4,block.name+" solid overhead surface occludes model");checks++;}finally{buffer.end();}
                }
                require(model.vertices(drawer.base)>36,block.name+" base alpha side geometry cached");checks++;
                if(NativeAtlasMesh.valid(block.region)){require(model.vertices(block.region)>36,block.name+" native gun alpha side geometry cached");checks++;}
                for(var part:drawer.parts)if(part instanceof mindustry.entities.part.RegionPart region&&region.drawRegion)for(var art:region.regions){require(model.vertices(art)>0,block.name+" separate native part rendered: "+art);checks++;}
                require(build.health==health&&build.reloadCounter==reload&&mindustry.gen.Groups.bullet.size()==bullets,block.name+" rendering does not mutate health/reload or spawn shots");checks++;
                report.append('|').append(block.name).append('|').append(totalVertices(model)).append('|').append(model.bytes()).append('|').append(batch.streamMeshCount()).append("|\n");
            }
            report.append("\nFallback native Turret drawers (procedural/non-region parts): ");
            for(var block:Vars.content.blocks())if(block instanceof Turret type&&!model.supports(type))report.append(block.name).append(' ');
            require(Gl.getError()==0,"no GL errors");checks++;output.child("turret-model-geometry.md").writeString(report.toString());
            return "PASS: "+checks+" actual GPU checks; seven native turrets, top/side/yaw/55/-35 pitch/recoil/Z0/Z2/world depth, cached atlas stream, no simulated combat changes; "+model.entries()+" silhouettes, "+model.bytes()+" cached CPU bytes, "+batch.streamMeshCount()+" atlas streams";
        }finally{tile.setBlock(Blocks.air);batch.dispose();shader.dispose();buffer.dispose();model.clear();}
    }
    private static long totalVertices(TurretModelRenderer model){return model.bytes()/32;}
    private static byte[] draw(FrameBuffer buffer,Shader shader,Camera3D camera,NativeAtlasMesh.Builder batch,TurretModelRenderer model,Turret.TurretBuild build,float base,float pitch){
        buffer.begin();try{reset();batch.clear();shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);batch.overflowShader=shader;require(model.render(build,batch,0,base,0,fpv.net.HeadlessGeometry.heightFor(build.block),pitch),"production turret path");batch.drawAndClear(shader);return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}finally{buffer.end();}
    }
    private static void reset(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static void capture(FrameBuffer buffer,Fi output,String name){buffer.begin();try{ScreenUtils.saveScreenshot(output.child("turret-model-"+name),0,0,640,480);}finally{buffer.end();}}
    private static int visible(byte[] pixels){int n=0;for(int i=0;i<pixels.length;i+=4)if((pixels[i]&255)>22||(pixels[i+1]&255)>25||(pixels[i+2]&255)>30)n++;return n;}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError("Turret model GPU: "+text);}
}
