package fpv.platform;

import arc.Input;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Uses the host's real pointer/focus state, with visible cursor and keyboard look at edges. */
public final class ArcFlightInput implements FlightMouseInput {
    private final Input input;
    private final BooleanSupplier focus;
    private final IntSupplier width,height;
    private final ArcMouseMotion motion=new ArcMouseMotion();
    private boolean closed,paused;
    public ArcFlightInput(Input input,BooleanSupplier focus,IntSupplier width,IntSupplier height){
        this.input=input;this.focus=focus;this.width=width;this.height=height;
    }
    public boolean focused(){return !closed&&!paused&&focus.getAsBoolean();}
    public void setCaptured(boolean value){motion.capture(value&&focused());}
    public void poll(){motion.sample(input.mouseX(),input.mouseY(),width.getAsInt(),height.getAsInt(),focused());}
    public float deltaX(){return motion.deltaX();}
    public float deltaY(){return motion.deltaY();}
    public boolean captured(){return motion.captured();}
    public String failure(){return null;}
    public boolean nativeCapture(){return false;}
    public void pause(){paused=true;motion.capture(false);}
    public void resume(){paused=false;motion.capture(false);}
    public void close(){closed=true;motion.capture(false);}
}
