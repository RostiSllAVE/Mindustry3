package fpv.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Locale;

/**
 * Cursor-relative input for this game's own foreground Windows window.
 *
 * <p>Call from the game input/render thread only. Configure jna.tmpdir on D:
 * before constructing this class. Cursor visibility is managed by the Arc
 * integration layer; this class never changes ShowCursor counters, activates
 * windows, installs global hooks, or sends input to another application.</p>
 *
 * <p>This is cursor recentering, not raw mouse input. Pointer acceleration and
 * screen-coordinate DPI behavior remain relevant to sensitivity.</p>
 */
public final class WindowsFlightInput implements AutoCloseable {
    /** JNA core definitions, deliberately independent of jna-platform and AWT. */
    public interface User32 extends StdCallLibrary {
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
        boolean IsWindow(Pointer window);
        boolean IsIconic(Pointer window);
        boolean GetClientRect(Pointer window, Rect rectangle);
        boolean ClientToScreen(Pointer window, Point point);
        boolean GetCursorPos(Point point);
        boolean SetCursorPos(int x, int y);
        boolean ClipCursor(Rect rectangle);
        boolean GetClipCursor(Rect rectangle);
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static final class Rect extends Structure {
        public int left, top, right, bottom;

        private boolean same(Rect other) {
            return left == other.left && top == other.top
                    && right == other.right && bottom == other.bottom;
        }

        private boolean contains(Point point) {
            return point.x >= left && point.x < right && point.y >= top && point.y < bottom;
        }

        private void copyFrom(Rect other) {
            left = other.left;
            top = other.top;
            right = other.right;
            bottom = other.bottom;
        }
    }

    @Structure.FieldOrder({"x", "y"})
    public static final class Point extends Structure {
        public int x, y;
    }

    private final User32 api;
    private final long ownProcessId;
    private final IntByReference processId = new IntByReference();
    private final Rect bounds = new Rect();
    private final Rect appliedClip = new Rect();
    private final Rect currentClip = new Rect();
    private final Point topLeft = new Point();
    private final Point bottomRight = new Point();
    private final Point cursor = new Point();
    private Pointer capturedWindow;
    private int centerX, centerY;
    private boolean captured, ownsClip, closed;
    private float sampledX, sampledY;
    private String failure;

    public WindowsFlightInput() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new UnsupportedOperationException("WindowsFlightInput requires Windows");
        }
        ownProcessId = ProcessHandle.current().pid();
        api = Native.load("user32", User32.class);
    }

    /** True only for a valid, non-minimized foreground window owned by this JVM. */
    public boolean focused() {
        if (closed) return false;
        try {
            if (!captured && ownsClip) release();
            Pointer foreground = ownForeground();
            if (captured && !sameWindow(foreground, capturedWindow)) release();
            return foreground != null;
        } catch (RuntimeException | LinkageError ex) {
            fail(ex);
            return false;
        }
    }

    /**
     * Capture requires a fresh own-foreground check and cursor inside its client
     * area. Focus loss clears capture; it is never automatically reacquired.
     */
    public void setCaptured(boolean value) {
        if (!value) {
            release();
            return;
        }
        if (closed) return;
        try {
            if (!captured && ownsClip) {
                release();
                if (ownsClip) return;
            }
            Pointer foreground = ownForeground();
            if (captured) {
                if (!sameWindow(foreground, capturedWindow)) release();
                return;
            }
            if (foreground == null) return;
            failure = null;
            readBounds(foreground);
            require(api.GetCursorPos(cursor), "GetCursorPos");
            // Do not warp a cursor currently over another application's client
            // area, even if this game still owns keyboard foreground focus.
            if (!bounds.contains(cursor)) return;
            capturedWindow = foreground;
            if (!stillOwnForeground()) {
                release();
                return;
            }
            require(api.ClipCursor(bounds), "ClipCursor");
            appliedClip.copyFrom(bounds);
            ownsClip = true;
            centerX = midpoint(bounds.left, bounds.right);
            centerY = midpoint(bounds.top, bounds.bottom);
            if (!recenter()) {
                release();
                return;
            }
            sampledX = sampledY = 0.0f;
            captured = true;
        } catch (RuntimeException | LinkageError ex) {
            fail(ex);
        }
    }

    /** Sample once per frame. Reading delta accessors does not consume the sample. */
    public void poll() {
        sampledX = sampledY = 0.0f;
        if (closed || !captured) {
            if (ownsClip) release();
            return;
        }
        try {
            if (!stillOwnForeground()) {
                release();
                return;
            }
            require(api.GetClipCursor(currentClip), "GetClipCursor");
            if (!currentClip.same(appliedClip)) {
                // Another owner/platform component has replaced the clipping.
                // Its rectangle must not be overwritten, including on cleanup.
                ownsClip = false;
                release();
                return;
            }
            readBounds(capturedWindow);
            require(api.GetCursorPos(cursor), "GetCursorPos");
            if (!bounds.contains(cursor)) {
                release();
                return;
            }
            boolean movedOrResized = !bounds.same(appliedClip);
            float nextX = movedOrResized ? 0.0f : (float)((long)cursor.x - centerX);
            float nextY = movedOrResized ? 0.0f : (float)((long)cursor.y - centerY);
            if (movedOrResized) {
                if (!stillOwnForeground()) {
                    release();
                    return;
                }
                require(api.ClipCursor(bounds), "ClipCursor resize");
                appliedClip.copyFrom(bounds);
                centerX = midpoint(bounds.left, bounds.right);
                centerY = midpoint(bounds.top, bounds.bottom);
            }
            if (!recenter()) {
                release();
                return;
            }
            sampledX = nextX;
            sampledY = nextY;
        } catch (RuntimeException | LinkageError ex) {
            fail(ex);
        }
    }

    public float deltaX() {
        return sampledX;
    }

    /** Positive down, matching Win32 screen coordinates. */
    public float deltaY() {
        return sampledY;
    }

    /** Allows the integration layer to restore visibility when capture is lost. */
    public boolean captured() {
        return captured;
    }

    /** Last native failure, or null; normal focus loss is not an error. */
    public String failure() {
        return failure;
    }

    @Override
    public void close() {
        closed = true;
        release();
    }

    private Pointer ownForeground() {
        Pointer foreground = api.GetForegroundWindow();
        if (foreground == null || Pointer.nativeValue(foreground) == 0L) return null;
        if (!api.IsWindow(foreground) || api.IsIconic(foreground)) return null;
        if (api.GetWindowThreadProcessId(foreground, processId) == 0) return null;
        return Integer.toUnsignedLong(processId.getValue()) == ownProcessId ? foreground : null;
    }

    private boolean stillOwnForeground() {
        return sameWindow(ownForeground(), capturedWindow);
    }

    private void readBounds(Pointer window) {
        require(api.GetClientRect(window, bounds), "GetClientRect");
        topLeft.x = bounds.left;
        topLeft.y = bounds.top;
        bottomRight.x = bounds.right;
        bottomRight.y = bounds.bottom;
        require(api.ClientToScreen(window, topLeft), "ClientToScreen top-left");
        require(api.ClientToScreen(window, bottomRight), "ClientToScreen bottom-right");
        bounds.left = topLeft.x;
        bounds.top = topLeft.y;
        bounds.right = bottomRight.x;
        bounds.bottom = bottomRight.y;
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            throw new IllegalStateException("Game client rectangle has no positive extent");
        }
    }

    private boolean recenter() {
        // Windows has no atomic foreground-owner check plus cursor mutation.
        // Recheck immediately before moving, and never activate/steal focus.
        if (!stillOwnForeground()) return false;
        require(api.GetCursorPos(cursor), "GetCursorPos before recenter");
        if (!bounds.contains(cursor)) return false;
        if (!stillOwnForeground()) return false;
        require(api.SetCursorPos(centerX, centerY), "SetCursorPos");
        return true;
    }

    private void release() {
        captured = false;
        capturedWindow = null;
        sampledX = sampledY = 0.0f;
        if (!ownsClip) return;
        try {
            // ClipCursor is a shared desktop resource. Only undo our last clip;
            // do not clear a rectangle installed by the newly focused app.
            if (!api.GetClipCursor(currentClip)) {
                failure = "GetClipCursor during release failed: " + Native.getLastError();
                return;
            }
            if (!currentClip.same(appliedClip)) {
                ownsClip = false;
                return;
            }
            if (api.ClipCursor(null)) {
                ownsClip = false;
            } else {
                failure = "ClipCursor release failed: " + Native.getLastError();
            }
        } catch (RuntimeException | LinkageError ex) {
            failure = "Cursor release failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private void fail(Throwable ex) {
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        release();
        failure = message;
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + " failed: " + Native.getLastError());
    }

    private static boolean sameWindow(Pointer a, Pointer b) {
        return a != null && b != null && Pointer.nativeValue(a) == Pointer.nativeValue(b);
    }

    private static int midpoint(int min, int max) {
        return (int)((long)min + ((long)max - min) / 2L);
    }
}
