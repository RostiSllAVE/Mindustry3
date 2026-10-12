package fpv.render;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import fpv.construction.BuildingMousePicker;
import fpv.construction.LayerCoordinates;

/** One cached stream batch; opaque scene depth hides hover edges and preview lines. */
final class BuildingInteractionRenderer implements AutoCloseable {
    private final NativeAtlasMesh.Builder batch=new NativeAtlasMesh.Builder(true);
    private Shader shader;
    private BuildingMousePicker.Hit hover,source,target;
    private boolean valid;
    private boolean removing;
    void set(BuildingMousePicker.Hit hover,BuildingMousePicker.Hit source,BuildingMousePicker.Hit target,boolean valid){this.hover=hover;this.source=source;this.target=target;this.valid=valid;removing=false;}
    void removal(BuildingMousePicker.Hit hit,boolean valid){set(hit,null,null,valid);removing=true;}
    void render(Camera3D camera){
        if(hover==null&&source==null)return;
        if(shader==null)shader=new Shader("attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec4 c;void main(){c=a_color;gl_Position=u_combined*vec4(a_position,1.0);}","varying vec4 c;void main(){gl_FragColor=c;}");
        batch.clear();batch.overflowShader=shader;
        if(hover!=null)bounds(camera,hover.bounds(),removing?(valid?mindustry.graphics.Pal.remove:Color.scarlet):hover.building()==null?Color.cyan:hover.building().team.color);
        if(source!=null){bounds(camera,source.bounds(),Color.white);if(target!=null){var a=source.bounds();var b=target.bounds();line(camera,a.centerX(),a.centerY(),a.maxZ()+.03,b.centerX(),b.centerY(),b.maxZ()+.03,.04,valid?Color.green:Color.scarlet);}}
        boolean blend=Gl.isEnabled(Gl.blend),write=Gl.getInt(Gl.depthWritemask)!=0,depth=Gl.isEnabled(Gl.depthTest),cull=Gl.isEnabled(Gl.cullFace);
        int src=Gl.getInt(Gl.blendSrcRgb),dst=Gl.getInt(Gl.blendDstRgb),srcA=Gl.getInt(Gl.blendSrcAlpha),dstA=Gl.getInt(Gl.blendDstAlpha);
        try{Gl.enable(Gl.depthTest);Gl.enable(Gl.blend);Gl.disable(Gl.cullFace);Gl.blendFunc(Gl.srcAlpha,Gl.oneMinusSrcAlpha);Gl.depthMask(false);shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);batch.drawAndClear(shader);}
        finally{Gl.depthMask(write);enabled(Gl.depthTest,depth);enabled(Gl.blend,blend);enabled(Gl.cullFace,cull);Gl.blendFuncSeparate(src,dst,srcA,dstA);}
    }
    private void bounds(Camera3D camera,LayerCoordinates.Bounds raw,Color color){
        var b=raw.expanded(.018);double width=.018;
        for(int z=0;z<2;z++){double h=z==0?b.minZ():b.maxZ();
            line(camera,b.minX(),b.minY(),h,b.maxX(),b.minY(),h,width,color);line(camera,b.maxX(),b.minY(),h,b.maxX(),b.maxY(),h,width,color);
            line(camera,b.maxX(),b.maxY(),h,b.minX(),b.maxY(),h,width,color);line(camera,b.minX(),b.maxY(),h,b.minX(),b.minY(),h,width,color);
        }
        for(int x=0;x<2;x++)for(int y=0;y<2;y++)line(camera,x==0?b.minX():b.maxX(),y==0?b.minY():b.maxY(),b.minZ(),x==0?b.minX():b.maxX(),y==0?b.minY():b.maxY(),b.maxZ(),width,color);
    }
    private void line(Camera3D camera,double ax,double ay,double az,double bx,double by,double bz,double width,Color c){
        double dx=bx-ax,dy=bz-az,dz=ay-by;
        double nx=dy*camera.direction.z-dz*camera.direction.y,ny=dz*camera.direction.x-dx*camera.direction.z,nz=dx*camera.direction.y-dy*camera.direction.x;
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<1e-8){nx=camera.up.x;ny=camera.up.y;nz=camera.up.z;length=1;}
        nx*=width/length;ny*=width/length;nz*=width/length;
        batch.projectionFace(Core.atlas.white(),(float)(ax-nx),(float)(az-ny),(float)(-ay-nz),(float)(ax+nx),(float)(az+ny),(float)(-ay+nz),
            (float)(bx+nx),(float)(bz+ny),(float)(-by+nz),(float)(bx-nx),(float)(bz-ny),(float)(-by-nz),c.r,c.g,c.b,.9f);
    }
    private static void enabled(int flag,boolean value){if(value)Gl.enable(flag);else Gl.disable(flag);}
    @Override public void close(){batch.dispose();if(shader!=null)shader.dispose();}
}
