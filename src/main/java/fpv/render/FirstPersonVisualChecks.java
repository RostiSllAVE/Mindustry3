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
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/** Isolated actual-atlas GPU verification; disposable native units are removed before returning. */
public final class FirstPersonVisualChecks {
    private FirstPersonVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Explicit isolated lab required");
        FrameBuffer target=new FrameBuffer(640,480,true);
        Camera3D camera=new Camera3D();camera.resize(640,480);camera.near=.04f;camera.far=300;camera.fov=80;
        camera.position.set(0,3,0);camera.direction.set(0,0,-1);camera.up.set(0,1,0);camera.update();
        var rig=new FirstPersonRigRenderer();var limbs=new GroundFamilyRenderer();var extrusion=new SpriteExtrusionRenderer();
        Shader worldShader=new Shader("attribute vec3 a_position;uniform vec3 u_color;varying vec3 v_color;void main(){v_color=u_color;gl_Position=vec4(a_position,1);}",
            "varying vec3 v_color;void main(){gl_FragColor=vec4(v_color,1);}");
        Mesh world=new Mesh(true,6,0,VertexAttribute.position3);
        int checks=0;
        try{
            for(UnitType type:new UnitType[]{UnitTypes.flare,UnitTypes.dagger,UnitTypes.fortress,UnitTypes.reign,UnitTypes.toxopid}){
                Unit unit=type.create(Team.sharded);unit.rotation=90;unit.add();
                float health=unit.health;int cargo=unit.stack.amount;target.begin();
                try{
                    clear();byte[] base=pixels();rig.render(camera,unit,1,true);byte[] neutral=pixels();
                    require(rig.stats().weapons()>0,type.name+" original weapon/integrated artwork submitted");checks++;
                    require(changed(base,neutral)>100,type.name+" actual visible perspective weapon pixels");checks++;
                    require(rig.stats().sideFaces()>0&&rig.stats().cachedModels()>0,type.name+" real cached silhouette sides, not a plane/box");checks++;
                    if(type==UnitTypes.flare){require(rig.stats().integratedArt()==1,"Flare uses its own integrated nose art");checks++;}
                    capture("firstperson-"+type.name+"-weapons.png");int streams=rig.stats().streamMeshes();
                    for(var mount:unit.mounts)mount.recoil=1;
                    clear();rig.render(camera,unit,1,true);byte[] firing=pixels();
                    require(rig.stats().maximumRecoil()>0&&changed(neutral,firing)>50,type.name+" native shot recoil moves real projected pixels");checks++;
                    require(rig.stats().streamMeshes()==streams,"perspective mesh buffers reused");checks++;
                    capture("firstperson-"+type.name+"-recoil.png");
                    long builds=rig.stats().meshBuilds();int framebuffer=rig.stats().targetFramebuffer();
                    clear();rig.render(camera,unit,1,true);
                    require(rig.stats().meshBuilds()==builds&&rig.stats().targetFramebuffer()==framebuffer,"geometry and private color/depth target reused");checks++;
                    // An opaque wall in front of every viewmodel vertex must not hide the guns.
                    clear();quad(world,worldShader,-.99f,.12f,.23f,.32f);byte[] wall=pixels();
                    int destination=Gl.getInt(Gl.framebufferBinding);worldShader.bind();int worldProgram=Gl.getInt(Gl.currentProgram);
                    Gl.depthFunc(Gl.less);Gl.depthMask(false);Gl.enable(Gl.cullFace);Gl.enable(Gl.blend);
                    rig.render(camera,unit,1,true);byte[] near=pixels();
                    require(changed(wall,near)>100,type.name+" near wall cannot depth-occlude weapons");checks++;
                    require(Gl.getInt(Gl.framebufferBinding)==destination&&Gl.getInt(Gl.currentProgram)==worldProgram
                        &&Gl.getInt(Gl.depthFunc)==Gl.less&&Gl.getInt(Gl.depthWritemask)==0&&Gl.isEnabled(Gl.cullFace)&&Gl.isEnabled(Gl.blend),"destination/program/depth/cull/blend restored");checks++;
                    capture("firstperson-"+type.name+"-wall.png");
                    Gl.depthMask(true);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);
                    quad(world,worldShader,0,1,0,1);
                    require(java.util.Arrays.equals(near,pixels()),"viewmodel never clears/replaces/writes world depth; hidden world objects remain blocked");checks++;
                    // An enclosed room/ceiling pattern surrounds the eye, with the same nearest depth.
                    clear();quad(world,worldShader,-.99f,.07f,.09f,.12f);
                    worldShader.bind();worldShader.setUniformf("u_color",.18f,.22f,.26f);Gl.enable(Gl.scissorTest);Gl.scissor(0,0,640,100);world.render(worldShader,Gl.triangles);Gl.disable(Gl.scissorTest);
                    byte[] room=pixels();camera.direction.set(.2f,-.1f,-1).nor();camera.update();
                    rig.render(camera,unit,1,true);require(changed(room,pixels())>100,type.name+" enclosed multilayer-room depth retains perspective guns while turning/firing");checks++;
                    capture("firstperson-"+type.name+"-enclosed.png");camera.direction.set(0,0,-1);camera.update();
                    clear();rig.render(camera,unit,1,false);require(changed(base,pixels())==0,"inactive third-person/build renders no viewmodel");checks++;
                    require(unit.health==health&&unit.stack.amount==cargo&&unit.x==0&&unit.y==0,"presentation leaves native combat state unchanged");checks++;
                    unit.dead=true;clear();rig.render(camera,unit,1,true);require(changed(base,pixels())==0,"destroyed owner hides viewmodel");checks++;unit.dead=false;
                    unit.remove();clear();rig.render(camera,unit,1,true);require(changed(base,pixels())==0,"removed owner hides viewmodel");checks++;
                    unit.add();for(var mount:unit.mounts)mount.recoil=0;
                    clear();rig.render(camera,unit,1,true);
                    require(rig.stats().meshBuilds()==builds,"taking control again reuses native and integrated sprite geometry");checks++;
                }finally{target.end();if(unit.isAdded())unit.remove();}
            }
            for(UnitType type:new UnitType[]{UnitTypes.dagger,UnitTypes.toxopid}){
                Unit unit=type.create(Team.sharded);unit.rotation=90;float h=type==UnitTypes.dagger?.15f:2.1f;
                camera.position.set(0,h+.78f,0);camera.direction.set(0,-.78f,-1).nor();camera.up.set(0,1,0);camera.update();
                target.begin();try{
                    clear();byte[] base=pixels();extrusion.begin();limbs.beginFrame();
                    try{require(limbs.renderLimbs(unit,0,h,0,90,0,0,camera,1,extrusion,(x,y)->0),type.name+" first-person limbs submitted");checks++;}
                    finally{limbs.endFrame();extrusion.end();}
                    require(limbs.stats().legs()>0&&changed(base,pixels())>50,type.name+" own feet have visible perspective pixels");checks++;
                    require(extrusion.stats().rendered()==0,"limbs-only does not render enclosing torso");checks++;
                    capture("firstperson-"+type.name+"-legs.png");
                }finally{target.end();}
            }
            require(Gl.getError()==0,"first-person shader and limbs produce no GL errors");checks++;
            return "PASS: "+checks+" actual-atlas GPU checks; Flare integrated nose, Dagger/Fortress/Reign/Toxopid extruded weapons, real side faces, native recoil, wall/enclosed depth independence, unchanged world depth/GL state, dead/removed/inactive owner gates, first-person legs without torso, cached models/targets, no combat mutations";
        }finally{rig.close();limbs.close();extrusion.dispose();target.dispose();world.dispose();worldShader.dispose();}
    }
    private static void quad(Mesh mesh,Shader shader,float z,float r,float g,float b){
        mesh.setVertices(new float[]{-1,-1,z,1,-1,z,1,1,z,-1,-1,z,1,1,z,-1,1,z});
        shader.bind();shader.setUniformf("u_color",r,g,b);mesh.render(shader,Gl.triangles);
    }
    private static void clear(){Gl.disable(Gl.scissorTest);Gl.disable(Gl.blend);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.025f,.035f,.055f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,640,480,false);}
    private static int changed(byte[] first,byte[] second){int n=0;for(int i=0;i<first.length;i+=4)if(Math.abs((first[i]&255)-(second[i]&255))+Math.abs((first[i+1]&255)-(second[i+1]&255))+Math.abs((first[i+2]&255)-(second[i+2]&255))>15)n++;return n;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,640,480);}
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("First-person GPU: "+label);}
}
