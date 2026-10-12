package fpv;

import arc.*;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.util.serialization.Jval;
import fpv.profile.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;

/** Full native game frames; identical generated scene, uncapped, async GPU and no pixel readback. */
final class CameraPerformanceHarness {
    private static final String[] MODES={"2d","2d-paused","fpv0","fpv+45","fpv-45","fpv+80","fpv-80","fpv+89","fpv-89","fpv0-paused","fpv+89-paused","2d-repeat"};
    private final FpvMod mod;private final VisualGpuTimer gpu=new VisualGpuTimer();
    private final int warmup=Integer.getInteger("fpv.cameraProfile.warmup",90),samples=Integer.getInteger("fpv.cameraProfile.samples",240);
    private final com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private final Jval report=Jval.newObject(),phases=Jval.newArray();
    private final double[] frameMs=new double[samples];private int phase,frame,wait;private boolean ready,finished,generated;
    private Fi output;private Field autoEnable;private long begin,previous,cpuStart,bytesStart,frameNanos,cpuNanos,bytes,logicNanos,renderNanos,uiNanos,visibleChunks;
    private Unit pilot;
    private double focusX=64,focusY=64,altitude=Double.parseDouble(System.getProperty("fpv.cameraProfile.altitude","16"));
    CameraPerformanceHarness(FpvMod mod){this.mod=mod;}
    void start(){try{
        String lab=System.getProperty("fpv.lab.path","");
        if(!CameraPerformanceProfile.ENABLED||lab.isBlank()||!Vars.dataDirectory.file().toPath().toAbsolutePath().normalize().startsWith(new java.io.File(lab).toPath().toAbsolutePath().normalize()))throw new IllegalStateException("Isolated performance profile required");
        output=new Fi(System.getProperty("fpv.cameraProfile.output"));output.mkdirs();
        Core.settings.put("backgroundpause",false);Core.settings.put("savecreate",false);Core.settings.put("hints",false);Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);Core.settings.put("fpscap",0);Core.settings.put("vsync",false);Core.graphics.setVSync(false);
        if(!Boolean.getBoolean("fpv.cameraProfile.userSettings")){Core.settings.put("fpv-camera-smoothing",false);Core.settings.put("fpv-camera-view",0);Core.settings.put("fpv-pov-angle",90);Core.settings.put("fpv-fov",80);}
        Core.settings.put("fpv-flight-mode",1);
        if(mod!=null){autoEnable=FpvMod.class.getDeclaredField("autoEnable");autoEnable.setAccessible(true);autoEnable.setBoolean(mod,false);}
        if(bean.isThreadAllocatedMemorySupported()&&!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        if(bean.isThreadCpuTimeSupported()&&!bean.isThreadCpuTimeEnabled())bean.setThreadCpuTimeEnabled(true);
        while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
        report.add("status","starting");report.add("runtime",mod==null?"native-only probe; no FPV adapters installed":"Mindustry3 full runtime");report.add("host",fpv.platform.HostCompatibility.currentVersion());report.add("resolution",Core.graphics.getWidth()+"x"+Core.graphics.getHeight());report.add("scope","Main-thread full module frame work and inter-frame interval, asynchronous whole-frame GPU queries; no synchronous pixel readbacks or per-frame logs. FPS includes SDL presentation/async waits. Draw counts sampled separately if available.");report.add("samples",Jval.valueOf(samples));report.add("warmup",Jval.valueOf(warmup));report.add("phases",phases);write();
        report.add("countOnlyRun",Jval.valueOf(Boolean.getBoolean("fpv.cameraProfile.countDraws")));CameraDrawCounter.install();
        report.add("gpuRenderer",Gl.getString(Gl.renderer));report.add("gpuVendor",Gl.getString(Gl.vendor));report.add("javaExecutable",ProcessHandle.current().info().command().orElse("unknown"));
        installTaps();Core.app.post(this::generate);
    }catch(Throwable e){fail(e);}}
    private void installTaps()throws Exception{
        Field field=ApplicationCore.class.getDeclaredField("modules");field.setAccessible(true);
        for(ApplicationListener listener:Core.app.getListeners())if(listener instanceof ApplicationCore){
            ApplicationListener[] original=(ApplicationListener[])field.get(listener),next=new ApplicationListener[original.length+2];
            next[0]=new ApplicationListener(){public void update(){beginFrame();}};
            System.arraycopy(original,0,next,1,original.length);next[next.length-1]=new ApplicationListener(){public void update(){endFrame();}};field.set(listener,next);return;
        }
        throw new IllegalStateException("Native frame modules not found");
    }
    private void generate(){try{
        Vars.control.saves.resetSave();Vars.logic.reset();Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.fog=false;Vars.state.rules.staticFog=false;Vars.state.rules.unitCap=1000;
        Vars.state.map=new mindustry.maps.Map(arc.struct.StringMap.of("name","Camera performance fixed fixture"));
        int size=Integer.getInteger("fpv.cameraProfile.mapSize",128);String saved=System.getProperty("fpv.cameraProfile.save","");
        if(saved.isBlank())Vars.world.loadGenerator(size,size,tiles->{for(int y=0;y<size;y++)for(int x=0;x<size;x++)tiles.set(x,y,new Tile(x,y,Blocks.sand,Blocks.air,Blocks.air));tiles.get(12,12).setBlock(Blocks.coreShard,Team.sharded,0);});
        else{mindustry.io.SaveIO.load(new Fi(saved));Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.fog=false;Vars.state.rules.staticFog=false;Vars.state.rules.unitCap=1000;}
        Team team=Vars.state.rules.defaultTeam;var core=team.core();
        if(!saved.isBlank()){focusX=core==null?Vars.world.width()*.5:core.x/(double)Vars.tilesize+core.block.size+2;focusY=core==null?Vars.world.height()*.5:core.y/(double)Vars.tilesize;}
        var type=Vars.content.unit(System.getProperty("fpv.cameraProfile.unit","flare"));if(type==null)throw new IllegalArgumentException("Unknown benchmark unit");if(!type.supportsEnv(Vars.state.rules.env)&&core!=null)type=((mindustry.world.blocks.storage.CoreBlock)core.block).unitType;
        pilot=type.create(team);pilot.spawnedByCore=Boolean.getBoolean("fpv.cameraProfile.coreSpawned");pilot.set((float)focusX*Vars.tilesize,(float)focusY*Vars.tilesize);pilot.rotation=90;pilot.add();Vars.player.team(team);Vars.player.unit(pilot);Vars.logic.play();generated=true;
        report.add("map",saved.isBlank()?size+"x"+size+" sand, 1 native core, 1 controlled Flare; no combat targets":"Copied user save, "+Vars.world.width()+"x"+Vars.world.height()+", units="+Groups.unit.size());report.add("pilot",pilot.type.name);put(report,"fov",Core.settings.getInt("fpv-fov",80));put(report,"altitude",altitude);write();
    }catch(Throwable e){fail(e);}}
    private void configure(){
        String mode=MODES[phase];boolean fpv=mod!=null&&mode.startsWith("fpv");
        Vars.state.set(mode.contains("paused")?GameState.State.paused:GameState.State.playing);
        if(mod!=null){autoEnableSet();if(mod.enabled!=fpv)mod.setEnabled(fpv);mod.setBuildMode(false);mod.flightMode=1;
            double pitch=mode.contains("+45")?45:mode.contains("-45")?-45:mode.contains("+80")?80:mode.contains("-80")?-80:mode.contains("+89")?89:mode.contains("-89")?-89:0;
            var state=mod.flight.state();state.x=focusX;state.y=altitude;state.z=-focusY;state.vx=state.vy=state.vz=0;state.wx=state.wy=state.wz=0;
            mod.freeLook.align(0,Math.sin(Math.toRadians(pitch)),-Math.cos(Math.toRadians(pitch)));
        }
        pilot.set((float)focusX*Vars.tilesize,(float)focusY*Vars.tilesize);pilot.vel.setZero();Core.camera.position.set(pilot.x,pilot.y);
    }
    private void autoEnableSet(){try{autoEnable.setBoolean(mod,false);}catch(Exception e){throw new IllegalStateException(e);}}
    private void beginFrame(){if(finished||!generated)return;try{
        if(!ready){if(++wait<120||Vars.player.dead()||mod!=null&&!mod.ready)return;ready=true;report.add("status","running");}
        configure();if(frame==warmup){CameraPerformanceProfile.reset();CameraDrawCounter.reset();gpu.reset();previous=0;}
        if(frame>=warmup){begin=System.nanoTime();if(previous!=0)frameNanos+=begin-previous;previous=begin;cpuStart=bean.getCurrentThreadCpuTime();bytesStart=bean.getThreadAllocatedBytes(Thread.currentThread().getId());gpu.begin();}
    }catch(Throwable e){fail(e);}}
    private void endFrame(){if(finished||!ready)return;try{
        if(frame>=warmup){gpu.end();long elapsed=System.nanoTime()-begin;frameMs[frame-warmup]=elapsed/1e6;cpuNanos+=Math.max(0,bean.getCurrentThreadCpuTime()-cpuStart);bytes+=Math.max(0,bean.getThreadAllocatedBytes(Thread.currentThread().getId())-bytesStart);
            logicNanos+=PerfCounter.stateUpdate.latestValueNs();renderNanos+=PerfCounter.render.latestValueNs();uiNanos+=PerfCounter.ui.latestValueNs();
            if(mod!=null&&mod.enabled)visibleChunks+=mod.renderer3d.stats().visibleChunks();
        }
        if(++frame>=warmup+samples){gpu.poll();Jval p=Jval.newObject();p.add("mode",MODES[phase]);Arrays.sort(frameMs);put(p,"moduleFrameMeanMs",Arrays.stream(frameMs).average().orElse(0));put(p,"moduleFrameP95Ms",frameMs[(int)(samples*.95)]);put(p,"interFrameMeanMs",frameNanos/(double)(samples-1)/1e6);put(p,"fps",(samples-1)*1e9/Math.max(1,frameNanos));put(p,"threadCpuMs",cpuNanos/(double)samples/1e6);put(p,"allocatedBytesPerFrame",bytes/(double)samples);put(p,"nativeSimulationMs",logicNanos/(double)samples/1e6);put(p,"nativeRenderCounterMs",renderNanos/(double)samples/1e6);put(p,"uiCounterMs",uiNanos/(double)samples/1e6);put(p,"visible3DChunks",visibleChunks/(double)samples);put(p,"gpuMs",gpu.meanMs());put(p,"gpuSamples",gpu.samples());p.add("gpuStatus",gpu.status());put(p,"drawCallsPerFrame",Boolean.getBoolean("fpv.cameraProfile.countDraws")?CameraDrawCounter.draws()/(double)samples:-1);put(p,"trianglesPerFrame",Boolean.getBoolean("fpv.cameraProfile.countDraws")?CameraDrawCounter.triangles()/(double)samples:-1);p.add("completedUtc",java.time.Instant.now().toString());p.add("parts",CameraPerformanceProfile.result(samples));phases.add(p);CameraPerformanceProfile.stop();write();
            frame=0;previous=frameNanos=cpuNanos=bytes=logicNanos=renderNanos=uiNanos=visibleChunks=0;
            phase++;
            if(mod==null&&phase==2||phase==MODES.length){int error=Gl.getError();put(report,"glError",error);if(error!=0)throw new IllegalStateException("GL error "+error);report.add("status","passed");write();finished=true;gpu.close();Core.app.post(Core.app::exit);}
        }
    }catch(Throwable e){fail(e);}}
    private static void put(Jval v,String key,double n){v.add(key,Jval.valueOf(n));}
    private void write(){if(output!=null)output.child("camera-profile.json").writeString(report.toString(Jval.Jformat.formatted));}
    private void fail(Throwable e){if(finished)return;finished=true;CameraPerformanceProfile.stop();gpu.close();report.add("status","failed");report.add("failure",e.toString());write();arc.util.Log.err(e);Core.app.post(Core.app::exit);}
}
