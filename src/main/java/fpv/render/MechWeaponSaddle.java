package fpv.render;

import arc.graphics.*;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;

/** One cached hard-edged mount, shared by all elevated trunnions. No unit-body geometry changes. */
final class MechWeaponSaddle implements AutoCloseable {
    private Mesh mesh;private Shader shader;
    void render(Camera3D camera,float ambient,double x,double y,double z,double yaw,double width,double height,double length){
        if(height<.005)return;
        if(mesh==null){
            float[] vertices=new float[36*6];int n=0;
            int[][] faces={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
            for(int f=0;f<6;f++)for(int corner:new int[]{0,1,2,0,2,3}){
                int v=faces[f][corner];vertices[n++]=(v&1)==0?-.5f:.5f;vertices[n++]=(v&2)==0?-.5f:.5f;vertices[n++]=(v&4)==0?-.5f:.5f;
                vertices[n++]=f==4?-1:f==5?1:0;vertices[n++]=f==2?-1:f==3?1:0;vertices[n++]=f==0?-1:f==1?1:0;
            }
            mesh=new Mesh(true,36,0,VertexAttribute.position3,VertexAttribute.normal);mesh.setVertices(vertices);
            shader=new Shader("attribute vec3 a_position,a_normal;uniform mat4 u_combined;uniform vec3 u_center,u_size;uniform vec2 u_yaw;varying float v_light;void main(){vec3 p=a_position*u_size;vec3 w=vec3(p.x*u_yaw.x-p.z*u_yaw.y,p.y,p.x*u_yaw.y+p.z*u_yaw.x);v_light=.65+.35*max(0.0,a_normal.y);gl_Position=u_combined*vec4(u_center+w,1.0);}",
                "uniform float u_ambient;varying float v_light;void main(){gl_FragColor=vec4(vec3(.37,.39,.43)*v_light*u_ambient,1.0);}");
        }
        Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.disable(Gl.cullFace);Gl.disable(Gl.blend);
        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformf("u_center",(float)x,(float)y,(float)z);
        shader.setUniformf("u_size",(float)width,(float)height,(float)length);double a=Math.toRadians(90-yaw);
        shader.setUniformf("u_yaw",(float)Math.cos(a),(float)Math.sin(a));shader.setUniformf("u_ambient",Math.max(.48f,ambient));mesh.render(shader,Gl.triangles);
    }
    public void close(){if(mesh!=null)mesh.dispose();if(shader!=null)shader.dispose();mesh=null;shader=null;}
}
