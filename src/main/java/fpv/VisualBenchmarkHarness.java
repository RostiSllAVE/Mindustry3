package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.Log;
import arc.util.ScreenUtils;
import arc.util.serialization.Jval;
import fpv.profile.VisualGpuTimer;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/** Same bytecode runs on immutable0.11 and candidate0.12 through an isolated SmokeHarness overlay.
 * Measures the production world renderer only, with no wall-query workload and no live user save. */
public final class VisualBenchmarkHarness {
    private static final String[] PHASES={"forest-low-off","forest-overview-off","units-off","units-on","units-off-repeat"};
    private static final int WARMUP=60,SAMPLES=180;
    private final FpvMod mod;private final Camera3D camera=new Camera3D();private final VisualGpuTimer gpu=new VisualGpuTimer();
    private final Jval results=Jval.newObject(),phases=Jval.newArray();private final com.sun.management.ThreadMXBean bean=bean();
    private java.lang.reflect.Field autoEnable;
    private Fi output;private boolean generated,ready,done;private int phase,frames,waiting;private long fingerprint,propsAtStart;
    private long elapsedNanos,cpuNanos,allocatedBytes;private double maxElapsed;private Method markerGetter,markerStats;private Method[] statMethods;
    private final String[] statNames={"candidates","submitted","culledDistance","culledFrustum","culledFog","overlapSuppressed","draws","vertices","depthCopies","cpuNanos"};
    private final double[] markerTotals=new double[statNames.length];
    public VisualBenchmarkHarness(FpvMod mod){this.mod=mod;}
    public void start(){try{
        if(!Boolean.getBoolean("fpv.smoke")||!Boolean.getBoolean("fpv.visualProfile"))throw new IllegalStateException("Explicit visual lab flags required");
        String lab=System.getProperty("fpv.lab.path","");if(lab.isBlank()||!Vars.dataDirectory.file().getCanonicalPath().startsWith(new java.io.File(lab).getCanonicalPath()))throw new IllegalStateException("Dedicated native profile required");
        output=new Fi(System.getProperty("fpv.visualProfile.output"));output.mkdirs();
        Core.settings.put("backgroundpause",false);Core.settings.put("savecreate",false);Core.settings.put("hints",false);Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);
        Core.settings.put("fpscap",0);Core.settings.put("vsync",false);Core.graphics.setVSync(false);Core.settings.put("fpv-threat-markers",false);
        autoEnable=FpvMod.class.getDeclaredField("autoEnable");autoEnable.setAccessible(true);autoEnable.setBoolean(mod,false);
        mod.setEnabled(false);while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
        results.add("status","starting");results.add("scope","Generated paused native forest/crowd; production renderer CPU and asynchronous GPU only. Not interactive FPS and no wall-query workload.");
        results.add("version",Vars.mods.getMod(FpvMod.class).meta.version);results.add("startedUtc",java.time.Instant.now().toString());results.add("warmupFrames",Jval.valueOf(WARMUP));results.add("measuredFrames",Jval.valueOf(SAMPLES));results.add("phases",phases);write();
        Core.app.post(this::generate);
    }catch(Throwable error){fail(error.toString());}}
    private void generate(){try{
        Vars.control.saves.resetSave();Vars.logic.reset();Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.unitCap=2000;Vars.state.rules.fog=false;Vars.state.rules.staticFog=false;
        Vars.state.map=new mindustry.maps.Map(arc.struct.StringMap.of("name","FPV generated visual performance fixture"));
        mindustry.world.Block[] props={Blocks.pine,Blocks.sporePine,Blocks.whiteTree,Blocks.whiteTreeDead,Blocks.shrubs,Blocks.sporeCluster};
        Vars.world.loadGenerator(256,192,tiles->{
            for(int y=0;y<192;y++)for(int x=0;x<256;x++)tiles.set(x,y,new Tile(x,y,Blocks.sand,Blocks.air,Blocks.air));
            tiles.get(12,12).setBlock(Blocks.coreShard,Team.sharded,0);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)tiles.get(18+x*3,18+y*3).setBlock(props[(x+3*y)%props.length]);
        });
        var types=new mindustry.type.UnitType[]{UnitTypes.flare,UnitTypes.horizon,UnitTypes.zenith,UnitTypes.antumbra,UnitTypes.eclipse};
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){var type=types[(x+2*y)%types.length];Unit u=type.create((x+y)%3==0?Team.sharded:(x+y)%3==1?Team.crux:Team.malis);u.set((156+x*4)*8,(28+y*4)*8);u.rotation=90;u.add();}
        Vars.player.team(Team.sharded);Unit pilot=UnitTypes.alpha.create(Team.sharded);pilot.set(12*8,12*8);pilot.add();Vars.player.unit(pilot);Vars.logic.play();Vars.state.set(State.paused);
        mod.renderer3d.invalidate();mod.renderer3d.terrain().invalidate();mod.unitAltitudes.clear();generated=true;
        results.add("nativePropCount",Jval.valueOf(1024));results.add("nativeCrowdCount",Jval.valueOf(256));results.add("mapWidth",Jval.valueOf(256));results.add("mapHeight",Jval.valueOf(192));write();
    }catch(Throwable error){fail(error.toString());}}
    public void update(){if(done||!generated)return;try{
        Vars.state.set(State.paused);if(autoEnable.getBoolean(mod))autoEnable.setBoolean(mod,false);if(mod.enabled)mod.setEnabled(false);
        if(!ready){if(++waiting<90)return;while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            for(Unit unit:Groups.unit)mod.unitAltitudes.unitState(unit);
            fingerprint=fingerprint();results.add("tileFingerprintBefore",Long.toUnsignedString(fingerprint));results.add("nativeUnitCount",Jval.valueOf(Groups.unit.size()));
            try{markerGetter=mod.renderer3d.getClass().getMethod("threatMarkers");Object markers=markerGetter.invoke(mod.renderer3d);markerStats=markers.getClass().getMethod("stats");Object stats=markerStats.invoke(markers);statMethods=new Method[statNames.length];for(int i=0;i<statNames.length;i++)statMethods[i]=stats.getClass().getMethod(statNames[i]);}catch(NoSuchMethodException absent){markerGetter=null;}
            results.add("markerModulePresent",Jval.valueOf(markerGetter!=null));results.add("status","running");ready=true;setCamera();write();
        }
    }catch(Throwable error){fail(error.toString());}}
    public void render(){if(done||!ready)return;try{
        boolean measuring=frames>=WARMUP;if(frames==WARMUP){gpu.reset();propsAtStart=mod.renderer3d.organicPropSubmissions();}
        long bytes=allocated(),cpu=cpu(),started=System.nanoTime();if(measuring)gpu.begin();
        mod.renderer3d.render(camera);
        if(measuring){gpu.end();long elapsed=System.nanoTime()-started;elapsedNanos+=elapsed;maxElapsed=Math.max(maxElapsed,elapsed/1e6);long afterCpu=cpu(),afterBytes=allocated();if(cpu>=0&&afterCpu>=0)cpuNanos+=afterCpu-cpu;if(bytes>=0&&afterBytes>=0)allocatedBytes+=afterBytes-bytes;captureStats();}
        if(++frames==WARMUP+SAMPLES){
            gpu.poll();Jval p=Jval.newObject();p.add("phase",PHASES[phase]);p.add("renderCpuElapsedMeanMs",Jval.valueOf(elapsedNanos/(double)SAMPLES/1e6));p.add("renderCpuElapsedMaxMs",Jval.valueOf(maxElapsed));p.add("threadCpuMeanMs",Jval.valueOf(bean==null?-1:cpuNanos/(double)SAMPLES/1e6));p.add("allocatedBytesPerRender",Jval.valueOf(bean==null?-1:allocatedBytes/(double)SAMPLES));
            p.add("gpuStatus",gpu.status());p.add("gpuSamples",Jval.valueOf(gpu.samples()));p.add("gpuMeanMs",Jval.valueOf(gpu.meanMs()));p.add("gpuMaxMs",Jval.valueOf(gpu.maxMs()));
            p.add("organicPropSubmissionsDuringMeasurement",Jval.valueOf(mod.renderer3d.organicPropSubmissions()-propsAtStart));var rs=mod.renderer3d.stats();p.add("detailedChunks",Jval.valueOf(rs.detailedChunks()));p.add("coarseChunks",Jval.valueOf(rs.coarseChunks()));p.add("cachedChunkBytes",Jval.valueOf(rs.cachedBytes()));
            if(markerGetter!=null){Jval m=Jval.newObject();for(int i=0;i<statNames.length;i++)m.add(statNames[i]+"PerRender",Jval.valueOf(markerTotals[i]/SAMPLES));p.add("markers",m);}
            phases.add(p);ScreenUtils.saveScreenshot(output.child(PHASES[phase]+".png"));write();Log.info("[FPV-VISUAL-PROFILE] @",p.toString());
            if(++phase==PHASES.length){long after=fingerprint();results.add("tileFingerprintAfter",Long.toUnsignedString(after));if(after!=fingerprint)throw new IllegalStateException("Renderer changed native map data");int error=Gl.getError();results.add("glError",Jval.valueOf(error));if(error!=0)throw new IllegalStateException("OpenGL error "+error);done=true;results.add("status","passed");write();gpu.close();Core.app.post(Core.app::exit);}
            else{frames=0;elapsedNanos=cpuNanos=allocatedBytes=0;maxElapsed=0;java.util.Arrays.fill(markerTotals,0);setCamera();}
        }
    }catch(Throwable error){fail(error.toString());}}
    private void captureStats()throws ReflectiveOperationException{if(markerGetter==null)return;Object s=markerStats.invoke(markerGetter.invoke(mod.renderer3d));for(int i=0;i<statMethods.length;i++)markerTotals[i]+=((Number)statMethods[i].invoke(s)).doubleValue();}
    private void setCamera(){Core.settings.put("fpv-threat-markers",phase==3);camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.up.set(0,1,0);camera.near=.05f;camera.far=800;camera.fov=65;
        if(phase==0){camera.position.set(64,7,-12);camera.lookAt(64,3,-72);}else if(phase==1){camera.position.set(64,85,-22);camera.lookAt(64,2,-68);}else{camera.position.set(186,33,-8);camera.lookAt(186,5,-59);}camera.update();}
    private static long fingerprint(){long h=0xcbf29ce484222325L;for(Tile t:Vars.world.tiles){h=(h^t.blockID())*0x100000001b3L;h=(h^t.floorID())*0x100000001b3L;h=(h^t.overlayID())*0x100000001b3L;}return h;}
    private static com.sun.management.ThreadMXBean bean(){try{var b=ManagementFactory.getThreadMXBean();if(!(b instanceof com.sun.management.ThreadMXBean x))return null;if(x.isThreadAllocatedMemorySupported()&&!x.isThreadAllocatedMemoryEnabled())x.setThreadAllocatedMemoryEnabled(true);if(x.isCurrentThreadCpuTimeSupported()&&!x.isThreadCpuTimeEnabled())x.setThreadCpuTimeEnabled(true);return x;}catch(Throwable ignored){return null;}}
    private long allocated(){return bean==null||!bean.isThreadAllocatedMemorySupported()?-1:bean.getThreadAllocatedBytes(Thread.currentThread().getId());}
    private long cpu(){return bean==null||!bean.isCurrentThreadCpuTimeSupported()?-1:bean.getCurrentThreadCpuTime();}
    private void write(){if(output!=null)output.child("results.json").writeString(results.toString(Jval.Jformat.formatted));}
    public void fail(String message){if(done)return;done=true;results.add("status","failed");results.add("failure",message);write();Log.err("[FPV-VISUAL-PROFILE] @",message);gpu.close();Core.app.post(Core.app::exit);}
}
