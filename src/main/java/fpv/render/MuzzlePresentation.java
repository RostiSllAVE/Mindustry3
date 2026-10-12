package fpv.render;

/** Immutable, spawn-anchored visual correction. Combat positions and hit detection remain untouched. */
public final class MuzzlePresentation {
    private MuzzlePresentation(){}
    public record Offset(double x,double y,double z,double upX,double upY,double upZ,double drop,boolean pilot){
        public double strength(double px,double py,double pz){
            return FirstPersonRigPose.falloff(Math.sqrt(square(px-x)+square(py-y)+square(pz-z)),pilot?4:2.5);
        }
    }
    public static Offset create(double x,double y,double z,double cameraX,double cameraY,double cameraZ,
            double cameraUpX,double cameraUpY,double cameraUpZ,double forwardX,double forwardY,double forwardZ,
            double endX,double endY,double endZ,double radius,boolean firstPerson,boolean allied){
        double dx=x-cameraX,dy=y-cameraY,dz=z-cameraZ;
        double along=dx*forwardX+dy*forwardY+dz*forwardZ;
        double travel=(endX-x)*forwardX+(endY-y)*forwardY+(endZ-z)*forwardZ;
        double cross=Math.max(0,dx*dx+dy*dy+dz*dz-along*along);
        boolean pilot=firstPerson&&allied&&along>-.8&&along<2.5&&cross<.65*.65&&travel>=-.0001;
        double drop=pilot?.50:Math.min(.24,.12+Math.max(0,radius)*.12);
        return new Offset(x,y,z,pilot?cameraUpX:0,pilot?cameraUpY:0,pilot?cameraUpZ:1,drop,pilot);
    }
    /** Very near pilot shots no longer inflate into a screen-covering ball; distant visibility is identical. */
    public static double nearRadius(double radius,double cameraDistance,boolean pilot){
        return pilot?Math.min(radius,.065+Math.max(0,cameraDistance)*.20):radius;
    }
    private static double square(double value){return value*value;}
}
