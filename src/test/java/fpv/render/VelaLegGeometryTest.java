package fpv.render;

/** Off-centre native sprites must not stretch their transparent atlas padding into a leg. */
public final class VelaLegGeometryTest {
    private static int checks;
    public static void main(String[] args){
        byte[] alpha=new byte[122*104];
        for(int y=12;y<100;y++)for(int x=81;x<120;x++)alpha[x+y*122]=(byte)255;
        byte[] saved=alpha.clone();
        for(boolean coarse:new boolean[]{false,true}){
            var pieces=VelaLegGeometry.split(alpha,122,104,coarse);check(pieces.length==4,"four original-art components per leg");
            for(var piece:pieces){
                check(piece.x()==81&&piece.width()==39,"remove left padding, preserve actual native width");
                check(piece.height()>0&&piece.vertices().length>0,"nonempty alpha-extruded component");
                for(int v=0;v<piece.vertices().length;v+=8){
                    check(Float.isFinite(piece.vertices()[v]),"finite coordinate");
                    check(piece.vertices()[v+6]>=81/122f-1e-6&&piece.vertices()[v+6]<=120/122f+1e-6,"UV samples original opaque columns");
                    check(piece.vertices()[v+7]>=piece.y()/104f-1e-6&&piece.vertices()[v+7]<=(piece.y()+piece.height())/104f+1e-6,"UV stays within original segment rows");
                }
            }
            check(pieces[0].y()+pieces[0].height()>pieces[1].y(),"upper/shin socket overlap");
            check(pieces[1].y()+pieces[1].height()>pieces[3].y(),"shin/toe overlap");
        }
        check(java.util.Arrays.equals(alpha,saved),"source silhouette untouched");
        double[] left=new double[3],right=new double[3],rotated=new double[3];
        for(double h:new double[]{.5,1,5,15,40}){
            VelaLegGeometry.knee(0,-.6,h,0,-1.14,.08,1.45,0,-1,left);
            VelaLegGeometry.knee(0,.6,h,0,1.14,.08,1.45,0,1,right);
            check(Math.abs(left[0]-right[0])+Math.abs(left[1]+right[1])+Math.abs(left[2]-right[2])<1e-8,"mirrored flanks, same forward bend");
            check(left[0]>.1&&right[0]>.1,"visible knee even at maximum telescoping");
            check(left[2]<h&&left[2]>.08,"low stance folds below hull rather than through it");
            VelaLegGeometry.knee(.6,0,h,1.14,0,.08,1.45,90,-1,rotated);
            check(Math.abs(rotated[0]+left[1])+Math.abs(rotated[1]-left[0])+Math.abs(rotated[2]-left[2])<1e-8,"knee rotates with native yaw");
            double upper=distance(0,-.6,h,left),lower=distance(0,-1.14,.08,left);
            check(Math.abs(upper-lower)<1e-8,"connected articulated links at every standing height");
        }
        GroundGait gait=new GroundGait(2);gait.update(0,0,Math.PI,1.14,1.5,1/60d,(x,y)->y>0?.8:0);
        double fixed=gait.x(1);boolean swing=false;
        for(int frame=1;frame<=160;frame++){
            gait.update(frame*.03,0,Math.PI+frame*.003,1.14,1.5,1/60d,(x,y)->y>0?.8:0);
            if(frame==1)check(gait.x(1)==fixed,"opposite foot remains planted");
            for(int foot=0;foot<2;foot++){swing|=!gait.planted(foot);check(gait.z(foot)>=(gait.y(foot)>0?.8:0),"terrain clearance while moving and turning");}
        }
        check(swing&&gait.count()==2,"two native support feet actually step");
        System.out.println("VelaLegGeometryTest: "+checks+" checks passed");
    }
    private static double distance(double x,double y,double z,double[] p){return Math.sqrt(Math.pow(x-p[0],2)+Math.pow(y-p[1],2)+Math.pow(z-p[2],2));}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
