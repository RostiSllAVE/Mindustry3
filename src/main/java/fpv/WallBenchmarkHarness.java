package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.g3d.Camera3D;
import arc.util.Log;
import arc.util.ScreenUtils;
import arc.util.serialization.Jval;
import fpv.profile.WallProfile;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.gen.Building;
import java.util.ArrayList;

/** Fixed-camera paired benchmark on a copied real save; never enters a normal game profile. */
final class WallBenchmarkHarness {
    private final FpvMod mod;
    private final Camera3D camera=new Camera3D();
    private final Jval result=Jval.newObject(),phases=Jval.newArray();
    private Fi output;
    private boolean ready,done,measuring;
    private int frames,phase;
    private double cx,cy,top;
    private double[][] rays;
    private final int warmup=120,samples=300;
    WallBenchmarkHarness(FpvMod mod){this.mod=mod;}
    void start(){try{
        String explicit=System.getProperty("fpv.lab.path","");
        if(explicit.isEmpty()||!Vars.dataDirectory.file().getCanonicalPath().startsWith(new java.io.File(explicit).getCanonicalPath()))throw new IllegalStateException("Isolated benchmark profile required");
        output=new Fi(System.getProperty("fpv.wallProfile.output"));output.mkdirs();
        Core.settings.put("backgroundpause",false);Core.settings.put("savecreate",false);Core.settings.put("hints",false);
        Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);Core.settings.put("fpscap",0);Core.settings.put("vsync",false);Core.graphics.setVSync(false);
        Core.settings.put("fpv-wall-segments",5);Core.settings.put("fpv-wall-hp-percent",85);
        while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
        var save=new Fi(System.getProperty("fpv.wallProfile.save"));
        if(!save.exists())throw new IllegalStateException("Copied real save required");
        Vars.control.saves.resetSave();mindustry.io.SaveIO.load(save);Vars.state.set(State.paused);
        // Native WorldLoad uses -1, also the old projection cache's uninitialized sentinel.
        // Advance the revision only (no tile edits) so both paired builds see a populated cache.
        Vars.world.tileChanges++;
        result.add("save",save.absolutePath());result.add("startedUtc",java.time.Instant.now().toString());
        result.add("version",Vars.mods.getMod(FpvMod.class).meta.version);result.add("label",System.getProperty("fpv.wallProfile.label","unspecified"));
        result.add("status","running");result.add("warmupFrames",Jval.valueOf(warmup));result.add("measuredFramesPerView",Jval.valueOf(samples));
        result.add("method","Real save, unchanged tiles, paused deterministic world; fixed near/mid/overview cameras, 256 identical XYZ wall queries per frame; frame CPU/submission work is not a GPU timer or uncapped interactive FPS guarantee.");
        result.add("phases",phases);write();
    }catch(Throwable e){fail(e);}}
    void update(){if(done)return;try{
        if(output==null)return;
        if(!ready){
            if(++frames<90||!Vars.state.isGame())return;
            while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
            int builds=0,walls=0,live=0;String example="";
            for(var t:Vars.world.tiles)if(t.build!=null&&t.build.tile==t){builds++;if(t.block() instanceof mindustry.world.blocks.defense.Wall){walls++;if(fpv.defense.ProjectedWallSystem.eligible(t.build))live++;example=t.block().name+" health="+t.build.health+" max="+t.build.maxHealth+" dead="+t.build.dead;}}
            result.add("nativeBuildings",Jval.valueOf(builds));result.add("nativeWalls",Jval.valueOf(walls));result.add("eligibleWalls",Jval.valueOf(live));result.add("exampleWall",example);result.add("nativeMapWidth",Jval.valueOf(Vars.world.width()));
            mod.projectedWalls.update(0);var all=mod.projectedWalls.volumes();
            if(all.size()<200)throw new IllegalStateException("Real-save benchmark requires >=200 wall anchors; found "+all.size());
            int[] density=new int[((Vars.world.width()+31)/32)*((Vars.world.height()+31)/32)];int width=(Vars.world.width()+31)/32,best=0;
            for(var v:all){int ix=Math.max(0,Math.min(width-1,(int)v.x()/32)),iy=Math.max(0,(int)v.y()/32),key=iy*width+ix;if(key<density.length&&++density[key]>density[best])best=key;top=Math.max(top,v.topZ());}
            cx=(best%width+.5)*32;cy=(best/width+.5)*32;
            result.add("map",Vars.state.map.name());result.add("width",Jval.valueOf(Vars.world.width()));result.add("height",Jval.valueOf(Vars.world.height()));
            result.add("logicalWallAnchors",Jval.valueOf(all.size()));result.add("denseChunkAnchors",Jval.valueOf(density[best]));
            result.add("focusX",Jval.valueOf(cx));result.add("focusY",Jval.valueOf(cy));
            rays=new double[256][6];for(int i=0;i<rays.length;i++){
                var wall=all.get((i*997)%all.size());double angle=i*2.399963229728653,span=i%4==0?Math.hypot(Vars.world.width(),Vars.world.height()):4+(i%12)*2;
                double z=(wall.baseZ()+wall.topZ())*.5;double dx=Math.cos(angle)*span,dy=Math.sin(angle)*span;
                rays[i]=new double[]{wall.x()-dx,wall.y()-dy,z,wall.x()+dx,wall.y()+dy,z};
            }
            result.add("tileFingerprintBefore",Long.toUnsignedString(fingerprint()));ready=true;frames=0;setCamera();
            Log.info("[FPV-WALL-PROFILE] Real map @x@ with @ wall anchors; dense chunk @",Vars.world.width(),Vars.world.height(),all.size(),density[best]);
        }
        Vars.state.set(State.paused);
        if(frames==warmup){WallProfile.reset();measuring=true;}
        if(measuring)WallProfile.frameBegin();
        mod.projectedWalls.update(0);
        for(double[] r:rays)mod.projectedWalls.trace(Team.derelict,r[0],r[1],r[2],r[3],r[4],r[5],.05);
    }catch(Throwable e){fail(e);}}
    void render(){if(done||!ready)return;try{
        mod.renderer3d.render(camera);
        if(measuring)WallProfile.frameEnd();
        frames++;
        if(frames==warmup+samples){
            var report=WallProfile.report();WallProfile.stop();measuring=false;
            String name=new String[]{"near","mid","overview"}[phase];report.add("view",name);phases.add(report);
            ScreenUtils.saveScreenshot(output.child(name+".png"));write();Log.info("[FPV-WALL-PROFILE] @ @",name,report.toString());
            if(++phase==3){
                result.add("exhaustiveQueryComparisons",Jval.valueOf(fpv.defense.ProjectedWallOptimizationChecks.compareQueries(mod.projectedWalls,2048)));
                result.add("nearestProjectionPixels",fpv.render.ProjectionCompositeChecks.verify());
                result.add("tileFingerprintAfter",Long.toUnsignedString(fingerprint()));
                if(!result.getString("tileFingerprintBefore").equals(result.getString("tileFingerprintAfter")))throw new IllegalStateException("Benchmark changed native tiles");
                int error=arc.graphics.Gl.getError();result.add("glError",Jval.valueOf(error));if(error!=0)throw new IllegalStateException("OpenGL error "+error);
                result.add("status","passed");done=true;write();Log.info("[FPV-WALL-PROFILE] PASS");Core.app.post(Core.app::exit);
            }else{frames=0;setCamera();}
        }
    }catch(Throwable e){fail(e);}}
    private void setCamera(){
        double distance=new double[]{38,115,Math.max(Vars.world.width(),Vars.world.height())*.9}[phase];
        double x=phase==2?Vars.world.width()*.5:cx,y=phase==2?Vars.world.height()*.5:cy;
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.position.set((float)(x+distance*.35),(float)(top+distance*.62),(float)(-y+distance*.55));
        camera.up.set(0,1,0);camera.lookAt((float)x,(float)(top*.25),(float)-y);camera.near=.05f;camera.far=4000;camera.fov=70;camera.update();
    }
    private long fingerprint(){long hash=0xcbf29ce484222325L;for(var t:Vars.world.tiles){hash=(hash^t.block().id)*0x100000001b3L;hash=(hash^t.floor().id)*0x100000001b3L;hash=(hash^t.overlay().id)*0x100000001b3L;hash=(hash^t.team().id)*0x100000001b3L;}return hash;}
    private void write(){if(output!=null)output.child("results.json").writeString(result.toString(Jval.Jformat.formatted));}
    private void fail(Throwable e){done=true;WallProfile.stop();result.add("status","failed");result.add("failure",e.toString());write();Log.err("[FPV-WALL-PROFILE] Failed",e);Core.app.post(Core.app::exit);}
}
