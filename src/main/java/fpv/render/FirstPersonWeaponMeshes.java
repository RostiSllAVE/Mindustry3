package fpv.render;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.geom.Vec3;
import mindustry.gen.Unit;
import java.util.LinkedHashMap;

/** Viewmodel-only cache. Reuses the existing alpha generator; never owns the native atlas. */
final class FirstPersonWeaponMeshes implements AutoCloseable {
    private static final int MAX_ENTRIES=64,MAX_VERTICES=16380;
    private static final long MAX_BYTES=8*1024*1024;
    private static final String VERTEX="""
        attribute vec3 a_position;attribute vec3 a_normal;attribute vec2 a_texCoord0;
        uniform mat4 u_combined;uniform vec3 u_center,u_right,u_up,u_back;
        varying vec2 v_uv;varying vec3 v_normal;
        void main(){v_uv=vec2(a_texCoord0.x,1.0-a_texCoord0.y);v_normal=a_normal;
            gl_Position=u_combined*vec4(u_center+u_right*a_position.x+u_up*(a_position.y-.5)+u_back*a_position.z,1.0);}
        """;
    private static final String FRAGMENT="""
        uniform sampler2D u_texture,u_cell;uniform vec4 u_uv,u_cellUv;
        uniform vec2 u_inset,u_texel,u_sideInset;uniform vec3 u_cellColor;
        uniform float u_ambient,u_cellEnabled;varying vec2 v_uv;varying vec3 v_normal;
        void main(){vec2 coords=clamp(mix(u_uv.xy,u_uv.zw,v_uv),u_uv.xy+u_inset,u_uv.zw-u_inset);
            vec4 art=texture2D(u_texture,coords);
            if(abs(v_normal.y)>.5&&art.a<.12)discard;
            if(abs(v_normal.y)<.5){vec2 local=clamp(v_uv+sign(vec2(.5)-v_uv)*u_sideInset,vec2(0),vec2(1));
                vec2 inner=clamp(mix(u_uv.xy,u_uv.zw,local),u_uv.xy+u_inset,u_uv.zw-u_inset);
                inner=(floor(inner/u_texel)+vec2(.5))*u_texel;vec4 side=texture2D(u_texture,inner);
                if(side.a>=.12)art.rgb=side.rgb;}
            if(v_normal.y>.5&&u_cellEnabled>.5){vec4 cell=texture2D(u_cell,mix(u_cellUv.xy,u_cellUv.zw,v_uv));
                art.rgb=mix(art.rgb,cell.rgb*u_cellColor,cell.a);}
            float shade=v_normal.y>.5?1.0:(v_normal.y<-.5?.57:(.78+.10*abs(v_normal.x)));
            gl_FragColor=vec4(art.rgb*u_ambient*shade,1.0);}
        """;
    private final LinkedHashMap<TextureRegion,Entry> cache=new LinkedHashMap<>(16,.75f,true);
    private Object atlas;
    private Shader shader;
    private long bytes,builds,frame=Long.MIN_VALUE;
    private int frameBuilds,sides;
    int entries(){return cache.size();} long bytes(){return bytes;} long builds(){return builds;} int sides(){return sides;}
    void begin(){sides=0;if(atlas!=Core.atlas){clear();atlas=Core.atlas;}}
    boolean render(Camera3D camera,TextureRegion art,TextureRegion cell,Unit unit,Vec3 center,Vec3 right,Vec3 up,Vec3 back,float ambient){
        Entry entry=cache.get(art);
        // Integrated guns recreate a small TextureRegion on possession. Identical atlas
        // rectangles share their old VBO rather than rebuilding after every view switch.
        if(entry==null){
            var aliases=cache.entrySet().iterator();
            while(aliases.hasNext()){var candidate=aliases.next();if(candidate.getValue().matches(art)){entry=candidate.getValue();aliases.remove();break;}}
            if(entry!=null)cache.put(art,entry);
        }
        if(entry!=null&&!entry.matches(art)){cache.remove(art);bytes-=entry.bytes;entry.close();entry=null;}
        if(entry==null){
            long now=Core.graphics.getFrameId();if(now!=frame){frame=now;frameBuilds=0;}
            if(frameBuilds>=8)return false;frameBuilds++;
            entry=build(art);var iterator=cache.entrySet().iterator();
            while(iterator.hasNext()&&(cache.size()>=MAX_ENTRIES||bytes+entry.bytes>MAX_BYTES)){
                var old=iterator.next().getValue();bytes-=old.bytes;old.close();iterator.remove();}
            cache.put(art,entry);bytes+=entry.bytes;
        }
        if(entry.mesh==null)return false;
        if(shader==null)shader=new Shader(VERTEX,FRAGMENT);
        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);
        shader.setUniformf("u_center",center);shader.setUniformf("u_right",right);shader.setUniformf("u_up",up);shader.setUniformf("u_back",back);
        shader.setUniformf("u_uv",art.u,art.v,art.u2,art.v2);
        shader.setUniformf("u_inset",.5f/art.texture.width,.5f/art.texture.height);
        shader.setUniformf("u_texel",1f/art.texture.width,1f/art.texture.height);
        shader.setUniformf("u_sideInset",2f/art.width,2f/art.height);
        shader.setUniformf("u_ambient",Math.max(.48f,ambient));shader.setUniformi("u_texture",0);shader.setUniformi("u_cell",1);
        boolean hasCell=NativeAtlasMesh.valid(cell);shader.setUniformf("u_cellEnabled",hasCell?1:0);
        var color=unit.type.cellColor(unit);shader.setUniformf("u_cellColor",color.r,color.g,color.b);
        TextureRegion overlay=hasCell?cell:art;
        shader.setUniformf("u_cellUv",overlay.u+.5f/overlay.texture.width,overlay.v+.5f/overlay.texture.height,
            overlay.u2-.5f/overlay.texture.width,overlay.v2-.5f/overlay.texture.height);
        overlay.texture.bind(1);art.texture.bind(0);entry.mesh.render(shader,Gl.triangles);sides+=entry.sides;return true;
    }
    private Entry build(TextureRegion region){
        Mesh mesh=null;
        try{
            var pixels=Core.atlas.getPixmaps().get(region.texture,()->region.texture.getTextureData().getPixmap());
            if(pixels==null||pixels.isDisposed()||(long)region.width*region.height>1024*1024)return new Entry(region,null,0,0);
            byte[] alpha=new byte[region.width*region.height];
            for(int y=0;y<region.height;y++)for(int x=0;x<region.width;x++)
                alpha[x+y*region.width]=(byte)pixels.getA(region.getX()+x,region.getY()+region.height-1-y);
            var shape=SpriteExtrusionGeometry.generate(alpha,region.width,region.height,32,8,32,.2,MAX_VERTICES);
            long size=shape.vertexBytes()*2;if(shape.vertexCount()==0||size>MAX_BYTES)return new Entry(region,null,0,0);
            mesh=new Mesh(true,shape.vertexCount(),0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords);
            mesh.setVertices(shape.vertices());builds++;return new Entry(region,mesh,size,shape.sideQuads());
        }catch(RuntimeException error){if(mesh!=null)mesh.dispose();return new Entry(region,null,0,0);}
    }
    private static final class Entry implements AutoCloseable {
        final Mesh mesh;final Texture texture;final float u,v,u2,v2;final int width,height,sides;final long bytes;
        Entry(TextureRegion region,Mesh mesh,long bytes,int sides){this.mesh=mesh;this.bytes=bytes;this.sides=sides;
            texture=region.texture;u=region.u;v=region.v;u2=region.u2;v2=region.v2;width=region.width;height=region.height;}
        boolean matches(TextureRegion r){return r.texture==texture&&r.u==u&&r.v==v&&r.u2==u2&&r.v2==v2&&r.width==width&&r.height==height;}
        public void close(){if(mesh!=null)mesh.dispose();}
    }
    private void clear(){for(var entry:cache.values())entry.close();cache.clear();bytes=0;}
    public void close(){clear();if(shader!=null)shader.dispose();shader=null;atlas=null;}
}
