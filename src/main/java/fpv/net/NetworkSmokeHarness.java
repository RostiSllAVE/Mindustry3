package fpv.net;

import arc.Core;
import arc.Events;
import arc.struct.StringMap;
import arc.struct.Queue;
import arc.util.Log;
import arc.util.serialization.Jval;
import fpv.flight.FlightController;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.entities.units.BuildPlan;
import mindustry.type.UnitType;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in localhost-only real-process integration fixture; refuses ordinary player profiles. */
public final class NetworkSmokeHarness {
    private final FpvNetwork network;
    private final fpv.render.FpvWorldRenderer renderer;
    private fpv.render.NetworkSupportEffectsChecks supportChecks;
    private GroundNetworkSmokeChecks groundChecks;
    private NetworkCreatorSmokeChecks creatorChecks;
    private LayerNetworkSmokeChecks layerChecks;
    private NavalProductionNetworkSmokeChecks navalProduction;
    private final boolean navalOnly=Boolean.getBoolean("fpv.navalProduction.netSmoke");
    private final String role=System.getProperty("fpv.netSmokeRole","");
    private final long started=System.nanoTime();
    private final Jval result=Jval.newObject();
    private Path output;
    private boolean done,forged,clientReported,buildSent,mismatchSent,reconnectQueued,wallForged,wallAttackChecked;
    private int stage,checks,stageSeen=-1,unitA=-1,unitB=-1,serverInputs,serverRejected;
    private double stageTime,startY,startZ,healthBefore,lastWrite,clientStageTime,expectedZ,expectedOwnA,expectedOwnB;
    private int lastReport=-1,worldWidth=96;private long oldEpoch;private float ownHealthBefore;
    private float baseWallHp,stableWallHp,expectedWallHp,expectedWallMax,expectedBaseWallHp;
    private double expectedWallTop,expectedWallBase;private boolean expectedWall;
    private Building wall;
    private mindustry.world.blocks.defense.ForceProjector.ForceBuild force;
    private boolean forceForged,forceAttackChecked,expectedForce,expectedForceBroken,groundForged,forceAircraftForged;
    private float forceBaseHp,expectedForceBuildup,expectedForceBaseHp;
    private double expectedForceRadius;
    private fpv.physics.CrashSystem ramA,ramB;
    private final Set<String> reported=new HashSet<>();
    private Unit a,b;
    private Player playerA,playerB;
    public NetworkSmokeHarness(FpvNetwork network){this(network,null);}
    public NetworkSmokeHarness(FpvNetwork network,fpv.render.FpvWorldRenderer renderer){this.network=Objects.requireNonNull(network);this.renderer=renderer;}
    public void install(){
        if(role.isEmpty())return;
        try{
            if(!Set.of("server","client-a","client-b").contains(role))throw new IllegalArgumentException("Unknown network smoke role");
            String explicitLab=System.getProperty("fpv.netSmokeRoot","");
            if(explicitLab.isBlank())throw new IllegalStateException("Explicit isolated network lab directory required");
            Path lab=Path.of(explicitLab).toRealPath();
            if(!Files.isDirectory(lab)||!Vars.dataDirectory.file().toPath().toRealPath().startsWith(lab))throw new IllegalStateException("Network smoke requires the explicitly selected isolated lab profile");
            output=lab.resolve(role+"-results.json");
            put("role",role);put("status","running");put("gameVersion",fpv.platform.HostCompatibility.currentVersion());put("modSha256",network.fingerprint());put("physicalInputTested",false);write();
            network.registerStateChannel("lab",this::serverPlan,this::acceptPlan);
            if(navalOnly){
                navalProduction=new NavalProductionNetworkSmokeChecks(network);
                network.registerStateChannel("lab-naval",()->NetProtocol.bytes(navalProduction::plan),bytes->NetProtocol.read(bytes,in->{navalProduction.accept(in);return true;}));
                if(role.equals("server"))Vars.netServer.addBinaryPacketHandler("fpv7-lab-naval",(player,bytes)->{
                    if(stage!=401)return;try{NetProtocol.read(bytes,in->{navalProduction.verify(in,true);reported.add(player.name);return true;});}catch(Throwable error){fail(error);}
                });
                put("scope","isolated native naval factory server authority and two-client XYZ/GPU replication");
            }
            if(!navalOnly){
                groundChecks=new GroundNetworkSmokeChecks(network);
                creatorChecks=new NetworkCreatorSmokeChecks(network);
                layerChecks=new LayerNetworkSmokeChecks(network);
            }
            if(role.equals("server")){
                if(!Vars.headless)throw new IllegalStateException("Server smoke requires official headless server");
                Vars.netServer.addBinaryPacketHandler("fpv7-lab",(player,bytes)->receiveReport(player,bytes));
                Core.app.post(this::startServer);
            }else{
                if(Vars.headless)throw new IllegalStateException("Client smoke requires native SDL client");
                network.fixtureControls(this::clientControls);
                network.fixtureTransmit(()->!(role.equals("client-a")&&stage==18&&elapsed()-clientStageTime>.3));
                Core.settings.put("name",role.equals("client-a")?"FPV-Lab-A":"FPV-Lab-B");Vars.player.name=Core.settings.getString("name");
                Core.settings.put("backgroundpause",false);Core.settings.put("soundvol",0);Core.settings.put("musicvol",0);
                Core.app.post(this::connect);
            }
            Events.run(Trigger.update,this::update);
        }catch(Throwable failure){fail(failure);}
    }
    private void startServer(){try{
        Vars.logic.reset();Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.pvp=true;Vars.state.rules.enemyCoreBuildRadius=0;
        Vars.state.rules.fog=false;Vars.state.rules.staticFog=false;Vars.state.rules.unitCap=1000;
        Vars.state.map=new mindustry.maps.Map(StringMap.of("name","FPV isolated network fixture"));
        Vars.world.loadGenerator(96,96,tiles->{for(int y=0;y<96;y++)for(int x=0;x<96;x++)tiles.set(x,y,new Tile(x,y,Blocks.sand,Blocks.air,Blocks.air));
            tiles.get(12,12).setBlock(Blocks.coreShard,Team.sharded,0);tiles.get(82,82).setBlock(Blocks.coreShard,Team.crux,0);});
        Vars.logic.play();Vars.net.host(Integer.getInteger("fpv.netSmokePort",16567));stageTime=elapsed();
        put("listening",true);write();Log.info("[FPV-NET-TEST] Listening localhost fixture port @",Integer.getInteger("fpv.netSmokePort",16567));
    }catch(Throwable failure){fail(failure);}}
    private void update(){
        if(done)return;
        try{
            if(elapsed()>360)throw new AssertionError("Network fixture timed out at stage "+stage+" status "+network.status());
            if(role.equals("server"))serverTick();else clientTick();
            if(elapsed()-lastWrite>1){
                lastWrite=elapsed();put("stage",stage);put("acceptedInputs",network.acceptedInputs());put("rejectedPackets",network.rejectedPackets());put("receivedSnapshots",network.receivedSnapshots());put("networkStatus",network.status());
                if(!navalOnly&&stage>=36)result.add("creatorFlight",creatorChecks.result());
                if(!navalOnly&&stage>=37)result.add("layeredConstruction",layerChecks.result());write();
            }
        }catch(Throwable failure){fail(failure);}
    }
    private void serverTick(){
        if(!Vars.net.server())return;
        if(!navalOnly&&stage>=37)layerChecks.diagnostics();
        if(stage==0){
            for(Player p:Groups.player){if(p.name.equals("FPV-Lab-A"))playerA=p;if(p.name.equals("FPV-Lab-B"))playerB=p;}
            if(playerA==null||playerB==null||network.acceptedInputs()<5)return;
            playerA.team(Team.sharded);playerB.team(Team.crux);
            a=UnitTypes.flare.create(Team.sharded);b=UnitTypes.flare.create(Team.crux);a.set(40*8,40*8);b.set(60*8,40*8);a.add();b.add();playerA.unit(a);playerB.unit(b);
            unitA=a.id;unitB=b.id;next(1);
            if(navalOnly){navalProduction.setup();next(401);}
        }else if(stage==401){
            if(elapsed()-stageTime>40)throw new AssertionError("Native naval production clients timed out");
            if(navalProduction.ready()&&reported.size()==2){
                put("nativeNavalFactoryBothClients",true);put("nativeNavalSingleReleaseAndCost",true);next(402);
                put("status","passed");write();done=true;arc.util.Timer.schedule(()->Core.app.exit(),3);
            }
        }else if(stage==1&&elapsed()-stageTime>1){
            var flight=network.fixtureFlight(playerA);if(flight==null||network.fixtureFlight(playerB)==null)return;
            startY=flight.state().z;startZ=flight.state().y;next(2);
        }else if(stage==2&&elapsed()-stageTime>1.5){
            var flight=network.fixtureFlight(playerA);check(flight.state().z<startY-.5,"remote forward input moved server XY");check(flight.state().y>startZ+.5,"remote climb input moved server Z");
            check(network.fixtureFlight(playerB).state().y<flight.state().y-.3,"pilots have independent authoritative heights");
            next(3);serverRejected=network.rejectedPackets();
        }else if(stage==3&&elapsed()-stageTime>1.2){
            check(a.x<96*8&&a.y<96*8,"forged native coordinates cannot teleport server unit");
            check(network.rejectedPackets()>=serverRejected+2,"forged epoch and damage-state packets rejected");
            next(4);
        }else if(stage==4&&elapsed()-stageTime>.6){
            position(playerA,40,40,4);position(playerB,40,46,12);healthBefore=b.health;
            fire();next(5);
        }else if(stage==5&&elapsed()-stageTime>.8){
            check(b.health==healthBefore,"same XY trajectory misses a higher aircraft");
            position(playerA,40,40,4);position(playerB,40,46,4);healthBefore=b.health;fire();next(6);
        }else if(stage==6&&elapsed()-stageTime>.8){
            check(b.health<healthBefore&&!b.dead,"server native projectile deals one XYZ target hit");put("targetHealth",b.health);put("targetHealthBefore",healthBefore);next(7);
        }else if(stage==7&&reported.contains("FPV-Lab-A")&&reported.contains("FPV-Lab-B")){
            check(network.sessions()==2,"both real clients retain controlled sessions");put("serverAuthoritativeXYZ",true);put("nativePositionForgeryRejected",true);put("verticalMissAndHit",true);put("twoClientAgreement",true);next(8);
            a=possess(playerA,UnitTypes.dagger,20,20);b=possess(playerB,UnitTypes.alpha,50,30);unitA=a.id;unitB=b.id;
            playerB.core().items.add(Items.copper,1000);
        }else if(stage==8&&elapsed()-stageTime>.6){startY=a.y;next(9);
        }else if(stage==9&&elapsed()-stageTime>1.2){
            check(a.y>startY+2,"mode3 remote native ground movement");var pose=network.fixtureWorld().unitState(a);check(pose!=null&&!pose.flying()&&pose.z()<1,"ground possession stays on ground");put("nativeGroundControl",true);next(10);
        }else if(stage==10&&elapsed()-stageTime>.8){startY=a.y;next(11);
        }else if(stage==11&&elapsed()-stageTime>.8){
            check(Math.abs(a.y-startY)<1,"inactive mode3 axes cannot continue commanded movement");check(!playerA.shooting,"inactive mode3 cannot shoot");
            startY=b.x;startZ=network.fixtureFlight(playerB).state().y;serverInputs=(int)Vars.state.tick;next(12);
        }else if(stage==12&&elapsed()-stageTime>1.2){
            check(b.x>startY+2&&network.fixtureFlight(playerB).state().y>startZ+.3,"server Build mode precision navigation");check(Vars.state.tick-serverInputs>30,"multiplayer Build mode keeps world time running");put("multiplayerBuildNavigation",true);next(13);
        }else if(stage==13&&Vars.world.tile(52,34).block()==Blocks.conveyor&&reported.size()==2){
            check(Vars.world.tile(52,34).team()==Team.crux,"native remote build plan preserves team");put("nativeRemoteBuilding",true);
            startY=b.y;startZ=network.fixtureFlight(playerB).state().y;next(14);
        }else if(stage==14&&elapsed()-stageTime>1.2){
            check(b.y>startY+2,"flying F5/mode3 native-speed travel");check(Math.abs(network.fixtureFlight(playerB).state().y-startZ)<.2,"native2D flying mode retains altitude");put("native2DFlightControl",true);
            serverRejected=network.rejectedPackets();next(15);
        }else if(stage==15){
            Player replacement=null;for(Player p:Groups.player)if(p.name.equals("FPV-Lab-B")&&p!=playerB)replacement=p;
            if(replacement!=null&&network.fixtureFlight(replacement)!=null){check(network.rejectedPackets()>serverRejected,"mismatched mod fingerprint rejected before reconnect");playerB=replacement;check(playerB.con!=null&&!playerB.con.kicked,"matching client reconnect accepted");put("mismatchAndReconnect",true);next(16);}
        }else if(stage==16&&elapsed()-stageTime>.7){
            oldEpoch=network.fixtureEpoch();reloadWorld();check(network.fixtureEpoch()!=oldEpoch,"new native world gets a fresh protocol epoch");next(17);
        }else if(stage==17&&reported.size()==2){
            check(Vars.world.width()==104&&network.sessions()==2,"native world reload retains two authenticated pilots");put("worldReload",true);
            serverInputs=network.acceptedInputs();startZ=network.fixtureFlight(playerA).state().y;next(18);
        }else if(stage==18&&elapsed()-stageTime>2.2){
            var s=network.fixtureFlight(playerA).state();check(network.acceptedInputs()>serverInputs+5&&s.y>startZ+.2,"new-world controls advance sequence and actually move the pilot");
            check(Math.sqrt(s.vx*s.vx+s.vy*s.vy+s.vz*s.vz)<.5,"input silence transitions to server stable hold");put("inputTimeoutHold",true);
            Core.settings.put("fpv-ram-cap-units",10);
            position(playerA,40,40,4);position(playerB,40,44,4);ownHealthBefore=a.health;healthBefore=b.health;
            ramA=network.fixtureCrash(playerA);ramB=network.fixtureCrash(playerB);
            check(ramA!=null&&ramB!=null,"both live pilot ram solvers available");next(19);
        }else if(stage==19){
            var ca=ramA;var cb=ramB;
            check(!a.dead&&!b.dead,"controlled low-speed ram must remain nonlethal: A="+a.health+", B="+b.health);
            if(ca.lastFeedback()!=null||cb.lastFeedback()!=null){
                check(ca.lastFeedback()==null||cb.lastFeedback()==null,"one controller owns reciprocal ram trauma");
                var feedback=ca.lastFeedback()!=null?ca.lastFeedback():cb.lastFeedback();check(feedback.sequence()==1,"first contact damage once");
                check(a.health<ownHealthBefore&&b.health<healthBefore&&!a.dead&&!b.dead,"both real pilots receive bounded native ram damage");
                check(ownHealthBefore-a.health<=10.02&&healthBefore-b.health<=10.02,"server ordinary ram cap limits each actual native HP loss to ten");put("serverRamCap",true);
                check(Math.abs((ownHealthBefore-a.health)-(healthBefore-b.health))<.02,"equal-mass ram trauma remains symmetric");
                put("ramDamageA",ownHealthBefore-a.health);put("ramDamageB",healthBefore-b.health);next(20);
            }
        }else if(stage==20&&reported.size()==2&&elapsed()-stageTime>.8){
            var ca=ramA;var cb=ramB;check((ca.lastFeedback()==null?0:ca.lastFeedback().sequence())+(cb.lastFeedback()==null?0:cb.lastFeedback().sequence())==1,"no duplicated reciprocal ram damage after settling");
            var sa=network.fixtureWorld().unitState(a);var sb=network.fixtureWorld().unitState(b);check(sa!=null&&sb!=null&&Double.isFinite(sa.distanceSquared(sb)),"finite authoritative contact poses");put("serverRamOnce",true);
            Core.settings.put("fpv-wall-hp-percent",85);Core.settings.put("fpv-wall-segments",5);Core.settings.put("fpv-wall-regen-delay",2);Core.settings.put("fpv-wall-regen-permille",100);Core.settings.put("fpv-leg-step-percent",175);
            Vars.world.tile(66,50).setNet(Blocks.copperWall,Team.crux,0);wall=Vars.world.tile(66,50).build;network.fixtureProjection().update(0);baseWallHp=wall.health;
            var volume=network.fixtureProjection().volume(wall);position(playerA,66,45,(volume.baseZ()+volume.topZ())*.5);position(playerB,74,45,4);next(21);
        }else if(stage==21&&elapsed()-stageTime>.8){
            fire();next(22);
        }else if(stage==22&&elapsed()-stageTime>.8){
            var walls=network.fixtureProjection();var volume=walls.volume(wall);check(volume.hp()<volume.maxHp(),"native server projectile damages upper projection");check(wall.health==baseWallHp,"projected hit preserves native base HP");
            var contact=walls.trace(Team.sharded,volume.x()-2,volume.y(),volume.baseZ()+.03,volume.x()+2,volume.y(),volume.baseZ()+.03,0);
            check(contact!=null&&walls.hit(contact,null,volume.maxHp()),"server collapses the shared upper pool");check(walls.volume(wall).hp()==0,"all upper segments collapse together");next(24);
        }else if(stage==24&&reported.size()==2){put("projectionCollapseReplicated",true);next(25);
        }else if(stage==25&&network.fixtureProjection().volume(wall).hp()>20){
            Core.settings.put("fpv-wall-regen-permille",0);next(23);
        }else if(stage==23&&elapsed()-stageTime>.3&&network.fixtureProjection().settings().regenRate()==0){
            // Settings are intentionally cached once per simulation frame. Capture only after
            // the host has applied the stopped regeneration rate, before the forged client write.
            stableWallHp=network.fixtureProjection().volume(wall).hp();serverRejected=network.rejectedPackets();next(26);
        }else if(stage==26&&reported.size()==2&&elapsed()-stageTime>.8){
            check(network.rejectedPackets()>serverRejected,"client forged projection channel rejected");check(Math.abs(network.fixtureProjection().volume(wall).hp()-stableWallHp)<.0001,"client cannot replace authoritative pool HP: "+network.fixtureProjection().volume(wall).hp()+" versus "+stableWallHp);check(wall.health==baseWallHp,"native base HP stays unchanged across collapse and regeneration");
            put("projectionRegenerationReplicated",true);put("projectionServerSettings",true);put("projectionClientWritesRejected",true);next(27);
            Vars.world.tile(48,62).setNet(Blocks.forceProjector,Team.crux,0);force=(mindustry.world.blocks.defense.ForceProjector.ForceBuild)Vars.world.tile(48,62).build;
            force.broken=false;force.buildup=0;force.radscl=.45f;force.warmup=1;force.efficiency=1;force.phaseHeat=0;force.hit=0;
            // Freeze this fixture's native resource tick between explicit lifecycle operations so
            // two asynchronous clients can acknowledge exactly the same native pool values.
            float delta=arc.util.Time.delta;arc.util.Time.delta=1000;try{force.sleep();}finally{arc.util.Time.delta=delta;}
            forceBaseHp=force.health;network.fixtureForceProjectors().update(0);position(playerA,48,53,3);position(playerB,75,73,4);
        }else if(stage==27&&elapsed()-stageTime>.8){
            check(network.fixtureForceProjectors().volume(force)!=null,"server native ForceProjector has real protective volume");fire();next(28);
        }else if(stage==28&&elapsed()-stageTime>.8){
            check(force.buildup>0&&force.health==forceBaseHp,"actual remote pilot native bullet absorbed by server ForceProjector");
            float before=force.buildup;var laser=new mindustry.entities.bullet.LaserBulletType(23);laser.length=160;laser.init();
            var ray=network.fixtureCombat().launchPhysical(laser,a,a.team,new fpv.world.WorldCoordinates.Point(48,53,3),new fpv.world.WorldCoordinates.Point(0,1,0),-1);
            check(ray!=null&&force.buildup>before&&force.health==forceBaseHp,"server managed laser blocked before native base HP");Core.settings.put("fpv-force-aircraft-collision",true);next(29);
        }else if(stage==29&&reported.size()==2){
            put("forceProjectorHitReplicated",true);var fields=network.fixtureForceProjectors();var v=fields.volume(force);
            var contact=fields.trace(Team.sharded,v.x(),v.y()-v.radius()-1,3,v.x(),v.y()+1,3,0);
            check(fields.hit(contact,null,100000)&&force.broken&&force.health==forceBaseHp,"server native shield pool breaks independently of base");Core.settings.put("fpv-force-aircraft-collision",false);put("forceAircraftOnBothClients",true);next(30);
        }else if(stage==30&&reported.size()==2){
            check(network.fixtureForceProjectors().volume(force)==null,"broken server shell absent");put("forceProjectorBreakReplicated",true);
            force.buildup=0;force.efficiency=1;force.warmup=1;force.updateTile();force.radscl=.45f;
            check(!force.broken&&network.fixtureForceProjectors().volume(force)!=null,"native server update recovers shield");serverRejected=network.rejectedPackets();next(31);
        }else if(stage==31&&reported.size()==2&&elapsed()-stageTime>.8){
            check(network.rejectedPackets()>=serverRejected+2,"forged force-projectors and force-aircraft state channels rejected");
            check(!network.fixtureForceProjectors().aircraftCollisionEnabled(),"client cannot enable host aircraft barrier");put("forceAircraftHostOnOffReplicated",true);put("forceAircraftClientWriteRejected",true);
            check(force.buildup<=0&&!force.broken&&force.health==forceBaseHp,"client cannot damage or replace server shield state");
            put("forceProjectorRecoveryReplicated",true);put("forceProjectorClientWritesRejected",true);
            supportChecks=new fpv.render.NetworkSupportEffectsChecks(network.supportEffects());supportChecks.serverSetup();next(32);
        }else if(stage==32&&supportChecks.serverReady()){
            put("nativeNetworkHealing",true);next(33);
        }else if(stage==33&&reported.size()==2){
            put("healingBeamBothClientGpu",true);groundChecks.serverSetup();next(34);
        }else if(stage==34&&elapsed()-stageTime>.8&&groundChecks.serverReady()){
            serverRejected=network.rejectedPackets();put("groundFamilyServerRaised",true);next(35);
        }else if(stage==35&&reported.size()==2&&elapsed()-stageTime>.8){
            check(network.rejectedPackets()>serverRejected,"forged ground-profiles state channel rejected");groundChecks.serverVerify();
            put("groundProfilesServerSettings",true);put("groundProfilesClientWritesRejected",true);put("groundFamilyBothClientGpu",true);creatorChecks.serverBegin(playerA,playerB);next(36);
        }else if(stage==36&&creatorChecks.serverTick()){
            result.add("creatorFlight",creatorChecks.result());layerChecks.serverBegin(playerA,playerB);next(37);
        }else if(stage==37&&layerChecks.serverTick()){
            result.add("layeredConstruction",layerChecks.result());next(38);
            put("status","passed");put("checks",checks);write();done=true;Log.info("[FPV-NET-TEST] SERVER PASS @ assertions",checks);arc.util.Timer.schedule(()->Core.app.exit(),3);
        }
    }
    private Unit possess(Player player,UnitType type,double x,double y){Unit old=player.unit();Unit next=type.create(player.team());next.set((float)x*8,(float)y*8);next.add();player.unit(next);if(old!=null&&old!=next)old.remove();return next;}
    private void reloadWorld(){
        playerA.clearUnit();playerB.clearUnit();Vars.logic.reset();Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.pvp=true;Vars.state.rules.enemyCoreBuildRadius=0;Vars.state.rules.unitCap=1000;
        Vars.state.map=new mindustry.maps.Map(StringMap.of("name","FPV network reloaded native world"));
        Vars.world.loadGenerator(104,96,tiles->{for(int y=0;y<96;y++)for(int x=0;x<104;x++)tiles.set(x,y,new Tile(x,y,Blocks.sand,Blocks.air,Blocks.air));tiles.get(12,12).setBlock(Blocks.coreShard,Team.sharded,0);tiles.get(82,82).setBlock(Blocks.coreShard,Team.crux,0);});
        playerA.team(Team.sharded);playerB.team(Team.crux);playerA.add();playerB.add();a=possess(playerA,UnitTypes.flare,40,40);b=possess(playerB,UnitTypes.flare,60,40);unitA=a.id;unitB=b.id;Vars.logic.play();
        Call.worldDataBegin(playerA.con);Call.worldDataBegin(playerB.con);Vars.netServer.sendWorldData(playerA);Vars.netServer.sendWorldData(playerB);
    }
    private void position(Player player,double x,double y,double z){
        var s=network.fixtureFlight(player).state();var u=player.unit();u.set((float)x*8,(float)y*8);u.vel.setZero();u.rotation=90;s.x=x;s.y=z;s.z=-y;s.vx=s.vy=s.vz=s.wx=s.wy=s.wz=0;s.qx=s.qy=s.qz=0;s.qw=1;
    }
    private void fire(){var type=UnitTypes.flare.weapons.first().bullet;type.create(a,a.team,a.x,a.y,90);}
    private void clientTick(){
        if(!navalOnly&&stage>=37)layerChecks.clientMaintainConnection();
        if(!navalOnly&&stage>=37)layerChecks.diagnostics();
        if(mismatchSent&&!Vars.net.active()&&!reconnectQueued){reconnectQueued=true;put("mismatchDisconnected",true);arc.util.Timer.schedule(()->Core.app.post(this::connect),.5f);}
        if(!Vars.net.client()||!network.allowed()||Vars.player.dead())return;
        if(navalOnly){
            if(stage==401&&lastReport!=stage&&navalProduction.clientReady(renderer)){
                Call.serverBinaryPacketReliable("fpv7-lab-naval",NetProtocol.bytes(navalProduction::report));lastReport=stage;put("nativeNavalFactoryAuthoritativeXYZAndGpu",true);
            }
            if(stage==402){put("status","passed");write();done=true;Core.app.exit();}return;
        }
        if(stage>=3&&!forged&&role.equals("client-a")){
            forged=true;var packet=new ClientSnapshotCallPacket();packet.snapshotID=1_000_000;packet.unitID=Vars.player.unit().id;packet.x=1_000_000;packet.y=1_000_000;packet.rotation=90;packet.pointerX=0;packet.pointerY=10000;packet.viewWidth=800;packet.viewHeight=600;
            Vars.net.send(packet,false);
            Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.INPUT,network.fixtureEpoch()+1,2_000_000,out->NetProtocol.controls(out,NetworkControls.hold())));
            Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.STATE,network.fixtureEpoch(),2_000_001,out->out.writeDouble(-100000)));
            put("forgeriesSent",true);
        }
        if(stage==13&&role.equals("client-b")&&!buildSent){
            buildSent=true;var packet=new ClientSnapshotCallPacket();packet.snapshotID=2_000_000;packet.unitID=Vars.player.unit().id;packet.rotation=90;packet.pointerX=52*8;packet.pointerY=34*8;packet.building=true;packet.plans=new Queue<>();packet.plans.addLast(new BuildPlan(52,34,0,Blocks.conveyor));packet.viewWidth=800;packet.viewHeight=600;Vars.net.send(packet,true);put("buildPlanSent",true);
        }
        if(stage==15&&role.equals("client-b")&&!mismatchSent){mismatchSent=true;Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.HELLO,0,0,out->out.writeUTF("0".repeat(64))));put("mismatchedFingerprintSent",true);}
        if(stage==19||stage==20)Core.settings.put("fpv-ram-cap-units",20000);
        if(stage>=21&&stage<=26){Core.settings.put("fpv-wall-hp-percent",75);Core.settings.put("fpv-wall-segments",1);Core.settings.put("fpv-leg-step-percent",50);}
        if(stage==26&&role.equals("client-a")&&!wallForged){wallForged=true;Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.CHANNEL,network.fixtureEpoch(),2_000_002,out->{out.writeUTF("projection");out.writeFloat(0);}));put("projectionForgerySent",true);}
        if(stage==31&&role.equals("client-a")&&!forceForged){forceForged=true;Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.CHANNEL,network.fixtureEpoch(),2_000_003,out->{out.writeUTF("force-projectors");out.writeFloat(100000);}));put("forceProjectorForgerySent",true);}
        NetworkForceAircraftChecks.disagreeWithHost(stage);
        if(stage==31&&role.equals("client-a")&&!forceAircraftForged){forceAircraftForged=true;Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.CHANNEL,network.fixtureEpoch(),2_000_006,out->{out.writeUTF("force-aircraft");out.writeByte(1);}));put("forceAircraftForgerySent",true);}
        if(stage>=34&&stage<=35){groundChecks.differingClientPreferences();Core.settings.put("fpv-ground-range",19);Core.settings.put("fpv-dagger-range",18);Core.settings.put("fpv-naval-range",17);Core.settings.put("fpv-unit-vertical-range",16);Core.settings.put("fpv-force-aircraft-collision",true);}
        if(stage==35&&role.equals("client-a")&&!groundForged){groundForged=true;Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.CHANNEL,network.fixtureEpoch(),2_000_004,out->{out.writeUTF("ground-profiles");out.write(new byte[40]);}));put("groundProfilesForgerySent",true);}
        if(Set.of(7,13,17,20,24,26,29,30,31,33,35).contains(stage)&&lastReport!=stage){
            Unit target=Groups.unit.getByID(unitB);if(target==null||network.receivedSnapshots()<5)return;
            var own=network.fixtureWorld().unitState(Vars.player.unit());var other=network.fixtureWorld().unitState(target);
            if(own==null||other==null||Math.abs(other.z()-expectedZ)>.25||Math.abs(target.health-healthBefore)>.05||Vars.world.width()!=worldWidth)return;
            if(stage==13&&Vars.world.tile(52,34).block()!=Blocks.conveyor)return;
            // A just-received phase can still display the preceding moving pose (100ms interpolation,
            // control delivery and channel intervals). Check agreement only after stop commands settle.
            if(elapsed()-clientStageTime<.75)return;
            if(stage==24||stage==26){
                var walls=network.fixtureProjection();var build=Vars.world.tile(66,50).build;var volume=build==null?null:walls.volume(build);
                if(!expectedWall||volume==null||Math.abs(volume.hp()-expectedWallHp)>.05||Math.abs(volume.topZ()-expectedWallTop)>.001||build.health!=expectedBaseWallHp)return;
                check(walls.settings().segments()==5&&Math.abs(walls.settings().healthMultiplier()-.85)<1e-9&&Math.abs(walls.settings().legStepMultiplier()-1.75)<1e-9,"server projection settings override differing client preferences");
                if(stage==26){var contact=walls.trace(Team.sharded,volume.x()-2,volume.y(),volume.baseZ()+.01,volume.x()+2,volume.y(),volume.baseZ()+.01,0);float before=volume.hp();check(!walls.hit(contact,null,10000)&&walls.volume(build).hp()==before,"client-side damage call cannot mutate replicated pool");wallAttackChecked=true;}
                put("projectionHp",volume.hp());put("projectionTop",volume.topZ());put("nativeWallHp",build.health);
            }
            if(stage>=29&&stage<=31){
                var build=Vars.world.tile(48,62).build;var fields=network.fixtureForceProjectors();var state=fields.state(build);var volume=fields.volume(build);
                double radius=volume==null?0:volume.radius();
                if(!expectedForce||state==null||state.broken()!=expectedForceBroken||Math.abs(state.buildup()-expectedForceBuildup)>.05||Math.abs(radius-expectedForceRadius)>.001||build.health!=expectedForceBaseHp)return;
                check((volume==null)==expectedForceBroken,"client shell geometry follows authoritative native broken state");
                if(!NetworkForceAircraftChecks.clientReady(stage,fields,build,Groups.unit.getByID(unitA),Groups.unit.getByID(unitB)))return;
                put("forceAircraftHostOnOff",true);
                if(stage==31){
                    var nativeBuild=(mindustry.world.blocks.defense.ForceProjector.ForceBuild)build;float before=nativeBuild.buildup;
                    var contact=fields.trace(Team.sharded,48,53,3,48,64,3,0);
                    check(contact!=null&&!fields.hit(contact,null,100000)&&nativeBuild.buildup==before,"client local hit cannot mutate native ForceProjector buildup");forceAttackChecked=true;
                }
                put("forceProjectorBuildup",state.buildup());put("forceProjectorRadius",radius);put("forceProjectorBroken",state.broken());
            }
            if(stage==33){
                if(supportChecks==null)supportChecks=new fpv.render.NetworkSupportEffectsChecks(network.supportEffects());
                if(!supportChecks.clientReady(renderer))return;put("healingBeamAuthoritativeGpu",true);
            }
            if(stage==35){if(!groundChecks.clientReady(renderer))return;put("groundFamilyAuthoritativeGpu",true);put("groundProfilesServerSettings",true);
                check(network.fixtureCombat().unitRanges().settings().equals(fpv.combat.UnitWeaponRanges.DEFAULT),"host unit range settings override differing client preferences");
                check(!network.fixtureForceProjectors().aircraftCollisionEnabled(),"host ForceProjector aircraft OFF overrides client ON without disabling projectile field");
                put("maintenanceHostSettings",true);
            }
            double ownExpected=role.equals("client-a")?expectedOwnA:expectedOwnB;if(Math.abs(Vars.player.unit().health-ownExpected)>.05)return;
            check(network.allowed(),"exact mod handshake completed");check(network.receivedSnapshots()>=5,"real authoritative snapshots received");check(Math.abs(other.z()-expectedZ)<.25,"client target height agrees with server");
            put("targetHealth",target.health);put("targetAltitude",other.z());put("ownUnit",Vars.player.unit().id);put("otherUnit",unitB);put("checks",checks);clientReported=true;lastReport=stage;
            Call.serverBinaryPacketReliable("fpv7-lab",NetProtocol.bytes(out->{out.writeInt(stage);out.writeInt(unitB);out.writeDouble(other.z());out.writeFloat(target.health);out.writeBoolean(stage!=13||Vars.world.tile(52,34).block()==Blocks.conveyor);out.writeInt(Vars.world.width());
                out.writeBoolean(stage==24||stage==26);if(stage==24||stage==26){var v=network.fixtureProjection().volume(Vars.world.tile(66,50).build);out.writeFloat(v.hp());out.writeDouble(v.topZ());out.writeFloat(Vars.world.tile(66,50).build.health);out.writeBoolean(stage!=26||wallAttackChecked);}
                out.writeBoolean(stage>=29&&stage<=31);if(stage>=29&&stage<=31){var build=Vars.world.tile(48,62).build;var fields=network.fixtureForceProjectors();var s=fields.state(build);var v=fields.volume(build);out.writeBoolean(s.broken());out.writeFloat(s.buildup());out.writeDouble(v==null?0:v.radius());out.writeFloat(build.health);out.writeBoolean(stage!=31||forceAttackChecked);out.writeBoolean(fields.aircraftCollisionEnabled());}
                out.writeBoolean(stage==33);
                out.writeBoolean(stage==35);if(stage==35)groundChecks.writeReport(out);
            }));
        }
        if(stage==36)creatorChecks.clientTick(renderer);
        if(stage>=37)layerChecks.clientTick();
        if(stage==38&&clientReported&&creatorChecks.clientComplete()&&layerChecks.clientComplete()){result.add("creatorFlight",creatorChecks.result());result.add("layeredConstruction",layerChecks.result());put("status","passed");write();done=true;Log.info("[FPV-NET-TEST] @ PASS",role);Core.app.exit();}
    }
    private void connect(){Vars.netClient.beginConnecting();Vars.net.connect("127.0.0.1",Integer.getInteger("fpv.netSmokePort",16567),()->{});}
    private NetworkControls clientControls(){
        if(navalOnly)return NetworkControls.hold();
        if(stage>=37)return layerChecks.controls();
        if(stage==36)return creatorChecks.controls();
        boolean first=role.equals("client-a"),move=first&&(stage==2||stage==18);int mode=1;double forward=move?1:0,right=0,climb=move?1:0;boolean active=true,shoot=false;
        if(first&&stage>=8&&stage<=14){mode=3;if(stage==9)forward=1;if(stage==10||stage==11){active=false;forward=1;shoot=true;}}
        if(!first&&stage==12){mode=2;right=1;climb=1;}
        if(!first&&stage==13)mode=2;
        if(!first&&stage==14){mode=3;forward=1;}
        // Full-stick Flare collision is intentionally lethal. Use a small real pilot command
        // above the safe-contact threshold so once-only native HP replication remains observable.
        if(stage==19)forward=first?.04:-.04;
        return new NetworkControls(active,mode,0,0,0,0,forward,right,climb,0,0,0,1,0,shoot);
    }
    private byte[] serverPlan(){return NetProtocol.bytes(out->{out.writeInt(stage);out.writeInt(unitA);out.writeInt(unitB);out.writeFloat(b==null?0:b.health);var pose=b==null?null:network.fixtureWorld().unitState(b);out.writeDouble(pose==null?0:pose.z());out.writeFloat(a==null?0:a.health);out.writeFloat(b==null?0:b.health);out.writeInt(Vars.world.width());
        var volume=wall==null?null:network.fixtureProjection().volume(wall);out.writeBoolean(volume!=null);if(volume!=null){out.writeFloat(volume.hp());out.writeFloat(volume.maxHp());out.writeDouble(volume.topZ());out.writeDouble(volume.baseZ());out.writeFloat(wall.health);}
        out.writeBoolean(force!=null);if(force!=null){var v=network.fixtureForceProjectors().volume(force);out.writeBoolean(force.broken);out.writeFloat(force.buildup);out.writeDouble(v==null?0:v.radius());out.writeFloat(force.health);}
    });}
    private void acceptPlan(byte[] bytes){if(role.equals("server"))return;NetProtocol.read(bytes,in->{int next=in.readInt();if(next!=stage){stage=next;clientStageTime=elapsed();}unitA=in.readInt();unitB=in.readInt();healthBefore=in.readFloat();expectedZ=in.readDouble();expectedOwnA=in.readFloat();expectedOwnB=in.readFloat();worldWidth=in.readInt();expectedWall=in.readBoolean();if(expectedWall){expectedWallHp=in.readFloat();expectedWallMax=in.readFloat();expectedWallTop=in.readDouble();expectedWallBase=in.readDouble();expectedBaseWallHp=in.readFloat();}expectedForce=in.readBoolean();if(expectedForce){expectedForceBroken=in.readBoolean();expectedForceBuildup=in.readFloat();expectedForceRadius=in.readDouble();expectedForceBaseHp=in.readFloat();}return true;});}
    private void receiveReport(Player player,byte[] bytes){
        if(!role.equals("server")||done||!Set.of(7,13,17,20,24,26,29,30,31,33,35).contains(stage))return;
        try{NetProtocol.read(bytes,in->{int reportedStage=in.readInt(),target=in.readInt();double z=in.readDouble();float health=in.readFloat();boolean built=in.readBoolean();int width=in.readInt();
            boolean projection=in.readBoolean();float pool=0,baseHp=0;double top=0;boolean noClientDamage=false;
            if(projection){pool=in.readFloat();top=in.readDouble();baseHp=in.readFloat();noClientDamage=in.readBoolean();}
            boolean field=in.readBoolean(),broken=false,noClientForceDamage=false,aircraftCollision=false;float buildup=0,fieldBaseHp=0;double fieldRadius=0;
            if(field){broken=in.readBoolean();buildup=in.readFloat();fieldRadius=in.readDouble();fieldBaseHp=in.readFloat();noClientForceDamage=in.readBoolean();aircraftCollision=in.readBoolean();}
            boolean support=in.readBoolean();
            boolean ground=in.readBoolean();if(ground)groundChecks.readAndVerifyReport(in,stage==35&&reportedStage==stage);
            var pose=network.fixtureWorld().unitState(b);if(reportedStage!=stage)return false;
            check(target==unitB&&pose!=null&&Math.abs(z-pose.z())<.25&&Math.abs(health-b.health)<.05&&built&&width==Vars.world.width(),
                "client observes current-world server XYZ and native HP/build: target="+target+"/"+unitB+", Z="+z+"/"+(pose==null?"absent":pose.z())+", HP="+health+"/"+b.health+", built="+built+", width="+width+"/"+Vars.world.width());
            if(stage==24||stage==26){var v=network.fixtureProjection().volume(wall);check(projection&&Math.abs(pool-v.hp())<.05&&Math.abs(top-v.topZ())<.001&&baseHp==wall.health&&noClientDamage,"both clients observe server projection collapse/regen and native base HP");}
            if(stage>=29&&stage<=31){var v=network.fixtureForceProjectors().volume(force);check(field&&broken==force.broken&&Math.abs(buildup-force.buildup)<.05&&Math.abs(fieldRadius-(v==null?0:v.radius()))<.001&&fieldBaseHp==force.health&&noClientForceDamage,"both clients observe native force hit/break/recovery without client mutation");check(aircraftCollision==network.fixtureForceProjectors().aircraftCollisionEnabled(),"both clients observe current host aircraft barrier policy");}
            if(stage==33)check(support,"client submitted healing beam from authoritative native repair state to actual GPU");
            if(stage==35)check(ground,"both clients submitted raised grounded family rigs with host-owned settings");
            reported.add(player.name);return true;});}catch(Throwable failure){fail(failure);}
    }
    private void next(int value){stage=value;stageTime=elapsed();reported.clear();put("stage",stage);write();Log.info("[FPV-NET-TEST] Stage @",stage);}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private double elapsed(){return (System.nanoTime()-started)/1e9;}
    private void write(){if(output==null)return;try{Files.writeString(output,result.toString(Jval.Jformat.formatted));}catch(IOException error){throw new UncheckedIOException(error);}}
    private void put(String key,String value){result.add(key,value);}
    private void put(String key,boolean value){result.add(key,Jval.valueOf(value));}
    private void put(String key,Number value){result.add(key,Jval.valueOf(value.doubleValue()));}
    private void fail(Throwable failure){done=true;put("status","failed");put("failure",failure.toString());put("stage",stage);put("checks",checks);write();Log.err("[FPV-NET-TEST] Failure",failure);}
}
