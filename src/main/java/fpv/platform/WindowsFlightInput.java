package fpv.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Existing complete-Java Windows backend; isolated from the reduced Steam runtime. */
public final class WindowsFlightInput extends WindowsMouseCapture {
    public WindowsFlightInput(){super(new JnaApi());}
    public interface User32 extends StdCallLibrary {
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
        boolean IsWindow(Pointer window);
        boolean IsIconic(Pointer window);
        boolean GetClientRect(Pointer window, JnaRect rectangle);
        boolean ClientToScreen(Pointer window, JnaPoint point);
        boolean GetCursorPos(JnaPoint point);
        boolean SetCursorPos(int x, int y);
        boolean ClipCursor(JnaRect rectangle);
        boolean GetClipCursor(JnaRect rectangle);
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static final class JnaRect extends Structure {
        public int left, top, right, bottom;

        private boolean same(JnaRect other) {
            return left == other.left && top == other.top
                    && right == other.right && bottom == other.bottom;
        }

        private boolean contains(JnaPoint point) {
            return point.x >= left && point.x < right && point.y >= top && point.y < bottom;
        }

        private void copyFrom(JnaRect other) {
            left = other.left;
            top = other.top;
            right = other.right;
            bottom = other.bottom;
        }
    }

    @Structure.FieldOrder({"x", "y"})
    public static final class JnaPoint extends Structure {
        public int x, y;
    }


    private static final class JnaApi implements Api {
        final User32 api=Native.load("user32",User32.class);
        final IntByReference pid=new IntByReference();
        final JnaRect rect=new JnaRect();
        final JnaPoint point=new JnaPoint();
        private Pointer window(long value){return new Pointer(value);}
        public long GetForegroundWindow(){return Pointer.nativeValue(api.GetForegroundWindow());}
        public long processId(long w){return api.GetWindowThreadProcessId(window(w),pid)==0?0:Integer.toUnsignedLong(pid.getValue());}
        public boolean IsWindow(long w){return api.IsWindow(window(w));}
        public boolean IsIconic(long w){return api.IsIconic(window(w));}
        private void from(Rect r){r.left=rect.left;r.top=rect.top;r.right=rect.right;r.bottom=rect.bottom;}
        public boolean GetClientRect(long w,Rect r){boolean ok=api.GetClientRect(window(w),rect);from(r);return ok;}
        public boolean GetClipCursor(Rect r){boolean ok=api.GetClipCursor(rect);from(r);return ok;}
        public boolean ClipCursor(Rect r){if(r==null)return api.ClipCursor(null);rect.left=r.left;rect.top=r.top;rect.right=r.right;rect.bottom=r.bottom;return api.ClipCursor(rect);}
        public boolean ClientToScreen(long w,Point p){point.x=p.x;point.y=p.y;boolean ok=api.ClientToScreen(window(w),point);p.x=point.x;p.y=point.y;return ok;}
        public boolean GetCursorPos(Point p){boolean ok=api.GetCursorPos(point);p.x=point.x;p.y=point.y;return ok;}
        public boolean SetCursorPos(int x,int y){return api.SetCursorPos(x,y);}
        public int lastError(){return Native.getLastError();}
    }
}
