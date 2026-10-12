package fpv.net;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.func.Cons2;
import arc.struct.ObjectMap;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import fpv.combat.*;
import fpv.effects.RadialExplosion;
import fpv.defense.ProjectedWallSystem;
import fpv.flight.*;
import fpv.control.*;
import fpv.construction.LayerActions;
import fpv.construction.LayerRuntime;
import fpv.generated.*;
import fpv.physics.*;
import fpv.units.UnitAltitudeSystem;
import fpv.units.ground.GroundUnitProfiles;
import fpv.world.*;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.net.NetConnection;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/** Matching-mod multiplayer: native economy/entities, server flight/damage, input-only clients. */
public final class FpvNetwork implements AutoCloseable {
    public static final String PACKET="fpv7";
    private static final String CONSTRUCTION_PACKET="fpv7-layers";
    private static final int CONSTRUCTION_MAGIC=0x46504c31;
    private final World3DAdapter world;
    private final UnitAltitudeSystem altitudes;
    private final Combat3DSystem combat;
    private final RadialExplosion explosions;
    private final DoubleBinaryOperator surface;
    private final CreatorFlightSystem creator;
    private final NativeBoostSystem boost;
    private final Map<Player,Peer> peers=new IdentityHashMap<>();
    private final Map<Integer,Session> sessions=new HashMap<>();
    private final Map<Integer,Unit3DState> frameStart=new HashMap<>();
    private final ContactLedger contacts=new ContactLedger();
    private Session collisionOwner;
    private ProjectedWallSystem projection;
    private fpv.defense.ForceProjectorSystem forceProjectors;
    private LayerRuntime construction;
    private long lastConstructionReply=-1;
    private final Cons2<Player,byte[]> constructionServerHandler=this::receiveConstructionServer;
    private final Cons<byte[]> constructionClientHandler=this::receiveConstructionClient;
    private final fpv.render.SupportEffectsState supportEffects=new fpv.render.SupportEffectsState();
    private final CrashSystem.ContactPolicy contactPolicy=new CrashSystem.ContactPolicy(){
        public boolean claimImpulse(Unit source,Unit target){return !controlledPair(source,target)||contacts.impulse(source.id,target.id);}
        public boolean claimDamage(Unit source,Unit target,double cooldown){return !controlledPair(source,target)||contacts.damage(source.id,target.id,cooldown);}
    };
    private final Map<String,Channel> channels=new LinkedHashMap<>();
    private final Map<String,NetProtocol.Assembly> assemblies=new HashMap<>();
    private final RemotePoses remote=new RemotePoses();
    private final Cons2<Player,byte[]> serverHandler=this::receiveServer;
    private final Cons<byte[]> clientHandler=this::receiveClient;
    private final Runnable beforeHook=this::before,afterHook=this::after;
    private final Cons<WorldLoadEvent> worldHook=e->resetWorld();
    private final Cons<ResetEvent> resetHook=e->resetWorld();
    private final Cons<PlayerLeave> leaveHook=e->remove(e.player);
    private final Cons<PlayerJoin> joinHook=e->{if(Vars.net.server())peers.computeIfAbsent(e.player,p->new Peer());};
    private final Cons<ClientServerConnectEvent> connectHook=e->{acknowledged=false;epoch=0;sequence=0;remote.clear();assemblies.clear();GroundUnitProfiles.clearRemoteSettings();nextHello=0;};
    private ObjectMap<Class<?>,Cons2<NetConnection,Object>> nativeListeners;
    private Cons2<NetConnection,Object> previousSnapshot;
    private ObjectMap<Class<?>,Cons> nativeClientListeners;
    private Cons previousEntitySnapshot;
    private final Cons<EntitySnapshotCallPacket> entitySnapshotHandler=p->{if(previousEntitySnapshot!=null)previousEntitySnapshot.get(p);else p.handleClient();if(construction!=null)construction.turretControls().restoreClient();};
    private final Cons2<NetConnection,Object> snapshotHandler=this::snapshot;
    private NetworkControls submitted=NetworkControls.hold();
    private String fingerprint,status="Offline";
    private long epoch,sequence,revision;
    private double nextHello,nextInput,nextSnapshot;
    private boolean installed,closed,acknowledged,wasActive;
    private int acceptedInputs,rejectedPackets,receivedSnapshots;
    private Supplier<NetworkControls> fixtureControls;
    private BooleanSupplier fixtureTransmit;

    public FpvNetwork(World3DAdapter world,UnitAltitudeSystem altitudes,Combat3DSystem combat,
                      RadialExplosion explosions,DoubleBinaryOperator surfaceHeight){
        this.world=Objects.requireNonNull(world);this.altitudes=Objects.requireNonNull(altitudes);
        this.combat=Objects.requireNonNull(combat);this.explosions=Objects.requireNonNull(explosions);surface=Objects.requireNonNull(surfaceHeight);
        creator=new CreatorFlightSystem(world,altitudes,explosions);
        boost=new NativeBoostSystem(world,altitudes,explosions);
    }
    public void install(){
        if(installed)return;fingerprint=jarFingerprint();
        Vars.netServer.addBinaryPacketHandler(PACKET,serverHandler);if(Vars.netClient!=null)Vars.netClient.addBinaryPacketHandler(PACKET,clientHandler);
        try{Field field=Vars.net.getClass().getDeclaredField("serverListeners");field.setAccessible(true);
            @SuppressWarnings("unchecked") var map=(ObjectMap<Class<?>,Cons2<NetConnection,Object>>)field.get(Vars.net);nativeListeners=map;
            previousSnapshot=map.get(ClientSnapshotCallPacket.class);map.put(ClientSnapshotCallPacket.class,snapshotHandler);
            Field clientField=Vars.net.getClass().getDeclaredField("clientListeners");clientField.setAccessible(true);
            nativeClientListeners=(ObjectMap<Class<?>,Cons>)clientField.get(Vars.net);previousEntitySnapshot=nativeClientListeners.get(EntitySnapshotCallPacket.class);nativeClientListeners.put(EntitySnapshotCallPacket.class,entitySnapshotHandler);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Pinned native snapshot safeguard unavailable",e);}
        altitudes.setExternalStateProvider(this::externalState);altitudes.setExternalVerticalImpulse(this::addVerticalVelocity);
        altitudes.setRuntimeStateProvider(u->{var body=boost.state(u);return body==null?creator.state(u):body;});
        altitudes.setRuntimeVerticalImpulse((u,d)->boost.active(u)?boost.addVerticalVelocity(u,d):creator.addVerticalVelocity(u,d));
        creator.setContactPolicy(contactPolicy);boost.setContactPolicy(contactPolicy);
        altitudes.setNetworkGeometry(()->Vars.net.active());
        registerStateChannel("combat",this::combatBytes,this::applyCombat);
        registerStateChannel("mech-pitch",combat::weaponAimReplication,combat::applyWeaponAimReplication);
        registerStateChannel("unit-ranges",()->NetProtocol.bytes(out->{var s=combat.unitRanges().settings();out.writeDouble(s.ground());out.writeDouble(s.dagger());out.writeDouble(s.naval());out.writeDouble(s.vertical());}),
            bytes->combat.unitRanges().settings(NetProtocol.read(bytes,in->new fpv.combat.UnitWeaponRanges.Settings(NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in)))));
        registerStateChannel("explosions",this::explosionBytes,this::applyExplosions);
        registerStateChannel("support-effects",()->fpv.render.SupportEffectsCodec.encode(supportEffects.snapshot()),
            bytes->supportEffects.applySnapshot(fpv.render.SupportEffectsCodec.decode(bytes)));
        registerStateChannel("ground-profiles",()->GroundUnitProfiles.encode(GroundUnitProfiles.settings()),
            bytes->GroundUnitProfiles.applyRemoteSettings(GroundUnitProfiles.decode(bytes)));
        // Share the existing bounded channel; production and smoke already use all 16 channels.
        registerStateChannel("creator-flight",()->NetProtocol.bytes(out->{byte[] a=creator.replication(),b=boost.replication();out.writeInt(a.length);out.write(a);out.writeInt(b.length);out.write(b);}),
            bytes->NetProtocol.read(bytes,in->{int a=in.readInt();if(a<5||a>in.available())throw new IOException("Creator mode length");creator.applyReplication(in.readNBytes(a));int b=in.readInt();if(b<5||b>in.available())throw new IOException("Boost mode length");boost.applyReplication(in.readNBytes(b));return null;}));
        Events.run(Trigger.update,beforeHook);Events.run(Trigger.afterGameUpdate,afterHook);
        Events.on(WorldLoadEvent.class,worldHook);Events.on(ResetEvent.class,resetHook);Events.on(PlayerLeave.class,leaveHook);
        Events.on(PlayerJoin.class,joinHook);Events.on(ClientServerConnectEvent.class,connectHook);
        installed=true;resetWorld();
        Log.info("[FPV-NET] Installed protocol @; fingerprint @",(int)NetworkConfig.PROTOCOL_VERSION,fingerprint.substring(0,12));
    }
    /** True means frontend skips local integration/bridge/damage and displays server flight instead. */
    public boolean controlsLocalFlight(){return Vars.net.active();}
    public boolean allowed(){return Vars.net.server()||!Vars.net.active()||acknowledged;}
    public boolean authoritative(){return !Vars.net.client();}
    public CreatorFlightSystem creatorFlight(){return creator;}
    public NativeBoostSystem nativeBoost(){return boost;}
    public void controlGroundFlight(Player player,Unit unit,NetworkControls controls){
        if(controls.boostFlight()&&creator.active(unit)){creator.release(unit);return;}
        if(controls.creatorFlight()&&boost.active(unit)){boost.release(unit);return;}
        boost.control(player,unit,controls);creator.control(player,unit,controls);
        if(UnitControlCapabilities.boostEligible(unit))player.boosting=boost.active(unit)||creator.active(unit);
    }
    public String status(){return status;}
    public String fingerprint(){return fingerprint;}
    public int acceptedInputs(){return acceptedInputs;}
    public int rejectedPackets(){return rejectedPackets;}
    public int receivedSnapshots(){return receivedSnapshots;}
    public int sessions(){return sessions.size();}
    public void submit(NetworkControls controls){submitted=Objects.requireNonNull(controls);}
    void fixtureControls(Supplier<NetworkControls> supplier){fixtureControls=supplier;}
    void fixtureTransmit(BooleanSupplier supplier){fixtureTransmit=supplier;}
    long fixtureEpoch(){return epoch;}
    FlightController fixtureFlight(Player player){Session s=sessions.get(player.id);return Vars.net.server()&&s!=null?s.flight:null;}
    CrashSystem fixtureCrash(Player player){Session s=sessions.get(player.id);return Vars.net.server()&&s!=null?s.crash:null;}
    World3DAdapter fixtureWorld(){return world;}
    ProjectedWallSystem fixtureProjection(){return projection;}
    fpv.defense.ForceProjectorSystem fixtureForceProjectors(){return forceProjectors;}
    Combat3DSystem fixtureCombat(){return combat;}
    LayerRuntime fixtureConstruction(){return construction;}
    byte[] fixtureConstructionPacket(LayerActions.Action action){
        if(System.getProperty("fpv.netSmokeRole","").isBlank())throw new IllegalStateException("Isolated network fixture required");
        return constructionPacket(action);
    }
    public fpv.render.SupportEffectsState supportEffects(){return supportEffects;}
    public boolean copyLocalFlight(FlightController target){
        if(Vars.player==null||Vars.player.dead())return false;
        if(Vars.net.server()){Session session=sessions.get(Vars.player.id);if(session==null||session.unit!=Vars.player.unit())return false;
            target.configureProfile(session.flight.profile());NetProtocol.copy(session.flight.state(),target.state());return true;}
        var latest=remote.latestFlight();return acknowledged&&latest!=null&&latest.playerId()==Vars.player.id&&latest.unitId()==Vars.player.unit().id&&remote.copyFlight(target,now());
    }
    public Unit3DState externalState(Unit unit){
        if(unit==null||!Vars.net.active())return null;
        if(Vars.net.client()){Unit3DState pose=remote.display(unit.id,now());return pose!=null&&pose.typeId()==unit.type.id&&pose.teamId()==unit.team.id?pose:null;}
        for(Session session:sessions.values())if(session.unit==unit&&session.unitId==unit.id){
            if(collisionOwner!=null&&collisionOwner!=session){Unit3DState start=frameStart.get(unit.id);if(start!=null)return start;}
            if(unit.type.flying||UnitControlCapabilities.stationary(unit))return session.body();
        }return null;
    }
    private boolean controlledPair(Unit a,Unit b){boolean ca=false,cb=false;for(Session s:sessions.values()){ca|=s.unit==a&&s.unitId==a.id;cb|=s.unit==b&&s.unitId==b.id;}return ca&&cb;}
    public boolean addVerticalVelocity(Unit unit,double delta){
        if(!Vars.net.server()||!Double.isFinite(delta))return false;
        for(Session session:sessions.values())if(session.unit==unit&&session.unitId==unit.id&&unit.type.flying){session.flight.state().vy+=Math.max(-30,Math.min(30,delta));return true;}return false;
    }
    /** Canonical XYZ weapon direction, derived from authenticated controls, not observer camera state. */
    public WorldCoordinates.Point aim(Unit unit){
        if(!Vars.net.server())return null;
        for(Session session:sessions.values())if(session.unit==unit&&session.unitId==unit.id){NetworkControls c=session.controls;return new WorldCoordinates.Point(c.aimX(),c.aimY(),c.aimZ());}return null;
    }
    public void registerStateChannel(String name,Supplier<byte[]> serverData,Consumer<byte[]> clientApply){
        if(!name.matches("[a-z0-9-]{1,32}")||name.equals("poses")||channels.containsKey(name)||channels.size()>=NetworkConfig.MAX_CHANNELS)
            throw new IllegalArgumentException("Unique bounded channel name required");channels.put(name,new Channel(serverData,clientApply));
    }
    public void registerProjection(ProjectedWallSystem projection){
        this.projection=Objects.requireNonNull(projection);
        registerStateChannel("projection",()->ProjectionCodec.encode(projection.replication(),Vars.world.width()*Vars.world.height()),
            bytes->projection.applyReplication(ProjectionCodec.decode(bytes,Vars.world.width()*Vars.world.height())));
    }
    public void registerForceProjectors(fpv.defense.ForceProjectorSystem value){
        forceProjectors=Objects.requireNonNull(value);
        registerStateChannel("force-aircraft",value::aircraftSettingsBytes,value::applyAircraftSettings);
        registerStateChannel("force-projectors",()->fpv.defense.ForceProjectorCodec.encode(value.snapshot(),Vars.world.width()*Vars.world.height()),
            bytes->value.applySnapshot(fpv.defense.ForceProjectorCodec.decode(bytes,Vars.world.width()*Vars.world.height())));
    }
    /** Separate bounded reliable commands; ordinary native build packets still mean Z0 only. */
    public void registerConstruction(LayerRuntime value){
        if(construction!=null)throw new IllegalStateException("Construction already registered");construction=Objects.requireNonNull(value);supportEffects.setConstruction(value);
        registerStateChannel("layered-world",value::replication,bytes->{value.applyReplication(bytes);value.turretControls().restoreClient();});
        registerStateChannel("layer-turrets",value.turretControls()::replication,value.turretControls()::applyReplication);
        Vars.netServer.addBinaryPacketHandler(CONSTRUCTION_PACKET,constructionServerHandler);
        if(Vars.netClient!=null)Vars.netClient.addBinaryPacketHandler(CONSTRUCTION_PACKET,constructionClientHandler);
    }
    public LayerActions.Result submitConstruction(LayerActions.Action action){
        if(construction==null)return LayerActions.Result.rejected("Construction is unavailable");
        if(!Vars.net.client())return construction.apply(Vars.player,action);
        if(!acknowledged)return LayerActions.Result.rejected("Waiting for the matching FPV server");
        byte[] bytes=constructionPacket(action);
        if(bytes.length>fpv.construction.LayerConfiguration.MAX_BYTES+1024)return LayerActions.Result.rejected("Construction request is too large");
        Call.serverBinaryPacketReliable(CONSTRUCTION_PACKET,bytes);return LayerActions.Result.queued();
    }
    private byte[] constructionPacket(LayerActions.Action action){long request=sequence++;return NetProtocol.bytes(out->{out.writeInt(CONSTRUCTION_MAGIC);out.writeByte(1);out.writeLong(epoch);out.writeLong(request);LayerActions.write(out,action);});}
    private void receiveConstructionServer(Player player,byte[] bytes){
        if(!Vars.net.server()||construction==null||player==null)return;
        Peer peer=peers.get(player);
        if(peer==null||!peer.accepted||bytes==null||bytes.length>fpv.construction.LayerConfiguration.MAX_BYTES+1024){rejectedPackets++;return;}
        try{
            double time=now();if(time-peer.rateStart>=1){peer.rateStart=time;peer.rateCount=0;}
            if(++peer.rateCount>NetworkConfig.INPUT_RATE_LIMIT){rejectedPackets++;return;}
            NetProtocol.read(bytes,in->{if(in.readInt()!=CONSTRUCTION_MAGIC||in.readUnsignedByte()!=1)throw new IOException("Construction header");
                long requestEpoch=in.readLong(),request=in.readLong();LayerActions.Action action=LayerActions.read(in);
                if(in.available()!=0||requestEpoch!=epoch||request<0||request<=peer.lastConstructionSequence)throw new IOException("Stale construction request");
                peer.lastConstructionSequence=request;LayerActions.Result result=construction.apply(player,action);
                if(player.con!=null)Call.clientBinaryPacketReliable(player.con,CONSTRUCTION_PACKET,NetProtocol.bytes(out->{out.writeInt(CONSTRUCTION_MAGIC);out.writeByte(2);out.writeLong(epoch);out.writeLong(request);LayerActions.writeResult(out,result);}));
                return null;});
        }catch(IllegalArgumentException invalid){rejectedPackets++;}
    }
    private void receiveConstructionClient(byte[] bytes){
        if(!Vars.net.client()||construction==null||!acknowledged)return;
        try{NetProtocol.read(bytes,in->{if(in.readInt()!=CONSTRUCTION_MAGIC||in.readUnsignedByte()!=2)throw new IOException("Construction reply header");
            long resultEpoch=in.readLong(),request=in.readLong();var result=LayerActions.readResult(in);
            if(resultEpoch!=epoch||request<0)throw new IOException("Construction reply epoch");
            if(request>lastConstructionReply){lastConstructionReply=request;construction.acceptResult(result);}return null;});
        }catch(IllegalArgumentException invalid){rejectedPackets++;}
    }
    private void resetWorld(){
        lastConstructionReply=-1;
        supportEffects.clear();GroundUnitProfiles.clearRemoteSettings();
        for(Session session:sessions.values())session.release();sessions.clear();creator.clear();boost.clear();remote.clear();assemblies.clear();frameStart.clear();contacts.clear();collisionOwner=null;
        epoch=Vars.net.server()?Math.max(1,System.nanoTime()):0;revision=0;acknowledged=false;
        // ACK can reach a client before native world streaming finishes. Retain its sequence
        // through WorldLoad so a new-epoch input cannot make later controls look like replays.
        if(!Vars.net.client())sequence=0;
        nextHello=nextInput=nextSnapshot=0;for(var channel:channels.values())channel.next=0;
        if(Vars.net.server())for(var entry:peers.entrySet()){entry.getValue().lastSequence=-1;entry.getValue().lastConstructionSequence=-1;entry.getValue().channels.clear();if(entry.getValue().accepted)sendAck(entry.getKey());}
    }
    private void remove(Player player){Peer peer=peers.remove(player);Session session=sessions.remove(player.id);if(session!=null)session.release();}
    private void before(){
        if(closed)return;boolean active=Vars.net.active();
        if(!active){if(wasActive){resetWorld();peers.clear();status="Offline";}wasActive=false;return;}wasActive=true;
        if(fixtureControls!=null)submitted=Objects.requireNonNull(fixtureControls.get());
        if(Vars.net.client()){
            if(Vars.state.isGame()&&Vars.player!=null&&now()>=nextHello&&!acknowledged){nextHello=now()+1;Call.serverBinaryPacketReliable(PACKET,NetProtocol.encode(NetProtocol.HELLO,0,sequence++,out->out.writeUTF(fingerprint)));status="Waiting for matching FPV server";}
            if(acknowledged&&now()>=nextInput&&(fixtureTransmit==null||fixtureTransmit.getAsBoolean())){nextInput=now()+1/NetworkConfig.INPUT_HZ;Call.serverBinaryPacketUnreliable(PACKET,NetProtocol.encode(NetProtocol.INPUT,epoch,sequence++,out->NetProtocol.controls(out,submitted)));}
            // Native SyncLocal fields deliberately omit the owner's XY. Reconcile those fields
            // explicitly; camera smoothing is separate and never changes the server sample.
            var own=remote.latestFlight();
            if(acknowledged&&own!=null&&Vars.player!=null&&!Vars.player.dead()&&own.playerId()==Vars.player.id&&own.unitId()==Vars.player.unit().id){
                Unit unit=Vars.player.unit();unit.set((float)(own.state().x*Vars.tilesize),(float)(-own.state().z*Vars.tilesize));unit.vel.setZero();
                Unit3DState body=remote.latest(unit.id);if(body!=null)unit.rotation=(float)body.yaw();
            }
            return;
        }
        if(epoch==0)epoch=Math.max(1,System.nanoTime());
        for(Player player:Groups.player){
            Peer peer=peers.computeIfAbsent(player,p->new Peer());
            if(player.isLocal()){peer.accepted=true;peer.controls=submitted;peer.lastInput=now();}
            if(!peer.accepted){if(now()-peer.joined>NetworkConfig.HANDSHAKE_TIMEOUT_SECONDS&&player.con!=null)player.con.kick("FPV requires the identical mod JAR and protocol",0);continue;}
            if(!UnitControlCapabilities.validOwner(player,player.unit())){Session old=sessions.remove(player.id);if(old!=null)old.release();continue;}
            Session session=sessions.get(player.id);if(session==null||session.unit!=player.unit()||session.unitId!=player.unit().id){if(session!=null)session.release();session=new Session(player,player.unit());sessions.put(player.id,session);}
            session.controls=now()-peer.lastInput>NetworkConfig.INPUT_TIMEOUT_SECONDS?NetworkControls.hold():peer.controls;
        }
        sessions.entrySet().removeIf(entry->{Session s=entry.getValue();if(!s.player.isAdded()){s.release();return true;}return false;});
        if(Vars.state.isPlaying()&&!Vars.state.isPaused()){
            double dt=Math.min(.1,Math.max(0,Time.delta/60.0));contacts.begin(dt);frameStart.clear();
            for(Session session:sessions.values()){session.prepare();Unit3DState pose=session.body();if(pose!=null)frameStart.put(session.unitId,pose);}
            for(Session session:sessions.values()){collisionOwner=session;try{session.step(dt);}finally{collisionOwner=null;}}
            creator.before(dt);boost.before(dt);
            altitudes.captureGroundIntent(dt);
        }
        status="Server authoritative: "+sessions.size()+" pilots";
    }
    private void after(){
        if(closed||!Vars.net.server())return;
        for(Session session:sessions.values()){session.motion.afterSimulation(session.unit,session.flight.state());session.crash.applyPending();}
        creator.after();boost.after();
        altitudes.update(Math.max(0,Math.min(.1,Time.delta/60.0)));
        for(Session session:sessions.values())if(!session.unit.type.flying)session.copyGroundPose();
        double time=now();if(time>=nextSnapshot){nextSnapshot=time+1/NetworkConfig.SNAPSHOT_HZ;sendSnapshots();}
        for(var entry:channels.entrySet()){
            Channel channel=entry.getValue();if(time<channel.next)continue;
            channel.next=time+1/(entry.getKey().equals("combat")||entry.getKey().equals("explosions")?NetworkConfig.VISUAL_HZ:NetworkConfig.CHANNEL_HZ);
            byte[] bytes;
            try{bytes=entry.getValue().serverData.get();if(bytes.length>NetworkConfig.MAX_MESSAGE_BYTES)throw new IllegalStateException("State channel too large");channel.failed=false;}
            catch(RuntimeException failure){
                if(!channel.failed)Log.err("[FPV] State channel @ could not encode; retaining its last complete snapshot.",entry.getKey(),failure);
                channel.failed=true;status="State synchronization paused: "+entry.getKey();
                if(construction!=null&&entry.getKey().equals("layered-world"))construction.replicationFailed("state exceeds the safe save/network budget");
                continue;
            }
            if(!Arrays.equals(channel.bytes,bytes)){channel.bytes=bytes;channel.version++;}
            for(var p:peers.entrySet())if(p.getValue().accepted&&p.getKey().con!=null&&p.getValue().channels.getOrDefault(entry.getKey(),-1L)!=channel.version){
                sendChunks(p.getKey(),entry.getKey(),bytes);p.getValue().channels.put(entry.getKey(),channel.version);
            }
        }
    }
    private void sendSnapshots(){
        for(var entry:peers.entrySet()){
            Player player=entry.getKey();if(!entry.getValue().accepted||player.con==null)continue;
            var units=new ArrayList<Unit3DState>();for(Unit unit:Groups.unit){if(unit.isSyncHidden(player.team()))continue;Unit3DState s=world.unitState(unit);if(s!=null&&units.size()<NetworkConfig.MAX_UNIT_STATES)units.add(s);}
            Session local=sessions.get(player.id);
            if(local!=null&&UnitControlCapabilities.stationary(local.unit))units.add(local.body());
            NetProtocol.Flight own=local==null?null:new NetProtocol.Flight(player.id,local.unitId,local.flight.profile(),local.flight.state());
            sendChunks(player,"poses",NetProtocol.snapshot(new NetProtocol.Snapshot(units,own)));
        }
    }
    private void sendAck(Player player){if(player.con!=null)Call.clientBinaryPacketReliable(player.con,PACKET,NetProtocol.encode(NetProtocol.ACK,epoch,sequence++,out->out.writeUTF(fingerprint)));}
    private void sendChunks(Player player,String name,byte[] data){
        int size=(int)NetworkConfig.CHUNK_BYTES,count=Math.max(1,(data.length+size-1)/size);long rev=++revision;
        for(int i=0;i<count;i++){int offset=i*size;var chunk=new NetProtocol.Chunk(name,rev,i,count,data.length,Arrays.copyOfRange(data,offset,Math.min(data.length,offset+size)));
            Call.clientBinaryPacketReliable(player.con,PACKET,NetProtocol.encode(NetProtocol.CHANNEL,epoch,sequence++,out->NetProtocol.chunk(out,chunk)));}
    }
    private void receiveServer(Player player,byte[] bytes){
        if(!Vars.net.server()||player==null)return;
        if(bytes==null||bytes.length>NetworkConfig.MAX_CLIENT_MESSAGE_BYTES){rejectedPackets++;return;}
        try{Peer peer=peers.computeIfAbsent(player,p->new Peer());double time=now();if(time-peer.rateStart>=1){peer.rateStart=time;peer.rateCount=0;}if(++peer.rateCount>NetworkConfig.INPUT_RATE_LIMIT){rejectedPackets++;return;}
            NetProtocol.Envelope packet=NetProtocol.decode(bytes);
            if(packet.kind()==NetProtocol.HELLO){String hash=NetProtocol.read(packet.payload(),in->in.readUTF());
                if(!fingerprint.equals(hash)){rejectedPackets++;if(player.con!=null)player.con.kick("FPV mod JAR differs from this server",0);return;}
                if(!peer.accepted){peer.accepted=true;peer.lastSequence=-1;peer.lastInput=time;}peer.channels.clear();sendAck(player);return;}
            if(packet.kind()!=NetProtocol.INPUT||!peer.accepted||packet.epoch()!=epoch||packet.sequence()<=peer.lastSequence){rejectedPackets++;return;}
            NetworkControls controls=NetProtocol.read(packet.payload(),NetProtocol::controls);peer.controls=controls;peer.lastSequence=packet.sequence();peer.lastInput=time;acceptedInputs++;
        }catch(IllegalArgumentException invalid){rejectedPackets++;}
    }
    private void receiveClient(byte[] bytes){
        if(!Vars.net.client())return;
        try{NetProtocol.Envelope packet=NetProtocol.decode(bytes);
            if(packet.kind()==NetProtocol.ACK){String hash=NetProtocol.read(packet.payload(),in->in.readUTF());if(!fingerprint.equals(hash)||packet.epoch()==0)throw new IllegalArgumentException("Server mismatch");
                if(epoch!=packet.epoch()){remote.clear();assemblies.clear();GroundUnitProfiles.clearRemoteSettings();}epoch=packet.epoch();acknowledged=true;status="Matching FPV server; authoritative XYZ";return;}
            if(!acknowledged||packet.epoch()!=epoch||packet.kind()!=NetProtocol.CHANNEL){rejectedPackets++;return;}
            var chunk=NetProtocol.chunk(packet.payload());if(!chunk.channel().equals("poses")&&!channels.containsKey(chunk.channel())){rejectedPackets++;return;}
            byte[] complete=assemblies.computeIfAbsent(chunk.channel(),key->new NetProtocol.Assembly()).accept(chunk,now());if(complete==null)return;
            if(chunk.channel().equals("poses")){remote.accept(NetProtocol.snapshot(complete),now());receivedSnapshots++;}
            else channels.get(chunk.channel()).clientApply.accept(complete);
        }catch(IllegalArgumentException invalid){rejectedPackets++;}
    }
    /** Preserve native validated build/aim plans while categorically ignoring client XY/velocity claims. */
    private void snapshot(NetConnection connection,Object object){
        var packet=(ClientSnapshotCallPacket)object;
        Unit unit=connection.player==null?null:connection.player.unit();
        if(Vars.net.server()&&unit!=null&&!unit.dead){packet.unitID=-1;packet.rotation=unit.rotation;
            Peer peer=peers.get(connection.player);NetworkControls c=peer==null||!peer.accepted||now()-peer.lastInput>NetworkConfig.INPUT_TIMEOUT_SECONDS?NetworkControls.hold():peer.controls;
            packet.shooting=c.active()&&c.mode()!=2&&c.shooting();
        }
        if(previousSnapshot!=null)previousSnapshot.get(connection,object);else packet.handleServer(connection);
        // Native snapshots include a client-side, delayed elevation/boost flag. The authenticated
        // input registry owns mode transitions; that flag must not restart a completed landing.
        if(Vars.net.server()&&unit!=null&&UnitControlCapabilities.boostEligible(unit))connection.player.boosting=boost.active(unit)||creator.active(unit);
        if(Vars.net.server()&&unit!=null&&!unit.dead)unit.lastUpdated=0;
    }
    private final class Session{
        final Player player;final Unit unit;final int unitId;
        final FlightController flight=new FlightController(FlightProfiles.create());
        final FlightSettings settings=new FlightSettings(FlightProfiles.create());
        final UnitMotionBridge motion=new UnitMotionBridge();final StabilizedFlight stabilized=new StabilizedFlight();
        final BuildNavigator builder=new BuildNavigator(flight.profile().metersPerTile);
        final CrashSystem crash=new CrashSystem(surface,explosions,flight.profile().metersPerTile);
        final RammingCollision obstacles=new RammingCollision(world);
        final Vec2 planar=new Vec2();final double[] direction=new double[3];
        final GroundedPilot groundPilot=new GroundedPilot();
        final FlightController.StepObserver collision=(s,x,y,z,dt,elapsed)->collide(x,y,z,dt,elapsed);
        NetworkControls controls=NetworkControls.hold();FlightProfile performance;
        Session(Player player,Unit unit){this.player=player;this.unit=unit;unitId=unit.id;
            var initial=altitudes.unitState(unit);double height=initial==null?surface.applyAsDouble(unit.x/8.0,-unit.y/8.0)+RuntimeConfig.START_HEIGHT:initial.z();
            flight.reset(unit.x/8.0,Math.max(height,surface.applyAsDouble(unit.x/8.0,-unit.y/8.0)+halfDepth()),-unit.y/8.0,90-unit.rotation);
            performance=settings.configure(flight,unit);crash.setWorld(world);crash.setContactPolicy(contactPolicy);crash.setControlledMass(performance.massKg());unit.lastUpdated=0;
        }
        Unit3DState body(){return UnitControlCapabilities.stationary(unit)?GroundedPilot.fixedPose(unit,world):unit.type.flying?Unit3DState.fromRenderFlight(unit.id,unit.type.id,unit.team.id,flight.state(),radius(),halfDepth()):altitudes.unitState(unit);}
        double radius(){return Math.max(.01,unit.hitSize/(double)Vars.tilesize*UnitsConfig.FOOTPRINT_RADIUS_PER_SIZE);}
        double halfDepth(){return Math.min(UnitsConfig.MAX_BODY_OFFSET,Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,unit.hitSize/(double)Vars.tilesize*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE));}
        void prepare(){
            unit.lastUpdated=0;performance=settings.configure(flight,unit);crash.setControlledMass(performance.massKg());
            if(unit.type.flying)motion.syncFromSimulation(unit,flight.state());else {motion.reset();copyGroundPose();}
        }
        void step(double dt){
            controlGroundFlight(player,unit,controls);
            if(!unit.type.flying){
                if(creator.active(unit)||boost.active(unit))GroundedPilot.weapons(player,unit,controls);
                else groundPilot.step(player,unit,controls,altitudes,dt);
                copyGroundPose();return;
            }
            var s=flight.state();
            if(!controls.active())stabilized.step(s,flight.profile(),dt,0,0,0,performance.practicalSpeedMps(),-2*Math.atan2(s.qy,s.qw),collision);
            else if(controls.mode()==0)flight.step(dt,controls.throttle(),controls.pitch(),controls.roll(),controls.yaw(),collision);
            else if(controls.mode()==1)stabilized.step(s,flight.profile(),dt,controls.forward(),controls.right(),controls.climb(),performance.practicalSpeedMps(),controls.headingRadians(),collision);
            else if(controls.mode()==2)build(dt);
            else stabilized.step(s,flight.profile(),dt,controls.forward(),controls.right(),0,Math.min(400,unit.speed()*60.0/Vars.tilesize*flight.profile().metersPerTile),controls.headingRadians(),collision);
            if(controls.mode()==3){nativeFacing();double yaw=Math.toRadians(unit.rotation-90)*.5;s.qx=s.qz=0;s.qy=Math.sin(yaw);s.qw=Math.cos(yaw);}
            motion.beforeSimulation(unit,s,Time.delta,false);
            flight.getForward(direction);unit.rotation=(float)Math.toDegrees(Math.atan2(-direction[2],direction[0]));weapons();
        }
        void nativeFacing(){if(unit.type.omniMovement&&controls.active()&&controls.shooting()&&unit.type.hasWeapons()&&unit.type.faceTarget)unit.lookAt((float)Math.toDegrees(Math.atan2(controls.aimY(),controls.aimX())));else unit.lookAt(unit.prefRotation());}
        void weapons(){GroundedPilot.weapons(player,unit,controls);}
        void copyGroundPose(){if(boost.copy(unit,flight)||creator.copy(unit,flight))return;var pose=UnitControlCapabilities.stationary(unit)?GroundedPilot.fixedPose(unit,world):altitudes.unitState(unit);if(pose==null)return;var s=flight.state();s.x=pose.x();s.y=pose.z();s.z=-pose.y();s.vx=pose.vx();s.vy=pose.vz();s.vz=-pose.vy();s.qx=s.qz=0;double yaw=Math.toRadians(unit.rotation-90)*.5;s.qy=Math.sin(yaw);s.qw=Math.cos(yaw);s.wx=s.wy=s.wz=0;}
        void collide(double x,double y,double z,double dt,double elapsed){bound();crash.resolve(unit,flight.state(),x,y,z,dt,Core.settings.getBool("fpv-crash-damage",true),elapsed);bound();}
        void bound(){var s=flight.state();s.x=Math.max(.1,Math.min(Vars.world.width()-1.1,s.x));s.z=Math.min(-.1,Math.max(-(Vars.world.height()-1.1),s.z));double ceiling=Math.max(RuntimeConfig.MAX_HEIGHT,world.maximumTerrainHeight()+RuntimeConfig.START_HEIGHT);if(s.y>ceiling){s.y=ceiling;s.vy=Math.min(0,s.vy);}}
        void build(double dt){var s=flight.state();double x=s.x,y=s.y,z=s.z;builder.step(s,dt,controls.forward(),controls.right(),controls.climb(),controls.turn(),Math.max(0,Math.min(60,Core.settings.getInt("fpv-build-speed",12))));bound();
            var body=body();var hit=obstacles.first(unit,x,-z,y,s.x,-s.z,s.y,s.vx,-s.vz,s.vy,body.radius(),body.halfHeight(),0);
            if(hit!=null){double t=Math.max(0,hit.time()-1e-6);s.x=x+(s.x-x)*t;s.y=y+(s.y-y)*t;s.z=z+(s.z-z)*t;s.vx=s.vy=s.vz=0;}
            new SweptCollision(surface,body.radius(),body.halfHeight(),CrashConfig.SWEEP_STEP_TILES).resolve(s,x,y,z);
        }
        void release(){creator.release(unit);boost.release(unit);altitudes.setGroundStanceControl(unit,false,0,0);motion.release(unit,flight.state());if(unit.id==unitId&&!unit.dead)unit.snapInterpolation();crash.reset();}
    }
    private byte[] combatBytes(){return NetProtocol.bytes(out->{var visuals=combat.networkVisuals();int size=Math.min(visuals.size(),(int)NetworkConfig.MAX_VISUAL_STATES);out.writeInt(size);
        for(int i=0;i<size;i++){var v=visuals.get(i);out.writeLong(v.id());out.writeByte(v.kind().ordinal());out.writeByte(v.teamId());for(double n:new double[]{v.ax(),v.ay(),v.az(),v.bx(),v.by(),v.bz(),v.radius()})out.writeDouble(n);out.writeFloat(v.r());out.writeFloat(v.g());out.writeFloat(v.b());out.writeFloat(v.alpha());}
    });}
    private void applyCombat(byte[] bytes){combat.acceptRemoteVisuals(NetProtocol.read(bytes,in->{int size=in.readInt();if(size<0||size>NetworkConfig.MAX_VISUAL_STATES)throw new IOException("Visual count");var list=new ArrayList<CombatVisual>(size);
        for(int i=0;i<size;i++){long id=in.readLong();int kind=in.readUnsignedByte(),team=in.readUnsignedByte();if(kind>=CombatVisual.Kind.values().length)throw new IOException("Visual kind");list.add(new CombatVisual(id,CombatVisual.Kind.values()[kind],team,NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),NetProtocol.number(in),color(in),color(in),color(in),color(in)));}return list;}));}
    private byte[] explosionBytes(){return NetProtocol.bytes(out->{var values=explosions.visualSnapshot();out.writeInt(values.size());for(var v:values){out.writeInt(v.id());out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());var s=v.spec();out.writeDouble(s.radius());out.writeDouble(s.damage());out.writeDouble(s.visualRadius());out.writeDouble(s.duration());out.writeByte(s.style().ordinal());out.writeBoolean(false);out.writeDouble(v.age());}});}
    private void applyExplosions(byte[] bytes){explosions.acceptVisuals(NetProtocol.read(bytes,in->{int count=in.readInt();if(count<0||count>CrashConfig.MAX_EFFECTS)throw new IOException("Explosion count");var list=new ArrayList<RadialExplosion.VisualBurst>(count);for(int i=0;i<count;i++){int id=in.readInt();double x=NetProtocol.number(in),y=NetProtocol.number(in),z=NetProtocol.number(in);double radius=NetProtocol.number(in),damage=NetProtocol.number(in),visual=NetProtocol.number(in),duration=NetProtocol.number(in);int style=in.readUnsignedByte();if(style>=RadialExplosion.Style.values().length)throw new IOException("Explosion style");var spec=new RadialExplosion.Spec(radius,damage,visual,duration,RadialExplosion.Style.values()[style],in.readBoolean());list.add(new RadialExplosion.VisualBurst(id,x,y,z,spec,NetProtocol.number(in)));}return list;}));}
    private static float color(DataInputStream in)throws IOException{float value=in.readFloat();if(!Float.isFinite(value)||value<0||value>16)throw new IOException("Invalid visual color");return value;}
    private static String jarFingerprint(){try{Path file=Path.of(FpvNetwork.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(!Files.isRegularFile(file))throw new IllegalStateException("Network requires a packaged matching mod JAR");var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] block=new byte[65536];for(int n;(n=input.read(block))>=0;)if(n>0)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new IllegalStateException("Cannot verify mod JAR",e);}}
    private static double now(){return System.nanoTime()/1e9;}
    private static final class Peer{boolean accepted;NetworkControls controls=NetworkControls.hold();long lastSequence=-1,lastConstructionSequence=-1;double joined=now(),lastInput,rateStart;int rateCount;final Map<String,Long> channels=new HashMap<>();}
    private static final class Channel{final Supplier<byte[]> serverData;final Consumer<byte[]> clientApply;double next;byte[] bytes;long version;boolean failed;
        Channel(Supplier<byte[]> source,Consumer<byte[]> destination){serverData=source;clientApply=destination;}}
    @Override public void close(){if(closed)return;closed=true;for(Session session:sessions.values())session.release();sessions.clear();peers.clear();remote.clear();assemblies.clear();
        creator.clear();boost.clear();supportEffects.clear();GroundUnitProfiles.clearRemoteSettings();
        if(nativeListeners!=null&&nativeListeners.get(ClientSnapshotCallPacket.class)==snapshotHandler){if(previousSnapshot==null)nativeListeners.remove(ClientSnapshotCallPacket.class);else nativeListeners.put(ClientSnapshotCallPacket.class,previousSnapshot);}
        if(nativeClientListeners!=null&&nativeClientListeners.get(EntitySnapshotCallPacket.class)==entitySnapshotHandler){if(previousEntitySnapshot==null)nativeClientListeners.remove(EntitySnapshotCallPacket.class);else nativeClientListeners.put(EntitySnapshotCallPacket.class,previousEntitySnapshot);}
        Vars.netServer.getBinaryPacketHandlers(PACKET).remove(serverHandler,true);if(Vars.netClient!=null)Vars.netClient.getBinaryPacketHandlers(PACKET).remove(clientHandler,true);
        if(construction!=null){Vars.netServer.getBinaryPacketHandlers(CONSTRUCTION_PACKET).remove(constructionServerHandler,true);if(Vars.netClient!=null)Vars.netClient.getBinaryPacketHandlers(CONSTRUCTION_PACKET).remove(constructionClientHandler,true);}
        // Arc's enum-trigger API has no removal operation. These two retained callbacks are inert after closed.
        Events.remove(WorldLoadEvent.class,worldHook);Events.remove(ResetEvent.class,resetHook);Events.remove(PlayerLeave.class,leaveHook);Events.remove(PlayerJoin.class,joinHook);Events.remove(ClientServerConnectEvent.class,connectHook);
        altitudes.setExternalStateProvider(null);altitudes.setExternalVerticalImpulse(null);altitudes.setNetworkGeometry(null);
        altitudes.setRuntimeStateProvider(null);altitudes.setRuntimeVerticalImpulse(null);
    }
}
