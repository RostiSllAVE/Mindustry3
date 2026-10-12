package fpv.render;

import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import arc.struct.IntMap;
import fpv.generated.RenderConfig;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/** Cosmetic closed shell of the native regular polygon. Native absorption remains unchanged XY. */
public final class ForceFieldRenderer {
    private static final String VERTEX="attribute vec3 a_position;attribute vec4 a_color;uniform mat4 u_combined;varying vec4 v_color;void main(){v_color=a_color;gl_Position=u_combined*vec4(a_position,1.0);}";
    private static final String FRAGMENT="varying vec4 v_color;void main(){gl_FragColor=v_color;}";
    private final float[] data=new float[(int)RenderConfig.SHIELD_BUFFER_VERTICES*7];
    private final IntMap<Unit> submitted=new IntMap<>();
    private final Color color=new Color();
    private final float[] a=new float[3],b=new float[3],c=new float[3],d=new float[3];
    private int count;
    private Mesh mesh;
    private Shader shader;
    public boolean wasSubmitted(Unit unit){return unit!=null&&submitted.get(unit.id)==unit;}
    public void clear(){submitted.clear();count=0;}

    /** Must be called after opaque world geometry. Every touched GL state is restored. */
    public void render(Camera3D camera,UnitAltitudeSystem altitudes){
        clear();if(!NativeForceFieldState.available())return;
        int srcRgb=Gl.getInt(Gl.blendSrcRgb),dstRgb=Gl.getInt(Gl.blendDstRgb),srcAlpha=Gl.getInt(Gl.blendSrcAlpha),dstAlpha=Gl.getInt(Gl.blendDstAlpha);
        int eqRgb=Gl.getInt(Gl.blendEquationRgb),eqAlpha=Gl.getInt(Gl.blendEquationAlpha),program=Gl.getInt(Gl.currentProgram);
        int depthFunc=Gl.getInt(Gl.depthFunc);boolean blend=Gl.isEnabled(Gl.blend),cull=Gl.isEnabled(Gl.cullFace),depth=Gl.isEnabled(Gl.depthTest),depthWrite=Gl.getInt(Gl.depthWritemask)!=0;
        try{
            Gl.enable(Gl.blend);Gl.blendEquation(Gl.funcAdd);Gl.blendFuncSeparate(Gl.srcAlpha,Gl.one,Gl.zero,Gl.one);
            Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(false);Gl.depthFunc(Gl.lequal);
            for(Unit unit:Groups.unit){
                if(unit.dead||unit.shield<=0||unit.inFogTo(Vars.player.team()))continue;
                for(var ability:unit.abilities)if(ability instanceof ForceFieldAbility field){
                    float radius=NativeForceFieldState.radius(unit,field)/Vars.tilesize;
                    if(radius<=.001f||field.sides<3||!Float.isFinite(field.rotation))continue;
                    var state=altitudes==null?null:altitudes.unitState(unit);
                    float x=state==null?unit.x/Vars.tilesize:(float)state.x(),y=state==null?1:(float)state.z(),z=state==null?-unit.y/Vars.tilesize:(float)-state.y();
                    if(!camera.frustum.containsBounds(x,y,z,radius,radius,radius))continue;
                    if(shader==null){shader=new Shader(VERTEX,FRAGMENT);mesh=new Mesh(false,data.length/7,0,VertexAttribute.position3,new VertexAttribute(4,"a_color"));}
                    shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);
                    color.set(unit.type.shieldColor(unit)).lerp(Color.white,NativeForceFieldState.flash(field));
                    shell(x,y,z,radius,Math.min(field.sides,(int)RenderConfig.SHIELD_MAX_SIDES),field.rotation,NativeForceFieldState.flash(field));
                    submitted.put(unit.id,unit);
                }
            }
            flush();
        }finally{
            count=0;Gl.depthMask(depthWrite);Gl.depthFunc(depthFunc);enabled(Gl.depthTest,depth);enabled(Gl.cullFace,cull);
            Gl.blendEquationSeparate(eqRgb,eqAlpha);Gl.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);enabled(Gl.blend,blend);Gl.useProgram(program);
        }
    }
    private void shell(float x,float y,float z,float radius,int sides,float rotation,float flash){
        int latitudes=(int)RenderConfig.SHIELD_LATITUDE_SEGMENTS;
        float fill=(float)(RenderConfig.SHIELD_FILL_ALPHA+flash*RenderConfig.SHIELD_FLASH_ALPHA);
        float edge=(float)(RenderConfig.SHIELD_EDGE_ALPHA+flash*RenderConfig.SHIELD_FLASH_ALPHA);
        float width=Math.max(.008f,radius*(float)RenderConfig.SHIELD_EDGE_RADIUS_FRACTION);
        for(int row=0;row<latitudes;row++)for(int col=0;col<sides;col++){
            point(a,x,y,z,radius,row,col,latitudes,sides,rotation);point(b,x,y,z,radius,row,col+1,latitudes,sides,rotation);
            point(c,x,y,z,radius,row+1,col+1,latitudes,sides,rotation);point(d,x,y,z,radius,row+1,col,latitudes,sides,rotation);
            reserve(6);triangle(a,b,c,fill);triangle(a,c,d,fill);
            if(row>0)edge(a,b,width,edge);
            edge(a,d,width,edge);
        }
    }
    private static void point(float[] out,float x,float y,float z,float radius,int row,int col,int rows,int cols,float rotation){
        float latitude=-Mathf.PI/2+row*Mathf.PI/rows,angle=rotation*Mathf.degRad+col*Mathf.PI2/cols;
        float horizontal=radius*Mathf.cos(latitude);
        out[0]=x+horizontal*Mathf.cos(angle);out[1]=y+radius*Mathf.sin(latitude);out[2]=z-horizontal*Mathf.sin(angle);
    }
    private void triangle(float[] a,float[] b,float[] c,float alpha){vertex(a[0],a[1],a[2],alpha);vertex(b[0],b[1],b[2],alpha);vertex(c[0],c[1],c[2],alpha);}
    private void edge(float[] a,float[] b,float width,float alpha){
        float dx=b[0]-a[0],dy=b[1]-a[1],dz=b[2]-a[2],length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);if(length<.0001f)return;
        dx/=length;dy/=length;dz/=length;
        float sx,sy,sz;if(Math.abs(dy)<.9f){sx=-dz;sy=0;sz=dx;}else{sx=0;sy=dz;sz=-dy;}
        float scale=width*.5f/(float)Math.sqrt(sx*sx+sy*sy+sz*sz);sx*=scale;sy*=scale;sz*=scale;
        reserve(12);edgeQuad(a,b,sx,sy,sz,alpha);edgeQuad(a,b,dy*sz-dz*sy,dz*sx-dx*sz,dx*sy-dy*sx,alpha);
    }
    private void edgeQuad(float[] a,float[] b,float x,float y,float z,float alpha){
        vertex(a[0]+x,a[1]+y,a[2]+z,alpha);vertex(b[0]+x,b[1]+y,b[2]+z,alpha);vertex(b[0]-x,b[1]-y,b[2]-z,alpha);
        vertex(a[0]+x,a[1]+y,a[2]+z,alpha);vertex(b[0]-x,b[1]-y,b[2]-z,alpha);vertex(a[0]-x,a[1]-y,a[2]-z,alpha);
    }
    private void vertex(float x,float y,float z,float alpha){data[count++]=x;data[count++]=y;data[count++]=z;data[count++]=color.r;data[count++]=color.g;data[count++]=color.b;data[count++]=alpha;}
    private void reserve(int vertices){if(count+vertices*7>data.length)flush();}
    private void flush(){if(count==0)return;shader.bind();mesh.setVertices(data,0,count);mesh.render(shader,Gl.triangles);count=0;}
    private static void enabled(int cap,boolean value){if(value)Gl.enable(cap);else Gl.disable(cap);}
    public void dispose(){clear();if(mesh!=null)mesh.dispose();if(shader!=null)shader.dispose();mesh=null;shader=null;}
}
