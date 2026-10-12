package fpv.platform;

import java.util.ArrayList;

public final class OptionalFlightInputTest {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static final class Mouse implements FlightMouseInput {
        boolean nativeInput=true,focused=true,captured,closed,paused,fail;
        int polls;
        public boolean focused(){return focused&&!paused&&!closed;}
        public void setCaptured(boolean value){captured=value;}
        public void poll(){polls++;if(fail)throw new NoClassDefFoundError("optional native library");}
        public float deltaX(){return captured?3:0;}
        public float deltaY(){return captured?4:0;}
        public boolean captured(){return captured;}
        public String failure(){return null;}
        public boolean nativeCapture(){return nativeInput;}
        public void pause(){paused=true;captured=false;}
        public void resume(){paused=false;captured=false;}
        public void close(){closed=true;captured=false;}
    }
    public static void main(String[] args){
        var warnings=new ArrayList<String>();int[] attempted={0};
        var host=new Mouse();host.nativeInput=false;
        var missing=new OptionalFlightInput(false,"java.logging absent",()->{attempted[0]++;throw new AssertionError("native initialized without dependencies");},()->host,warnings::add);
        check(attempted[0]==0&&!missing.nativeCapture(),"unavailable modules never initialize JNA");
        check(missing.focused()&&warnings.size()==1,"fallback focus is usable and warns once");
        missing.setCaptured(true);missing.poll();check(missing.deltaX()==3&&missing.deltaY()==4,"fallback supplies actual motion");
        missing.pause();check(!missing.focused()&&!missing.captured(),"pause releases fallback");
        missing.resume();check(missing.focused()&&!missing.captured(),"resume requires fresh arm");
        missing.close();check(!missing.focused()&&missing.deltaX()==0&&host.closed,"disposal releases fallback");
        var broken=new OptionalFlightInput(true,"",()->{throw new NoClassDefFoundError("java/util/logging/Logger");},()->{Mouse m=new Mouse();m.nativeInput=false;return m;},warnings::add);
        // Use a fallback whose identity reflects bounded host input.
        check(broken.failure().contains("Logger"),"linkage error becomes diagnostic, not initialization failure");
        Mouse nativeMouse=new Mouse();Mouse backup=new Mouse();backup.nativeInput=false;
        var available=new OptionalFlightInput(true,"",()->nativeMouse,()->backup,warnings::add);
        available.setCaptured(true);available.poll();check(available.nativeCapture()&&available.captured()&&nativeMouse.polls==1,"full runtime retains native capture and sampling");
        nativeMouse.focused=false;check(!available.focused()&&available.nativeCapture(),"normal focus loss is not backend failure");
        nativeMouse.focused=true;nativeMouse.fail=true;available.poll();check(!available.nativeCapture()&&nativeMouse.closed,"runtime linkage failure switches to host input");
        check(!available.captured()&&available.deltaX()==0,"failover cannot inject stale camera motion");
        available.setCaptured(true);available.poll();check(backup.polls==1&&available.deltaX()==3,"host works after native failure");
        check(warnings.size()==3,"no per-frame native retry or repeated warning");
        available.close();check(backup.closed,"active fallback cleanup works");
        boolean logging=OptionalFlightInput.loggingAvailable();
        if(Boolean.getBoolean("fpv.test.expectNoLogging"))check(!logging,"actual stripped runtime has no Logger");
        else check(logging,"full test runtime has Logger");
        System.out.println("OptionalFlightInputTest: "+checks+" checks passed; loggingAvailable="+logging);
    }
}
