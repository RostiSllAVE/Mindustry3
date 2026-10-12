package fpv;

/** Native-only measurement entry: no FPV controller, renderer or simulation adapters installed. */
public final class CameraProfileProbeMod extends mindustry.mod.Mod {
    @Override public void init(){
        if(!Boolean.getBoolean("fpv.cameraProfile"))throw new IllegalStateException("Explicit native-only lab required");
        arc.Events.on(mindustry.game.EventType.ClientLoadEvent.class,e->arc.Core.app.post(()->new CameraPerformanceHarness(null).start()));
    }
}
