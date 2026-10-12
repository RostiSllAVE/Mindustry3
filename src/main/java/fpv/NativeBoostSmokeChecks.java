package fpv;

import arc.Core;
import arc.files.Fi;
import arc.util.ScreenUtils;
import fpv.net.NetworkControls;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;

/** Real SDL frames through production pilot, camera, collision and cached visual poses. */
final class NativeBoostSmokeChecks implements AutoCloseable {
    private final FpvMod mod;private final Unit original;private Unit unit;
    private int typeIndex,phase,ticks,checks;private double startZ,phaseSeconds;private float health;
    private final int view,angle;private final boolean build;
    private final mindustry.type.UnitType[] types={UnitTypes.vela,UnitTypes.nova,UnitTypes.pulsar,UnitTypes.quasar};
    NativeBoostSmokeChecks(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated SDL lab required");
        this.mod=mod;original=Vars.player.unit();view=fpv.ui.CameraView.selected();angle=Core.settings.getInt("fpv-pov-angle",90);build=mod.buildMode;
        mod.setBuildMode(false);Core.settings.put("fpv-pov-angle",90);spawn();
    }
    private void spawn(){
        unit=types[typeIndex].create(original.team);unit.set(80*Vars.tilesize,80*Vars.tilesize);unit.rotation=90;unit.add();Vars.player.unit(unit);
        Core.settings.put("fpv-camera-view",2);mod.resetPose();mod.smokeBoostControls=control(false,3,0,0,0);ticks=phase=0;phaseSeconds=0;health=unit.health;
    }
    boolean update(){
        ticks++;phaseSeconds+=Vars.state.isPaused()?0:Math.min(.1,Math.max(0,arc.util.Time.delta/60d));var boost=mod.network.nativeBoost();
        if(ticks>1800)throw new AssertionError("Native boost SDL timeout "+unit.type.name+" "+phase+" "+boost.describeForSmoke(unit));
        if(phase==0&&ticks>=45){
            startZ=mod.world3d.unitState(unit).z();check(!unit.isFlying()&&unit.canShoot(),"native ground mode");
            capture("ground");mod.smokeBoostControls=control(true,1,.5,0,0);next(1);
        }else if(phase==1&&ticks>=90&&phaseSeconds>=.5){
            check(boost.active(unit)&&boost.state(unit).z()>startZ+.5,"native continuous XYZ takeoff");
            check(!unit.canShoot(),"native boost canShoot restriction");capture("boost-rear");
            if(unit.type==UnitTypes.vela)check(mod.renderer3d.groundFamily().velaFold(unit)>.99,"three Vela legs fully folded on real frames");
            long meshes=mod.renderer3d.groundFamily().nativeLimbStats().builds();
            for(int mode:new int[]{0,1,2}){
                Core.settings.put("fpv-camera-view",mode);mod.updateCamera(1d/60);mod.renderer3d.render(mod.camera);
                check(mod.renderer3d.firstPersonActive()==(mode==0),"boost F7 view "+mode);capture("boost-view-"+mode);
            }
            check(mod.renderer3d.groundFamily().nativeLimbStats().builds()==meshes,"boost view switching reuses meshes");
            mod.smokeBoostControls=control(true,0,0,.04,-.025);next(2);
        }else if(phase==2&&ticks>=20){
            var body=boost.state(unit);check(Math.abs(body.pitch())+Math.abs(body.roll())>.1,"real acro pitch/roll quaternion");
            mod.smokeBoostControls=control(false,1,0,0,0);next(3);
        }else if(phase==3){
            if(ticks==2)check(boost.active(unit)&&boost.landing(unit)&&boost.state(unit).z()>startZ+.25,"Ground requests descent without teleport");
            if(boost.active(unit))return false;
            check(!unit.dead&&!unit.isFlying()&&unit.canShoot(),"physical landing restores native ground firing");next(4);
        }else if(phase==4&&ticks>=45&&phaseSeconds>=.5){
            capture("landed");check(unit.health==health,"mode toggle/descent causes no spurious damage");
            if(unit.type==UnitTypes.vela)check(mod.renderer3d.groundFamily().velaFold(unit)<.01,"Vela tripod redeploys after touchdown");
            unit.remove();mod.network.nativeBoost().after();typeIndex++;
            if(typeIndex==types.length){close();return true;}spawn();
        }
        return false;
    }
    private NetworkControls control(boolean boosted,int mode,double climb,double pitch,double roll){return new NetworkControls(true,mode,mod.flight.profile().hoverThrottle(),pitch,roll,0,0,0,climb,0,0,0,1,0,true,false,boosted);}
    private void next(int value){phase=value;ticks=0;phaseSeconds=0;}
    private void capture(String name){mod.updateCamera(1d/60);mod.renderer3d.render(mod.camera);ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(unit.type.name+"-native-"+name+".png"));}
    private void check(boolean value,String label){checks++;if(!value)throw new AssertionError(unit.type.name+": "+label);}
    String result(){return "PASS: "+checks+" real SDL Vela/Nova/Pulsar/Quasar grounded/XYZ takeoff/acro pitch-roll/native firing/physical descent/F7/three-leg fold-deploy/cached mesh checks";}
    public void close(){mod.smokeBoostControls=null;if(unit!=null&&unit.isAdded())unit.remove();mod.network.nativeBoost().after();Vars.player.unit(original);Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.resetPose();mod.setBuildMode(build);}
}
