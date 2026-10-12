package fpv.construction;

/** Construction floors use canonical gameplay tiles (X/Y horizontal, Z up). */
public record LayerCoordinates(double spacing,int maxLayers) {
    public static final int MAX_LAYERS=512,CHUNK_SIZE=16;
    public static final double PLATFORM_THICKNESS=.16;
    public static final LayerCoordinates DEFAULT=new LayerCoordinates(1.4,64);
    public LayerCoordinates {
        if(!Double.isFinite(spacing)||spacing<.2||spacing>64||maxLayers<1||maxLayers>MAX_LAYERS)
            throw new IllegalArgumentException("Invalid construction floor configuration");
    }
    public double plane(int layer){if(layer<0||layer>maxLayers)throw new IllegalArgumentException("Construction layer outside configured bounds");return spacing*layer;}
    /** Ceiling, never nearest-to-player or rounded down into a solid cap. May exceed the configured limit. */
    public int layerAtOrAbove(double height){if(!Double.isFinite(height))throw new IllegalArgumentException("Finite surface required");return Math.max(0,(int)Math.ceil(height/spacing-1e-7));}
    public static int minCell(int anchor,int size){if(size<1||size>32)throw new IllegalArgumentException("Invalid building size");return anchor-(size-1)/2;}
    public static double center(int anchor,int size){return minCell(anchor,size)+(size-1)*.5;}
    public Bounds bounds(LayerComponent component){
        if(component==null||component.layer()>maxLayers)throw new IllegalArgumentException("Component outside configured layers");
        double x=minCell(component.x(),component.size())-.5,y=minCell(component.y(),component.size())-.5,z=plane(component.layer());
        return new Bounds(x,y,component.kind()==LayerComponent.Kind.PLATFORM?z-PLATFORM_THICKNESS:z,
            x+component.size(),y+component.size(),component.kind()==LayerComponent.Kind.PLATFORM?z:z+component.height());
    }
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        public Bounds {
            if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)||minX>maxX||minY>maxY||minZ>maxZ)
                throw new IllegalArgumentException("Finite ordered construction bounds required");
        }
        public boolean intersects(Bounds other){return other!=null&&minX<=other.maxX&&maxX>=other.minX&&minY<=other.maxY&&maxY>=other.minY&&minZ<=other.maxZ&&maxZ>=other.minZ;}
        public boolean overlaps(Bounds other){return other!=null&&Math.min(maxX,other.maxX)-Math.max(minX,other.minX)>1e-7&&Math.min(maxY,other.maxY)-Math.max(minY,other.minY)>1e-7&&Math.min(maxZ,other.maxZ)-Math.max(minZ,other.minZ)>1e-7;}
        public Bounds expanded(double amount){if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Nonnegative expansion required");return new Bounds(minX-amount,minY-amount,minZ-amount,maxX+amount,maxY+amount,maxZ+amount);}
        public double centerX(){return (minX+maxX)*.5;}public double centerY(){return (minY+maxY)*.5;}public double centerZ(){return (minZ+maxZ)*.5;}
    }
}
