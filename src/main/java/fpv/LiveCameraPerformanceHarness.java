package fpv;

import arc.*;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.util.serialization.Jval;
import fpv.profile.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import mindustry.Vars;
import mindustry.core.PerfCounter;

/** Opt-in real-input capture in a cloned profile. Does not change settings, controls or simulation. */
final class LiveCameraPerformanceHarness {
    private final FpvMod mod;
    private final VisualGpuTimer gpu=new VisualGpuTimer();
    private final boolean gpuEnabled=Boolean.parseBoolean(System.getProperty("fpv.cameraProfile.gpu","true"));
    private final com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private Fi output;
    private Field suspended;
    private boolean stopped,measuring,inFrame;
    private int key=-1,frames,intervals;
    private long windowStart,begin,previous,cpuStart,bytesStart,intervalNanos,moduleNanos,cpuNanos,allocated,logicNanos,renderNanos,uiNanos;
    private double maxMs,pitchSum;
    LiveCameraPerformanceHarness(FpvMod mod){this.mod=mod;}
    void start(){try{
        String lab=System.getProperty("fpv.lab.path","");
        if(!CameraPerformanceProfile.ENABLED||lab.isBlank()||!Vars.dataDirectory.file().toPath().toAbsolutePath().normalize().startsWith(new java.io.File(lab).toPath().toAbsolutePath().normalize()))throw new IllegalStateException("Cloned diagnostic profile required");
        output=new Fi(System.getProperty("fpv.cameraProfile.output"));output.mkdirs();
        suspended=FpvMod.class.getDeclaredField("inputSuspended");suspended.setAccessible(true);
        if(bean.isThreadAllocatedMemorySupported()&&!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        if(bean.isThreadCpuTimeSupported()&&!bean.isThreadCpuTimeEnabled())bean.setThreadCpuTimeEnabled(true);
        Jval meta=Jval.newObject();meta.add("startedUtc",java.time.Instant.now().toString());meta.add("gpu",Gl.getString(Gl.renderer));meta.add("gpuQueriesEnabled",Jval.valueOf(gpuEnabled));meta.add("host",fpv.platform.HostCompatibility.currentVersion());meta.add("java",ProcessHandle.current().info().command().orElse("unknown"));meta.add("scope","Live user input; unchanged gameplay/settings in copied profile. Full module CPU/wall time, main-thread allocation and optional asynchronous GPU queries. Windows split by mode, pitch, pause and input suspension; short transition windows excluded. Nested part times must not be summed.");
        output.child("live-metadata.json").writeString(meta.toString(Jval.Jformat.formatted));
        Field field=ApplicationCore.class.getDeclaredField("modules");field.setAccessible(true);
        for(ApplicationListener listener:Core.app.getListeners())if(listener instanceof ApplicationCore){
            ApplicationListener[] original=(ApplicationListener[])field.get(listener),next=new ApplicationListener[original.length+2];
            next[0]=new ApplicationListener(){public void update(){beginFrame();}};
            System.arraycopy(original,0,next,1,original.length);
            next[next.length-1]=new ApplicationListener(){public void update(){endFrame();}public void dispose(){finish();}};
            field.set(listener,next);return;
        }
        throw new IllegalStateException("Native frame modules not found");
    }catch(Throwable e){fail(e);}}
    private double pitch(){return mod.enabled?Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,mod.camera.direction.y)))):0;}
    private int stateKey()throws IllegalAccessException{
        double pitch=pitch(),absolute=Math.abs(pitch);
        int bin=!mod.enabled?0:absolute<20?1:absolute<65?2:absolute<85?3:4;
        return bin+(pitch<0?8:0)+(Vars.state.isPaused()?16:0)+(mod.buildMode?32:0)+(Core.graphics.isHidden()?64:0)+(suspended.getBoolean(mod)?128:0);
    }
    private void beginFrame(){if(stopped)return;try{
        if(!Vars.state.isGame()||!mod.ready){if(measuring){flush();measuring=false;CameraPerformanceProfile.stop();}return;}
        long now=System.nanoTime();int current=stateKey();
        if(!measuring||key!=current||now-windowStart>=3_000_000_000L){if(measuring)flush();reset(current,now);}
        begin=now;inFrame=true;
        if(previous!=0){intervalNanos+=now-previous;intervals++;}previous=now;
        cpuStart=bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():0;
        bytesStart=bean.isThreadAllocatedMemorySupported()?bean.getThreadAllocatedBytes(Thread.currentThread().getId()):0;
        if(gpuEnabled)gpu.begin();
    }catch(Throwable e){fail(e);}}
    private void endFrame(){if(stopped||!inFrame)return;try{
        if(gpuEnabled)gpu.end();long elapsed=System.nanoTime()-begin;moduleNanos+=elapsed;maxMs=Math.max(maxMs,elapsed/1e6);frames++;pitchSum+=pitch();
        if(bean.isCurrentThreadCpuTimeSupported())cpuNanos+=Math.max(0,bean.getCurrentThreadCpuTime()-cpuStart);
        if(bean.isThreadAllocatedMemorySupported())allocated+=Math.max(0,bean.getThreadAllocatedBytes(Thread.currentThread().getId())-bytesStart);
        logicNanos+=PerfCounter.stateUpdate.latestValueNs();renderNanos+=PerfCounter.render.latestValueNs();uiNanos+=PerfCounter.ui.latestValueNs();inFrame=false;
    }catch(Throwable e){fail(e);}}
    private void reset(int current,long now){key=current;windowStart=now;frames=intervals=0;previous=intervalNanos=moduleNanos=cpuNanos=allocated=logicNanos=renderNanos=uiNanos=0;maxMs=pitchSum=0;measuring=true;CameraPerformanceProfile.reset();gpu.reset();}
    private void flush(){
        if(frames<2||System.nanoTime()-windowStart<750_000_000L)return;
        gpu.poll();Jval row=Jval.newObject();row.add("completedUtc",java.time.Instant.now().toString());row.add("mode",(key&7)==0?"2d":"fpv");put(row,"pitchBin",key&7);put(row,"pitchMeanDegrees",pitchSum/frames);row.add("paused",Jval.valueOf((key&16)!=0));row.add("build",Jval.valueOf((key&32)!=0));row.add("windowHidden",Jval.valueOf((key&64)!=0));row.add("inputSuspended",Jval.valueOf((key&128)!=0));put(row,"frames",frames);put(row,"fps",intervalNanos==0?0:intervals*1e9/intervalNanos);put(row,"intervalMs",intervals==0?0:intervalNanos/(double)intervals/1e6);put(row,"moduleMs",moduleNanos/(double)frames/1e6);put(row,"maxModuleMs",maxMs);put(row,"threadCpuMs",cpuNanos/(double)frames/1e6);put(row,"allocatedBytesPerFrame",allocated/(double)frames);put(row,"simulationMs",logicNanos/(double)frames/1e6);put(row,"nativeRenderMs",renderNanos/(double)frames/1e6);put(row,"uiMs",uiNanos/(double)frames/1e6);put(row,"gpuMs",gpu.meanMs());put(row,"gpuSamples",gpu.samples());row.add("gpuStatus",gpu.status());row.add("unit",Vars.player.dead()?"none":Vars.player.unit().type.name);put(row,"units",mindustry.gen.Groups.unit.size());put(row,"fpsCap",Core.settings.getInt("fpscap",0));row.add("vsync",Jval.valueOf(Core.settings.getBool("vsync",false)));row.add("resolution",Core.graphics.getWidth()+"x"+Core.graphics.getHeight());put(row,"glError",Gl.getError());row.add("parts",CameraPerformanceProfile.result(frames));output.child("live-windows.jsonl").writeString(row.toString(Jval.Jformat.plain)+"\n",true);
    }
    private static void put(Jval v,String key,double value){v.add(key,Jval.valueOf(value));}
    private void finish(){if(stopped)return;try{
        if(measuring)flush();
        // SDL/native shutdown can precede JVM dumponexit; finish our named recording explicitly.
        for(var recording:jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings())if(recording.getName().equals("fpv-camera-profile"))recording.dump(output.child("frames.jfr").file().toPath());
    }catch(Exception e){arc.util.Log.err("Could not finish diagnostic recording",e);}finally{stopped=true;CameraPerformanceProfile.stop();gpu.close();}}
    private void fail(Throwable e){stopped=true;CameraPerformanceProfile.stop();gpu.close();arc.util.Log.err("Live performance capture stopped; gameplay remains available",e);if(output!=null)output.child("live-error.txt").writeString(e.toString());}
}
