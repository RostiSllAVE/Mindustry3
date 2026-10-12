package fpv;

import arc.Core;
import arc.files.Fi;
import arc.util.Time;
import arc.util.serialization.Jval;
import fpv.control.*;
import fpv.net.NetworkControls;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.blocks.ControlBlock;
import mindustry.world.blocks.defense.turrets.*;
import java.util.ArrayList;

/** Real native possession, movement, ammo and creator transitions, guarded to the isolated SDL lab. */
final class ControlSmokeChecks {
    private int checks;
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static String run(FpvMod mod){return new ControlSmokeChecks().verify(mod);}
    private String verify(FpvMod mod){
        check(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame()&&!Vars.net.active(),"isolated offline world required");
        var original=Vars.player.unit();var controller=original.controller();var owned=new ArrayList<Unit>();
        boolean oldPermission=Core.settings.getBool(CreatorFlightSystem.SETTING,false),oldPaused=Vars.state.isPaused();
        float delta=Time.delta;Tile site=findSite();check(site!=null,"open ground control fixture");
        var pilot=new GroundedPilot();var creator=mod.network.creatorFlight();Jval catalog=Jval.newObject(),units=Jval.newArray(),turrets=Jval.newArray();
        try{
            Time.delta=1;Vars.state.set(mindustry.core.GameState.State.playing);
            int controllable=0;
            for(var type:Vars.content.units()){
                Unit u=type.create(Vars.player.team());var row=Jval.newObject();row.add("name",type.name);row.add("movement",UnitControlCapabilities.movement(u).name());
                row.add("nativePlayerControllable",Jval.valueOf(u.playerControllable()));row.add("weapons",Jval.valueOf(type.weapons.size));
                row.add("scope",u.playerControllable()?"native possession; capability movement; native weapon mounts":"native playerControllable=false (e.g. internal missile/segment); not overridden");units.add(row);if(u.playerControllable())controllable++;
                check(UnitControlCapabilities.stationary(u)||UnitControlCapabilities.movement(u)!=null,"all native unit types classified");
            }
            for(var block:Vars.content.blocks())if(block instanceof BaseTurret||block instanceof mindustry.world.blocks.units.RepairTurret||block instanceof mindustry.world.blocks.units.RepairTower){
                var build=block.buildType.get();var row=Jval.newObject();row.add("name",block.name);boolean supports=build instanceof ControlBlock c&&c.canControl();
                row.add("nativeControlBlock",Jval.valueOf(supports));row.add("scope",supports?"fixed native turret; existing spatial shot adapter":"native block has no player ControlBlock / canControl; retains native automatic defense or healing with existing 3D adapter/presentation");turrets.add(row);
            }
            catalog.add("units",units);catalog.add("turrets",turrets);catalog.add("controllableUnits",Jval.valueOf(controllable));
            new Fi(System.getProperty("fpv.smoke.output")).child("control-coverage.json").writeString(catalog.toString(Jval.Jformat.formatted));
            for(var type:new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.atrax,UnitTypes.stell,UnitTypes.elude}){
                Unit u=spawn(owned,type,site.x+5,site.y+5);u.rotation=90;Vars.player.unit(u);mod.resetPose();
                check(UnitControlCapabilities.validOwner(Vars.player,u),type.name+" native player owns controlled body");
                check(mod.enabled&&mod.ready&&!mod.world3d.unitState(u).flying(),type.name+" stays visible and grounded in FPV");
                double x=u.x,y=u.y,z=mod.world3d.unitState(u).z();
                for(int i=0;i<30;i++){pilot.step(Vars.player,u,command(true,0,1,0,0,0,false,false),mod.unitAltitudes,1d/60);mod.unitAltitudes.captureGroundIntent(1d/60);u.update();mod.unitAltitudes.update(1d/60);}
                check(u.y>y+.2&&Math.abs(u.x-x)<.5,type.name+" native forward movement follows pilot heading");
                check(!u.type.flying&&!mod.world3d.unitState(u).flying(),type.name+" has no aircraft lift");
                check(mod.flight.state().y<z+100,type.name+" camera reset does not initialize a flight altitude");
                pilot.step(Vars.player,u,command(true,2,1,1,1,0,true,false),mod.unitAltitudes,1d/60);
                check(!u.isShooting,"Build mode blocks native gun trigger");u.remove();
            }
            Unit selected=spawn(owned,UnitTypes.atrax,site.x+5,site.y+5);Vars.player.unit(original);mod.resetPose();
            for(int i=0;i<100;i++)mod.unitAltitudes.update(1d/60);
            var selectedPose=mod.world3d.unitState(selected);var selector=new arc.graphics.g3d.Camera3D();selector.resize(Core.graphics.getWidth(),Core.graphics.getHeight());selector.fov=60;selector.near=.04f;selector.far=300;
            selector.position.set((float)selectedPose.x(),(float)selectedPose.z(),(float)-selectedPose.y()+8);selector.up.set(0,1,0);selector.lookAt((float)selectedPose.x(),(float)selectedPose.z(),(float)-selectedPose.y());selector.update();
            check(PilotSelection.pick(selector,mod.world3d,Vars.player,Core.graphics.getWidth()/2f,Core.graphics.getHeight()/2f)==selected,"3D ray selects raised walker above terrain rather than ground tile behind it");
            mindustry.input.InputHandler.unitControl(Vars.player,selected);check(Vars.player.unit()==selected&&selected.controller()==Vars.player,"3D selected unit transfers through native possession validation");selected.remove();
            for(var type:new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.atrax}){
                Unit u=spawn(owned,type,site.x+5,site.y+5);Vars.player.unit(u);mod.resetPose();
                double grounded=mod.world3d.unitState(u).z();Core.settings.put(CreatorFlightSystem.SETTING,false);
                creator.control(Vars.player,u,command(true,3,0,0,1,0,false,true));check(!creator.active(u),"creator permission defaults off / request cannot bypass host");
                Core.settings.put(CreatorFlightSystem.SETTING,true);
                for(int i=0;i<50;i++){creator.control(Vars.player,u,command(true,3,0,0,.2,0,false,true));creator.before(1d/60);u.update();creator.after();mod.unitAltitudes.update(1d/60);}
                check(creator.active(u)&&mod.world3d.unitState(u).z()>grounded+.5,type.name+" real XYZ creator climb");
                check(!type.flying&&u.isFlying(),"per-unit runtime flight leaves shared UnitType grounded");
                check(mod.world3d.unitState(u).flying(),"canonical combat/render pose marks authorized creator flight");
                var pose=mod.world3d.unitState(u);var camera=new arc.graphics.g3d.Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=800;camera.fov=65;
                camera.fov=45;camera.position.set((float)pose.x()+3,(float)pose.z()+1.4f,(float)-pose.y()+4);camera.up.set(0,1,0);camera.lookAt((float)pose.x(),(float)pose.z(),(float)-pose.y());camera.update();
                mod.renderer3d.setShowControlledUnit(true);mod.renderer3d.render(camera);
                check(mod.renderer3d.creatorFlightWasSubmitted(u),type.name+" authorized creator exhaust submitted to GPU");
                arc.util.ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child("creator-"+type.name+".png"));
                Core.settings.put(CreatorFlightSystem.SETTING,false);
                int steps=0;while(creator.active(u)&&steps++<2400){creator.control(Vars.player,u,command(true,3,1,1,1,0,false,true));creator.before(1d/60);u.update();creator.after();mod.unitAltitudes.update(1d/60);}
                check(!creator.active(u)&&!u.isFlying(),type.name+" permission revocation lands despite held forged flight axes");
                check(u.canPassOn()&&!u.dead,type.name+" landed on valid native terrain alive");u.remove();
            }
            // Native turret proxy is controlled without converting the building into a moving Unit.
            site.setBlock(Blocks.duo,Vars.player.team(),0);var turret=(ItemTurret.ItemTurretBuild)site.build;var proxy=turret.unit();Vars.player.unit(proxy);mod.resetPose();
            check(UnitControlCapabilities.validOwner(Vars.player,proxy),"native turret proxy is a valid fixed pilot");
            float tx=turret.x,ty=turret.y;Core.settings.put(CreatorFlightSystem.SETTING,true);
            for(int i=0;i<20;i++){var c=command(true,3,1,1,1,0,false,true);creator.control(Vars.player,proxy,c);pilot.step(Vars.player,proxy,c,mod.unitAltitudes,1d/60);turret.updateTile();}
            check(!creator.active(proxy)&&turret.x==tx&&turret.y==ty&&proxy.x==tx&&proxy.y==ty,"WASD/QE/creator requests cannot move a turret");
            turret.handleItem(null,Items.copper);turret.handleItem(null,Items.copper);int ammo=turret.totalAmmo;
            turret.efficiency=1;turret.potentialEfficiency=1;turret.reloadCounter=((Turret)turret.block).reload;turret.rotation=90;
            mod.freeLook.align(0,0,-1);mod.freeLook.look(0,35);mod.updateCamera();mod.updatePick();var aim=mod.controlledAim();
            pilot.step(Vars.player,proxy,new NetworkControls(true,3,0,0,0,0,0,0,0,0,0,aim.x(),aim.y(),aim.z(),true),mod.unitAltitudes,1d/60);
            for(int i=0;i<4;i++)turret.updateTile();
            check(turret.totalAmmo<ammo,"controlled native turret consumes real ammunition");
            boolean rising=false;for(var bullet:Groups.bullet)if(bullet.owner==turret){var shot=mod.combat.projectileState(bullet);rising|=shot!=null&&shot.vz()>0;}
            check(rising,"controlled turret shoots with actual XYZ camera elevation");
            Vars.player.unit(original);check(proxy.isAI(),"release restores native turret AI ownership");site.setAir();
            return "PASS: "+checks+" native assertions; all-unit/turret capability catalog, grounded controls, Build input, turret ammo/XYZ, creator denial/climb/landing";
        }finally{
            creator.clear();Vars.player.unit(original);original.controller(controller);for(Unit u:owned)if(u.isAdded())u.remove();if(site!=null&&site.block()==Blocks.duo)site.setAir();
            Core.settings.put(CreatorFlightSystem.SETTING,oldPermission);Time.delta=delta;Vars.state.set(oldPaused?mindustry.core.GameState.State.paused:mindustry.core.GameState.State.playing);mod.combat.clear();mod.resetPose();mod.input.setSuspended(false);
        }
    }
    static NetworkControls command(boolean active,int mode,double f,double r,double climb,double heading,boolean shoot,boolean creator){return new NetworkControls(active,mode,0,0,0,0,f,r,climb,0,heading,0,1,0,shoot,creator);}
    private static Unit spawn(ArrayList<Unit> owned,mindustry.type.UnitType type,double x,double y){Unit u=type.create(Vars.player.team());u.set((float)x*8,(float)y*8);u.rotation=90;u.add();owned.add(u);return u;}
    private static Tile findSite(){for(int y=8;y<Vars.world.height()-24;y++)for(int x=8;x<Vars.world.width()-24;x++){boolean valid=true;for(int yy=y;yy<y+20&&valid;yy++)for(int xx=x;xx<x+20;xx++){var t=Vars.world.tile(xx,yy);if(t==null||t.build!=null||t.block().solid||t.floor().isDeep()){valid=false;break;}}if(valid)return Vars.world.tile(x,y);}return null;}
}
