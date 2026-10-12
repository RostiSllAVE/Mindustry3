package fpv.render;

public final class VolumetricBeamGeometryTest {
    public static void main(String[] args){
        int[] checks={0};
        for(int yaw=0;yaw<360;yaw+=30)for(int pitch=-90;pitch<=90;pitch+=15)for(float width:new float[]{.02f,.2f,2f}){
            double a=Math.toRadians(yaw),p=Math.toRadians(pitch);float dx=(float)(Math.cos(a)*Math.cos(p)*7),dy=(float)(Math.sin(p)*7),dz=(float)(Math.sin(a)*Math.cos(p)*7);
            float ex=2+dx,ey=3+dy,ez=4+dz;double vx=ex-2f,vy=ey-3f,vz=ez-4f,len=Math.sqrt(vx*vx+vy*vy+vz*vz);int[] count={0},caps={0};
            int n=VolumetricBeamGeometry.emit(2,3,4,ex,ey,ez,width,.2f,.8f,.4f,9,8,7,(x,y,z,r,g,b)->{
                double px=x-2,py=y-3,pz=z-4,t=(px*vx+py*vy+pz*vz)/(len*len),rx=px-vx*t,ry=py-vy*t,rz=pz-vz*t,rad=Math.sqrt(rx*rx+ry*ry+rz*rz);
                require(t>=-.00001&&t<=1.00001,"exact closed endpoint envelope",checks);require(rad<=width*.5+.00001,"actual circular radial volume",checks);
                require(Float.isFinite(x+y+z+r+g+b)&&Math.abs(g-r*4)<.00001&&Math.abs(b-r*2)<.00001,"finite geometry preserves color identity",checks);
                if(rad<.00001)caps[0]++;count[0]++;
            });
            require(n==96&&count[0]==96&&caps[0]>=16,"eight sides and two closed caps",checks);
        }
        require(VolumetricBeamGeometry.emit(0,0,0,0,0,0,1,1,1,1,0,0,0,(x,y,z,r,g,b)->{throw new AssertionError();})==0,"degenerate beam safely absent",checks);
        System.out.println("VolumetricBeamGeometryTest: "+checks[0]+" closed tube/color/vertical/XYZ/endpoints checks passed; 32 triangles per beam");
    }
    private static void require(boolean b,String text,int[] count){count[0]++;if(!b)throw new AssertionError(text);}
}
