package fpv.render;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.geom.Vec3;
import fpv.combat.AircraftWeapons;
import fpv.combat.AircraftWeaponGeometry;
import fpv.combat.WeaponHeightStagger;
import fpv.world.WorldCoordinates.Point;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.type.Weapon;
import java.util.IdentityHashMap;

/** Body is untouched. Cached alpha guns and one shared hollow bomb port follow physical mount transforms. */
final class AircraftWeaponRenderer implements AutoCloseable {
    private final FirstPersonWeaponMeshes guns=new FirstPersonWeaponMeshes();
    private final IdentityHashMap<Weapon,TextureRegion> integrated=new IdentityHashMap<>();
    private final IdentityHashMap<Object,TextureRegion> colors=new IdentityHashMap<>();
    private final Vec3 center=new Vec3(),right=new Vec3(),up=new Vec3(),forward=new Vec3(),eyeRight=new Vec3();
    private AircraftWeapons aim;
    private Mesh port,support;private Shader portShader;private Object atlas;
    int draws,ports;
    double maximumRecoil;
    java.util.function.DoubleBinaryOperator recoil;
    void setAim(AircraftWeapons value){aim=value;}
    void begin(){draws=ports=0;maximumRecoil=0;if(atlas!=Core.atlas){integrated.clear();colors.clear();atlas=Core.atlas;}guns.begin();}
    long builds(){return guns.builds();}long bytes(){return guns.bytes()+(port==null?0:12288)+(support==null?0:2304);}int models(){return guns.entries();}
    int sides(){return guns.sides();}int integratedArt(){return integrated.size();}
    boolean supports(Unit unit){if(aim==null||!aim.supports(unit))return false;for(var m:unit.mounts)if(aim.profile(unit,m)!=null)return true;return false;}
    void render(Unit unit,Camera3D camera,float ambient,boolean fpv){
        if(aim==null)return;
        double maxX=0;if(fpv)for(var m:unit.mounts)maxX=Math.max(maxX,Math.abs(m.weapon.x));
        for(int index=0;index<unit.mounts.length;index++){var mount=unit.mounts[index];
            var s=aim.visual(unit,mount);if(s==null)continue;
            Weapon w=mount.weapon;TextureRegion art=w.region;
            boolean bomb=s.bomber()&&!NativeAtlasMesh.valid(art);
            if(!bomb&&!NativeAtlasMesh.valid(art))art=integrated.computeIfAbsent(w,key->crop(unit,key));
            if(!bomb&&!NativeAtlasMesh.valid(art))continue;
            double width=bomb?Math.max(.15,Math.min(.6,unit.hitSize/Vars.tilesize*.12)):art.width*art.scl()/Vars.tilesize;
            double length=bomb?width:art.height*art.scl()/Vars.tilesize;
            if(fpv){width=bomb?.24:FirstPersonRigPose.width(width/Math.max(.001,length));length=bomb?width:.98;}
            for(int side=1;side>=-1;side-=2){
                if(s.bomber()&&side!=s.side()||fpv&&side!=s.side())continue;
                Point pivot=s.pivot();
                if(side!=s.side())pivot=s.oppositePivot();
                if(!fpv&&!s.bomber())drawSupport(unit,s,pivot,camera,ambient);
                double kick=side==s.side()?FirstPersonRigPose.recoil(mount.recoil,w.recoilPow,w.recoil/Vars.tilesize):0;
                if(fpv&&recoil!=null)kick=recoil.applyAsDouble(index,kick);maximumRecoil=Math.max(maximumRecoil,kick);
                if(fpv){eyeRight.set(camera.direction).crs(camera.up).nor();double fore=FirstPersonRigPose.clamp(w.y/Vars.tilesize*.05,-.12,.12);center.set(camera.position).mulAdd(camera.direction,1.34f+(float)fore-(float)kick).mulAdd(camera.up,-.46f+(float)WeaponHeightStagger.offset(unit,mount)).mulAdd(eyeRight,(float)FirstPersonRigPose.lateral(w.x,maxX));}
                else set(center,AircraftWeaponGeometry.add(pivot,AircraftWeaponGeometry.scale(s.direction(),-kick)));
                set(right,s.right()).scl((float)width*(w.flipSprite?-1:1));set(up,s.up()).scl((float)(fpv?FirstPersonRigPose.thickness(width):s.thickness()));set(forward,s.direction()).scl((float)length);
                if(bomb){
                    var f=s.frame();
                    set(right,f.right()).scl((float)width);set(up,f.up()).scl((float)(width*.5));set(forward,f.forward()).scl((float)width);drawPort(unit,camera,ambient);ports++;draws++;
                }else if(guns.render(camera,art,w.cellRegion,unit,center,right,up,forward,ambient))draws++;
            }
        }
    }
    private static Vec3 set(Vec3 v,Point p){return v.set((float)p.x(),(float)p.z(),(float)-p.y());}
    private TextureRegion crop(Unit unit,Weapon weapon){
        var body=unit.type.region;if(!NativeAtlasMesh.valid(body))return null;
        int width=Math.max(4,Math.min(body.width,(int)(body.width*.28))),height=Math.max(4,Math.min(body.height,(int)(body.height*.35)));
        int x=Math.max(0,Math.min(body.width-width,(int)(body.width*.5+weapon.x*4-width*.5))),y=Math.max(0,Math.min(body.height-height,(int)(body.height*.32-weapon.y*4-height*.5)));
        return new TextureRegion(body,x,y,width,height);
    }
    private TextureRegion sample(Unit unit){
        return colors.computeIfAbsent(unit.type,type->{var art=unit.type.region;
            if(!NativeAtlasMesh.valid(art))return Core.atlas.white();var pixels=Core.atlas.getPixmaps().get(art.texture,()->art.texture.getTextureData().getPixmap());
            for(int radius=0;radius<Math.max(art.width,art.height);radius++)for(int x=Math.max(0,art.width/2-radius);x<=Math.min(art.width-1,art.width/2+radius);x++){
                int y=Math.max(0,Math.min(art.height-1,art.height/2-radius));if(pixels.getA(art.getX()+x,art.getY()+y)>200)return new TextureRegion(art,x,y,1,1);
            }return Core.atlas.white();});
    }
    private void drawPort(Unit unit,Camera3D camera,float ambient){
        if(port==null){var vertices=AircraftBombPortGeometry.generate();port=new Mesh(true,vertices.length/8,0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords);port.setVertices(vertices);}
        drawSolid(port,unit,camera,ambient);
    }
    private void drawSupport(Unit unit,AircraftWeapons.Solution s,Point pivot,Camera3D camera,float ambient){
        var body=aim.body(unit);var bodyCenter=new Point(body.x(),body.y(),body.z());var offset=s.frame().local(AircraftWeaponGeometry.difference(pivot,bodyCenter));
        double half=AircraftWeapons.walker(unit.type)?body.halfHeight():Math.min(Math.max(.15,Math.min(.75,unit.hitSize/(double)Vars.tilesize*.1)),Math.max(.025,SpriteExtrusionRenderer.halfDepth(unit))),gap=Math.max(.01,Math.abs(offset.y())-half),side=Math.signum(offset.y());
        if(support==null){float[] vertices=new float[36*8];int n=0;int[][] faces={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
            for(int f=0;f<6;f++)for(int corner:new int[]{0,1,2,0,2,3}){int v=faces[f][corner];vertices[n++]=(v&1)==0?-.5f:.5f;vertices[n++]=(v&2)==0?-.5f:.5f;vertices[n++]=(v&4)==0?-.5f:.5f;vertices[n++]=f==4?-1:f==5?1:0;vertices[n++]=f==2?-1:f==3?1:0;vertices[n++]=f==0?-1:f==1?1:0;vertices[n++]=.5f;vertices[n++]=.5f;}
            support=new Mesh(true,36,0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords);support.setVertices(vertices);
        }
        double width=Math.max(.07,Math.min(.2,unit.hitSize/(double)Vars.tilesize*.05));
        set(center,AircraftWeaponGeometry.add(bodyCenter,s.frame().world(offset.x(),side*(half+gap*.5),offset.z())));
        set(right,s.frame().right()).scl((float)width);set(up,s.frame().up()).scl((float)gap);set(forward,s.frame().forward()).scl((float)(width*1.2));drawSolid(support,unit,camera,ambient);
    }
    private void drawSolid(Mesh geometry,Unit unit,Camera3D camera,float ambient){
        if(portShader==null)portShader=new Shader("attribute vec3 a_position;attribute vec3 a_normal;uniform mat4 u_combined;uniform vec3 center,right,up,back;varying vec3 n;void main(){n=a_normal;gl_Position=u_combined*vec4(center+right*a_position.x+up*a_position.y+back*a_position.z,1.0);}","uniform sampler2D u_texture;uniform vec2 uv;uniform float ambient;varying vec3 n;void main(){vec3 c=texture2D(u_texture,uv).rgb;gl_FragColor=vec4(c*ambient*(.55+.3*abs(n.y)),1.0);}");
        var art=sample(unit);portShader.bind();portShader.setUniformMatrix4("u_combined",camera.combined.val);portShader.setUniformf("center",center);portShader.setUniformf("right",right);portShader.setUniformf("up",up);portShader.setUniformf("back",forward);portShader.setUniformf("uv",(art.u+art.u2)*.5f,(art.v+art.v2)*.5f);portShader.setUniformf("ambient",Math.max(.35f,ambient));portShader.setUniformi("u_texture",0);art.texture.bind(0);geometry.render(portShader,Gl.triangles);
    }
    public void close(){guns.close();integrated.clear();colors.clear();if(port!=null)port.dispose();if(support!=null)support.dispose();if(portShader!=null)portShader.dispose();}
}
