package fpv.render;

import java.util.Arrays;

/** Executable pure silhouette/attitude tests; never loads game textures or starts OpenGL. */
public final class SpriteExtrusionGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        rectangle();
        holeAndIslands();
        transparencyAndCoverage();
        budgetAndDeterminism();
        basisSignsAndOrthonormality();
        validation();
        System.out.println("SpriteExtrusionGeometry: "+checks+" assertions passed (pure geometry/axes; GPU checks separate).");
    }

    private static SpriteExtrusionGeometry.Result shape(byte[] pixels,int w,int h) {
        return SpriteExtrusionGeometry.generate(pixels,w,h,64,8,32,.2,65520);
    }
    private static void rectangle() {
        byte[] pixels=new byte[6];Arrays.fill(pixels,(byte)255);
        var mesh=shape(pixels,2,3);
        check(mesh.opaqueCells()==6,"all rectangle cells retained");
        check(mesh.topQuads()==1&&mesh.bottomQuads()==1,"greedy caps merge full rectangle");
        check(mesh.sideQuads()==10,"only outer perimeter edges have sides");
        close(capArea(mesh,1),1,1e-6,"top covers exact normalized rectangle");
        close(capArea(mesh,-1),1,1e-6,"bottom covers exact normalized rectangle");
        validateVertices(mesh);
        check(covers(mesh,0,0),"opaque center covered");
    }
    private static void holeAndIslands() {
        byte[] ring=new byte[9];Arrays.fill(ring,(byte)255);ring[4]=0;
        var mesh=shape(ring,3,3);
        check(mesh.opaqueCells()==8,"ring occupancy");
        check(mesh.sideQuads()==16,"outer perimeter12 plus inner hole4");
        check(!covers(mesh,0,0),"ring hole is not filled by greedy caps");
        check(covers(mesh,-.34,0),"ring left section exists");
        close(capArea(mesh,1),8.0/9,1e-6,"ring top excludes hole area");
        close(capArea(mesh,-1),8.0/9,1e-6,"ring bottom excludes hole area");
        validateVertices(mesh);
        byte[] islands={(byte)255,0,0,0,(byte)255};
        var separate=shape(islands,5,1);
        check(separate.topQuads()==2&&separate.sideQuads()==8,"islands keep independent silhouettes");
        check(!covers(separate,0,0)&&covers(separate,-.4,0)&&covers(separate,.4,0),"no bridge between disconnected islands");
        validateVertices(separate);
    }
    private static void transparencyAndCoverage() {
        var empty=shape(new byte[64],8,8);
        check(empty.vertexCount()==0&&empty.opaqueCells()==0,"transparent sprite generates no VBO payload");
        byte[] threshold={31,32};
        var edge=shape(threshold,2,1);
        check(edge.opaqueCells()==1,"alpha threshold includes exact boundary only");
        byte[] tiny=new byte[16];tiny[0]=(byte)255;
        check(SpriteExtrusionGeometry.generate(tiny,4,4,1,1,32,.2,100).opaqueCells()==0,"coverage rejects isolated subcell speck");
        Arrays.fill(tiny,0,4,(byte)255);
        var quarter=SpriteExtrusionGeometry.generate(tiny,4,4,1,1,32,.2,100);
        check(quarter.opaqueCells()==1,"coverage retains quarter-filled cell");
        float[] data=quarter.vertices();
        for(int i=0;i<data.length;i+=SpriteExtrusionGeometry.STRIDE) {
            if(data[i+4]!=0)continue;
            int x=Math.min(3,(int)(data[i+6]*4)),y=Math.min(3,(int)(data[i+7]*4));
            check((tiny[x+y*4]&255)>=32,"side sample references an opaque native source pixel");
        }
    }
    private static void budgetAndDeterminism() {
        byte[] checker=new byte[64*64];
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)if((x+y)%2==0)checker[x+y*64]=(byte)255;
        var mesh=shape(checker,64,64);
        check(mesh.vertexCount()<=65520,"bounded vertex count");
        check(mesh.gridWidth()<64,"complex outline automatically reduces resolution");
        check(mesh.gridWidth()>=8&&mesh.gridHeight()>=8,"minimum grid bound retained");
        var repeated=shape(checker,64,64);
        check(Arrays.equals(mesh.vertices(),repeated.vertices()),"silhouette generation deterministic");
        validateVertices(mesh);
        byte[] strip=new byte[128*8];Arrays.fill(strip,(byte)255);
        var aspect=shape(strip,128,8);
        check(aspect.gridWidth()==64&&aspect.gridHeight()==4,"grid preserves native sprite aspect");
        check(aspect.vertexBytes()==(long)aspect.vertexCount()*SpriteExtrusionGeometry.STRIDE*4,"cache byte accounting agrees with payload");
        expectFailure(()->SpriteExtrusionGeometry.generate(checker,64,64,64,64,32,.2,100),"hard cap never exceeded when reduction forbidden");
    }
    private static void basisSignsAndOrthonormality() {
        double[] b=new double[9];
        SpriteExtrusionGeometry.basis(0,0,0,b);
        close(-b[6],1,1e-12,"yaw0 nose east");
        close(-b[8],0,1e-12,"yaw0 no north component");
        SpriteExtrusionGeometry.basis(90,0,0,b);
        close(-b[8],-1,1e-12,"yaw90 nose north");
        close(b[0],1,1e-12,"north-facing right wing east");
        SpriteExtrusionGeometry.basis(90,30,0,b);
        close(-b[7],.5,1e-12,"positive pitch lifts nose");
        SpriteExtrusionGeometry.basis(90,0,30,b);
        close(b[1],-.5,1e-12,"positive roll lowers right wing");
        for(double yaw:new double[]{0,39,90,180,271})for(double pitch:new double[]{-80,0,50,90})for(double roll:new double[]{-90,0,135}) {
            SpriteExtrusionGeometry.basis(yaw,pitch,roll,b);
            for(int axis=0;axis<3;axis++)close(dot(b,axis,axis),1,1e-12,"unit basis length");
            close(dot(b,0,1),0,1e-12,"right perpendicular up");
            close(dot(b,0,2),0,1e-12,"right perpendicular back");
            close(dot(b,1,2),0,1e-12,"up perpendicular back");
            double crossX=b[1]*b[5]-b[2]*b[4],crossY=b[2]*b[3]-b[0]*b[5],crossZ=b[0]*b[4]-b[1]*b[3];
            close(crossX*b[6]+crossY*b[7]+crossZ*b[8],1,1e-12,"right-handed body axes");
        }
    }
    private static void validation() {
        expectFailure(()->shape(new byte[1],2,1),"reject inconsistent dimensions");
        expectFailure(()->SpriteExtrusionGeometry.generate(new byte[1],1,1,2,3,32,.2,100),"reject inverted grid limits");
        expectFailure(()->SpriteExtrusionGeometry.generate(new byte[1],1,1,2,1,0,.2,100),"reject zero alpha threshold");
        expectFailure(()->SpriteExtrusionGeometry.generate(new byte[1],1,1,2,1,32,Double.NaN,100),"reject nonfinite coverage");
        expectFailure(()->SpriteExtrusionGeometry.basis(Double.NaN,0,0,new double[9]),"reject nonfinite attitude");
        expectFailure(()->SpriteExtrusionGeometry.basis(0,0,0,new double[8]),"reject undersized basis output");
    }
    private static void validateVertices(SpriteExtrusionGeometry.Result mesh) {
        float[] v=mesh.vertices();
        int stride=SpriteExtrusionGeometry.STRIDE;
        check(v.length%(stride*3)==0,"whole triangles only");
        for(int i=0;i<v.length;i+=stride) {
            for(int j=0;j<stride;j++)check(Float.isFinite(v[i+j]),"finite vertex");
            check(Math.abs(v[i])<=.500001&&Math.abs(v[i+1])<=.500001&&Math.abs(v[i+2])<=.500001,"normalized bounds");
            check(v[i+6]>=0&&v[i+6]<=1&&v[i+7]>=0&&v[i+7]<=1,"UV inside sprite");
            if(Math.abs(v[i+4])>.5) {
                close(v[i+6],v[i]+.5,1e-6,"cap U preserves footprint");
                close(v[i+7],v[i+2]+.5,1e-6,"cap V preserves footprint");
            }
        }
        for(int i=0;i<v.length;i+=stride*3) {
            double ax=v[i+stride]-v[i],ay=v[i+stride+1]-v[i+1],az=v[i+stride+2]-v[i+2];
            double bx=v[i+stride*2]-v[i],by=v[i+stride*2+1]-v[i+1],bz=v[i+stride*2+2]-v[i+2];
            double dot=(ay*bz-az*by)*v[i+3]+(az*bx-ax*bz)*v[i+4]+(ax*by-ay*bx)*v[i+5];
            check(dot>0,"triangle winding agrees with outward normal");
        }
    }
    private static double capArea(SpriteExtrusionGeometry.Result mesh,int normalY) {
        float[] v=mesh.vertices();int s=SpriteExtrusionGeometry.STRIDE;double area=0;
        for(int i=0;i<v.length;i+=s*3)if(v[i+4]==normalY)
            area+=Math.abs((v[i+s]-v[i])*(v[i+s*2+2]-v[i+2])-(v[i+s+2]-v[i+2])*(v[i+s*2]-v[i]))/2;
        return area;
    }
    private static boolean covers(SpriteExtrusionGeometry.Result mesh,double x,double z) {
        float[] v=mesh.vertices();int s=SpriteExtrusionGeometry.STRIDE;
        for(int i=0;i<v.length;i+=s*3) {
            if(v[i+4]!=1)continue;
            double a=cross(v[i],v[i+2],v[i+s],v[i+s+2],x,z);
            double b=cross(v[i+s],v[i+s+2],v[i+s*2],v[i+s*2+2],x,z);
            double c=cross(v[i+s*2],v[i+s*2+2],v[i],v[i+2],x,z);
            if((a>=-1e-9&&b>=-1e-9&&c>=-1e-9)||(a<=1e-9&&b<=1e-9&&c<=1e-9))return true;
        }
        return false;
    }
    private static double cross(double ax,double az,double bx,double bz,double px,double pz) {return (bx-ax)*(pz-az)-(bz-az)*(px-ax);}
    private static double dot(double[] b,int a,int c) {return b[a*3]*b[c*3]+b[a*3+1]*b[c*3+1]+b[a*3+2]*b[c*3+2];}
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static void close(double value,double expected,double tolerance,String message) {check(Math.abs(value-expected)<=tolerance,message+": "+value+" expected "+expected);}
    private static void expectFailure(Runnable body,String message) {try{body.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
}
