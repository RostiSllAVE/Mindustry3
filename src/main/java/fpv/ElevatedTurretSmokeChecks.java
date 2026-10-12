package fpv;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.math.geom.Vec3;
import arc.util.Time;
import fpv.construction.*;
import fpv.control.*;
import fpv.net.NetworkControls;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import java.util.*;

/** Opt-in actual native weapon loops, native resources, shared XYZ combat and stationary possession. */
final class ElevatedTurretSmokeChecks {
    private final FpvMod mod;private int checks;private final ArrayList<Unit> enemies=new ArrayList<>();private final ArrayList<Tile> ground=new ArrayList<>();
    private boolean xyzShot,xyzBeam;
    private ElevatedTurretSmokeChecks(FpvMod mod){this.mod=mod;}
    static String run(FpvMod mod)throws Exception{
        if(!Boolean.getBoolean("fpv.elevatedTurrets.smoke"))throw new IllegalStateException("Isolated turret fixture required");
        boolean permission=Core.settings.getBool(LayerRuntime.FREE_PERMISSION,false),fog=Vars.state.rules.fog,infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor;
        int view=Core.settings.getInt("fpv-camera-view",0),angle=Core.settings.getInt("fpv-pov-angle",110),mode=mod.flightMode;boolean build=mod.buildMode,ready=mod.ready;float delta=Time.delta;Unit original=Vars.player.unit();
        var test=new ElevatedTurretSmokeChecks(mod);
        try{
            Core.settings.put(LayerRuntime.FREE_PERMISSION,true);Vars.state.rules.editor=true;Vars.state.rules.fog=false;Vars.state.rules.infiniteResources=true;Time.delta=1;mod.combat.syncActive();
            for(Block type:new Block[]{Blocks.duo,Blocks.salvo,Blocks.scatter,Blocks.lancer,Blocks.hail,Blocks.wave})for(int floor=0;floor<=3;floor++)test.automatic(type,floor);
            test.automatic(Blocks.meltdown,1);test.automatic(Blocks.meltdown,3);test.automatic(Blocks.arc,1);test.automatic(Blocks.arc,3);test.naval();test.occlusion();test.manual();
            return "PASS: "+test.checks+" actual SDL turret checks; Duo/Salvo/Scatter/Lancer/Hail/Wave at Z0/Z1/Z2/Z3 and Meltdown/Arc at Z1/Z3, true XYZ shots/hits, native item/liquid/power exhaustion/supply, anti-air restrictions, naval targeting, solid-wall occlusion, exact 3D possession and stationary camera, manual up/down/vertical aim, release/AI/destruction/save state. Scripted production methods; no physical hardware input automation.";
        }finally{
            test.clean();Vars.player.unit(original);mod.ready=ready;mod.flightMode=mode;mod.buildMode=build;mod.resetPose();Time.delta=delta;Vars.state.rules.fog=fog;Vars.state.rules.editor=editor;Vars.state.rules.infiniteResources=infinite;
            Core.settings.put(LayerRuntime.FREE_PERMISSION,permission);Core.settings.put("fpv-camera-view",view);Core.settings.put("fpv-pov-angle",angle);
        }
    }
    private Turret.TurretBuild turret(Block block,int floor){
        Vars.state.rules.infiniteResources=true;
        if(floor==0){Tile tile=Vars.world.tile(24,40);tile.setBlock(block,Team.sharded,0);ground.add(tile);return (Turret.TurretBuild)tile.build;}
        var r=mod.construction.requestPlace(24,40,floor,block.name,false,0,LayerComponent.Mode.FREE);check(r.accepted(),block.name+" Z"+floor+" placement: "+r.reason());
        var b=mod.construction.building(r.componentId());int minX=LayerCoordinates.minCell(24,block.size),minY=LayerCoordinates.minCell(40,block.size);
        for(int x=minX;x<minX+block.size;x++)for(int y=minY;y<minY+block.size;y++)check(mod.construction.requestPlace(x,y,floor,"",true,0,LayerComponent.Mode.FREE).accepted(),"real platform underneath turret");
        return (Turret.TurretBuild)b;
    }
    private Unit enemy(mindustry.type.UnitType type,double x,double y,double z){var u=type.create(Team.crux);u.set((float)x*8,(float)y*8);u.add();u.health=u.maxHealth=10000;u.shield=0;mod.unitAltitudes.initializePayloadRelease(u,z);enemies.add(u);Vars.state.teams.updateTeamStats();return u;}
    private void tick(Turret.TurretBuild turret,int frames){
        for(int i=0;i<frames;i++){Time.update();mod.combat.update(1d/60);if(!(turret.tile instanceof LayerTile)){
                for(var b:ground)if(b.build!=null&&b.build!=turret)b.build.update();for(var graph:Groups.powerGraph)graph.update();turret.update();
            }mod.construction.update(1d/60);
            for(var bullet:Groups.bullet.copy())if(bullet.isAdded()){var p=mod.combat.projectileState(bullet);if(bullet.owner==turret&&p!=null&&p.z()>mod.world3d.buildingBase(turret)&&Math.abs(p.vz())>.01)xyzShot=true;bullet.update();}
            if(turret.block instanceof PowerTurret)for(var visual:mod.combat.visuals())if(visual.kind()==fpv.combat.CombatVisual.Kind.BEAM&&visual.teamId()==turret.team.id&&visual.az()>mod.world3d.buildingBase(turret)&&Math.abs(visual.bz()-visual.az())>.01)xyzBeam=true;
        }
    }
    private void ammo(Turret.TurretBuild turret){if(turret instanceof ItemTurret.ItemTurretBuild t){var type=(ItemTurret)t.block;var item=type.ammoTypes.containsKey(Items.copper)?Items.copper:type.ammoTypes.keys().next();t.handleStack(item,t.acceptStack(item,30,null),null);}else if(turret instanceof LiquidTurret.LiquidTurretBuild t)t.handleLiquid(t,Liquids.water,Math.max(0,t.block.liquidCapacity-t.liquids.get(Liquids.water)));else if(turret.block==Blocks.meltdown)turret.handleLiquid(turret,Liquids.cryofluid,turret.block.liquidCapacity);}
    private void power(int layer){
        for(var part:new Object[][]{{Blocks.powerNode,22,40},{Blocks.powerSource,18,40}}){Block b=(Block)part[0];int x=(Integer)part[1],y=(Integer)part[2];
            if(layer==0){Tile tile=Vars.world.tile(x,y);tile.setBlock(b,Team.sharded,0);ground.add(tile);}
            else check(mod.construction.requestPlace(x,y,layer,b.name,false,0,LayerComponent.Mode.FREE).accepted(),"native power fixture "+b.name);
        }
        if(layer==0){var node=Vars.world.tile(22,40).build;node.configureAny(Vars.world.tile(18,40).build.pos());node.configureAny(Vars.world.tile(24,40).build.pos());}
    }
    private void automatic(Block type,int floor)throws Exception{
        clean();xyzShot=xyzBeam=false;var t=turret(type,floor);ammo(t);var victim=enemy(type==Blocks.scatter?UnitTypes.flare:UnitTypes.dagger,type==Blocks.arc?32:38,40,type==Blocks.scatter?10:.15);
        if(type==Blocks.scatter){var groundOnly=enemy(UnitTypes.dagger,30,40,.15);tick(t,40);check(t.target!=groundOnly,"Scatter targetGround=false respected Z"+floor);groundOnly.remove();enemies.remove(groundOnly);}
        if(type==Blocks.lancer||type==Blocks.meltdown||type==Blocks.arc){Vars.state.rules.infiniteResources=false;tick(t,140);check(t.totalShots==0,type.name+" cannot fire without power Z"+floor);Vars.state.rules.infiniteResources=true;power(floor);}
        int ammo=t.totalAmmo;float liquid=t.liquids==null?0:t.liquids.get(Liquids.water),health=victim.health;Vars.state.rules.infiniteResources=false;tick(t,700);
        check(t.target==victim,type.name+" automatically acquires actual 3D enemy Z"+floor);
        check(t.totalShots>0&&(type==Blocks.wave?victim.hasEffect(StatusEffects.wet):victim.health<health),type.name+" normal weapon damages enemy Z"+floor+": shots="+t.totalShots+", damage="+(health-victim.health)+", efficiency="+t.efficiency+", power="+(t.power==null?"none":t.power.status)+", need="+(t.power==null?0:t.power.graph.getLastPowerNeeded()));
        if(type==Blocks.lancer||type==Blocks.meltdown||type==Blocks.arc){check(t.power.graph.getLastPowerNeeded()>0,"native power graph consumption Z"+floor);check(xyzBeam,type.name+" beam/lightning has actual elevated XYZ origin/direction Z"+floor);}
        else if(type==Blocks.wave){check(t.liquids.get(Liquids.water)<liquid,"actual water consumed Z"+floor);}
        else check(t.totalAmmo<ammo,"actual ammunition consumed Z"+floor);
        if(type!=Blocks.lancer&&type!=Blocks.meltdown&&type!=Blocks.arc)check(xyzShot,type.name+" actual projectile Z velocity/origin Z"+floor);
        if(floor>0){var id=mod.construction.grid().at(24,40,floor,LayerComponent.Kind.BLOCK).id();int left=t.totalAmmo;float heading=t.rotation;var snapshot=LayerRuntime.class.getDeclaredMethod("snapshotNative");snapshot.setAccessible(true);snapshot.invoke(mod.construction);byte[] save=mod.construction.replication();mod.construction.applyHostSettings();
            var restore=LayerRuntime.class.getDeclaredMethod("restore",byte[].class,boolean.class);restore.setAccessible(true);restore.invoke(mod.construction,save,false);var restored=(Turret.TurretBuild)mod.construction.building(id);check(restored.totalAmmo==left&&Math.abs(restored.rotation-heading)<.001,"native ammo/heading survive save/load "+type.name+" Z"+floor+": "+left+"/"+restored.totalAmmo+", "+heading+"/"+restored.rotation);
        }
    }
    private void naval(){clean();var water=Vars.world.tile(38,40);var original=water.floor();try{water.setFloor((mindustry.world.blocks.environment.Floor)Blocks.water);var t=turret(Blocks.duo,2);ammo(t);var u=enemy(UnitTypes.risso,38,40,.4);float hp=u.health;tick(t,500);check(t.target==u&&u.health<hp,"elevated Duo targets native naval unit on water with ground-target rules");}finally{water.setFloor(original);}}
    private void occlusion(){
        clean();var t=turret(Blocks.duo,2);ammo(t);var u=enemy(UnitTypes.flare,34,40,5);var r=mod.construction.requestPlace(29,40,2,Blocks.copperWallLarge.name,false,0,LayerComponent.Mode.FREE);check(r.accepted(),"opaque upper wall fixture");float hp=u.health;tick(t,200);check(t.target!=u&&u.health==hp,"solid upper wall blocks acquisition and projectile damage");mod.construction.requestRemove(r.componentId());tick(t,300);check(t.target==u&&u.health<hp,"removing obstruction restores existing shared targeting");
    }
    private void manual(){
        clean();Vars.state.rules.infiniteResources=true;var original=Vars.player.unit();var t=turret(Blocks.duo,3);ammo(t);long id=mod.construction.grid().at(24,40,3,LayerComponent.Kind.BLOCK).id();
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.position.set(24,16,-46);camera.up.set(0,1,0);camera.lookAt(24,(float)mod.world3d.buildingTop(t),-40);camera.near=.04f;camera.far=100;camera.update();
        Unit selected=PilotSelection.pick(camera,mod.world3d,Vars.player,camera.width*.5f,camera.height*.5f);check(selected==t.unit(),"3D selection resolves elevated turret, not ground tile");
        check(mod.construction.requestPossess(id).accepted()&&t.controlled(),"possess friendly elevated turret through actual authority action");mod.resetPose();mod.flightMode=1;mod.buildMode=false;Core.settings.put("fpv-camera-view",0);Core.settings.put("fpv-pov-angle",90);
        check(Math.abs(mod.flight.state().y-mod.world3d.buildingTop(t)-.12)<1e-6,"FPV camera starts at real elevated turret top");
        var target=enemy(UnitTypes.flare,36,40,10);var low=enemy(UnitTypes.dagger,36,40,.15);float hp=target.health,lowHp=low.health;
        double dz=10-(mod.world3d.buildingTop(t)+.06+.25),norm=Math.hypot(12,dz);double dx=12/norm,dzNorm=dz/norm;
        mod.freeLook.align(dx,dzNorm,0);mod.updateCamera();var c=new NetworkControls(true,3,0,0,0,0,1,1,1,1,0,dx,0,dzNorm,true);
        float x=t.x,y=t.y;int before=t.totalAmmo;var pilot=new GroundedPilot();pilot.step(Vars.player,t.unit(),c,mod.unitAltitudes,.1);tick(t,240);
        check(target.health<hp&&low.health==lowHp,"manual rising bullets hit correct Z and miss overlapping ground enemy");check(t.totalAmmo<before&&t.x==x&&t.y==y&&t.unit().vel.isZero(),"manual controls consume ammo without moving stationary turret");
        check(mod.construction.turretControls().pitch(t)>0,"rendered barrel follows actual positive firing pitch");
        mod.freeLook.align(0,1,0);mod.updateCamera();pilot.step(Vars.player,t.unit(),new NetworkControls(true,3,0,0,0,0,0,0,0,0,0,0,0,1,true),mod.unitAltitudes,.1);tick(t,20);check(mod.combat.invalidAimEvents()==0,"exact vertical manual direction remains finite");
        var underneath=enemy(UnitTypes.dagger,24,40,.15);float underHp=underneath.health;mod.freeLook.align(0,-1,0);mod.updateCamera();ammo(t);pilot.step(Vars.player,t.unit(),new NetworkControls(true,3,0,0,0,0,0,0,0,0,0,0,0,-1,true),mod.unitAltitudes,.1);tick(t,70);check(underneath.health==underHp,"own-hull exclusion never bypasses real platform underneath");
        check(mod.construction.requestReleaseTurret().accepted()&&Vars.player.unit()==original&&!t.controlled(),"release restores previous live unit and native AI");ammo(t);tick(t,160);check(t.target!=null&&t.totalShots>0,"automatic targeting resumes after release");
        boolean wasEnabled=mod.enabled;mod.enabled=false;mod.combat.syncActive();check(mod.combat.coverage().active(),"upper turret keeps XYZ combat active when presentation returns to native 2D");mod.enabled=wasEnabled;
        check(mod.construction.requestPossess(id).accepted(),"re-possess before destruction");mod.construction.damage(id,100000);mod.construction.turretControls().update();check(Vars.player.unit()==original&&!mod.construction.turretControls().owns(Vars.player),"destroyed turret safely releases player");
    }
    private void clean(){for(var u:enemies)if(u.isAdded())u.remove();enemies.clear();mod.combat.clear();mod.construction.reset();for(var tile:ground)tile.setAir();ground.clear();}
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError("Elevated turret SDL: "+message);}
}
