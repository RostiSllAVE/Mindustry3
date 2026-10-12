package fpv.platform;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native failures are isolated from renderer/gameplay initialization and never retried each frame. */
public final class OptionalFlightInput implements FlightMouseInput {
    private FlightMouseInput delegate;
    private final Supplier<FlightMouseInput> fallback;
    private final Consumer<String> warning;
    private String reason;
    private boolean closed;
    public OptionalFlightInput(boolean nativeEligible,String unavailable,Supplier<FlightMouseInput> nativeFactory,
                               Supplier<FlightMouseInput> fallback,Consumer<String> warning){
        this.fallback=fallback;this.warning=warning;
        if(nativeEligible){try{delegate=nativeFactory.get();}catch(RuntimeException|LinkageError failure){switchToFallback(describe(failure));}}
        else switchToFallback(unavailable);
    }
    public static boolean loggingAvailable(){
        try{Class.forName("java.util.logging.Logger",false,null);return true;}
        catch(ClassNotFoundException|LinkageError unavailable){return false;}
    }
    private static String describe(Throwable t){return t.getClass().getSimpleName()+": "+t.getMessage();}
    private void switchToFallback(String message){
        if(delegate!=null){try{delegate.close();}catch(RuntimeException|LinkageError ignored){}}
        reason=message;delegate=fallback.get();
        warning.accept(message);
    }
    private void recover(){if(!closed&&delegate.nativeCapture()&&delegate.failure()!=null)switchToFallback(delegate.failure());}
    public boolean focused(){
        if(closed)return false;
        try{boolean focused=delegate.focused();recover();return delegate.nativeCapture()?focused:delegate.focused();}
        catch(RuntimeException|LinkageError failure){if(!delegate.nativeCapture())throw failure;switchToFallback(describe(failure));return delegate.focused();}
    }
    public void setCaptured(boolean value){
        if(closed)return;
        try{delegate.setCaptured(value);recover();}
        catch(RuntimeException|LinkageError failure){if(!delegate.nativeCapture())throw failure;switchToFallback(describe(failure));}
        // Failover deliberately leaves the pointer unarmed until the next mode/focus update.
    }
    public void poll(){
        if(closed)return;
        try{delegate.poll();recover();}
        catch(RuntimeException|LinkageError failure){if(!delegate.nativeCapture())throw failure;switchToFallback(describe(failure));}
    }
    public float deltaX(){return closed?0:delegate.deltaX();}
    public float deltaY(){return closed?0:delegate.deltaY();}
    public boolean captured(){return !closed&&delegate.captured();}
    public boolean nativeCapture(){return delegate.nativeCapture();}
    public String failure(){return reason;}
    public void pause(){delegate.pause();}
    public void resume(){delegate.resume();}
    public void close(){if(!closed){closed=true;delegate.close();}}
}
