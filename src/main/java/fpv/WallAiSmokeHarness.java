package fpv;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.ai.types.GroundAI;
import mindustry.content.*;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.*;

/** Opt-in disposable SDL world: original asynchronous GroundAI/pathfinder and actual game frames. */
final class WallAiSmokeHarness {
    private final FpvMod mod;
    private final Fi output=new Fi(System.getProperty("fpv.smoke.output"));
    private final Jval report=Jval.newObject();
    private int frame,phase=-1,start,checks;
    private Unit enemy;
    private Building core;
    private boolean damaged,finished;
    private float minimumWallHp;
    WallAiSmokeHarness(FpvMod mod){this.mod=mod;}
    void start(){
        String lab=System.getProperty("fpv.lab.path","");
        if(!Boolean.getBoolean("fpv.smoke")||lab.isEmpty()||!Vars.dataDirectory.file().toPath().toAbsolutePath().normalize().startsWith(new java.io.File(lab).toPath().toAbsolutePath().normalize()))throw new IllegalStateException("Isolated wall AI lab required");
        output.mkdirs();report.add("runStatus","running");report.add("physicalInputTested",Jval.valueOf(false));write();
        Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);Core.settings.put("backgroundpause",false);Core.settings.put("fpscap",60);Core.settings.put("savecreate",false);
        for(String key:new String[]{"fpv-ground-range","fpv-dagger-range","fpv-naval-range","fpv-unit-vertical-range"})Core.settings.put(key,1);
        while(Core.scene.getDialog()!=null)Core.scene.getDialog().hide(null);
        fpv.render.GroundFamilyVisualChecks.loadFixtureWorld();Events.run(Trigger.update,this::tick);
    }
    private void setup(boolean xyz){
        if(enemy!=null&&enemy.isAdded())enemy.remove();mod.combat.clear();
        for(int y=0;y<128;y++)Vars.world.tile(96,y).setBlock(Blocks.copperWall,Team.sharded,0);
        Vars.world.tile(110,64).setBlock(Blocks.coreShard,Team.sharded,0);core=Vars.world.tile(110,64).build;
        Vars.state.rules.attackMode=true; // Native Team.isAI then creates the campaign/attack GroundAI.
        Vars.state.teams.updateTeamStats();mod.setEnabled(xyz);mod.setBuildMode(false);
        // Equal native range in both fixtures isolates the target-validity regression.
        mod.combat.unitRanges().settings(new fpv.combat.UnitWeaponRanges.Settings(1,1,1,1));
        enemy=UnitTypes.dagger.create(Team.crux);enemy.set(88*Vars.tilesize,64*Vars.tilesize);enemy.rotation=0;enemy.add();
        require(enemy.controller() instanceof GroundAI,"original native GroundAI retained");
        damaged=false;minimumWallHp=Blocks.copperWall.health;start=frame;
    }
    private void tick(){
        if(finished)return;frame++;
        try{
            if(!Vars.state.isGame()||!mod.ready||Vars.player.dead()||frame<40)return;
            if(phase<0){phase=0;setup(false);return;}
            if(mod.enabled!=(phase==1))mod.setEnabled(phase==1); // Initial native player respawn can auto-enable FPV.
            if(frame-start>1800)throw new AssertionError("Native wall AI timeout, phase="+phase+", x="+enemy.x/Vars.tilesize+", target="+enemy.mounts[0].target+", shoot="+enemy.mounts[0].shoot+", wallHP="+minimumWallHp);
            requireLive();
            for(int y=60;y<=68;y++){
                var b=Vars.world.tile(96,y).build;
                if(b==null||b.health<Blocks.copperWall.health){damaged=true;if(b!=null)minimumWallHp=Math.min(minimumWallHp,b.health);}
            }
            if(enemy.x/Vars.tilesize>97.25){
                require(damaged,"real native weapon damages blocking wall before passing");
                require(core.isValid()&&enemy.x/Vars.tilesize<110,"movement resumes toward the surviving original core");
                report.add(phase==0?"native2D":"true3D","PASS: native GroundAI independently acquires a connected Copper Wall barrier, shoots, destroys an opening and resumes core movement; frames="+(frame-start));
                if(phase==1){
                    var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=200;camera.fov=60;camera.position.set(94,7,-53);camera.lookAt(96,1,-64);camera.update();mod.renderer3d.render(camera);
                    ScreenUtils.saveScreenshot(output.child("wall-ai-opened-route.png"),0,0,Core.graphics.getWidth(),Core.graphics.getHeight());
                    report.add("checks",Jval.valueOf(checks));report.add("runStatus","passed");write();finished=true;Core.app.post(()->Core.app.exit());
                }else{phase=1;setup(true);write();}
            }
        }catch(Throwable failure){report.add("runStatus","failed");report.add("failure",failure.toString());report.add("checks",Jval.valueOf(checks));write();finished=true;Core.app.post(()->Core.app.exit());}
    }
    private void requireLive(){if(enemy.dead||!enemy.isAdded())throw new AssertionError("fixture enemy unexpectedly removed");}
    private void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void write(){output.child("wall-ai-result.json").writeString(report.toString(Jval.Jformat.formatted));}
}
