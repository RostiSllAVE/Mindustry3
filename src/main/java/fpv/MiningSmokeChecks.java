package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.Tile;

/** Actual production world and piloting camera, native mining state and original ore atlas. */
final class MiningSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated mining lab required");checks=0;
        var original=Vars.player.unit();int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",105);
        boolean build=mod.buildMode,enabled=mod.enabled;int flightMode=mod.flightMode;
        Tile copper=Vars.world.tile(62,60),lead=Vars.world.tile(62,61);var oldCopper=copper.overlay();var oldLead=lead.overlay();
        Unit ai=UnitTypes.mono.create(Vars.player.team()),pilot=UnitTypes.alpha.create(Vars.player.team());
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=200;camera.fov=60;
        try{
            copper.setOverlay(Blocks.oreCopper);lead.setOverlay(Blocks.oreLead);ai.set(60*8,60*8);ai.rotation=0;ai.add();mod.unitAltitudes.initializePayloadRelease(ai,2);
            var miningAI=new mindustry.ai.types.MinerAI();miningAI.ore=copper;miningAI.targetItem=Items.copper;ai.controller(miningAI);miningAI.updateMovement();
            require(ai.isAI()&&ai.mineTile==copper,"native MinerAI starts real copper extraction");
            float delta=arc.util.Time.delta;ai.mineTimer=0;
            try{arc.util.Time.delta=1;ai.update();require(Math.abs(ai.mineTimer-ai.type.mineSpeed*Vars.state.rules.unitMineSpeed(ai.team))<.0001,"original native update advances mining at its unchanged rate");}finally{arc.util.Time.delta=delta;}
            ai.mineTimer=10;
            mod.setEnabled(true);mod.setBuildMode(false);mod.renderer3d.setFirstPersonActive(false);
            var worldPose=mod.unitAltitudes.unitState(ai);float span=(float)Math.max(4,worldPose.z()*1.3);
            camera.up.set(0,1,0);camera.position.set(61+span*.65f,span*.85f,-60+span*1.3f);camera.lookAt(61,(float)worldPose.z()*.5f,-60);camera.update();
            ai.mineTile=null;mod.renderer3d.render(camera);byte[] idle=pixels();ai.mineTile=copper;mod.renderer3d.render(camera);byte[] active=pixels();
            require(mod.renderer3d.wasUnitSubmitted(ai)&&mod.renderer3d.miningVisuals().wasSubmitted(ai)&&changed(idle,active)>30,"AI miner and mining beam/ore impact visible in production third-person world pass");capture("mining-mono-copper-world.png");
            var sample=mod.renderer3d.miningVisuals().sample(ai);var pose=mod.unitAltitudes.unitState(ai);
            require(sample!=null&&sample.az>1.5&&sample.bx==62&&sample.by==60&&Math.abs(sample.bz-mod.renderer3d.surfaceHeight(62,-60)-.028)<1e-7,"physical airborne XYZ emitter reaches correct ore surface");
            int cargo=ai.stack.amount;ai.mineTimer=50;mod.renderer3d.render(camera);
            require(changed(active,pixels())>20,"native progress animates ore ring/impact");require(ai.mineTimer==50&&ai.stack.amount==cargo,"drawing preserves native progress and inventory");
            ai.mineTile=lead;ai.mineTimer=25;mod.renderer3d.render(camera);sample=mod.renderer3d.miningVisuals().sample(ai);
            require(sample!=null&&sample.target==lead&&sample.item==Items.lead&&sample.by==61,"AI target change replaces old ore endpoint/color immediately");capture("mining-mono-lead-world.png");
            ai.mineTile=null;mod.renderer3d.render(camera);require(!mod.renderer3d.miningVisuals().wasSubmitted(ai)&&mod.renderer3d.miningVisuals().stats().miners()==0,"mining stop leaves no beam, tile reaction or particles");
            pilot.set(60*8,60*8);pilot.rotation=0;pilot.add();Vars.player.unit(pilot);Core.settings.put("fpv-camera-view",0);Core.settings.put("fpv-pov-angle",90);mod.resetPose();mod.flightMode=1;mod.updateCamera(1/60d);
            var target=mod.world3d.unitState(pilot);mod.freeLook.align(2,-target.z(),0);mod.updateCamera(1/60d);
            pilot.mineTile=null;mod.renderer3d.setFirstPersonActive(true);mod.renderer3d.render(mod.camera);byte[] fpvIdle=pixels();
            mod.updatePick();require(mod.input.miningPick()==copper,"center ray retains real ore separately from weapon aim");
            require(mod.input.applyFlightMining(true)&&pilot.mineTile==copper,"FPV hold action starts actual native mining");
            float mineDelta=Time.delta;pilot.mineTimer=0;try{Time.delta=1;pilot.update();require(pilot.mineTimer>0&&pilot.mineTile==copper,"FPV action feeds the original native mining progress update");}finally{Time.delta=mineDelta;}
            pilot.mineTimer=30;mod.renderer3d.render(mod.camera);
            require(pilot.isPlayer()&&mod.renderer3d.miningVisuals().wasSubmitted(pilot)&&changed(fpvIdle,pixels())>20,"player-controlled first-person mining is visible with real piloting camera");capture("mining-alpha-copper-fpv.png");
            var fpvSample=mod.renderer3d.miningVisuals().sample(pilot);require(mod.camera.frustum.containsPoint((float)fpvSample.bx,(float)fpvSample.bz,(float)-fpvSample.by),"actual ore impact is inside the first-person viewport");
            mod.input.setMiningPick(lead);require(mod.input.applyFlightMining(true)&&pilot.mineTile==lead,"real FPV input changes mining target");pilot.mineTimer=40;mod.renderer3d.render(mod.camera);require(mod.renderer3d.miningVisuals().sample(pilot).target==lead,"player ore switch immediate in FPV");capture("mining-alpha-lead-fpv.png");
            mod.input.applyFlightMining(false);mod.renderer3d.render(mod.camera);require(pilot.mineTile==null&&!mod.renderer3d.miningVisuals().wasSubmitted(pilot),"player FPV release stops native state and hides effects");
            mod.input.setMiningPick(Vars.world.tile(64,60));require(!mod.input.applyFlightMining(true)&&pilot.mineTile==null,"no mining on empty tile");
            mod.input.setMiningPick(copper);pilot.stack.item=Items.copper;pilot.stack.amount=pilot.type.itemCapacity;require(!mod.input.applyFlightMining(true)&&pilot.mineTile==null,"full native inventory stops FPV mining");pilot.clearItem();
            mod.input.setMiningPick(copper);mod.input.applyFlightMining(true);mod.setBuildMode(true);require(pilot.mineTile==null,"Build transition stops owned mining");mod.setBuildMode(false);
            require(Gl.getError()==0,"no GL errors in world/FPV mining render");
            return "PASS: "+checks+" native MinerAI/controlled Alpha, copper/lead, world/FPV GPU pixels, actual XYZ source/ore surface/progress, immediate stop/target changes and read-only inventory";
        }finally{
            ai.remove();pilot.remove();copper.setOverlay(oldCopper);lead.setOverlay(oldLead);Vars.player.unit(original);Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.flightMode=flightMode;mod.setBuildMode(build);mod.setEnabled(enabled);mod.renderer3d.setFirstPersonActive(false);mod.resetPose();
        }
    }
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,Core.graphics.getWidth(),Core.graphics.getHeight(),false);}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,Core.graphics.getWidth(),Core.graphics.getHeight());}
    private static int changed(byte[] a,byte[] b){int n=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>15)n++;return n;}
    private static void require(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Mining GPU: "+label);}
}
