package fpv;

import arc.Core;
import fpv.flight.FlightController;
import fpv.generated.RuntimeConfig;
import mindustry.Vars;

/** Reversible integration checks at the real camera/controller/movement boundary. */
final class FlightControlsSmokeChecks {
    private final FpvMod mod;
    private int checks;
    FlightControlsSmokeChecks(FpvMod mod){this.mod=mod;}
    String run(){
        check(Boolean.getBoolean("fpv.smoke")&&!mod.motion.pending(),"explicit lab outside queued movement");
        var unit=Vars.player.unit();var s=mod.flight.state();
        double[] saved={s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz};
        String[] keys={"fpv-flight-mode","fpv-camera-view","fpv-pov-angle","fpv-flight-speed"};
        Object[] old=new Object[keys.length];boolean[] had=new boolean[keys.length];
        for(int i=0;i<keys.length;i++){had[i]=Core.settings.has(keys[i]);old[i]=Core.settings.get(keys[i],null);}
        boolean build=mod.buildMode;int mode=mod.flightMode;double throttle=mod.throttle;
        float ux=unit.x,uy=unit.y,vx=unit.vel.x,vy=unit.vel.y;
        try{
            mod.buildMode=false;
            Core.settings.put("fpv-flight-mode",1);Core.settings.put("fpv-camera-view",0);
            Core.settings.put("fpv-pov-angle",90);mod.syncFlightMode();
            check(mod.flightMode==1,"live mode setting applied");
            fixture(s,ux/Vars.tilesize,-uy/Vars.tilesize,100);
            mod.freeLook.align(0,0,-1);mod.freeLook.look(90,55);mod.updateCamera();
            check(mod.camera.direction.x>.5&&mod.camera.direction.y>.5,"independent look turned east/up");
            double z=s.z;mod.navigateStabilized(.1,1,0,0);
            check(Math.abs(s.z-z)<1e-8&&s.x>ux/Vars.tilesize&&s.y==100,"W follows east-facing pilot without climbing with camera");
            mod.motion.reset();mod.motion.syncFromSimulation(unit,s);
            mod.navigateStabilized(.1,0,1,0);
            double desiredX=s.x*Vars.tilesize;
            check(mod.motion.beforeSimulation(unit,s,6),"stabilized move queues on native bridge");
            unit.move(unit.vel.x*6,unit.vel.y*6);unit.vel.scl(Math.max(0,1-unit.drag*6));
            mod.motion.afterSimulation(unit,s);
            check(Math.abs(unit.x-desiredX)<.001,"native XY receives independent flight displacement once");
            fixture(s,ux/Vars.tilesize,-uy/Vars.tilesize,RuntimeConfig.MAX_HEIGHT+.5);
            s.vy=2;mod.collide(s.x,RuntimeConfig.MAX_HEIGHT-.1,s.z,.01,false);
            check(RuntimeConfig.MAX_HEIGHT>=45*16&&s.y==RuntimeConfig.MAX_HEIGHT&&s.vy==0,"16x ceiling clamps safely");
            mod.updateCamera();check(mod.camera.far>s.y,"far plane includes ground from ceiling");
            fixture(s,ux/Vars.tilesize,-uy/Vars.tilesize,100);mod.freeLook.align(0,0,-1);
            Core.settings.put("fpv-camera-view",2);for(int i=0;i<120;i++)mod.updateCamera(1/60.0);
            check(mod.renderer3d.showControlledUnit()&&mod.camera.position.dst((float)s.x,(float)s.y,(float)s.z)>3,
                "third person moves actual camera and enables possessed model");
            mod.updatePick();
            var aim=mod.controlledAim();
            check(Math.abs(aim.z())<.001&&aim.y()>.99,"observer does not change pilot weapon ray");
            for(float value:mod.camera.combined.val)check(Float.isFinite(value),"finite chase projection");
            Core.settings.put("fpv-camera-view",1);for(int i=0;i<120;i++)mod.updateCamera(1/60.0);mod.updatePick();
            check(mod.camera.direction.z>.95&&mod.camera.position.z<s.z,"front camera is ahead looking toward unit");
            check(mod.controlledAim().y()>.99,"front observer does not reverse guns");
            for(float value:mod.camera.combined.val)check(Float.isFinite(value),"finite rear projection");
            fpv.ui.CameraView.cycle();check(fpv.ui.CameraView.selected()==2,"front view cycles to rear");
            fpv.ui.CameraView.cycle();check(fpv.ui.CameraView.selected()==0,"rear view cycles back to FPV");
            Core.settings.put("fpv-camera-view",2);
            mod.buildMode=true;mod.updateCamera();
            check(!mod.renderer3d.showControlledUnit()&&mod.camera.position.dst((float)s.x,(float)s.y,(float)s.z)<.001,
                "Build uses original first-person builder camera");
            mod.buildMode=false;for(int i=0;i<120;i++)mod.updateCamera(1/60.0);
            check(fpv.ui.CameraView.selected()==2&&mod.renderer3d.showControlledUnit(),"Build exit smoothly restores selected view");
            Core.settings.put("fpv-flight-mode",0);mod.syncFlightMode();check(mod.flightMode==0,"Acro restores without reset");
            return "passed: "+checks+" assertions; pilot-relative WASD, pitch-independent altitude, native motion bridge,720tile ceiling, first/chase/rear cameras and Build view restoration";
        }finally{
            for(int i=0;i<keys.length;i++){if(had[i])Core.settings.put(keys[i],old[i]);else Core.settings.remove(keys[i]);}
            mod.buildMode=build;mod.flightMode=mode;mod.throttle=throttle;
            unit.set(ux,uy);unit.vel.set(vx,vy);mod.motion.reset();mod.motion.syncFromSimulation(unit,s);
            s.x=saved[0];s.y=saved[1];s.z=saved[2];s.vx=saved[3];s.vy=saved[4];s.vz=saved[5];
            s.qx=saved[6];s.qy=saved[7];s.qz=saved[8];s.qw=saved[9];s.wx=saved[10];s.wy=saved[11];s.wz=saved[12];
            mod.flightSettings.configure(mod.flight,unit);mod.updateCamera();
        }
    }
    private static void fixture(FlightController.State s,double x,double z,double h){
        s.x=x;s.y=h;s.z=z;s.vx=s.vy=s.vz=s.wx=s.wy=s.wz=0;s.qx=s.qy=s.qz=0;s.qw=1;
    }
    private void check(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Flight controls smoke: "+label);}
}
