package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.Groups;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.units.UnitFactory;

/** Compares actual GPU pixels for native recipe progress, carried items and liquid state on two floors. */
public final class NativeBlockAnimationVisualChecks {
    public static String run(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated GL lab required");
        var source=Vars.world.tile(64,64);var adapter=new NativeBlockAnimationAdapter();var batch=new NativeAtlasMesh.Builder(true);
        FrameBuffer buffer=new FrameBuffer(640,480,true);Camera3D camera=new Camera3D();camera.resize(640,480);camera.near=.04f;camera.far=100;
        Shader shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec4 c;varying vec2 uv;void main(){c=a_color;uv=a_texCoord0;gl_Position=u_combined*vec4(a_position,1.0);}","uniform sampler2D u_texture;varying vec4 c;varying vec2 uv;void main(){vec4 t=texture2D(u_texture,uv);if(t.a<.1)discard;gl_FragColor=vec4(t.rgb*c.rgb,1.0);}");
        float oldTime=Time.time;int actors=Groups.unit.size(),checks=0;
        try{
            source.setBlock(Blocks.navalFactory,Vars.player.team(),0);var factory=(UnitFactory.UnitFactoryBuild)source.build;factory.currentPlan=0;factory.speedScl=1;factory.time=120;
            var plan=((UnitFactory)factory.block).plans.get(0);
            for(int layer:new int[]{0,2}){
                float base=layer*2.5f;camera.position.set(0,base+6,6);camera.up.set(0,1,0);camera.lookAt(0,base+.8f,0);camera.update();
                factory.progress=plan.time*.15f;byte[] partial=draw(buffer,shader,camera,batch,adapter,factory,base);
                factory.progress=plan.time*.8f;byte[] complete=draw(buffer,shader,camera,batch,adapter,factory,base);
                require(changed(partial,complete)>50,"recipe progress changes actual assembly pixels Z"+layer);checks++;
                capture(buffer,"native-factory-assembly-z"+layer+".png");
                int streams=batch.streamMeshCount();draw(buffer,shader,camera,batch,adapter,factory,base);require(batch.streamMeshCount()==streams,"cached atlas mesh reused");checks++;
                require(Groups.unit.size()==actors,"assembly preview creates no native unit");checks++;
            }
            factory.progress=0;var released=UnitTypes.risso.create(factory.team);released.set(factory.x,factory.y);factory.payload=new mindustry.world.blocks.payloads.UnitPayload(released);
            draw(buffer,shader,camera,batch,adapter,factory,0);require(!adapter.releaseFlash(factory),"stored payload does not fake completion");checks++;
            released.add();factory.payload=null;draw(buffer,shader,camera,batch,adapter,factory,0);require(adapter.releaseFlash(factory),"completion flash requires a real released unit");checks++;
            Time.time+=25;draw(buffer,shader,camera,batch,adapter,factory,0);require(!adapter.releaseFlash(factory),"completion flash expires without repetition");checks++;released.remove();
            source.setBlock(Blocks.conveyor,Vars.player.team(),0);var belt=(Conveyor.ConveyorBuild)source.build;belt.efficiency=1;belt.enabled=true;belt.len=1;belt.ids[0]=Items.copper;belt.ys[0]=.1f;
            camera.position.set(0,6,6);camera.lookAt(0,.8f,0);camera.update();Time.time=0;byte[] beltStart=draw(buffer,shader,camera,batch,adapter,belt,0);
            Time.time=4;belt.ys[0]=.9f;byte[] beltEnd=draw(buffer,shader,camera,batch,adapter,belt,0);require(changed(beltStart,beltEnd)>30,"native moving item/frame visibly moves");checks++;capture(buffer,"native-conveyor-items.png");
            source.setBlock(Blocks.liquidTank,Vars.player.team(),0);var tank=source.build;tank.liquids.add(Liquids.water,tank.block.liquidCapacity*.1f);byte[] low=draw(buffer,shader,camera,batch,adapter,tank,0);
            tank.liquids.add(Liquids.water,tank.block.liquidCapacity*.8f);byte[] high=draw(buffer,shader,camera,batch,adapter,tank,0);require(changed(low,high)>50,"native tank fill changes actual pixels");checks++;capture(buffer,"native-liquid-tank.png");
            tank.liquids.clear();tank.liquids.add(Liquids.oil,tank.block.liquidCapacity*.9f);require(changed(high,draw(buffer,shader,camera,batch,adapter,tank,0))>50,"native liquid type uses real color/frame");checks++;
            require(Groups.unit.size()==actors&&Gl.getError()==0,"no duplicate actors or GL errors");checks++;
            return "PASS: "+checks+" actual GPU checks; authoritative recipe mask at Z0/Z2, cached streaming meshes, no preview entities, animated conveyor items and native tank amount/liquid colors";
        }finally{source.setBlock(Blocks.air);Time.time=oldTime;adapter.clear();batch.dispose();shader.dispose();buffer.dispose();}
    }
    private static byte[] draw(FrameBuffer buffer,Shader shader,Camera3D camera,NativeAtlasMesh.Builder batch,NativeBlockAnimationAdapter adapter,mindustry.gen.Building b,float base){
        buffer.begin();try{Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);batch.overflowShader=shader;batch.clear();adapter.render(b,batch,0,base,0,b.block.size,.8f);batch.drawAndClear(shader);return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}finally{buffer.end();}
    }
    private static void capture(FrameBuffer buffer,String name){buffer.begin();try{ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,640,480);}finally{buffer.end();}}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException("Native block animation GPU: "+message);}
}
