package fpv;

import arc.ApplicationCore;
import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Time;
import mindustry.Vars;

import java.lang.reflect.Field;
import java.util.function.DoubleSupplier;

/** Version-pinned scope around the ORIGINAL Logic; both clock inputs are restored in finally. */
final class SimulationClock implements ApplicationListener {
    private final ApplicationListener original;
    private final DoubleSupplier scale;
    private final Field backendDelta;
    private float rawSeconds;
    private float rawDelta;
    private boolean inUpdateScope;
    private SimulationClock(ApplicationListener original, DoubleSupplier scale) throws ReflectiveOperationException {
        this.original = original;
        this.scale = scale;
        if (!Core.graphics.getClass().getName().equals("arc.backend.sdl.SdlGraphics")) {
            throw new IllegalStateException("FPV time adapter requires the verified SDL desktop backend");
        }
        backendDelta = Core.graphics.getClass().getDeclaredField("deltaTime");
        backendDelta.setAccessible(true);
    }

    static SimulationClock install(DoubleSupplier scale) throws ReflectiveOperationException {
        Field field = ApplicationCore.class.getDeclaredField("modules");
        field.setAccessible(true);
        for (ApplicationListener listener : Core.app.getListeners()) {
            if (!(listener instanceof ApplicationCore)) continue;
            ApplicationListener[] modules = (ApplicationListener[])field.get(listener);
            for (int i = 0; i < modules.length; i++) {
                if (modules[i] == Vars.logic) {
                    SimulationClock clock = new SimulationClock(modules[i], scale);
                    ApplicationListener[] changed = modules.clone();
                    changed[i] = clock;
                    field.set(listener, changed);
                    // Logic fires Trigger.update before deciding whether to simulate. That callback
                    // can change Build speed or unpause; refresh both clocks after that decision.
                    Events.run(mindustry.game.EventType.Trigger.beforeGameUpdate,clock::refreshSimulationDelta);
                    return clock;
                }
            }
        }
        throw new IllegalStateException("Original Mindustry Logic module was not found");
    }

    float rawSeconds() { return rawSeconds; }

    void refreshSimulationDelta() {
        if(!inUpdateScope)return;
        double requested=scale.getAsDouble();
        if(!Double.isFinite(requested))throw new IllegalStateException("Finite simulation time scale required");
        float factor=(float)Math.max(0,Math.min(1,requested));
        try{backendDelta.setFloat(Core.graphics,rawSeconds*factor);}
        catch(IllegalAccessException e){throw new IllegalStateException("Mindustry FPV clock bridge failed",e);}
        Time.delta=rawDelta*factor;
    }

    @Override public void update() {
        rawSeconds = Core.graphics.getDeltaTime();
        rawDelta = Time.delta;
        inUpdateScope=true;
        try {
            refreshSimulationDelta();
            original.update();
        } finally {
            inUpdateScope=false;
            Time.delta = rawDelta;
            try { backendDelta.setFloat(Core.graphics, rawSeconds); }
            catch (IllegalAccessException e) { throw new IllegalStateException("Could not restore clock", e); }
        }
    }
    @Override public void resize(int w,int h){original.resize(w,h);}
    @Override public void pause(){original.pause();}
    @Override public void resume(){original.resume();}
    @Override public void dispose(){original.dispose();}
    @Override public void exit(){original.exit();}
    @Override public void fileDropped(Fi file){original.fileDropped(file);}
}
