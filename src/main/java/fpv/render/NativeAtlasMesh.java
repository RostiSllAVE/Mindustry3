package fpv.render;

import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import fpv.generated.RenderConfig;
import java.util.*;

/** Meshes reference the game's live atlas textures; they never own or alter those textures. */
final class NativeAtlasMesh {
    private static final VertexAttribute color = new VertexAttribute(4, "a_color");
    private final List<Part> parts = new ArrayList<>();
    long bytes;

    void draw(Shader shader) {
        shader.bind();
        for (Part part : parts) {
            part.texture.bind(0);
            part.mesh.render(shader, Gl.triangles);
        }
    }
    void dispose() { for (Part part : parts) part.mesh.dispose(); parts.clear(); }
    private record Part(Texture texture, Mesh mesh) {}

    static boolean valid(TextureRegion region) {
        return region != null && region.texture != null && region.found();
    }

    static final class Builder {
        private final IdentityHashMap<Texture, Data> data = new IdentityHashMap<>();
        private final IdentityHashMap<Texture, Mesh> streamMeshes = new IdentityHashMap<>();
        private final boolean streaming;
        private final int capacity = (int)RenderConfig.ATLAS_STREAM_VERTICES;
        Shader overflowShader;
        boolean profileWalls;
        int streamMeshCount(){return streamMeshes.size();}
        Builder(boolean streaming) { this.streaming = streaming; }
        void clear() { for (Data d : data.values()) d.count = 0; }
        void forget(Texture texture){data.remove(texture);Mesh mesh=streamMeshes.remove(texture);if(mesh!=null)mesh.dispose();}
        void dispose() { for (Mesh m : streamMeshes.values()) m.dispose(); streamMeshes.clear(); data.clear(); }

        NativeAtlasMesh upload() {
            NativeAtlasMesh result = new NativeAtlasMesh();
            for (Map.Entry<Texture, Data> e : data.entrySet()) {
                Data d = e.getValue();
                if (d.count == 0) continue;
                Mesh mesh = new Mesh(true, d.count / 9, 0, VertexAttribute.position3, color, VertexAttribute.texCoords);
                mesh.setVertices(d.values, 0, d.count);
                result.parts.add(new Part(e.getKey(), mesh));
                result.bytes += (long)d.count * Float.BYTES;
            }
            return result;
        }

        void drawAndClear(Shader shader) {
            for (Map.Entry<Texture, Data> e : data.entrySet()) flush(e.getKey(), e.getValue(), shader);
        }
        private void flush(Texture texture, Data d, Shader shader) {
            if (d.count == 0) return;
            Mesh mesh = streamMeshes.computeIfAbsent(texture, t -> new Mesh(false, capacity, 0,
                VertexAttribute.position3, color, VertexAttribute.texCoords));
            shader.bind(); texture.bind(0);
            mesh.setVertices(d.values, 0, d.count);
            mesh.render(shader, Gl.triangles);
            if(profileWalls)fpv.profile.WallProfile.draw(d.count/9,d.count);
            d.count = 0;
        }

        /** The sprite's north/top edge follows native +Y, represented by render -Z. */
        void top(TextureRegion region, float x, float y, float z, float width, float depth, float angle,
                 float r, float g, float b, boolean water) {
            if (!valid(region)) return;
            float hw=width/2, hd=depth/2, c=Mathf.cosDeg(angle), s=Mathf.sinDeg(angle);
            quad(region, x-hw*c+hd*s,y,z-hw*s-hd*c, x+hw*c+hd*s,y,z+hw*s-hd*c,
                x+hw*c-hd*s,y,z+hw*s+hd*c, x-hw*c-hd*s,y,z-hw*s+hd*c, r,g,b,water);
        }

        void face(TextureRegion region, float x1,float y1,float z1,float x2,float y2,float z2,
                  float x3,float y3,float z3,float x4,float y4,float z4,float brightness) {
            quad(region,x1,y1,z1,x2,y2,z2,x3,y3,z3,x4,y4,z4,brightness,brightness,brightness,false);
        }

        /** Native gun art rotates as a pitched plane; the fixed base remains in its cached mesh. */
        void pitchedTop(TextureRegion region,float x,float y,float z,float size,float yaw,float pitch){
            float half=size*.5f,c=Mathf.cosDeg(yaw),s=Mathf.sinDeg(yaw),cp=Mathf.cosDeg(pitch),sp=Mathf.sinDeg(pitch);
            float rx=-s*half,rz=-c*half,fx=c*half*cp,fz=-s*half*cp,fy=half*sp;
            quad(region,x+rx+fx,y+fy,z+rz+fz,x-rx+fx,y+fy,z-rz+fz,
                x-rx-fx,y-fy,z-rz-fz,x+rx-fx,y-fy,z+rz-fz,1,1,1,false);
        }

        /** Cached alpha geometry, native rectangular proportions and affine pose; same world depth batch. */
        void extrusion(TextureRegion region,float[] vertices,float x,float y,float z,float width,float thickness,float length,
                       double[] basis,float r,float g,float b){
            if(!valid(region))return;
            Data d=data.computeIfAbsent(region.texture,t->new Data(streaming?capacity*9:4096));
            float du=Math.signum(region.u2-region.u)*.5f/region.texture.width,dv=Math.signum(region.v2-region.v)*.5f/region.texture.height;
            float u=region.u+du,v=region.v+dv,us=region.u2-du-u,vs=region.v2-dv-v;
            for(int i=0;i<vertices.length;i+=SpriteExtrusionGeometry.STRIDE){
                if(streaming&&d.count+9>d.values.length)flush(region.texture,d,overflowShader);
                if(d.count+9>d.values.length)d.values=Arrays.copyOf(d.values,Math.max(d.values.length*2,d.count+9));
                double px=vertices[i]*width,py=vertices[i+1]*thickness,pz=vertices[i+2]*length;
                float shade=vertices[i+4]>.5f?1:vertices[i+4]<-.5f?.58f:.78f;
                d.vertex(x+(float)(basis[0]*px+basis[3]*py+basis[6]*pz),y+(float)(basis[1]*px+basis[4]*py+basis[7]*pz),
                    z+(float)(basis[2]*px+basis[5]*py+basis[8]*pz),r*shade,g*shade,b*shade,0,
                    u+vertices[i+6]*us,v+vertices[i+7]*vs);
            }
        }

        /** Separate transparent-pass shader interprets a_color.a as opacity, not opaque material. */
        void projectionFace(TextureRegion region,float ax,float ay,float az,float bx,float by,float bz,
                            float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b,float alpha){
            quad(region,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,r,g,b,alpha,false);
        }

        void terrainTop(TextureRegion region,float x,float z,float width,float depth,float sw,float se,float ne,float nw,float shade){
            quad(region,x-width/2,sw,z+depth/2,x+width/2,se,z+depth/2,
                x+width/2,ne,z-depth/2,x-width/2,nw,z-depth/2,shade,shade,shade,false,true);
        }

        /** Bakes a cached local prop template into the existing texture batch; no per-object GL draw or mesh. */
        void template(TextureRegion region,float[] vertices,float x,float y,float z,float width,float height,float depth,float angle,float sampleU,float sampleV){
            if(!valid(region)||vertices==null)return;
            Data d=data.computeIfAbsent(region.texture,t->new Data(streaming?capacity*9:4096));
            float c=Mathf.cosDeg(angle),s=Mathf.sinDeg(angle);
            float du=Math.signum(region.u2-region.u)*.5f/region.texture.width,dv=Math.signum(region.v2-region.v)*.5f/region.texture.height;
            float u=region.u+du,v=region.v+dv,us=region.u2-du-u,vs=region.v2-dv-v;
            for(int i=0;i<vertices.length;i+=OrganicPropGeometry.STRIDE){
                if(streaming&&d.count+9>d.values.length)flush(region.texture,d,overflowShader);
                if(d.count+9>d.values.length)d.values=Arrays.copyOf(d.values,Math.max(d.values.length*2,d.count+9));
                float lx=vertices[i]*width,lz=vertices[i+2]*depth,shade=vertices[i+5];
                float tu=vertices[i+3]<0?sampleU:vertices[i+3],tv=vertices[i+4]<0?sampleV:vertices[i+4];
                d.vertex(x+lx*c-lz*s,y+vertices[i+1]*height,z+lx*s+lz*c,shade,shade,shade,0,u+tu*us,v+tv*vs);
            }
        }

        private void quad(TextureRegion region, float ax,float ay,float az,float bx,float by,float bz,
                          float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b,boolean water) {
            quad(region,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,r,g,b,water,false);
        }
        private void quad(TextureRegion region, float ax,float ay,float az,float bx,float by,float bz,
                          float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b,boolean water,boolean flipV) {
            quad(region,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,r,g,b,water?1:0,flipV);
        }
        private void quad(TextureRegion region, float ax,float ay,float az,float bx,float by,float bz,
                          float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b,float material,boolean flipV) {
            if (!valid(region)) return;
            Data d=data.computeIfAbsent(region.texture, t -> new Data(streaming ? capacity*9 : 4096));
            if (streaming && d.count+54>d.values.length) flush(region.texture,d,overflowShader);
            if (d.count+54>d.values.length) d.values=Arrays.copyOf(d.values,Math.max(d.values.length*2,d.count+54));
            // Half-texel inset prevents neighboring atlas sprites bleeding into 3D surfaces.
            float du=Math.signum(region.u2-region.u)*.5f/region.texture.width;
            float dv=Math.signum(region.v2-region.v)*.5f/region.texture.height;
            float u=region.u+du, u2=region.u2-du, v=region.v+dv, v2=region.v2-dv;
            if(flipV){float swap=v;v=v2;v2=swap;}
            d.vertex(ax,ay,az,r,g,b,material,u,v); d.vertex(bx,by,bz,r,g,b,material,u2,v); d.vertex(cx,cy,cz,r,g,b,material,u2,v2);
            d.vertex(ax,ay,az,r,g,b,material,u,v); d.vertex(cx,cy,cz,r,g,b,material,u2,v2); d.vertex(dx,dy,dz,r,g,b,material,u,v2);
        }
    }
    private static final class Data {
        float[] values; int count;
        Data(int size){values=new float[size];}
        void vertex(float x,float y,float z,float r,float g,float b,float material,float u,float v){
            values[count++]=x;values[count++]=y;values[count++]=z;
            values[count++]=r;values[count++]=g;values[count++]=b;values[count++]=material;
            values[count++]=u;values[count++]=v;
        }
    }
}
