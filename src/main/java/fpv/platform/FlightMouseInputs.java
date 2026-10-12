package fpv.platform;

import arc.Core;
import arc.Input;
import arc.backend.sdl.SdlApplication;
import arc.backend.sdl.jni.SDL;
import arc.util.Log;

/** SDL is already part of the running host; its focus query does not load JNA. */
public final class FlightMouseInputs {
    private FlightMouseInputs(){}
    public static FlightMouseInput create(Input input){
        boolean logging=OptionalFlightInput.loggingAvailable();
        boolean windows=System.getProperty("os.name","").startsWith("Windows");
        boolean forced=Boolean.getBoolean("fpv.input.forceArc");
        String reason=forced?"host input explicitly selected":!windows?"Win32 input is unavailable on this platform":"java.logging is absent from this runtime";
        return new OptionalFlightInput(windows&&logging&&!forced,reason,WindowsFlightInput::new,
            ()->new ArcFlightInput(input,FlightMouseInputs::hostFocused,()->Core.graphics.getWidth(),()->Core.graphics.getHeight()),
            message->Log.warn("[FPV] Optional native mouse input unavailable (@). Host input remains active: visible cursor, bounded mouse movement; arrow keys continue aiming.",message));
    }
    private static boolean hostFocused(){
        if(!(Core.app instanceof SdlApplication app))return true; // lifecycle pause/resume still fences other backends
        int flags=SDL.SDL_GetWindowFlags(app.getWindow());
        return (flags&SDL.SDL_WINDOW_INPUT_FOCUS)!=0&&(flags&SDL.SDL_WINDOW_MINIMIZED)==0;
    }
}
