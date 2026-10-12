package fpv.net;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import fpv.render.FpvWorldRenderer;
import fpv.units.ground.GroundUnitProfiles;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Explicit real-process lab extension. Production channel/pose replication supplies every state under test. */
public final class GroundNetworkSmokeChecks {
    private static final GroundUnitProfiles.Settings HOST=new GroundUnitProfiles.Settings(2.8,5.6,11.2,22.4,.37,3.5);
    private final FpvNetwork network;
    private final NavalNetworkSmokeChecks naval;
    private final Unit[] serverUnits=new Unit[2];
    private final int[] ids={-1,-1},types={-1,-1};
    private final double[] heights=new double[2];
    private boolean serverStarted,clientVerified;
    private Unit pitchMech,pitchTarget;
    private int pitchId=-1;
    private double expectedPitch;
    public GroundNetworkSmokeChecks(FpvNetwork network){
        if(System.getProperty("fpv.netSmokeRole","").isEmpty())throw new IllegalStateException("Explicit network lab required");
        this.network=network;naval=new NavalNetworkSmokeChecks(network);network.registerStateChannel("lab-ground",this::plan,this::acceptPlan);
    }
    public void serverSetup(){
        require(Vars.net.server()&&Vars.headless,"dedicated lab server required");
        Core.settings.put("fpv-ground-atrax-extension",28);Core.settings.put("fpv-ground-spiroct-extension",56);
        Core.settings.put("fpv-ground-arkyid-extension",112);Core.settings.put("fpv-ground-toxopid-extension",224);
        Core.settings.put("fpv-green-walker-height-percent",350);Core.settings.put("fpv-ground-min-speed-percent",37);GroundUnitProfiles.refresh();
        for(int i=0;i<2;i++){
            int x=28+i*12,y=78;Vars.world.tile(x,y).setNet(Blocks.duo,Team.sharded,0);
            Unit unit=(i==0?UnitTypes.atrax:UnitTypes.toxopid).create(Team.sharded);
            unit.set(x*Vars.tilesize,y*Vars.tilesize);unit.rotation=90;unit.controller(new Stationary());unit.add();serverUnits[i]=unit;
        }
        pitchMech=UnitTypes.dagger.create(Team.sharded);pitchMech.set(52*8,78*8);pitchMech.rotation=0;pitchMech.controller(new Stationary());pitchMech.add();
        pitchTarget=UnitTypes.flare.create(Team.crux);pitchTarget.set(60*8,78*8);pitchTarget.controller(new Stationary());pitchTarget.add();
        for(var mount:pitchMech.mounts){mount.target=pitchTarget;mount.aimX=pitchTarget.x;mount.aimY=pitchTarget.y;}
        naval.setup();serverStarted=true;
    }
    public boolean serverReady(){
        if(!serverStarted||!GroundUnitProfiles.settings().equals(HOST))return false;
        for(Unit unit:serverUnits){
            Unit3DState pose=network.fixtureWorld().unitState(unit);var profile=GroundUnitProfiles.profile(unit);
            if(pose==null||pose.flying()||pose.z()-pose.halfHeight()-profile.normalStance()<.10)return false;
        }
        var gun=network.fixtureCombat().mechWeapons().visual(pitchMech,pitchMech.mounts[0]);if(gun==null||gun.pitch()<=5)return false;
        return naval.ready();
    }
    public void serverVerify(){require(serverReady(),"authoritative raised grounded poses remain valid");require(GroundUnitProfiles.settings().equals(HOST),"forged client settings cannot replace host values");}
    /** Deliberately disagree with every host setting without touching the authoritative remote snapshot. */
    public void differingClientPreferences(){
        if(!Vars.net.client())return;
        for(String type:new String[]{"atrax","spiroct","arkyid","toxopid"})Core.settings.put("fpv-ground-"+type+"-extension",1);
        Core.settings.put("fpv-green-walker-height-percent",100);Core.settings.put("fpv-ground-min-speed-percent",95);GroundUnitProfiles.refresh();
    }
    public boolean clientReady(FpvWorldRenderer renderer){
        if(clientVerified)return true;
        if(renderer==null||!Vars.net.client()||!GroundUnitProfiles.settings().equals(HOST))return false;
        for(int i=0;i<2;i++){
            Unit unit=Groups.unit.getByID(ids[i]);if(unit==null||unit.type.id!=types[i])return false;
            Unit3DState pose=network.fixtureWorld().unitState(unit);
            if(pose==null||pose.flying()||Math.abs(pose.z()-heights[i])>.06||pose.z()<=.7)return false;
        }
        Unit mech=Groups.unit.getByID(pitchId);if(mech==null||mech.type!=UnitTypes.dagger)return false;
        var remote=network.fixtureCombat().mechWeapons().remotePitch(mech,mech.mounts[0]);
        var gun=network.fixtureCombat().mechWeapons().visual(mech,mech.mounts[0]);
        if(remote==null||gun==null||Math.abs(remote-expectedPitch)>.15||Math.abs(gun.pitch()-remote)>.001)return false;
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());
        camera.position.set(40,9,-66);camera.lookAt(34,1,-78);camera.near=.05f;camera.far=1000;camera.fov=52;camera.update();
        renderer.render(camera);
        for(int id:ids)if(!renderer.groundFamily().wasSubmitted(Groups.unit.getByID(id)))return false;
        require(Core.settings.getInt("fpv-ground-atrax-extension",0)==1&&Core.settings.getInt("fpv-ground-min-speed-percent",0)==95&&Core.settings.getInt("fpv-green-walker-height-percent",0)==100,
            "client preferences intentionally disagree with authoritative settings");
        require(GroundUnitProfiles.settings().equals(HOST),"server settings override conflicting local preferences");
        arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(System.getProperty("fpv.netSmokeRole")+"-ground-family.png"));
        camera.position.set(52,2,-72);camera.lookAt(52,1,-78);camera.update();renderer.render(camera);
        if(!renderer.groundFamily().wasSubmitted(mech))return false;
        arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(System.getProperty("fpv.netSmokeRole")+"-mech-pitch.png"));
        if(!naval.clientReady(renderer))return false;clientVerified=true;return true;
    }
    public void writeReport(DataOutputStream out)throws IOException{
        out.write(GroundUnitProfiles.encode(GroundUnitProfiles.settings()));
        for(int i=0;i<2;i++){
            Unit unit=Groups.unit.getByID(ids[i]);Unit3DState pose=network.fixtureWorld().unitState(unit);
            out.writeInt(ids[i]);out.writeDouble(pose.z());out.writeBoolean(pose.flying());
        }
        var mech=Groups.unit.getByID(pitchId);out.writeInt(pitchId);out.writeDouble(network.fixtureCombat().mechWeapons().visual(mech,mech.mounts[0]).pitch());
        naval.report(out);
    }
    public void readAndVerifyReport(DataInputStream in,boolean currentStage)throws IOException{
        var settings=GroundUnitProfiles.decode(in.readNBytes(GroundUnitProfiles.ENCODED_BYTES));
        for(int i=0;i<2;i++){
            int id=in.readInt();double z=in.readDouble();boolean flying=in.readBoolean();
            if(currentStage){var pose=network.fixtureWorld().unitState(serverUnits[i]);require(settings.equals(HOST)&&id==serverUnits[i].id&&Double.isFinite(z)&&Math.abs(z-pose.z())<.06&&!flying,
                "remote client reports actual server-raised grounded XYZ with server settings");}
        }
        int id=in.readInt();double angle=in.readDouble();
        if(currentStage)require(id==pitchMech.id&&Double.isFinite(angle)&&angle>5&&Math.abs(angle-network.fixtureCombat().mechWeapons().visual(pitchMech,pitchMech.mounts[0]).pitch())<2,
            "both clients render server mech pitch at actual XYZ target height");
        naval.verify(in,currentStage);
    }
    private byte[] plan(){return NetProtocol.bytes(out->{out.writeBoolean(serverStarted);if(serverStarted){for(Unit unit:serverUnits){
        var pose=network.fixtureWorld().unitState(unit);out.writeInt(unit.id);out.writeInt(unit.type.id);out.writeDouble(pose==null?0:pose.z());
    }out.writeInt(pitchMech.id);var gun=network.fixtureCombat().mechWeapons().visual(pitchMech,pitchMech.mounts[0]);out.writeDouble(gun==null?0:gun.pitch());naval.plan(out);}});}
    private void acceptPlan(byte[] bytes){NetProtocol.read(bytes,in->{boolean active=in.readBoolean();if(active){for(int i=0;i<2;i++){ids[i]=in.readInt();types[i]=in.readInt();heights[i]=in.readDouble();if(!Double.isFinite(heights[i]))throw new IllegalArgumentException("Ground fixture height");}pitchId=in.readInt();expectedPitch=in.readDouble();if(!Double.isFinite(expectedPitch))throw new IllegalArgumentException("Ground fixture mech angle");naval.accept(in);}return true;});}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Ground network smoke: "+message);}
    private static final class Stationary implements UnitController{
        private Unit unit;
        @Override public Unit unit(){return unit;}
        @Override public void unit(Unit value){unit=value;}
        @Override public void updateUnit(){unit.vel.setZero();unit.controlWeapons(false,false);}
    }
}
