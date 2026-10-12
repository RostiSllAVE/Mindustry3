package fpv;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.*;
import fpv.combat.CombatVisual;
import fpv.world.WorldCoordinates;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.world.blocks.defense.turrets.*;
import java.util.ArrayList;

/** Real native healing/laser sources, actual HP and the existing production world/FPV renderer. */
final class VfxSmokeChecks {
    private static int checks;
    static String run(FpvMod mod){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated VFX lab required");checks=0;
        var original=Vars.player.unit();int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",105);boolean build=mod.buildMode,enabled=mod.enabled;float delta=Time.delta;
        var healedTile=Vars.world.tile(88,80);var targetTile=Vars.world.tile(96,80);var gunTile=Vars.world.tile(90,80);var owned=new ArrayList<Bullet>();
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=200;camera.fov=60;
        try{
            mod.setEnabled(true);mod.setBuildMode(false);Time.delta=1;mod.combat.syncActive();
            healedTile.setBlock(Blocks.copperWall,Team.sharded,0);var block=healedTile.build;block.health=100;
            camera.up.set(0,1,0);camera.position.set(90,3,-76);camera.lookAt(88,.5f,-80);camera.update();mod.renderer3d.render(camera);int beforeGreen=green(pixels());
            BulletType healing=mod.combat.originalType(UnitTypes.nova.weapons.first().bullet);float before=block.health,amount=healing.healAmount+healing.healPercent*.01f*block.maxHealth;
            var shot=mod.combat.launchPhysical(healing,null,Team.sharded,new WorldCoordinates.Point(85,80,.35),new WorldCoordinates.Point(1,0,0),-1);owned.add(shot);
            for(int i=0;i<20&&block.health==before;i++){mod.combat.update(1/60d);shot.update();}
            require(Math.abs(block.health-before-amount)<.01,"native Nova healing amount applied exactly once");require(mod.combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.HEAL),"actual successful healing emits authoritative block flash");
            mod.renderer3d.render(camera);require(green(pixels())>beforeGreen+20,"green healing flash is visibly attached to block");capture("healing-block-flash.png");
            float hp=block.health;mod.combat.update(.3);mod.renderer3d.render(camera);require(mod.combat.visuals().stream().noneMatch(v->v.kind()==CombatVisual.Kind.HEAL)&&block.health==hp,"flash expires without permanent recolor or extra healing");
            targetTile.setBlock(Blocks.copperWall,Team.crux,0);var target=targetTile.build;target.maxHealth=10000;target.health=10000;
            camera.position.set(93,3,-75);camera.lookAt(93,.4f,-80);camera.update();
            BulletType[] lasers={((PowerTurret)Blocks.lancer).shootType,((PowerTurret)Blocks.meltdown).shootType,mod.combat.originalType(UnitTypes.vela.weapons.first().bullet)};
            for(int i=0;i<lasers.length;i++){
                var source=mod.combat.originalType(lasers[i]);float damage=source.damage,lifetime=source.lifetime,oldHP=target.health;
                var laser=mod.combat.launchPhysical(source,null,Team.sharded,new WorldCoordinates.Point(90,80,.4),new WorldCoordinates.Point(1,0,0),-1);owned.add(laser);
                for(int t=0;t<8;t++){mod.combat.update(1/60d);if(laser.isAdded())laser.update();}
                require(target.health<oldHP,"native laser "+i+" still damages real target");require(mod.combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM),"native laser "+i+" has original XYZ visual segment");
                mod.renderer3d.render(camera);require(mod.renderer3d.combatVisualStats().beams()>0,"native laser "+i+" rendered through new tube path");capture("laser-native-"+i+"-world.png");
                require(source.damage==damage&&source.lifetime==lifetime,"native laser damage/timing metadata unchanged");
                if(laser.isAdded())laser.remove();
            }
            gunTile.setBlock(Blocks.lancer,Team.sharded,0);var gun=(Turret.TurretBuild)gunTile.build;gun.rotation=0;Vars.player.unit(gun.unit());Core.settings.put("fpv-camera-view",0);Core.settings.put("fpv-pov-angle",90);mod.resetPose();mod.updateCamera(1/60d);
            var fpvLaser=mod.combat.launchPhysical(lasers[0],gun,Team.sharded,new WorldCoordinates.Point(90,80,mod.world3d.buildingTop(gun)+.1),new WorldCoordinates.Point(1,0,0),-1);owned.add(fpvLaser);
            mod.renderer3d.render(mod.camera);require(mod.renderer3d.combatVisualStats().beams()>0,"controlled turret first-person laser is visible");capture("laser-lancer-fpv.png");
            require(Gl.getError()==0,"normal world/FPV VFX depth and GL state intact");
            return "PASS: "+checks+" native Nova heal/actual HP/green GPU flash/expiry; Lancer/Meltdown/Vela real hits/color/timing, world and controlled Lancer FPV, GL0";
        }finally{
            for(var shot:owned)if(shot!=null&&shot.isAdded())shot.remove();healedTile.setBlock(Blocks.air);targetTile.setBlock(Blocks.air);Vars.player.unit(original);gunTile.setBlock(Blocks.air);Time.delta=delta;Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);mod.setBuildMode(build);mod.setEnabled(enabled);mod.resetPose();
        }
    }
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,Core.graphics.getWidth(),Core.graphics.getHeight(),false);}
    private static int green(byte[] p){int n=0;for(int i=0;i<p.length;i+=4)if((p[i+1]&255)>(p[i]&255)*1.3&&(p[i+1]&255)>(p[i+2]&255)*1.15&&(p[i+1]&255)>70)n++;return n;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name),0,0,Core.graphics.getWidth(),Core.graphics.getHeight());}
    private static void require(boolean ok,String text){checks++;if(!ok)throw new AssertionError("VFX GPU: "+text);}
}
