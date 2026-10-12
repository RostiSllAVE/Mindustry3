package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import fpv.profile.VisualGpuTimer;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.*;
import java.util.ArrayList;

/** Explicit disposable warm-scene comparison; no gameplay profiling, timing gates or FPS claims. */
final class ReignModelPerfChecks {
    static String run(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active()||Boolean.getBoolean("fpv.layers.profile")||Boolean.getBoolean("fpv.wallProfile"))
            throw new IllegalStateException("Isolated non-nested native profile required");
        String previous=System.getProperty("fpv.visualProfile");System.setProperty("fpv.visualProfile","true");
        var units=new ArrayList<Unit>();var timer=new VisualGpuTimer();var camera=new Camera3D();
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=65;camera.near=.05f;camera.far=2000;
        camera.up.set(0,1,0);camera.position.set(101,20,-66);camera.lookAt(95,.5f,-95);camera.update();
        StringBuilder report=new StringBuilder("Warm render comparison: same 1280x800 sand scene, camera, 13 units at identical XY with native ground Z, 24 warm renders + 64 measured submissions per phase; S/R/S/R order. CPU elapsed includes submission only; GPU queries asynchronous, available samples only; excludes AI, swap/vsync and hardware input, not FPS.\n");
        try{
            for(int phase=0;phase<4;phase++){
                var type=phase%2==0?UnitTypes.scepter:UnitTypes.reign;
                for(int i=0;i<13;i++){
                    var unit=type.create(Vars.player.team());unit.set((95+(i%4-1.5f)*6f)*Vars.tilesize,(86+i/4*6f)*Vars.tilesize);
                    unit.rotation=90;((Mechc)unit).baseRotation(90);unit.add();altitudes.pose(unit);units.add(unit);
                }
                for(int i=0;i<24;i++)renderer.render(camera);
                for(var unit:units)if(!renderer.groundFamily().wasSubmitted(unit))throw new IllegalStateException("Benchmark crowd not visible");
                long builds=renderer.extrusionStats().builds(),limbs=renderer.groundFamily().nativeLimbStats().builds();
                long cpu=0;double maximum=0;timer.reset();
                for(int i=0;i<64;i++){
                    timer.begin();long start=System.nanoTime();renderer.render(camera);long elapsed=System.nanoTime()-start;timer.end();
                    cpu+=elapsed;maximum=Math.max(maximum,elapsed/1e6);
                }
                timer.poll();
                if(builds!=renderer.extrusionStats().builds()||limbs!=renderer.groundFamily().nativeLimbStats().builds())throw new IllegalStateException("Warm benchmark regenerated geometry");
                report.append(type.name).append(" phase=").append(phase).append(" CPUmeanMs=").append(cpu/64.0/1e6).append(" CPUmaxMs=").append(maximum)
                    .append(" GPUmeanMs=").append(timer.meanMs()).append(" GPUsamples=").append(timer.samples()).append(" GPUstatus=").append(timer.status()).append(" buildsDuringMeasurement=0\n");
                for(var unit:units)unit.remove();units.clear();
            }
            if(Gl.getError()!=0)throw new IllegalStateException("Benchmark GL error");return report.toString();
        }finally{
            for(var unit:units)unit.remove();timer.close();if(previous==null)System.clearProperty("fpv.visualProfile");else System.setProperty("fpv.visualProfile",previous);
        }
    }
}
