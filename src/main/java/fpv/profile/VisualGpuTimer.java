package fpv.profile;

import arc.Core;
import arc.graphics.gl.GLVersion;
import arc.util.Buffers;
import java.nio.IntBuffer;

/** Isolated visual benchmark timer. Available asynchronous results only; never waits for the GPU. */
public final class VisualGpuTimer implements AutoCloseable {
    private static final int ELAPSED=0x88BF,RESULT=0x8866,AVAILABLE=0x8867,BITS=0x8864,SIZE=12;
    private final IntBuffer values=Buffers.newIntBuffer(SIZE),one=Buffers.newIntBuffer(1);
    private final int[] ids=new int[SIZE];private final long[] epochs=new long[SIZE];private final boolean[] pending=new boolean[SIZE];
    private boolean initialized,supported;private int next,active=-1;private long epoch,samples,nanos,max;
    private String status="not initialized";
    public void reset(){epoch++;samples=nanos=max=0;}
    public void begin(){
        if(!Boolean.getBoolean("fpv.visualProfile"))throw new IllegalStateException("Explicit visual benchmark only");
        if(!initialized)initialize();if(!supported)return;poll();if(pending[next])return;
        active=next;epochs[active]=epoch;Core.gl30.glBeginQuery(ELAPSED,ids[active]);
    }
    public void end(){if(active<0)return;Core.gl30.glEndQuery(ELAPSED);pending[active]=true;next=(active+1)%SIZE;active=-1;}
    public void poll(){if(!supported)return;for(int i=0;i<SIZE;i++)if(pending[i]){
        one.clear();Core.gl30.glGetQueryObjectuiv(ids[i],AVAILABLE,one);if(one.get(0)==0)continue;
        one.clear();Core.gl30.glGetQueryObjectuiv(ids[i],RESULT,one);long value=Integer.toUnsignedLong(one.get(0));pending[i]=false;
        if(epochs[i]==epoch){samples++;nanos+=value;max=Math.max(max,value);}
    }}
    private void initialize(){
        initialized=true;if(Core.gl30==null||Core.graphics.getGLVersion().type!=GLVersion.GlType.OpenGL||!(Core.graphics.getGLVersion().atLeast(3,3)||Core.graphics.supportsExtension("GL_ARB_timer_query"))){status="unavailable: desktop elapsed query unsupported";return;}
        one.clear();Core.gl30.glGetQueryiv(ELAPSED,BITS,one);if(one.get(0)==0){status="unavailable: no elapsed counter bits";return;}
        values.clear();Core.gl30.glGenQueries(SIZE,values);for(int i=0;i<SIZE;i++)ids[i]=values.get(i);supported=true;status="asynchronous GL_TIME_ELAPSED; available results only, unsigned 32-bit durations below4.29s";
    }
    public long samples(){return samples;}public double meanMs(){return samples==0?-1:nanos/(double)samples/1e6;}public double maxMs(){return samples==0?-1:max/1e6;}public String status(){return status;}
    public void close(){end();if(supported){values.clear();for(int i=0;i<SIZE;i++)values.put(i,ids[i]);Core.gl30.glDeleteQueries(SIZE,values);}supported=false;}
}
