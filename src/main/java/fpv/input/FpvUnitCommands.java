package fpv.input;

import arc.Core;
import arc.Input;
import arc.Events;
import arc.graphics.g2d.*;
import arc.graphics.g3d.Camera3D;
import arc.input.KeyCode;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.Touchable;
import fpv.combat.CombatGeometry;
import fpv.render.FpvWorldRenderer;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.input.Binding;
import mindustry.ui.Fonts;
import arc.util.Align;

/** Screen-space selection adapter; native selectedUnits, CommandAI and validated Call RPCs stay authoritative. */
public final class FpvUnitCommands implements AutoCloseable {
    private final FpvDesktopInput input;
    private final World3DAdapter world;
    private final FpvWorldRenderer renderer;
    private final Camera3D camera;
    private final fpv.construction.LayerRuntime construction;
    private final Vec3 point=new Vec3();
    private final Vec2 stagePoint=new Vec2();
    private final Element overlay=new Element(){
        {touchable=Touchable.disabled;setFillParent(true);name="fpv-unit-commands";}
        public void draw(){super.draw();if(active&&!input.isSuspended()&&!modal())drawOverlay(this);}
    };
    private boolean active,pinned,dragging,pointerFence,ignoreHold,leaveAfterDrag,consumeEscape;
    private float startX,startY,endX,endY;
    private long modeFrame=Long.MIN_VALUE;
    private String status="";
    private mindustry.world.Block savedBlock;
    private int savedRotation;
    public FpvUnitCommands(FpvDesktopInput input,World3DAdapter world,FpvWorldRenderer renderer,Camera3D camera,fpv.construction.LayerRuntime construction){
        this.input=input;this.world=world;this.renderer=renderer;this.camera=camera;this.construction=construction;
    }
    public boolean active(){return active;}
    public boolean pointerFence(){return pointerFence;}
    public boolean consumeEscape(){return consumeEscape;}
    public boolean dragging(){return dragging;}
    public String status(){return status;}
    public void install(){Core.scene.root.addChild(overlay);}
    private static boolean modal(){return Core.scene!=null&&(Core.scene.hasKeyboard()||Core.scene.hasDialog());}
    public void syncMode(){
        long frame=Core.graphics.getFrameId();if(frame==modeFrame)return;modeFrame=frame;Input keys=input.originalInput();
        boolean boostConflict=Vars.player!=null&&!Vars.player.dead()&&Vars.player.unit().type.canBoost
            &&Binding.commandMode.value.key==Binding.boost.value.key;
        modeInput(input.isFpvEnabled()&&Vars.state.isGame()&&Vars.player!=null&&!Vars.player.dead(),input.isBuildMode(),
            modal()||input.isSuspended(),!boostConflict&&keys.keyTap(Binding.commandMode),!boostConflict&&keys.keyDown(Binding.commandMode),keys.keyTap(KeyCode.f10),
            Core.settings.getBool("commandmodehold"),keys.keyDown(Binding.select),keys.keyDown(KeyCode.mouseRight),keys.keyDown(Binding.commandQueue),
            keys.keyTap(Binding.select)||keys.keyTap(KeyCode.mouseRight)||keys.keyTap(Binding.commandQueue));
    }
    /** Production/test boundary: mode exits fence a held mouse until release, without a cooldown. */
    public void modeInput(boolean available,boolean build,boolean suspended,boolean commandTap,boolean commandHeld,
            boolean toggleTap,boolean holdPreference,boolean leftHeld,boolean rightHeld,boolean queueHeld,boolean freshPointer){
        consumeEscape=false;boolean held=leftHeld||rightHeld||queueHeld;
        if(!held)pointerFence=false;if(!commandHeld||commandTap)ignoreHold=false;
        if(!available||suspended){leave(held);return;}
        boolean wanted=pinned;
        if(toggleTap||!build&&!holdPreference&&commandTap){wanted=!active;pinned=wanted;if(!wanted)ignoreHold=true;}
        else if(!pinned&&!build&&holdPreference)wanted=commandHeld&&!ignoreHold;
        if(active&&!wanted&&dragging&&!toggleTap&&!(commandTap&&!holdPreference)){leaveAfterDrag=true;return;}
        if(wanted&&!active){active=true;dragging=false;leaveAfterDrag=false;input.cancelPointerGesture();input.commandMode=true;
            savedBlock=input.block;savedRotation=input.rotation;input.block=null;
            pointerFence=held&&!freshPointer;status="";}
        else if(!wanted&&active)leave(held);
    }
    public void leave(boolean held){if(active){input.block=savedBlock;input.rotation=savedRotation;savedBlock=null;}
        active=pinned=dragging=leaveAfterDrag=false;input.commandMode=false;pointerFence|=held;ignoreHold=true;}
    public void modeTransition(){leave(input.originalInput().keyDown(Binding.select)||input.originalInput().keyDown(KeyCode.mouseRight)||input.originalInput().keyDown(Binding.commandQueue));}
    public record Pointer(float x,float y,boolean leftTap,boolean leftHeld,boolean leftReleased,boolean rightTap,boolean queueTap,boolean escapeTap,boolean ui){}
    public void update(){
        Input keys=input.originalInput();process(new Pointer(keys.mouseX(),keys.mouseY(),keys.keyTap(Binding.select),keys.keyDown(Binding.select),
            keys.keyRelease(Binding.select),keys.keyTap(KeyCode.mouseRight),keys.keyTap(Binding.commandQueue),keys.keyTap(Binding.menu),input.interfaceOwnsInput()));
    }
    public void process(Pointer keys){
        prune();if(!active)return;input.commandMode=true;input.stopCommandFire();
        if(Vars.player!=null&&!Vars.player.dead()){Vars.player.shooting=false;Vars.player.unit().controlWeapons(false,false);}
        if(keys.escapeTap){consumeEscape=true;if(dragging){dragging=false;pointerFence=keys.leftHeld;}else{input.selectedUnits.clear();input.commandBuildings.clear();changed();leave(keys.leftHeld);}return;}
        if(keys.ui||input.isSuspended()){if(dragging){dragging=false;pointerFence=keys.leftHeld;}return;}
        if(pointerFence)return;
        if(keys.rightTap&&dragging){dragging=false;pointerFence=keys.leftHeld;return;}
        if(keys.leftTap){dragging=true;startX=endX=keys.x;startY=endY=keys.y;}
        if(dragging){endX=keys.x;endY=keys.y;if(keys.leftReleased||!keys.leftHeld&&!keys.leftTap){
            if(Math.hypot(endX-startX,endY-startY)<5)selectOne(endX,endY);else selectRectangle(startX,startY,endX,endY);
            dragging=false;if(leaveAfterDrag)leave(false);}}
        if(!dragging&&(keys.rightTap||keys.queueTap))order(keys.x,keys.y,keys.queueTap);
    }
    public static boolean eligible(Unit unit){return Vars.player!=null&&unit!=null&&unit.isValid()&&unit.team==Vars.player.team()
        &&unit.isCommandable()&&unit.allowCommand()&&!unit.inFogTo(Vars.player.team());}
    public void prune(){input.selectedUnits.removeAll(u->!eligible(u));}
    private static void changed(){Events.fire(Trigger.unitCommandChange);}
    public void selectOne(float x,float y){
        Unit unit=pickUnit(x,y);input.commandBuildings.clear();
        if(eligible(unit)){if(!input.selectedUnits.remove(unit))input.selectedUnits.add(unit);}else input.selectedUnits.clear();changed();
    }
    public void selectRectangle(float x1,float y1,float x2,float y2){
        input.selectedUnits.clear();input.commandBuildings.clear();
        float minX=Math.min(x1,x2),maxX=Math.max(x1,x2),minY=Math.min(y1,y2),maxY=Math.max(y1,y2);
        for(Unit unit:Groups.unit)if(eligible(unit)&&projectVisible(unit)&&point.x>=minX&&point.x<=maxX&&point.y>=minY&&point.y<=maxY)input.selectedUnits.add(unit);
        changed();
    }
    public boolean projectVisible(Unit unit){
        var body=world.unitState(unit);if(body==null||unit.dead()||!unit.isValid()||unit.inFogTo(Vars.player.team()))return false;
        point.set((float)body.x(),(float)body.z(),(float)-body.y());
        double dx=point.x-camera.position.x,dy=point.y-camera.position.y,dz=point.z-camera.position.z;
        if(dx*camera.direction.x+dy*camera.direction.y+dz*camera.direction.z<=camera.near)return false;
        camera.project(point,0,0,Core.graphics.getWidth(),Core.graphics.getHeight());
        if(point.z<0||point.z>1||point.x<0||point.y<0||point.x>Core.graphics.getWidth()||point.y>Core.graphics.getHeight())return false;
        var hit=world.traceSegment(camera.position.x,-camera.position.z,camera.position.y,body.x(),body.y(),body.z(),0);
        double front=CombatGeometry.cylinder(camera.position.x,-camera.position.z,camera.position.y,body.x(),body.y(),body.z(),body.x(),body.y(),body.z(),body.radius(),body.halfHeight());
        return !hit.hit()||hit.fraction()+1e-5>=front;
    }
    /** Nearest visible body first; enemies/owned pilots cannot be selected through to a friendly behind them. */
    public Unit pickUnit(float x,float y){
        var ray=camera.getPickRay(x,y);double reach=Math.min(camera.far,2000),ax=ray.origin.x,ay=-ray.origin.z,az=ray.origin.y;
        double bx=ax+ray.direction.x*reach,by=ay-ray.direction.z*reach,bz=az+ray.direction.y*reach;
        var hit=world.traceSegment(ax,ay,az,bx,by,bz,0);double best=hit.hit()?hit.fraction()+1e-6:1;Unit result=null;
        for(Unit unit:Groups.unit){if(unit==Vars.player.unit()||unit.dead()||unit.inFogTo(Vars.player.team()))continue;var body=world.unitState(unit);if(body==null)continue;
            double t=CombatGeometry.cylinder(ax,ay,az,bx,by,bz,body.x(),body.y(),body.z(),body.radius()+.12,body.halfHeight()+.12);
            if(t<best){best=t;result=unit;}}
        return result;
    }
    public boolean order(float x,float y,boolean queue){
        prune();if(!active||input.selectedUnits.isEmpty()||input.isSuspended()||input.interfaceOwnsInput())return false;
        Unit target=pickUnit(x,y);Building build=null;Vec2 destination;
        if(target!=null){destination=new Vec2(target.x,target.y);if(target.team==Vars.player.team())target=null;}
        else{var ray=camera.getPickRay(x,y);var hit=fpv.construction.BuildingMousePicker.pick(construction,ray,camera.far/Math.max(.001,ray.direction.dot(camera.direction)),renderer.pickInteraction(camera,x,y));
            if(hit==null){status="No visible destination";return false;}
            destination=new Vec2((float)hit.x()*Vars.tilesize,(float)hit.y()*Vars.tilesize);
            if(hit.building()!=null&&hit.building().team!=Vars.player.team()){
                if(hit.layer()>0&&Vars.net.active()){status="Native group attack RPC cannot address elevated buildings online";return false;}
                build=hit.building();}}
        int[] ids=input.selectedUnits.mapInt(u->u.id).toArray();
        for(int i=0;i<ids.length;i+=200)Call.commandUnits(Vars.player,java.util.Arrays.copyOfRange(ids,i,Math.min(i+200,ids.length)),build,target,destination,queue,i+200>=ids.length);
        status=queue?"Order queued":"Order issued";return true;
    }
    private Vec2 local(Element element,float x,float y){Core.scene.screenToStageCoordinates(stagePoint.set(x,y));return element.stageToLocalCoordinates(stagePoint);}
    private void drawOverlay(Element element){
        float stroke=Lines.getStroke();try{
            prune();Draw.color(mindustry.graphics.Pal.accent);Lines.stroke(2);
            for(Unit unit:input.selectedUnits)if(projectVisible(unit)){var p=local(element,point.x,point.y);Lines.poly(p.x,p.y,6,13,0);}
            if(dragging){var a=local(element,startX,startY);float x=a.x,y=a.y;var b=local(element,endX,endY);
                float minX=Math.min(x,b.x),minY=Math.min(y,b.y),width=Math.abs(b.x-x),height=Math.abs(b.y-y);
                Draw.color(mindustry.graphics.Pal.accent,.15f);Fill.crect(minX,minY,width,height);Draw.color(mindustry.graphics.Pal.accent);Lines.rect(minX,minY,width,height);}
            Fonts.outline.draw("COMMAND / Shift · F10 exit / LMB select · drag group / RMB order · MMB queue / "+input.selectedUnits.size+" selected"+(status.isEmpty()?"":" / "+status),
                element.getWidth()/2,element.getHeight()-60,mindustry.graphics.Pal.accent,.75f,false,Align.center);
        }finally{Lines.stroke(stroke);Draw.reset();}
    }
    public void close(){leave(false);overlay.remove();}
}
