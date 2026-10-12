package fpv.render;

import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import fpv.defense.ForceProjectorSystem;
import fpv.generated.ForceConfig;
import java.util.HashSet;
import mindustry.Vars;
import mindustry.gen.Building;

/** Closed regular-polygon shell shares its vertices and exact height with authoritative shield contact. */
public final class ForceProjectorRenderer {
    private static final String VERTEX="attribute vec3 a_position;attribute vec4 a_color;uniform mat4 u_combined;varying vec4 v_color;void main(){v_color=a_color;gl_Position=u_combined*vec4(a_position,1.0);}";
    private static final String FRAGMENT="varying vec4 v_color;void main(){gl_FragColor=v_color;}";
    private final float[] data=new float[(int)ForceConfig.BUFFER_VERTICES*7];
    private final HashSet<Building> submitted=new HashSet<>();
    private final Color color=new Color();
    private final float[] a=new float[3],b=new float[3],c=new float[3],d=new float[3],center=new float[3];
    private int count,faces;
    private Mesh mesh;
    private Shader shader;
    public boolean wasSubmitted(Building building){return submitted.contains(building);}
    public int faceCount(){return faces;}
    public void clear(){submitted.clear();count=0;faces=0;}
    /** Draw after opaque world geometry; no GPU state leaks into Mindustry UI or the next frame. */
    public void render(Camera3D camera,ForceProjectorSystem system){
        render(camera,system,null);
    }
    public void render(Camera3D camera,ForceProjectorSystem system,fpv.construction.LayerRuntime construction){
        clear();if(system==null&&construction==null)return;
        int sr=Gl.getInt(Gl.blendSrcRgb),dr=Gl.getInt(Gl.blendDstRgb),sa=Gl.getInt(Gl.blendSrcAlpha),da=Gl.getInt(Gl.blendDstAlpha);
        int er=Gl.getInt(Gl.blendEquationRgb),ea=Gl.getInt(Gl.blendEquationAlpha),program=Gl.getInt(Gl.currentProgram),df=Gl.getInt(Gl.depthFunc);
        boolean blend=Gl.isEnabled(Gl.blend),cull=Gl.isEnabled(Gl.cullFace),depth=Gl.isEnabled(Gl.depthTest),write=Gl.getInt(Gl.depthWritemask)!=0;
        try{
            Gl.enable(Gl.blend);Gl.blendEquation(Gl.funcAdd);Gl.blendFuncSeparate(Gl.srcAlpha,Gl.one,Gl.zero,Gl.one);
            Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(false);Gl.depthFunc(Gl.lequal);
            if(system!=null)for(var v:system.volumes())submit(camera,v.building(),v.x(),v.y(),v.bottom(),v.top(),v.radius(),v.sides(),v.rotation(),v.flash());
            if(construction!=null){
                for(var v:construction.baseShields().volumes())submit(camera,v.building(),v.x(),v.y(),v.bottom(),v.top(),v.radius(),v.sides(),v.rotation(),v.flash());
                for(var entry:construction.nativeBuildings().entries())if(entry.building() instanceof mindustry.world.blocks.defense.ShieldWall.ShieldWallBuild wall&&wall.shieldRadius>.001f){
                    var component=construction.grid().get(entry.id());if(component==null)continue;double bottom=construction.planeHeight(component.layer());
                    float depletion=1-wall.shield/Math.max(1,((mindustry.world.blocks.defense.ShieldWall)wall.block).shieldHealth);
                    submit(camera,wall,wall.x/Vars.tilesize,wall.y/Vars.tilesize,bottom,bottom+component.height()+.025,component.size()*.707107*wall.shieldRadius,4,Math.PI*.25,Math.max(0,Math.min(1,depletion))*.2f);
                }
            }
            flush();
        }finally{
            count=0;Gl.depthMask(write);Gl.depthFunc(df);enabled(Gl.depthTest,depth);enabled(Gl.cullFace,cull);
            Gl.blendEquationSeparate(er,ea);Gl.blendFuncSeparate(sr,dr,sa,da);enabled(Gl.blend,blend);Gl.useProgram(program);
        }
    }
    private void submit(Camera3D camera,Building building,double x,double y,double bottom,double top,double radius,int sides,double rotation,float flash){
        if(building==null||radius<=0||top<=bottom||sides<3||sides>128||Vars.player!=null&&building.inFogTo(Vars.player.team()))return;
        if(!camera.frustum.containsBounds((float)x,(float)((top+bottom)*.5),(float)-y,(float)radius,(float)((top-bottom)*.5),(float)radius))return;
        if(shader==null){shader=new Shader(VERTEX,FRAGMENT);mesh=new Mesh(false,data.length/7,0,VertexAttribute.position3,new VertexAttribute(4,"a_color"));}
        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);color.set(building.team.color).lerp(Color.white,flash);
        shell(x,y,bottom,top,radius,sides,rotation,flash);submitted.add(building);
    }
    private void shell(double x,double y,double bottom,double top,double radius,int sides,double rotation,float flash){
        float fill=(float)(ForceConfig.FILL_ALPHA+flash*ForceConfig.FLASH_ALPHA),outline=(float)(ForceConfig.EDGE_ALPHA+flash*ForceConfig.FLASH_ALPHA);
        float width=(float)Math.max(.012,radius*ForceConfig.EDGE_RADIUS_FRACTION);
        for(int side=0;side<sides;side++){
            point(a,x,y,radius,sides,rotation,side,bottom);point(b,x,y,radius,sides,rotation,side+1,bottom);point(c,x,y,radius,sides,rotation,side+1,top);point(d,x,y,radius,sides,rotation,side,top);
            reserve(12);triangle(a,b,c,fill);triangle(a,c,d,fill);faces++;
            center[0]=(float)x;center[1]=(float)top;center[2]=(float)-y;triangle(center,d,c,fill*.65f);faces++;
            center[1]=(float)bottom;triangle(center,b,a,fill*.65f);faces++;
            edge(a,b,width,outline);edge(d,c,width,outline);edge(a,d,width,outline);
            // Mid-height perimeter exposes the finite protective altitude from inside the field.
            point(a,x,y,radius,sides,rotation,side,(bottom+top)*.5);point(b,x,y,radius,sides,rotation,side+1,(bottom+top)*.5);edge(a,b,width*.6f,outline*.25f);
        }
    }
    private static void point(float[] out,double x,double y,double radius,int sides,double rotation,int side,double z){
        double angle=rotation+side*Math.PI*2/sides;out[0]=(float)(x+radius*Math.cos(angle));out[1]=(float)z;out[2]=(float)-(y+radius*Math.sin(angle));
    }
    private void triangle(float[] p,float[] q,float[] r,float alpha){vertex(p[0],p[1],p[2],alpha);vertex(q[0],q[1],q[2],alpha);vertex(r[0],r[1],r[2],alpha);}
    private void edge(float[] p,float[] q,float width,float alpha){
        float dx=q[0]-p[0],dy=q[1]-p[1],dz=q[2]-p[2],length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(length<.00001f)return;
        dx/=length;dy/=length;dz/=length;float sx,sy,sz;if(Math.abs(dy)<.9f){sx=-dz;sy=0;sz=dx;}else{sx=0;sy=dz;sz=-dy;}
        float scale=width*.5f/(float)Math.sqrt(sx*sx+sy*sy+sz*sz);sx*=scale;sy*=scale;sz*=scale;
        reserve(12);edgeQuad(p,q,sx,sy,sz,alpha);edgeQuad(p,q,dy*sz-dz*sy,dz*sx-dx*sz,dx*sy-dy*sx,alpha);
    }
    private void edgeQuad(float[] p,float[] q,float x,float y,float z,float alpha){
        vertex(p[0]+x,p[1]+y,p[2]+z,alpha);vertex(q[0]+x,q[1]+y,q[2]+z,alpha);vertex(q[0]-x,q[1]-y,q[2]-z,alpha);
        vertex(p[0]+x,p[1]+y,p[2]+z,alpha);vertex(q[0]-x,q[1]-y,q[2]-z,alpha);vertex(p[0]-x,p[1]-y,p[2]-z,alpha);
    }
    private void vertex(float x,float y,float z,float a){data[count++]=x;data[count++]=y;data[count++]=z;data[count++]=color.r;data[count++]=color.g;data[count++]=color.b;data[count++]=a;}
    private void reserve(int vertices){if(count+vertices*7>data.length)flush();}
    private void flush(){if(count==0)return;shader.bind();mesh.setVertices(data,0,count);mesh.render(shader,Gl.triangles);count=0;}
    private static void enabled(int cap,boolean on){if(on)Gl.enable(cap);else Gl.disable(cap);}
    public void dispose(){clear();if(mesh!=null)mesh.dispose();if(shader!=null)shader.dispose();mesh=null;shader=null;}
}
