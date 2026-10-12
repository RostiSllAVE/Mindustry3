package fpv.net;

import arc.Core;
import arc.util.Log;
import arc.util.serialization.Jval;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import java.util.*;

/** Real-process construction requests and snapshots; opt-in isolated fixture only. */
final class LayerNetworkSmokeChecks {
    private static final String REPORT="fpv7-lab-layers";
    private final FpvNetwork network;
    private final String role=System.getProperty("fpv.netSmokeRole","");
    private final Jval result=Jval.newObject();
    private final Set<String> reports=new HashSet<>();
    private int phase,checks,lastSent=-1,lastReport=-1,rejectedBefore,copperBefore,leadBefore,expectedInventory;
    private long platformId,freeId,containerId,sorterId;
    private long powerNodeId,powerConsumerId;
    private long turretId;private int aimTargetId,manualShots;private float targetHealth;
    private double phaseAt,clientPhaseAt;
    private final boolean powerPass=Boolean.getBoolean("fpv.xyzPower.netSmoke");
    private Player a,b,oldB;
    private boolean expectedFree,seededInventory,reconnectAttempted,reconnectQueued,rejoined,clientComplete;
    private Throwable failure;
    LayerNetworkSmokeChecks(FpvNetwork network){
        if(role.isEmpty()||System.getProperty("fpv.netSmokeRoot","").isBlank())throw new IllegalStateException("Explicit isolated construction network fixture required");
        this.network=network;network.registerStateChannel("lab-layers",this::plan,this::acceptPlan);
        if(role.equals("server"))Vars.netServer.addBinaryPacketHandler(REPORT,this::receiveReport);
        result.add("physicalInputTested",Jval.valueOf(false));
    }
    private LayerRuntime layers(){return Objects.requireNonNull(network.fixtureConstruction(),"Construction runtime required in network fixture");}
    void serverBegin(Player first,Player second){
        check(Vars.headless&&Vars.net.server(),"real dedicated server construction fixture");a=first;b=second;
        check(!layers().allowedFree(),"new server Free construction defaults OFF");
        Vars.state.rules.infiniteResources=false;Vars.state.rules.pvp=false;Vars.state.rules.placeRangeCheck=false;Vars.state.rules.polygonCoreProtection=false;
        a.team(Team.sharded);b.team(Team.crux);a.team().rules().infiniteResources=false;b.team().rules().infiniteResources=false;
        spawn(a,20,66);spawn(b,30,66);
        for(Player player:new Player[]{a,b}){player.core().items.set(Items.copper,500);player.core().items.set(Items.lead,500);player.core().items.set(Items.titanium,500);}
        Vars.world.tile(20,72).setNet(Blocks.copperWall,a.team(),0);Vars.world.tile(24,72).setNet(Blocks.copperWall,a.team(),0);
        Core.settings.put(LayerRuntime.FREE_PERMISSION,false);rejectedBefore=network.rejectedPackets();captureCost();next(1);
    }
    boolean serverTick(){
        if(failure!=null)throw new IllegalStateException("Construction client report failed",failure);
        if(phase==0)return false;if(seconds()-phaseAt>50)throw new AssertionError("Layer network phase "+phase+" timed out; reports="+reports+", objects="+layers().grid().size()+", result="+layers().lastResult());
        if(phase==1&&reports.size()==2&&age()>.5){
            check(layers().grid().size()==0,"host denies unauthorized floating placement");check(copper()==copperBefore&&lead()==leadBefore,"rejected placement consumes no resources");check(network.rejectedPackets()>rejectedBefore,"forged layer-state channel rejected");
            put("freePermissionAndStateForgeryRejected",true);captureCost();rejectedBefore=network.rejectedPackets();next(2);
        }else if(phase==2){
            var c=layers().grid().at(20,72,1,LayerComponent.Kind.PLATFORM);if(c!=null)platformId=c.id();
            if(platformId>0&&reports.size()==2&&age()>.5){
                var price=layers().catalog().entry(LayerCatalog.PLATFORM,true,layers().grid().coordinates().spacing());
                check(copper()==copperBefore-cost(price,Items.copper)&&lead()==leadBefore-cost(price,Items.lead),"exactly one native platform recipe consumed");
                check(network.rejectedPackets()>rejectedBefore,"identical reliable construction request replay rejected");check(layers().grid().size()==1&&c.team()==a.team().id,"one authoritative component after replay");
                put("reliableReplayAndSingleCost",true);put("twoClientStructuralPlacement",true);next(3);
            }
        }else if(phase==3&&reports.size()==2&&age()>.4){
            check(layers().grid().get(platformId)!=null,"foreign client cannot remove another team's layer component");put("foreignRemovalDenied",true);
            Core.settings.put(LayerRuntime.FREE_PERMISSION,true);captureCost();next(4);
        }else if(phase==4){
            var c=layers().grid().at(22,72,3,LayerComponent.Kind.BLOCK);if(c!=null)freeId=c.id();
            if(freeId>0&&reports.size()==2&&age()>.4){
                var price=layers().catalog().entry("copper-wall",false,layers().grid().coordinates().spacing());
                check(c.mode()==LayerComponent.Mode.FREE&&layers().solver().support(c.id()).status()==StructuralSolver.Status.FREE,"explicit host permission creates persistent Free component");
                check(copper()==copperBefore-cost(price,Items.copper),"Free placement retains native recipe cost");put("authorizedFreePlacement",true);
                Core.settings.put(LayerRuntime.FREE_PERMISSION,false);captureCost();expectedInventory=7;next(5);
            }
        }else if(phase==5){
            var c=layers().grid().at(24,72,1,LayerComponent.Kind.BLOCK);if(c!=null){containerId=c.id();var build=layers().building(containerId);if(build!=null&&build.items!=null&&!seededInventory){build.items.set(Items.copper,7);seededInventory=true;}}
            if(containerId>0&&seededInventory&&reports.size()==2&&age()>.5){
                var price=layers().catalog().entry(Blocks.container.name,false,layers().grid().coordinates().spacing());
                check(copper()==copperBefore-cost(price,Items.copper)&&lead()==leadBefore-cost(price,Items.lead),"elevated native container pays unchanged original recipe");
                check(layers().building(containerId).items.get(Items.copper)==7,"server owns native elevated inventory");
                check(layers().grid().get(freeId).mode()==LayerComponent.Mode.FREE&&!layers().allowedFree(),"revocation changes future placements without converting old Free structures");
                put("nativeInventoryAndPersistentModeReplicated",true);a.unit().addItem(Items.copper,11);expectedInventory=12;next(6);
            }
        }else if(phase==6&&reports.size()==2&&age()>.4){
            check(layers().building(containerId).items.get(Items.copper)==12&&a.unit().stack.amount==6,"authenticated deposit transfers existing cargo exactly once");
            put("authoritativeNativeInventoryTransfer",true);oldB=b;next(7);
        }else if(phase==7){
            for(Player player:Groups.player)if(player.name.equals("FPV-Lab-B")&&player!=oldB){if(b!=player){b=player;b.team(Team.crux);spawn(b,30,66);rejoined=true;}}
            if(rejoined&&reports.size()==2&&network.fixtureFlight(b)!=null&&age()>1){
                check(network.sessions()==2,"reconnected client obtains new authenticated session");check(layers().grid().size()==3,"reconnect did not duplicate upper components");put("reconnectLateJoinFullState",true);captureCost();next(8);
            }
        }else if(phase==8&&reports.size()==2&&age()>.4){
            check(layers().grid().get(freeId)==null&&layers().grid().size()==2,"own removal replicated without deleting other floors");
            check(Vars.world.tile(20,72).block()==Blocks.copperWall&&Vars.world.tile(24,72).block()==Blocks.copperWall,"all commands preserve Z0 foundation tiles");
            var recipe=layers().catalog().entry("copper-wall",false,layers().grid().coordinates().spacing());int refund=Math.round(cost(recipe,Items.copper)*Vars.state.rules.deconstructRefundMultiplier);
            check(copper()==copperBefore+refund,"native deconstruction refund applied once");
            put("ownRemovalAndNativeRefund",true);
            Core.settings.put(LayerRuntime.FREE_PERMISSION,true);var placed=layers().apply(a,LayerActions.Action.place(28,72,4,Blocks.sorter.name,false,0,LayerComponent.Mode.FREE));Core.settings.put(LayerRuntime.FREE_PERMISSION,false);
            check(placed.accepted(),"configuration fixture placed on real server: "+placed.reason());sorterId=placed.componentId();
            Vars.world.tile(20,68).setNet(Blocks.container,a.team(),0);Vars.world.tile(20,68).build.items.set(Items.copper,20);next(9);
        }else if(phase==9&&reports.size()==2&&age()>.4){
            check(layers().building(sorterId).config()==Items.lead,"client configuration applied to real native sorter");put("nativeConfigurationReplicated",true);next(10);
        }else if(phase==10&&reports.size()==2&&age()>.4){
            check(Vars.world.tile(20,68).build.items.get(Items.copper)==16&&a.unit().stack.amount==10,"real client ground withdrawal changes exact cargo");expectedInventory=22;next(11);
        }else if(phase==11&&reports.size()==2&&age()>.4){
            check(layers().building(containerId).items.get(Items.copper)==22&&a.unit().stack.amount==0,"ground-to-upper cargo conserved over real network");put("groundToUpperCargoReplicated",true);
            Core.settings.put(LayerRuntime.FREE_PERMISSION,true);captureCost();rejectedBefore=network.rejectedPackets();next(12);
        }else if(phase==12&&reports.size()==2&&age()>.4){
            var copy=layers().grid().at(32,72,3,LayerComponent.Kind.BLOCK);
            check(copy!=null&&layers().building(copy.id()).config()==Items.lead,"real remote Z4-to-Z3 schematic copies native configuration");
            var recipe=layers().catalog().entry(Blocks.sorter.name,false,layers().grid().coordinates().spacing());
            check(copper()==copperBefore-cost(recipe,Items.copper)&&lead()==leadBefore-cost(recipe,Items.lead),"replayed schematic pays exactly one ordinary recipe");
            check(network.rejectedPackets()>rejectedBefore&&layers().pastes().pending(a)==0,"reliable stamp replay rejected and authoritative build queue completed");
            put("crossFloorSchematicReplaySingleCostAndConfig",true);
            if(powerPass){beginPower();return false;}
            var placed=layers().apply(a,LayerActions.Action.place(28,66,3,Blocks.duo.name,false,0,LayerComponent.Mode.FREE));check(placed.accepted(),"native upper Duo network fixture");turretId=placed.componentId();
            Vars.world.tile(28,66).setNet(Blocks.duo,Team.crux,0);
            var old=b.unit();var target=UnitTypes.flare.create(Team.crux);target.set(38*8,66*8);target.health=target.maxHealth=5000;target.add();b.unit(target);old.remove();aimTargetId=target.id;next(13);
        }else if(phase==13&&reports.size()==2&&age()>.4&&network.fixtureFlight(b)!=null){
            var turret=(mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)layers().building(turretId);
            check(a.unit()==turret.unit()&&turret.controlled(),"authenticated client controls exact Z3 building proxy");
            check(validTurretOwner()&&Vars.world.tile(28,66).build.team==Team.crux,"native wire decoding does not possess/change the overlapping Z0 turret");
            var s=network.fixtureFlight(b).state();s.x=38;s.y=12;s.z=-66;s.vx=s.vy=s.vz=0;b.unit().set(38*8,66*8);targetHealth=b.unit().health;
            turret.handleStack(Items.copper,30,null);manualShots=turret.totalShots;next(14);
        }else if(phase==14&&reports.size()==2&&age()>.7){
            var turret=(mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)layers().building(turretId);
            check(turret.totalShots>manualShots&&turret.totalAmmo<60&&b.unit().health<targetHealth,"remote native item ammunition produces server XYZ damage to higher aircraft");
            check(turret.x==28*8&&turret.y==66*8&&layers().grid().get(turretId).layer()==3,"full remote movement axes cannot move turret");
            check(layers().turretControls().pitch(turret)>0,"server manual barrel pitch follows authenticated XYZ aim");put("upperTurretManualXYZAmmoDamageAndStationary",true);next(15);
        }else if(phase==15&&reports.size()==2&&age()>.4){
            var turret=(mindustry.world.blocks.defense.turrets.Turret.TurretBuild)layers().building(turretId);check(!turret.controlled()&&!layers().turretControls().owns(a)&&a.unit().type==UnitTypes.alpha,"remote release returns original native unit and restores AI");put("upperTurretReleaseBothClients",true);next(16);
        }else if(phase==16&&reports.size()==2&&age()>.4){
            check(layers().turretControls().owns(a),"remote re-possession accepted");layers().damage(turretId,100000);layers().turretControls().update();check(!layers().turretControls().owns(a)&&a.unit().type==UnitTypes.alpha,"server destruction restores previous player unit");next(17);
        }else if(phase==17&&reports.size()==2&&age()>.4){
            check(layers().grid().get(turretId)==null&&Vars.world.tile(28,66).build.team==Team.crux,"upper destruction preserves overlapping native ground turret");put("upperTurretDestructionAndNoGroundAlias",true);
            beginPower();
        }
        if(phase==18&&reports.size()==2&&age()>.6){
            check(layers().building(powerConsumerId).power.status>.5,"remote command powers legitimate Z5 consumer from fueled ground RTG");
            check(layers().building(powerConsumerId).power.graph==Vars.world.tile(21,64).build.power.graph,"one shared authority native graph");
            check(layers().transfers().links().stream().filter(l->l.kind()==LayerVerticalTransfers.Kind.POWER).count()==1,"replayed/reversed power link cannot duplicate energy edge");
            check(copper()==copperBefore&&lead()==leadBefore,"ordinary wire has no duplicated cost or mandatory riser cost");
            check(network.rejectedPackets()>rejectedBefore,"replayed reliable power packet rejected");put("xyzPowerRemoteLinkAndReplay",true);next(19);
        }else if(phase==19&&reports.size()==2&&age()>.4){
            check(layers().building(powerConsumerId).power.status==0&&layers().transfers().links().isEmpty(),"remote unlink cuts power and splits network");put("xyzPowerRemoteUnlink",true);next(20);
        }else if(phase==20&&reports.size()==2&&age()>.4){
            check(layers().building(powerConsumerId).power.status>.5,"remote reconnect restores generation");put("xyzPowerRemoteReconnect",true);Vars.world.tile(24,64).setNet(Blocks.air,Team.derelict,0);next(21);
        }else if(phase==21&&reports.size()==2&&age()>.4){
            check(layers().building(powerConsumerId).power.status==0&&layers().transfers().links().isEmpty(),"transmitter destruction replicated and cuts power");put("xyzPowerDestructionAndClientState",true);put("status","passed");put("checks",checks);next(22);return true;
        }
        return phase==22;
    }
    private void beginPower(){
        Core.settings.put(LayerRuntime.FREE_PERMISSION,true);Vars.state.rules.lighting=true;a.core().items.set(Items.silicon,50);a.core().items.set(Items.graphite,50);Vars.world.tile(21,64).setNet(Blocks.rtgGenerator,a.team(),0);Vars.world.tile(24,64).setNet(Blocks.powerNodeLarge,a.team(),0);
            var generator=Vars.world.tile(21,64).build;generator.items.add(Items.thorium,10);Vars.world.tile(24,64).build.configureAny(generator.pos());
            var powerNodeResult=layers().apply(a,LayerActions.Action.place(24,64,5,Blocks.powerNodeLarge.name,false,0,LayerComponent.Mode.FREE));check(powerNodeResult.accepted(),"power fixture node: "+powerNodeResult.reason());powerNodeId=powerNodeResult.componentId();
            var powerConsumerResult=layers().apply(a,LayerActions.Action.place(27,64,5,Blocks.illuminator.name,false,0,LayerComponent.Mode.FREE));check(powerConsumerResult.accepted(),"power fixture consumer: "+powerConsumerResult.reason());powerConsumerId=powerConsumerResult.componentId();
            check(powerNodeId>0&&powerConsumerId>0,"real network Z5 electrical fixture created");captureCost();rejectedBefore=network.rejectedPackets();next(18);
    }
    void clientMaintainConnection(){
        if(role.equals("client-b")&&reconnectAttempted&&!Vars.net.active()&&!reconnectQueued){reconnectQueued=true;arc.util.Timer.schedule(()->Core.app.post(()->{Vars.netClient.beginConnecting();Vars.net.connect("127.0.0.1",Integer.getInteger("fpv.netSmokePort",16567),()->{});}),.6f);}
    }
    void clientTick(){
        if(phase==0||!Vars.net.client()||!network.allowed()||Vars.player.dead())return;
        if(phase==22){clientComplete=true;put("status","passed");put("checks",checks);return;}
        if(seconds()-clientPhaseAt<.5)return;
        LayerRuntime layers=layers();boolean first=role.equals("client-a");
        Core.settings.put(LayerRuntime.FREE_PERMISSION,true);
        if(layers.allowedFree()!=expectedFree)return;
        if(lastSent!=phase){
            if(phase>=3&&platformId<=0||phase>=6&&containerId<=0)return;
            lastSent=phase;
            if(phase==1){
                if(first)layers.submit(LayerActions.Action.place(22,72,3,"",true,0,LayerComponent.Mode.FREE));
                else Call.serverBinaryPacketReliable(FpvNetwork.PACKET,NetProtocol.encode(NetProtocol.CHANNEL,network.fixtureEpoch(),20_000_000,out->{out.writeUTF("layered-world");out.writeInt(-1);}));
            }else if(phase==2&&first){
                byte[] request=network.fixtureConstructionPacket(LayerActions.Action.place(20,72,1,"",true,0,LayerComponent.Mode.STRUCTURAL));layers.acceptResult(LayerActions.Result.queued());
                Call.serverBinaryPacketReliable("fpv7-layers",request);Call.serverBinaryPacketReliable("fpv7-layers",request);
            }else if(phase==3&&!first)layers.submit(LayerActions.Action.remove(platformId));
            else if(phase==4&&first)layers.submit(LayerActions.Action.place(22,72,3,"copper-wall",false,0,LayerComponent.Mode.FREE));
            else if(phase==5&&first)layers.submit(LayerActions.Action.place(24,72,1,Blocks.container.name,false,0,LayerComponent.Mode.STRUCTURAL));
            else if(phase==6&&first)layers.submit(LayerActions.Action.deposit(containerId,Items.copper.id,5));
            else if(phase==7&&!first&&!reconnectAttempted){reconnectAttempted=true;Vars.net.disconnect();return;}
            else if(phase==8&&first)layers.submit(LayerActions.Action.remove(freeId));
            else if(phase==9&&first)layers.submit(LayerActions.Action.configure(sorterId,Items.lead));
            else if(phase==10&&first)layers.submit(LayerActions.Action.withdraw(LayerVerticalTransfers.groundId(20,68),Items.copper.id,4));
            else if(phase==11&&first)layers.submit(LayerActions.Action.deposit(containerId,Items.copper.id,10));
            else if(phase==12&&first){
                var schematic=LayerSchematic.capture(layers,4,28,72,28,72);
                byte[] request=network.fixtureConstructionPacket(LayerActions.Action.paste(new LayerSchematic.Stamp(schematic,32,72),3,LayerComponent.Mode.FREE));
                layers.acceptResult(LayerActions.Result.queued());Call.serverBinaryPacketReliable("fpv7-layers",request);Call.serverBinaryPacketReliable("fpv7-layers",request);
            }
            else if((phase==13||phase==16)&&first)layers.submit(LayerActions.Action.possessTurret(turretId));
            else if(phase==13&&!first)layers.submit(LayerActions.Action.possessTurret(turretId));
            else if(phase==15&&first)layers.submit(LayerActions.Action.releaseTurret());
            else if(phase==18){
                var action=LayerActions.Action.link(LayerVerticalTransfers.groundId(24,64),powerNodeId,LayerVerticalTransfers.Kind.POWER,false);
                if(first){byte[] packet=network.fixtureConstructionPacket(action);layers.acceptResult(LayerActions.Result.queued());Call.serverBinaryPacketReliable("fpv7-layers",packet);Call.serverBinaryPacketReliable("fpv7-layers",packet);}else layers.submit(action);
            }
            else if(phase==19&&first)layers.submit(LayerActions.Action.link(powerNodeId,LayerVerticalTransfers.groundId(24,64),LayerVerticalTransfers.Kind.POWER,true));
            else if(phase==20&&first)layers.submit(LayerActions.Action.link(LayerVerticalTransfers.groundId(24,64),powerNodeId,LayerVerticalTransfers.Kind.POWER,false));
        }
        if(lastReport==phase)return;
        if(phase==1&&first){if(layers.lastResult().pending())return;check(!layers.lastResult().accepted()&&layers.lastResult().reason().contains("host"),"real remote unauthorized Free command rejected");}
        if(phase==3&&!first){if(layers.lastResult().pending())return;check(!layers.lastResult().accepted()&&layers.lastResult().reason().contains("another team"),"real remote foreign remove rejected");}
        if(first&&Set.of(2,4,5,6,8,9,10,11,12).contains(phase)){if(layers.lastResult().pending())return;check(layers.lastResult().accepted(),"authenticated command accepted at phase "+phase+": "+layers.lastResult().reason());}
        if(phase>=2){var platform=layers.grid().get(platformId);if(platform==null||platform.kind()!=LayerComponent.Kind.PLATFORM||platform.layer()!=1||platform.mode()!=LayerComponent.Mode.STRUCTURAL)return;}
        if(phase>=4&&phase<8){var free=layers.grid().get(freeId);if(free==null||free.mode()!=LayerComponent.Mode.FREE||free.layer()!=3)return;}
        if(phase==8&&layers.grid().get(freeId)!=null)return;
        if(phase>=5){var build=layers.building(containerId);if(build==null||build.items==null||build.items.get(Items.copper)!=expectedInventory)return;}
        if(phase>=9){var build=layers.building(sorterId);if(build==null||build.config()!=Items.lead)return;}
        if(phase==12){var copy=layers.grid().at(32,72,3,LayerComponent.Kind.BLOCK);if(copy==null||layers.building(copy.id()).config()!=Items.lead)return;check(layers.grid().size()==4,"both clients receive exactly one cross-floor schematic building");}
        if(phase==13||phase==14||phase==16){
            var turret=layers.building(turretId);if(!(turret instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t)||!t.controlled())return;
            if(first&&Vars.player.unit()!=t.unit())return;if(!first&&phase==13){if(layers.lastResult().pending())return;check(!layers.lastResult().accepted(),"foreign-team turret possession rejected on authority");}
            check(Vars.world.tile(28,66).build.team==Team.crux,"upper possession preserves real lower-floor team");
            if(phase==14){if(!(t instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild item)||item.totalAmmo>=60||network.fixtureWorld().unitState(Groups.unit.getByID(aimTargetId))==null)return;
                if(first&&Vars.player.unit().type!=UnitTypes.block)return;var target=Groups.unit.getByID(aimTargetId);if(target==null||target.health>=targetHealth)return;
                check(layers.turretControls().pitch(t)>0,"both clients receive actual turret pitch");
            }
        }
        if(phase==15&&first){if(layers.turretControls().owns(Vars.player)||Vars.player.unit().type!=UnitTypes.alpha)return;check(layers.lastResult().accepted(),"remote release acknowledged");}
        if(phase==17){if(layers.grid().get(turretId)!=null||first&&(Vars.player.dead()||Vars.player.unit().type!=UnitTypes.alpha))return;check(Vars.world.tile(28,66).build.team==Team.crux,"upper destruction does not change ground turret");}
        if(phase>=18&&phase<=21){
            if(phase<=20&&(first||phase==18)){if(layers.lastResult().pending())return;if(first)check(layers.lastResult().accepted(),"real authenticated power command accepted");else check(!layers.lastResult().accepted(),"foreign team power command rejected");}
            var consumer=layers.building(powerConsumerId);if(consumer==null)return;
            boolean powered=phase==18||phase==20;if(powered?consumer.power.status<=.5:consumer.power.status!=0)return;
            boolean linked=layers.nativeBuildings().linkedPower(LayerVerticalTransfers.groundId(24,64),powerNodeId);if(linked!=powered)return;
            if(phase==21&&Vars.world.tile(24,64).block()!=Blocks.air)return;
            float status=consumer.power.status;layers.update(.01);check(consumer.power.status==status,"client runtime does not independently generate energy");
            check(linked==powered,"both clients observe synchronized XYZ wire and electrical state");
        }
        if(phase==7&&!first&&!reconnectQueued)return;
        check(layers.allowedFree()==expectedFree,"server policy overrides local client preferences");
        check(Vars.world.tile(20,72).block()==Blocks.copperWall,"client native foundation remains Z0");
        if(phase>=2){double hp=layers.grid().get(platformId).health();layers.damage(platformId,10000);check(layers.grid().get(platformId).health()==hp,"client cannot apply local structural damage");}
        lastReport=phase;
        Call.serverBinaryPacketReliable(REPORT,NetProtocol.bytes(out->{out.writeInt(phase);out.writeLong(platformId);out.writeLong(freeId);out.writeLong(containerId);out.writeInt(phase>=5?layers.building(containerId).items.get(Items.copper):0);out.writeBoolean(layers.allowedFree());out.writeBoolean(reconnectQueued);out.writeInt(layers.grid().size());}));
        put("lastObservedPhase",phase);put("checks",checks);
    }
    boolean clientComplete(){return clientComplete;}
    void diagnostics(){
        var player=role.equals("server")?a:Vars.player;var unit=player==null?null:player.unit();put("debugPhase",phase);put("debugLastSent",lastSent);put("debugLastReport",lastReport);
        put("debugPilotDead",player==null||player.dead());put("debugAllowed",network.allowed());put("debugFree",layers().allowedFree());
        put("debugPilotHealth",unit==null?0:(int)unit.health);put("debugPilotAdded",unit!=null&&unit.isAdded());
        result.add("debugPilotType",unit==null?"none":unit.type.name);result.add("debugResult",layers().lastResult().toString());
        put("debugOwns",player!=null&&layers().turretControls().owns(player));put("debugGroupPlayer",player!=null&&Groups.player.getByID(player.id)==player);
        var build=layers().building(turretId);put("debugBuildingValid",build!=null&&build.isValid());put("debugBuildingTeam",build==null?-1:build.team.id);
        if(build instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t){put("debugTurretControlled",t.controlled());put("debugTurretUnitId",t.unit().id);put("debugPilotId",player==null?-1:player.id);}
    }
    Jval result(){return result;}
    NetworkControls controls(){
        if(phase!=14||!role.equals("client-a"))return NetworkControls.hold();var turret=layers().building(turretId);var target=Groups.unit.getByID(aimTargetId);if(turret==null||target==null)return NetworkControls.hold();var pose=network.fixtureWorld().unitState(target);if(pose==null)return NetworkControls.hold();
        var direction=fpv.combat.CombatGeometry.direction(pose.x()-turret.x/8,pose.y()-turret.y/8,pose.z()-network.fixtureWorld().buildingTop(turret)-.31);
        return new NetworkControls(true,3,0,0,0,0,1,1,1,1,0,direction.x(),direction.y(),direction.z(),true);
    }
    private boolean validTurretOwner(){return fpv.control.UnitControlCapabilities.validOwner(a,a.unit());}
    private byte[] plan(){return NetProtocol.bytes(out->{out.writeInt(phase);out.writeLong(platformId);out.writeLong(freeId);out.writeLong(containerId);out.writeInt(expectedInventory);out.writeBoolean(phase>0&&layers().allowedFree());out.writeLong(sorterId);out.writeLong(turretId);out.writeInt(aimTargetId);out.writeFloat(targetHealth);out.writeLong(powerNodeId);out.writeLong(powerConsumerId);});}
    private void acceptPlan(byte[] bytes){if(role.equals("server"))return;NetProtocol.read(bytes,in->{int next=in.readInt();if(next!=phase){phase=next;clientPhaseAt=seconds();}platformId=in.readLong();freeId=in.readLong();containerId=in.readLong();expectedInventory=in.readInt();expectedFree=in.readBoolean();sorterId=in.readLong();turretId=in.readLong();aimTargetId=in.readInt();targetHealth=in.readFloat();powerNodeId=in.readLong();powerConsumerId=in.readLong();return null;});}
    private void receiveReport(Player player,byte[] bytes){
        if(!Vars.net.server()||!Set.of("FPV-Lab-A","FPV-Lab-B").contains(player.name))return;
        try{NetProtocol.read(bytes,in->{int reported=in.readInt();long platform=in.readLong(),free=in.readLong(),container=in.readLong();int inventory=in.readInt();boolean permitted=in.readBoolean(),reconnected=in.readBoolean();int count=in.readInt();if(reported!=phase)return null;
            check(platform==platformId&&free==freeId&&container==containerId,"clients observe identical stable component IDs");check(permitted==layers().allowedFree(),"clients observe authoritative Free permission");check(count==layers().grid().size(),"client sparse world count matches server");if(phase>=5)check(inventory==expectedInventory,"replicated native upper inventory contents agree");
            if(phase==7&&player.name.equals("FPV-Lab-B"))check(reconnected&&player!=oldB,"late join report came from reconnected authenticated player");reports.add(player.name);return null;});}
        catch(Throwable error){failure=error;}
    }
    private void spawn(Player player,int x,int y){Unit old=player.unit(),unit=UnitTypes.alpha.create(player.team());unit.set(x*Vars.tilesize,y*Vars.tilesize);unit.add();player.unit(unit);if(old!=null&&old!=unit)old.remove();}
    private int copper(){return a.core().items.get(Items.copper);}private int lead(){return a.core().items.get(Items.lead);}
    private void captureCost(){copperBefore=copper();leadBefore=lead();}
    private static int cost(LayerCatalog.Entry entry,Item item){for(var stack:entry.requirements())if(stack.item==item)return Math.max(0,Math.round(stack.amount*Vars.state.rules.buildCostMultiplier));return 0;}
    private void next(int value){phase=value;phaseAt=seconds();reports.clear();put("phase",phase);Log.info("[FPV-NET-LAYERS] Phase @",phase);}
    private double age(){return seconds()-phaseAt;}private static double seconds(){return System.nanoTime()/1e9;}
    private void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private void put(String key,boolean value){result.add(key,Jval.valueOf(value));}private void put(String key,int value){result.add(key,Jval.valueOf(value));}private void put(String key,String value){result.add(key,value);}
}
