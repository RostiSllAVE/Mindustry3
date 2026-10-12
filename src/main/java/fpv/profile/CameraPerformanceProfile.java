package fpv.profile;

import arc.util.serialization.Jval;
import java.util.Arrays;

/** Explicit lab-only primitive counters. Disabled calls constant-fold out of normal gameplay. */
public final class CameraPerformanceProfile {
    public static final boolean ENABLED=Boolean.getBoolean("fpv.cameraProfile");
    public enum Part {CONTROLS,CAMERA,PICK,SIM_ADAPTERS,WORLD_RENDER,ENVIRONMENT,TERRAIN,BUILDINGS,LAYERS,UNITS,VFX,PROJECTIONS,FIELDS,MARKERS,VIEWMODEL,HUD}
    private static final long[] nanos=new long[Part.values().length],calls=new long[nanos.length];
    private static boolean measuring;
    private static long rays,cells,pickCells;
    private CameraPerformanceProfile(){}
    public static long start(){return ENABLED&&measuring?System.nanoTime():0;}
    public static void end(Part part,long start){if(start!=0){nanos[part.ordinal()]+=System.nanoTime()-start;calls[part.ordinal()]++;}}
    public static void ray(){if(ENABLED&&measuring)rays++;}
    public static void cell(){if(ENABLED&&measuring)cells++;}
    public static void pickCell(){if(ENABLED&&measuring)pickCells++;}
    public static void reset(){Arrays.fill(nanos,0);Arrays.fill(calls,0);rays=cells=pickCells=0;measuring=true;}
    public static void stop(){measuring=false;}
    public static Jval result(int frames){
        Jval out=Jval.newObject();for(Part p:Part.values()){
            Jval part=Jval.newObject();part.add("msPerFrame",Jval.valueOf(nanos[p.ordinal()]/(double)frames/1e6));part.add("callsPerFrame",Jval.valueOf(calls[p.ordinal()]/(double)frames));out.add(p.name(),part);
        }
        out.add("heightRayQueriesPerFrame",Jval.valueOf(rays/(double)frames));out.add("heightRayCellsPerFrame",Jval.valueOf(cells/(double)frames));out.add("pickCellsPerFrame",Jval.valueOf(pickCells/(double)frames));return out;
    }
}
