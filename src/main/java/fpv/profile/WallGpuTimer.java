package fpv.profile;

import arc.Core;
import arc.graphics.gl.GLVersion;
import arc.util.Buffers;
import java.nio.IntBuffer;

/** Optional asynchronous elapsed-time queries. Never waits for unavailable GPU results. */
public final class WallGpuTimer implements AutoCloseable {
    private static final boolean enabled=Boolean.getBoolean("fpv.wallProfile");
    private static final int TIME_ELAPSED=0x88BF,QUERY_RESULT=0x8866,QUERY_AVAILABLE=0x8867,QUERY_COUNTER_BITS=0x8864;
    private static final int RING=12;
    private final IntBuffer values=Buffers.newIntBuffer(RING),one=Buffers.newIntBuffer(1);
    private final int[] ids=new int[RING];
    private final boolean[] pending=new boolean[RING];
    private final long[] epochs=new long[RING];
    private boolean initialized,supported;
    private int next,active=-1;
    public void begin(){
        if(!enabled)return;
        if(!initialized)initialize();
        if(!supported)return;
        poll();if(!WallProfile.active()||pending[next])return;
        active=next;epochs[active]=WallProfile.epoch();Core.gl30.glBeginQuery(TIME_ELAPSED,ids[active]);
    }
    public void end(){
        if(active<0)return;
        Core.gl30.glEndQuery(TIME_ELAPSED);pending[active]=true;next=(active+1)%RING;active=-1;
    }
    private void initialize(){
        initialized=true;
        if(Core.gl30==null||Core.graphics==null||Core.graphics.getGLVersion().type!=GLVersion.GlType.OpenGL||
            !(Core.graphics.getGLVersion().atLeast(3,3)||Core.graphics.supportsExtension("GL_ARB_timer_query"))){
            WallProfile.gpuStatus(false,"unavailable: native desktop timer-query support not exposed");return;
        }
        one.clear();Core.gl30.glGetQueryiv(TIME_ELAPSED,QUERY_COUNTER_BITS,one);
        if(one.get(0)==0){WallProfile.gpuStatus(false,"unavailable: elapsed-time query has no counter bits");return;}
        values.clear();Core.gl30.glGenQueries(RING,values);for(int i=0;i<RING;i++)ids[i]=values.get(i);
        supported=true;WallProfile.gpuStatus(true,"asynchronous GL_TIME_ELAPSED; available results only, 32-bit read for sub-4.29-second pass durations");
    }
    private void poll(){
        for(int i=0;i<RING;i++)if(pending[i]){
            one.clear();Core.gl30.glGetQueryObjectuiv(ids[i],QUERY_AVAILABLE,one);if(one.get(0)==0)continue;
            one.clear();Core.gl30.glGetQueryObjectuiv(ids[i],QUERY_RESULT,one);long nanos=Integer.toUnsignedLong(one.get(0));
            pending[i]=false;if(WallProfile.active()&&epochs[i]==WallProfile.epoch())WallProfile.gpuSample(nanos);
        }
    }
    public void close(){
        if(active>=0)end();
        if(supported&&Core.gl30!=null){values.clear();for(int i=0;i<RING;i++)values.put(i,ids[i]);Core.gl30.glDeleteQueries(RING,values);}
        initialized=supported=false;active=-1;for(int i=0;i<RING;i++)pending[i]=false;
    }
}
