package fpv;

import arc.Core;
import arc.math.Mathf;
import arc.scene.Element;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Groups;

/** Actual native fixed-mount firing, including sideways/reverse and past-zenith sights. */
final class FlightFiringSmokeChecks {
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("isolated lab required");
        var original=Vars.player.unit();var originalController=original.controller();
        var test=UnitTypes.flare.create(original.team);test.set(original.x,original.y);test.add();
        int oldMode=Core.settings.getInt("fpv-flight-mode",0),oldView=Core.settings.getInt("fpv-camera-view",0),oldAngle=Core.settings.getInt("fpv-pov-angle",105);
        boolean oldBuild=mod.buildMode,oldSuspended=mod.input.isSuspended(),oldBuilding=mod.input.isBuilding;
        var block=mod.input.block;float delta=Time.delta;var focus=Core.scene.getKeyboardFocus();
        Element field=new Element();int cases=0;
        try{
            Vars.player.unit(test);mod.resetPose();mod.buildMode=false;mod.input.setBuildMode(false);mod.input.setSuspended(false);
            Core.settings.put("fpv-flight-mode",1);mod.syncFlightMode();mod.input.block=Blocks.conveyor;mod.input.isBuilding=true;
            var plan=new mindustry.entities.units.BuildPlan((int)(test.x/Vars.tilesize),(int)(test.y/Vars.tilesize),0,Blocks.conveyor);
            test.plans.addLast(plan);Time.delta=1;
            for(int view=0;view<3;view++)for(int heading:new int[]{0,90,180,270})for(int pitch:new int[]{-85,0,85}){
                var s=mod.flight.state();s.y=100;s.vx=s.vy=s.vz=0;
                mod.freeLook.align(0,0,-1);mod.freeLook.look(heading,pitch);
                Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",105);
                mod.navigateStabilized(.02,-1,1,0);
                double[] f=new double[3];mod.flight.getForward(f);test.rotation=Mathf.angle((float)f[0],(float)-f[2]);
                mod.updateCamera();mod.updatePick();mod.input.applyFlightTrigger(true);
                check(Vars.player.shooting&&test.isShooting,"held trigger passes flight gate with retained builder state");
                int before=shots(test);
                for(var mount:test.mounts){mount.reload=0;mount.warmup=1;mount.weapon.update(test,mount);}
                check(shots(test)>before,"real Flare fixed weapon shoots at heading="+heading+", pitch="+pitch+", view="+view);
                check(test.plans.contains(plan),"firing preserves queued construction");cases++;
                clearBullets(test);
            }
            mod.input.setBuildMode(true);mod.input.applyFlightTrigger(true);check(!Vars.player.shooting&&!test.isShooting,"Build blocks gun trigger");
            mod.input.setBuildMode(false);mod.input.setSuspended(true);mod.input.applyFlightTrigger(true);check(!test.isShooting,"lost focus blocks gun trigger");
            mod.input.setSuspended(false);Core.scene.add(field);Core.scene.setKeyboardFocus(field);mod.input.applyFlightTrigger(true);check(!test.isShooting,"text entry blocks gun trigger");
            Core.scene.setKeyboardFocus(null);test.disarmed=true;mod.input.applyFlightTrigger(true);check(!test.isShooting,"native disarm still blocks gun trigger");
            test.disarmed=false;mod.input.applyFlightTrigger(false);check(!test.isShooting,"released trigger stops weapons");
            return "PASS: "+cases+" actual native Flare fixed-mount firing cases across 4 headings, 3 pitches, 3 views while reversing/strafing; retained builder queue, Build/focus/text/disarm/release gates";
        }finally{
            clearBullets(test);field.remove();Core.scene.setKeyboardFocus(focus);Time.delta=delta;
            Vars.player.unit(original);original.controller(originalController);test.remove();
            Core.settings.put("fpv-flight-mode",oldMode);Core.settings.put("fpv-camera-view",oldView);Core.settings.put("fpv-pov-angle",oldAngle);
            mod.input.block=block;mod.input.isBuilding=oldBuilding;mod.input.setBuildMode(oldBuild);mod.input.setSuspended(oldSuspended);mod.buildMode=oldBuild;
            mod.resetPose();mod.motion.syncFromSimulation(original,mod.flight.state());mod.combat.clear();
        }
    }
    private static int shots(mindustry.gen.Unit unit){int n=0;for(var mount:unit.mounts)n+=mount.totalShots;return n;}
    private static void clearBullets(mindustry.gen.Unit unit){var list=new java.util.ArrayList<mindustry.gen.Bullet>();for(var b:Groups.bullet)if(b.owner==unit)list.add(b);for(var b:list)b.remove();}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException("Flight firing smoke: "+message);}
}
