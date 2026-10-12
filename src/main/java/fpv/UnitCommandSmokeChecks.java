package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.math.geom.Vec2;
import arc.math.geom.Vec3;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.input.FpvUnitCommands;
import mindustry.Vars;
import mindustry.ai.types.CommandAI;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.*;
import java.util.ArrayList;

/** Real native units, command RPCs/AI, 3D occlusion and scripted input boundaries; disposable SDL lab only. */
final class UnitCommandSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated offline SDL lab required");checks=0;
        var input=mod.input;var commands=input.commands();var oldSelection=input.selectedUnits.copy();var oldBuildings=input.commandBuildings.copy();
        var block=input.block;int rotation=input.rotation;boolean build=mod.buildMode,enabled=mod.enabled,suspended=input.isSuspended();
        boolean free=Core.settings.getBool("fpv-layer-free-permission",false),infinite=Vars.state.rules.infiniteResources;
        float delta=Time.delta;var owned=new ArrayList<Unit>();long occluder=0;var owner=Vars.player.unit();
        BuildPlan queued=new BuildPlan(12,12,0,Blocks.conveyor);owner.plans.add(queued);
        try{
            mod.setEnabled(true);mod.setBuildMode(false);input.setSuspended(false);input.block=Blocks.conveyor;commands.modeInput(true,false,false,false,false,true,false,false,false,false,false);
            check(commands.active()&&input.commandMode,"explicit command mode active");
            check(input.block==null&&!input.isPlacing(),"stored build block cannot own command gesture or native recipe panel");
            Unit ground=spawn(mod,owned,UnitTypes.dagger,Vars.player.team(),70,70);
            Unit air=spawn(mod,owned,UnitTypes.flare,Vars.player.team(),70,70);
            Unit second=spawn(mod,owned,UnitTypes.dagger,Vars.player.team(),73,70);
            Unit enemy=spawn(mod,owned,UnitTypes.flare,Team.crux,66,70);
            Unit core=spawn(mod,owned,UnitTypes.alpha,Vars.player.team(),76,70);
            check(FpvUnitCommands.eligible(ground)&&FpvUnitCommands.eligible(air)&&FpvUnitCommands.eligible(second),"native commandable friends accepted");
            check(!FpvUnitCommands.eligible(enemy)&&!FpvUnitCommands.eligible(owner),"enemy/player command restrictions preserved");
            check(FpvUnitCommands.eligible(core)==(core.isCommandable()&&core.allowCommand()),"core-unit selection follows native controller metadata, without a new type blacklist");
            camera(mod);var low=screen(mod,ground);var high=screen(mod,air);var other=screen(mod,second);
            check(low.dst(high)>20,"overlapping native XY occupy separate real-Z screen positions");
            check(commands.pickUnit(low.x,low.y)==ground&&commands.pickUnit(high.x,high.y)==air,"clicks resolve ground/air at actual XYZ");
            commands.process(pointer(high,true,true,false,false,false,false,false));commands.process(pointer(high,false,false,true,false,false,false,false));
            check(input.selectedUnits.size==1&&input.selectedUnits.first()==air,"single click selects one friendly");
            Vec2 first=new Vec2(Math.min(low.x,Math.min(high.x,other.x))-12,Math.min(low.y,Math.min(high.y,other.y))-12);
            Vec2 last=new Vec2(Math.max(low.x,Math.max(high.x,other.x))+12,Math.max(low.y,Math.max(high.y,other.y))+12);
            commands.process(pointer(first,true,true,false,false,false,false,false));commands.process(pointer(last,false,true,false,false,false,false,false));
            capture(mod,"commands-rectangle.png");check(commands.dragging(),"screen rectangle remains live while held");
            commands.process(pointer(last,false,false,true,false,false,false,false));
            check(input.selectedUnits.contains(ground)&&input.selectedUnits.contains(air)&&input.selectedUnits.contains(second)&&!input.selectedUnits.contains(enemy),"screen drag selects friendly group on multiple physical heights");
            capture(mod,"commands-selected.png");
            check(owner.plans.contains(queued)&&Vars.player.unit()==owner&&owner.controller()==Vars.player,"group selection preserves queued costs and single pilot ownership");
            input.applyFlightTrigger(true);check(!Vars.player.shooting&&!owner.isShooting,"selection LMB never fires pilot weapons");
            // Actual native RPC installs ordinary CommandAI orders; no new AI is substituted.
            Vec2 destination=new Vec2(72*8,76*8);var projected=mod.camera.project(new Vec3(72,0,-76),0,0,Core.graphics.getWidth(),Core.graphics.getHeight());
            check(commands.order(projected.x,projected.y,false),"movement issued through native Call.commandUnits");
            check(((CommandAI)ground.controller()).targetPos!=null&&((CommandAI)air.controller()).targetPos!=null,"native ground/flying CommandAI stores move targets");
            check(commands.order(projected.x,projected.y,true)&&((CommandAI)ground.controller()).commandQueue.size>0,"native queue command preserved");
            float originalX=ground.x,originalY=ground.y;Time.delta=1;
            for(int i=0;i<25;i++){ground.update();air.update();second.update();mod.unitAltitudes.update(1d/60);}
            check(Math.hypot(ground.x-originalX,ground.y-originalY)>.1,"real native AI moves the selected ground unit");
            var attack=screen(mod,enemy);check(commands.order(attack.x,attack.y,false),"visible aerial enemy attack order accepted");
            check(((CommandAI)air.controller()).attackTarget==enemy,"native attack target is actual XYZ ray-selected enemy");
            var enemyAI=(CommandAI)enemy.controller();var enemyTarget=enemyAI.targetPos;
            mindustry.input.InputHandler.commandUnits(Vars.player,new int[]{enemy.id},null,null,destination,false,true);
            check(enemyAI.targetPos==enemyTarget,"native authority rejects forged enemy group ID");
            input.selectedUnits.add(enemy);commands.prune();check(!input.selectedUnits.contains(enemy),"UI ownership validation also rejects enemy IDs");
            commands.process(pointer(first,true,true,false,false,false,false,false));commands.process(pointer(last,false,true,false,true,false,false,false));
            check(!commands.dragging()&&commands.pointerFence(),"RMB cancels a drag without issuing an attack");
            commands.modeInput(true,false,false,false,false,false,false,false,false,false,false);
            commands.process(pointer(first,true,true,false,false,false,false,false));commands.process(pointer(last,false,true,false,false,false,true,false));
            check(!commands.dragging()&&commands.active()&&commands.consumeEscape(),"Escape cancels rectangle and consumes menu binding");
            commands.modeInput(true,false,false,false,false,false,false,false,false,false,false);
            commands.process(pointer(first,false,false,false,false,false,true,false));check(!commands.active()&&input.selectedUnits.isEmpty(),"Escape without drag clears selection and exits");
            commands.modeInput(true,false,false,true,true,false,true,false,false,false,false);
            check(commands.active(),"native Shift hold preference enters commands");
            commands.process(pointer(first,true,true,false,false,false,false,false));
            commands.modeInput(true,false,false,false,false,false,true,true,false,false,false);
            check(commands.active(),"releasing Shift mid-drag defers mouse capture until drag release");
            commands.process(pointer(last,false,false,true,false,false,false,false));check(!commands.active()&&!input.selectedUnits.isEmpty(),"held-Shift release completes group and returns to FPV");
            int selected=input.selectedUnits.size;mod.setBuildMode(true);commands.modeInput(true,true,false,true,true,false,false,false,false,false,false);
            check(!commands.active(),"Build Shift remains native linking/placement modifier");
            commands.modeInput(true,true,false,false,false,true,false,false,false,false,false);check(commands.active(),"F10 enters command mode from Build");
            input.setControlPick(ground);check(!input.applyControlSelection(true,true)&&Vars.player.unit()==owner,"Ctrl+click cannot possess a unit while selecting commands");
            commands.process(pointer(first,true,true,false,false,false,false,true));check(!commands.dragging(),"native UI consumes selection without a world rectangle");
            mod.updatePick();check(!input.hasValidPick()&&input.commands().active(),"Build picker cannot place while command mode owns pointer");
            mod.setBuildMode(false);check(!commands.active()&&input.selectedUnits.size==selected&&input.block==Blocks.conveyor,"Tab transition preserves group, selected block and cancels command gesture");
            // Existing physical layer picker/trace occludes group candidates under a platform.
            ground.set(70*8,70*8);mod.unitAltitudes.update(0);camera(mod);
            Core.settings.put("fpv-layer-free-permission",true);Vars.state.rules.infiniteResources=true;
            var roof=mod.construction.requestPlace(72,67,2,"",true,0,fpv.construction.LayerComponent.Mode.FREE);
            check(roof.accepted(),"temporary upper occluder constructed: "+roof.reason());occluder=roof.componentId();
            check(!commands.projectVisible(ground),"actual elevated platform hides lower unit selection");
            mod.construction.requestRemove(occluder);occluder=0;check(commands.projectVisible(ground),"removing occluder restores visible candidate");
            commands.modeInput(true,false,false,false,false,true,false,false,false,false,false);
            commands.selectRectangle(0,0,Core.graphics.getWidth(),Core.graphics.getHeight());int group=input.selectedUnits.size;
            commands.modeInput(true,false,false,false,false,true,false,true,false,false,false);input.applyFlightTrigger(true);
            check(!commands.active()&&commands.pointerFence()&&!Vars.player.shooting,"mode exit fences held LMB, without cooldown");
            commands.modeInput(true,false,false,false,false,false,false,false,false,false,false);check(!commands.pointerFence(),"mouse release clears fence immediately");
            input.setFpvEnabled(false);check(input.selectedUnits.size==group,"native 2D camera return retains selected group");
            input.commandMode=true;input.syncCommands();check(input.commandMode,"2D native command toggle remains owned by vanilla, never reset by FPV adapter");input.setFpvEnabled(true);
            check(Gl.getError()==0,"command overlay and native world GPU state clean");
            return "PASS: "+checks+" actual native command checks; single/XYZ-area selection, owner/enemy/core rules, native movement/queue/attack RPCs and AI, physical platform occlusion, rectangle/selection GPU captures, Shift toggle/hold, F10/Tab/2D transitions, UI/cancel/held-button gates, no pilot firing or queued-build loss";
        }finally{
            if(occluder!=0)mod.construction.requestRemove(occluder);commands.leave(false);
            owner.plans.remove(queued);for(Unit u:owned)if(u.isAdded())u.remove();input.selectedUnits.set(oldSelection);input.commandBuildings.set(oldBuildings);
            input.block=block;input.rotation=rotation;Time.delta=delta;Core.settings.put("fpv-layer-free-permission",free);Vars.state.rules.infiniteResources=infinite;
            input.setSuspended(suspended);mod.setEnabled(enabled);mod.setBuildMode(build);mod.resetPose();mod.hud.update();
        }
    }
    private static Unit spawn(FpvMod mod,ArrayList<Unit> owned,mindustry.type.UnitType type,Team team,int x,int y){
        Unit u=type.create(team);u.set(x*8,y*8);if(type!=UnitTypes.alpha)u.controller(new CommandAI());u.add();owned.add(u);mod.unitAltitudes.pose(u);return u;
    }
    private static void camera(FpvMod mod){var c=mod.camera;c.resize(Core.graphics.getWidth(),Core.graphics.getHeight());c.near=.04f;c.far=300;c.fov=65;c.position.set(80,16,-53);c.up.set(0,1,0);c.lookAt(70,4,-70);c.update();}
    private static Vec2 screen(FpvMod mod,Unit unit){var p=mod.world3d.unitState(unit);var out=mod.camera.project(new Vec3((float)p.x(),(float)p.z(),(float)-p.y()),0,0,Core.graphics.getWidth(),Core.graphics.getHeight());return new Vec2(out.x,out.y);}
    private static FpvUnitCommands.Pointer pointer(Vec2 p,boolean tap,boolean held,boolean released,boolean right,boolean queue,boolean escape,boolean ui){return new FpvUnitCommands.Pointer(p.x,p.y,tap,held,released,right,queue,escape,ui);}
    private static void capture(FpvMod mod,String name){mod.renderer3d.render(mod.camera);mod.hud.update();Core.scene.act(0);Core.scene.draw();ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError("3D unit commands: "+reason);}
}
