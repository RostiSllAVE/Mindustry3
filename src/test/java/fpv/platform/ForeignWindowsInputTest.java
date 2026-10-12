package fpv.platform;

/** Calls real bindings without capturing or moving the desktop pointer. */
public final class ForeignWindowsInputTest {
    public static void main(String[] args){
        boolean supported=Runtime.version().feature()>=22&&System.getProperty("os.name","").startsWith("Windows")&&!Boolean.getBoolean("fpv.test.denyNative");
        if(supported){
            try(var api=probeApi()){
                var point=new WindowsMouseCapture.Point();var rect=new WindowsMouseCapture.Rect();
                if(!api.GetCursorPos(point)||!api.GetClipCursor(rect)||rect.right<=rect.left)throw new AssertionError("Native POINT/RECT reads failed");
                long window=api.GetForegroundWindow();
                if(window!=0&&(!api.IsWindow(window)||api.processId(window)==0||!api.GetClientRect(window,rect)||!api.ClientToScreen(window,point)))
                    throw new AssertionError("Native HWND/owner/client/coordinate bindings failed");
            }
        }
        try(FlightMouseInput input=new ForeignWindowsInput()){
            if(!supported)throw new AssertionError("Unavailable runtime unexpectedly initialized FFM");
            input.focused();input.poll();input.setCaptured(false);
            if(input.failure()!=null||input.captured()||input.deltaX()!=0||input.deltaY()!=0)throw new AssertionError("Real native queries or idle/release failed: "+input.failure());
            input.pause();input.resume();
            System.out.println("ForeignWindowsInputTest: actual java.base Win32 bindings/query/release PASS; java="+Runtime.version());
        }catch(IllegalStateException unavailable){
            if(supported)throw unavailable;
            System.out.println("ForeignWindowsInputTest: unavailable/restricted API correctly isolated; java="+Runtime.version());
        }
    }
    private static WindowsMouseCapture.Api probeApi(){
        try{
            var constructor=Class.forName("fpv.platform.ForeignWindowsInput$ForeignApi").getDeclaredConstructor();
            constructor.setAccessible(true);return (WindowsMouseCapture.Api)constructor.newInstance();
        }catch(ReflectiveOperationException failure){throw new AssertionError("Native read-only probe failed",failure);}
    }
}
