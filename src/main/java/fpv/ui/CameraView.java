package fpv.ui;

import arc.Core;

/** Persisted FIRST -> FRONT -> REAR order, preserving old observer preferences on upgrade. */
public final class CameraView {
    public static final int FIRST=0, FRONT=1, REAR=2, CHASE=REAR;
    public static final String SMOOTHING="fpv-camera-smoothing",MODIFIER="fpv-camera-smoothing-modifier";
    public static final arc.input.KeyBind smoothingKey=arc.input.KeyBind.add("fpv-camera-smoothing-toggle",arc.input.KeyCode.f7,"FPV camera");
    private static long feedbackUntil;
    private CameraView(){}
    public static int selected(){
        if(!Core.settings.getBool("fpv-camera-layout-v2",false)){
            int old=Core.settings.getInt("fpv-camera-view",Core.settings.getBool("fpv-third-person",false)?1:0);
            Core.settings.put("fpv-camera-view",old==1?REAR:old==2?FRONT:FIRST);
            Core.settings.put("fpv-camera-layout-v2",true);
        }
        return Math.max(FIRST,Math.min(REAR,Core.settings.getInt("fpv-camera-view",FIRST)));
    }
    public static void cycle(){Core.settings.put("fpv-camera-view",(selected()+1)%3);}
    public static boolean smoothing(){return Core.settings.getBool(SMOOTHING,false);}
    public static void toggleSmoothing(){Core.settings.put(SMOOTHING,!smoothing());feedbackUntil=arc.util.Time.millis()+2000;}
    public static String feedback(){return arc.util.Time.millis()<feedbackUntil?"CAMERA SMOOTHING "+(smoothing()?"ON":"OFF"):"";}
    public static String modifierLabel(int modifier){return switch(modifier){case 0->"None (F7 alone stays reserved)";case 2->"Ctrl";case 3->"Shift";default->"Alt";};}
    public static boolean handleShortcut(arc.Input input){
        int modifier=Math.max(0,Math.min(3,Core.settings.getInt(MODIFIER,1)));
        boolean matches=switch(modifier){case 0->!input.alt()&&!input.ctrl()&&!input.shift();case 2->input.ctrl();case 3->input.shift();default->input.alt();};
        if(modifier==0&&smoothingKey.value.key==arc.input.KeyCode.f7||!matches||!input.keyTap(smoothingKey))return false;
        toggleSmoothing();return true;
    }
    public static String label(int view){return view==FRONT?"Front view":view==REAR?"Rear follow":"FPV";}
    public static final String DISTANCE="fpv-camera-distance";
    public static int distance(){return Math.max(25,Math.min(400,Core.settings.getInt(DISTANCE,100)));}
    public static void zoom(float wheel){if(Float.isFinite(wheel)&&wheel!=0)Core.settings.put(DISTANCE,Math.max(25,Math.min(400,distance()-Math.round(wheel*10))));}
    public static boolean zoomAllowed(boolean build,boolean suspended,boolean commands,boolean fence,int view,boolean alt){
        return !build&&!suspended&&!commands&&!fence&&view!=FIRST&&alt;
    }
}
