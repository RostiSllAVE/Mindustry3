package fpv.net;

import arc.Events;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.blocks.units.UnitFactory;
import java.io.*;

/** Opt-in lab only: one native server factory completion, observed by both real clients. */
final class NavalProductionNetworkSmokeChecks {
    private final FpvNetwork network;
    private UnitFactory.UnitFactoryBuild factory;
    private Unit produced;
    private int id=-1,inventory,releases;
    NavalProductionNetworkSmokeChecks(FpvNetwork network){this.network=network;}
    void setup(){
        for(int x=76;x<94;x++)for(int y=18;y<36;y++)Vars.world.tile(x,y).setFloorNet(Blocks.water.asFloor(),Blocks.air);
        Vars.world.tile(84,26).setNet(Blocks.navalFactory,Team.sharded,0);factory=(UnitFactory.UnitFactoryBuild)Vars.world.build(84,26);
        for(var item:Vars.content.items())factory.items.add(item,10000);int before=factory.items.total();factory.currentPlan=0;factory.efficiency=1;factory.progress=((UnitFactory)factory.block).plans.get(0).time;factory.updateTile();
        require(factory.payload!=null&&factory.items.total()<before,"authoritative factory completes and pays native recipe");produced=factory.payload.unit;id=produced.id;inventory=factory.items.total();factory.enabled=false;
        Events.on(mindustry.game.EventType.UnitUnloadEvent.class,event->{if(event.unit==produced)releases++;});
        Vars.world.tile(87,26).setNet(Blocks.copperWall,Team.sharded,0);
        for(int n=0;n<160;n++)factory.moveOutPayload();require(!produced.isAdded()&&factory.payload!=null,"blocked native server factory waits");
        Vars.world.tile(87,26).setNet(Blocks.air,Team.sharded,0);
    }
    boolean ready(){
        if(produced==null||!produced.isAdded())return false;
        produced.controller(new mindustry.entities.units.AIController(){@Override public void updateUnit(){unit.vel.setZero();unit.controlWeapons(false,false);}});
        var pose=network.fixtureWorld().unitState(produced);
        require(releases==1&&factory.payload==null&&factory.items.total()==inventory,"one authoritative release and one resource charge");
        require(pose!=null&&Math.abs(pose.z()-pose.halfHeight())<.03&&!produced.onSolid(),"server boat initialized on navigable water");return true;
    }
    boolean clientReady(){
        var unit=Groups.unit.getByID(id);if(unit==null)return false;var pose=network.fixtureWorld().unitState(unit);
        return unit.type==UnitTypes.risso&&unit.team==Team.sharded&&!unit.dead&&pose!=null&&!pose.flying()&&Math.abs(pose.z()-pose.halfHeight())<.03;
    }
    void plan(DataOutputStream out)throws IOException{out.writeInt(id);}
    void accept(DataInputStream in)throws IOException{id=in.readInt();}
    void report(DataOutputStream out)throws IOException{out.writeInt(id);out.writeBoolean(clientReady());}
    void verify(DataInputStream in,boolean current)throws IOException{int observed=in.readInt();boolean ok=in.readBoolean();if(current)require(observed==id&&ok&&releases==1,"both clients observe the one healthy native factory boat at water Z");}
    boolean clientReady(fpv.render.FpvWorldRenderer renderer){
        if(!clientReady()||renderer==null)return false;var boat=Groups.unit.getByID(id);
        var camera=new arc.graphics.g3d.Camera3D();camera.resize(arc.Core.graphics.getWidth(),arc.Core.graphics.getHeight());camera.near=.025f;camera.far=200;camera.fov=60;
        camera.position.set(86,4,-20);camera.lookAt(86,.2f,-26);camera.update();renderer.render(camera);
        if(!renderer.wasUnitSubmitted(boat)||renderer.unitGeometry(boat.type.region)==null)return false;
        arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(System.getProperty("fpv.netSmokeRole")+"-factory-boat.png"));return true;
    }
    private static void require(boolean ok,String label){if(!ok)throw new IllegalStateException("Naval production network: "+label);}
}
