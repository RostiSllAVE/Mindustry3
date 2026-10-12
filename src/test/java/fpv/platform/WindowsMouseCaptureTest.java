package fpv.platform;

/** Shared JNA/FFM lifecycle tests. Fake desktop deliberately avoids moving a user's cursor. */
public final class WindowsMouseCaptureTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Desktop implements WindowsMouseCapture.Api {
        long window=10,pid=ProcessHandle.current().pid();
        int x=150,y=150,left=100,top=100,width=400,height=300,warps;
        boolean iconic,valid=true,clipFail,closed;
        final WindowsMouseCapture.Rect clipping=new WindowsMouseCapture.Rect();
        Desktop(){clipping.right=1920;clipping.bottom=1080;}
        public long GetForegroundWindow(){return window;}
        public long processId(long w){return pid;}
        public boolean IsWindow(long w){return valid;}
        public boolean IsIconic(long w){return iconic;}
        public boolean GetClientRect(long w,WindowsMouseCapture.Rect r){r.left=r.top=0;r.right=width;r.bottom=height;return true;}
        public boolean ClientToScreen(long w,WindowsMouseCapture.Point p){p.x+=left;p.y+=top;return true;}
        public boolean GetCursorPos(WindowsMouseCapture.Point p){p.x=x;p.y=y;return true;}
        public boolean SetCursorPos(int a,int b){warps++;x=a;y=b;return true;}
        public boolean ClipCursor(WindowsMouseCapture.Rect r){if(clipFail)return false;if(r==null){clipping.left=clipping.top=0;clipping.right=1920;clipping.bottom=1080;}else{clipping.left=r.left;clipping.top=r.top;clipping.right=r.right;clipping.bottom=r.bottom;}return true;}
        public boolean GetClipCursor(WindowsMouseCapture.Rect r){r.left=clipping.left;r.top=clipping.top;r.right=clipping.right;r.bottom=clipping.bottom;return true;}
        public int lastError(){return 5;}
        public void close(){closed=true;}
    }
    public static void main(String[] args){
        Desktop d=new Desktop();WindowsMouseCapture m=new WindowsMouseCapture(d);
        check(m.focused()&&!m.captured(),"focus query doesn't arm");
        m.setCaptured(true);check(m.captured()&&d.x==300&&d.y==250&&d.clipping.left==100,"own client capture recenters and clips");
        check(m.deltaX()==0&&m.deltaY()==0,"entry has no stale motion");
        d.x+=25;d.y-=15;m.poll();check(m.deltaX()==25&&m.deltaY()==-15&&d.x==300,"actual right/down sampling before recenter");
        check(m.deltaX()==25,"reads do not consume sample");
        for(int i=0;i<200;i++){d.x+=80;m.poll();check(m.deltaX()==80&&m.captured(),"continuous motion without edge saturation");}
        int warps=d.warps;m.setCaptured(true);check(d.warps==warps,"idempotent capture doesn't swallow delta");
        d.width=600;m.poll();check(m.deltaX()==0&&d.x==400&&d.clipping.right==700,"resize fences old sample and updates clip");
        d.window=20;d.pid=999;m.poll();check(!m.captured()&&d.clipping.left==0&&m.deltaX()==0,"Alt+Tab releases owned clip and motion");
        warps=d.warps;m.setCaptured(true);check(!m.captured()&&d.warps==warps,"foreign process can't be captured");
        d.window=10;d.pid=ProcessHandle.current().pid();d.x=150;d.y=150;m.setCaptured(true);check(m.captured()&&m.deltaX()==0,"fresh focus reacquires cleanly");
        m.pause();check(!m.captured()&&d.clipping.left==0,"lifecycle pause releases");m.resume();check(!m.captured(),"resume awaits flight arm");
        m.setCaptured(true);m.setCaptured(false);check(!m.captured()&&m.deltaY()==0,"Build/menu release clears sample");
        d.x=50;m.setCaptured(true);check(!m.captured(),"pointer outside client never warped");
        d.x=150;m.setCaptured(true);d.clipping.left=7;d.clipping.right=800;m.poll();check(!m.captured()&&d.clipping.left==7,"another owner's clip preserved");
        d.iconic=true;check(!m.focused(),"minimized game can't capture");d.iconic=false;
        d.clipFail=true;m.setCaptured(true);check(!m.captured()&&m.failure()!=null,"native error reported for optional fallback");
        m.close();m.close();check(d.closed&&!m.focused()&&m.deltaX()==0,"idempotent disposal closes native arena");
        System.out.println("WindowsMouseCaptureTest: "+checks+" lifecycle checks PASS");
    }
}
