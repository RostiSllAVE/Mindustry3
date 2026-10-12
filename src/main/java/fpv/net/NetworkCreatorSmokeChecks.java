package fpv.net;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Log;
import arc.util.serialization.Jval;
import fpv.control.CreatorFlightSystem;
import fpv.render.FpvWorldRenderer;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import java.util.*;

/** Actual two-client regression: only authenticated production input creates motion or weapon shots. */
final class NetworkCreatorSmokeChecks {
    private static final String REPORT="fpv7-lab-creator";
    private static final Set<Integer> REPORT_PHASES=Set.of(1,3,5,9,10,11,13,15);
    private final FpvNetwork network;
    private final String role=System.getProperty("fpv.netSmokeRole","");
    private final Jval result=Jval.newObject();
    private final Set<Integer> reports=new HashSet<>();
    private final int[] ids={-1,-1},types={-1,-1};
    private final double[] expectedZ=new double[2],baseZ=new double[2];
    private final boolean[] expectedActive=new boolean[2],expectedFlying=new boolean[2];
    private Player a,b;private Unit dagger,atrax;private UnitController daggerAI,atraxAI;
    private ItemTurret.ItemTurretBuild turret;private Unit proxy;
    private int phase,checks,lastReported=-1,ammo;private double phaseAt,clientPhaseAt;
    private boolean expectedAllowed,clientComplete,exhaustVerified,risingBullet,landingSeen,releaseLandingSeen,turretReleased;
    private Throwable asyncFailure;
    private float observedDaggerHealth=Float.NaN,observedAtraxHealth=Float.NaN;
    private String lastDagger="not sampled",lastAtrax="not sampled",lastDaggerDamage="none",lastAtraxDamage="none";
    NetworkCreatorSmokeChecks(FpvNetwork network){
        if(role.isEmpty()||System.getProperty("fpv.netSmokeRoot","").isBlank())throw new IllegalStateException("Explicit isolated network lab required");
        this.network=network;network.registerStateChannel("lab-creator",this::plan,this::acceptPlan);
        if(role.equals("server"))Vars.netServer.addBinaryPacketHandler(REPORT,this::receiveReport);
        if(role.equals("server")){
            arc.Events.on(mindustry.game.EventType.UnitDamageEvent.class,e->{
                if(e.unit!=dagger&&e.unit!=atrax)return;
                String damage="bullet="+(e.bullet==null?"null":e.bullet.type+" owner="+e.bullet.owner+" team="+e.bullet.team)+" unit="+describe(e.unit);
                if(e.unit==dagger)lastDaggerDamage=damage;else lastAtraxDamage=damage;
                Log.info("[FPV-NET-CREATOR] Native damage @",damage);
            });
            arc.Events.on(mindustry.game.EventType.UnitDestroyEvent.class,e->{if(e.unit==dagger||e.unit==atrax)Log.err("[FPV-NET-CREATOR] Destroyed "+describe(e.unit),new RuntimeException("Creator fixture native destruction source"));});
        }
        result.add("physicalInputTested",Jval.valueOf(false));
    }
    void serverBegin(Player first,Player second){
        check(Vars.headless&&Vars.net.server(),"official isolated dedicated server");a=first;b=second;
        check(!Core.settings.getBool(CreatorFlightSystem.SETTING,false),"fresh server ground-flight permission defaults OFF");
        // The preceding combat phases use opposing teams. This landing fixture uses allies so
        // restored native AI cannot kill the other retained test body while clients acknowledge it.
        Vars.state.rules.pvp=false;b.team(a.team());
        dagger=spawn(a,UnitTypes.dagger,40,20,true);atrax=spawn(b,UnitTypes.atrax,60,20,true);
        next(1);
    }
    boolean serverTick(){
        if(asyncFailure!=null)throw new IllegalStateException("Creator client report failed",asyncFailure);
        if(phase==0)return false;
        if(seconds()-phaseAt>65)throw new AssertionError("Creator network phase timed out: "+phase+" reports="+reports+" dagger="+describe(dagger)+" atrax="+describe(atrax));
        var creator=network.creatorFlight();
        observeHealth();
        if(phase==1&&age()>1.5&&reports.size()==2){
            check(!creator.allowed()&&!creator.active(dagger)&&!creator.active(atrax),"client local preference and explicit input cannot authorize flight");
            check(!pose(dagger).flying()&&!pose(atrax).flying(),"both native ground bodies remain grounded when host denies");
            baseZ[0]=pose(dagger).z();baseZ[1]=pose(atrax).z();result.add("defaultOffRejectsClientRequest",Jval.valueOf(true));
            Core.settings.put(CreatorFlightSystem.SETTING,true);next(2);
        }else if(phase==2&&age()>1.7){
            if(!creator.active(dagger)||!creator.active(atrax))return false;
            check(pose(dagger).z()>baseZ[0]+.5&&pose(atrax).z()>baseZ[1]+.5,"both real client climb commands change authoritative Z");
            check(!dagger.type.flying&&!atrax.type.flying,"creator mode never mutates native shared UnitType");next(3);
        }else if(phase==3&&age()>1&&reports.size()==2){
            check(pose(dagger).flying()&&pose(atrax).flying(),"canonical combat poses carry runtime flight");
            result.add("twoClientAuthorizedXYZAndGpuExhaust",Jval.valueOf(true));Core.settings.put(CreatorFlightSystem.SETTING,false);next(4);
        }else if(phase==4){
            landingSeen|=creator.landing(dagger)&&creator.landing(atrax);
            if(creator.active(dagger)||creator.active(atrax))return false;
            check(landingSeen,"host revocation enters actual safe-landing state");check(!dagger.dead&&!atrax.dead&&dagger.canPassOn()&&atrax.canPassOn(),"host revocation lands both native units alive on passable terrain; dagger="+describe(dagger)+"; atrax="+describe(atrax)+"; priorDagger="+lastDagger+"; priorAtrax="+lastAtrax+"; daggerDamage="+lastDaggerDamage+"; atraxDamage="+lastAtraxDamage);
            check(!dagger.isFlying()&&!atrax.isFlying(),"native elevation restored after host revocation");next(5);
        }else if(phase==5&&age()>1&&reports.size()==2){
            result.add("hostRevocationSafeLandingReplicated",Jval.valueOf(true));baseZ[0]=pose(dagger).z();baseZ[1]=pose(atrax).z();
            Core.settings.put(CreatorFlightSystem.SETTING,true);next(6);
        }else if(phase==6&&age()>1.5){
            if(!creator.active(dagger)||!creator.active(atrax))return false;
            check(pose(dagger).z()>baseZ[0]+.5&&pose(atrax).z()>baseZ[1]+.5,"both units can enter a fresh authorized flight after landing");next(7);
        }else if(phase==7&&age()>.8){
            Vars.world.tile(42,36).setNet(Blocks.duo,a.team(),0);turret=(ItemTurret.ItemTurretBuild)Vars.world.tile(42,36).build;
            for(int i=0;i<12;i++)turret.handleItem(null,Items.copper);ammo=turret.totalAmmo;turret.rotation=90;turret.reloadCounter=((Turret)turret.block).reload;
            proxy=turret.unit();a.unit(proxy);daggerAI=dagger.controller();spawn(b,UnitTypes.dagger,75,20,false);atraxAI=atrax.controller();
            check(dagger.isAI()&&atrax.isAI(),"native possession transfer releases old bodies to AI");next(8);
        }else if(phase==8){
            releaseLandingSeen|=creator.landing(dagger)&&creator.landing(atrax);
            check(turret.x==42*8&&turret.y==36*8&&proxy.x==turret.x&&proxy.y==turret.y,"remote WASD/QE cannot move controlled native turret");
            check(!creator.active(proxy),"host permission never converts turret proxy into aircraft");
            for(Bullet bullet:Groups.bullet)if(bullet.owner==turret){var p=network.fixtureCombat().projectileState(bullet);risingBullet|=p!=null&&p.vz()>1;}
            if(age()<2||creator.active(dagger)||creator.active(atrax)||!risingBullet)return false;
            check(releaseLandingSeen,"control change enters safe landing for both abandoned aircraft");
            check(!dagger.dead&&!atrax.dead&&dagger.canPassOn()&&atrax.canPassOn(),"released aircraft land safely; dagger="+describe(dagger)+"; atrax="+describe(atrax)+"; daggerDamage="+lastDaggerDamage+"; atraxDamage="+lastAtraxDamage);
            check(dagger.controller()==daggerAI&&atrax.controller()==atraxAI,"landing restores exactly the released native AI controllers");
            check(turret.totalAmmo<ammo,"actual remote turret trigger consumes native ammunition");
            check(risingBullet,"actual remote turret uses the received upward XYZ aim");
            result.add("controlChangeSafeLandingAndAIRestored",Jval.valueOf(true));result.add("stationaryTurretNativeAmmoXYZ",Jval.valueOf(true));next(9);
        }else if(phase==9&&age()>1&&reports.size()==2){
            spawn(a,UnitTypes.dagger,82,20,false);check(proxy.isAI(),"releasing controlled turret restores native AI");turretReleased=true;next(10);
        }else if(phase==10&&age()>1&&reports.size()==2){
            check(!creator.active(dagger)&&!creator.active(atrax),"released grounded bodies do not regain stale creator requests");
            Core.settings.put(CreatorFlightSystem.SETTING,false);
            dagger=spawn(a,UnitTypes.vela,40,20,true);atrax=spawn(b,UnitTypes.nova,60,20,true);next(11);
        }else if(phase==11&&age()>1&&reports.size()==2){
            check(!network.nativeBoost().active(dagger)&&!network.nativeBoost().active(atrax),"ordinary boost starts grounded");
            baseZ[0]=pose(dagger).z();baseZ[1]=pose(atrax).z();next(12);
        }else if(phase==12&&age()>2){
            if(!network.nativeBoost().active(dagger)||!network.nativeBoost().active(atrax))return false;
            check(pose(dagger).z()>baseZ[0]+.5&&pose(atrax).z()>baseZ[1]+.5,"real remote native boost XYZ for Vela and Nova");
            check(!creator.allowed()&&!creator.active(dagger)&&!creator.active(atrax),"natural boost needs no Creator permission");next(13);
        }else if(phase==13&&age()>1&&reports.size()==2){
            check(!dagger.canShoot()&&!atrax.canShoot(),"native per-unit airborne fire restriction on server");
            result.add("nativeBoostTwoClientsXYZGpuRestrictions",Jval.valueOf(true));landingSeen=false;next(14);
        }else if(phase==14){
            landingSeen|=network.nativeBoost().landing(dagger)&&network.nativeBoost().landing(atrax);
            if(network.nativeBoost().active(dagger)||network.nativeBoost().active(atrax))return false;
            check(landingSeen&&!dagger.dead&&!atrax.dead&&!dagger.isFlying()&&!atrax.isFlying(),"both remote boost pilots physically land alive; seen="+landingSeen+" vela="+describe(dagger)+" nova="+describe(atrax));
            check(dagger.canShoot()&&atrax.canShoot(),"ground native firing restored");next(15);
        }else if(phase==15&&age()>1&&reports.size()==2){
            result.add("nativeBoostLandingReplicated",Jval.valueOf(true));
            result.add("checks",Jval.valueOf(checks));result.add("status","passed");return true;
        }
        return false;
    }
    void clientTick(FpvWorldRenderer renderer){
        if(phase==0||!Vars.net.client()||!network.allowed()||Vars.player.dead())return;
        Core.settings.put(CreatorFlightSystem.SETTING,true); // An untrusted local preference must never enable host-denied flight.
        if(!REPORT_PHASES.contains(phase)||lastReported==phase||seconds()-clientPhaseAt<1)return;
        var creator=network.creatorFlight();if(creator.allowed()!=expectedAllowed)return;
        Unit[] units={Groups.unit.getByID(ids[0]),Groups.unit.getByID(ids[1])};
        for(int i=0;i<2;i++){
            Unit u=units[i];if(u==null||u.type.id!=types[i]||u.type.flying)return;
            Unit3DState p=pose(u);if(p==null||modeActive(u)!=expectedActive[i]||p.flying()!=expectedFlying[i]||Math.abs(p.z()-expectedZ[i])>.35)return;
        }
        if(phase==1){check(Core.settings.getBool(CreatorFlightSystem.SETTING,false)&&!creator.allowed(),"client true preference cannot override host false permission");}
        if(phase==3||phase==13){
            if(renderer==null)return;var p=pose(units[0]);var q=pose(units[1]);
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.far=1000;camera.fov=65;
            camera.position.set(50,(float)Math.max(p.z(),q.z())+10,-3);camera.up.set(0,1,0);camera.lookAt(50,(float)((p.z()+q.z())*.5),-20);camera.update();
            renderer.setShowControlledUnit(true);renderer.render(camera);
            if(!renderer.creatorFlightWasSubmitted(units[0])||!renderer.creatorFlightWasSubmitted(units[1]))return;
            check((phase==13?network.nativeBoost().allowed():creator.allowed())&&modeActive(units[0])&&modeActive(units[1])&&p.flying()&&q.flying(),"both authoritative airborne flags reach both clients");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(role+"-creator-flight.png"));exhaustVerified=true;
        }
        if(phase==9&&role.equals("client-a")){
            Unit u=Vars.player.unit();if(!(u instanceof BlockUnitc)||Math.abs(u.x-42*8)>.01||Math.abs(u.y-36*8)>.01||creator.active(u))return;
        }
        final int reportPhase=phase;
        Call.serverBinaryPacketReliable(REPORT,NetProtocol.bytes(out->{
            out.writeInt(reportPhase);out.writeBoolean(creator.allowed());out.writeBoolean(exhaustVerified);
            for(Unit u:units){out.writeInt(u.id);out.writeInt(u.type.id);out.writeDouble(pose(u).z());out.writeBoolean(pose(u).flying());out.writeBoolean(modeActive(u));out.writeBoolean(u.type.flying);}
        }));
        lastReported=phase;result.add("phase",Jval.valueOf(phase));result.add("exhaustGpuSubmitted",Jval.valueOf(exhaustVerified));result.add("checks",Jval.valueOf(checks));
        if(phase==15){clientComplete=true;result.add("status","passed");}
    }
    NetworkControls controls(){
        boolean climb=phase==1||phase==2||phase==6;boolean creator=phase>=1&&phase<=7;
        boolean turretInput=role.equals("client-a")&&(phase==8||phase==9);
        if(phase==8||phase==9)creator=turretInput;
        double f=turretInput?1:0,r=turretInput?1:0,c=turretInput?1:climb?.2:0;
        return new NetworkControls(true,phase>=11?1:3,0,0,0,0,f,r,phase==12?.35:c,0,0,0,turretInput?.8:1,turretInput?.6:0,turretInput||phase>=11,creator,phase==12||phase==13);
    }
    boolean clientComplete(){return clientComplete;}
    Jval result(){return result;}
    int phase(){return phase;}
    private void receiveReport(Player player,byte[] bytes){
        if(!Vars.net.server()||player!=a&&player!=b)return;
        try{NetProtocol.read(bytes,in->{int reported=in.readInt();boolean permission=in.readBoolean(),gpu=in.readBoolean();
            for(Unit u:new Unit[]{dagger,atrax}){
                int id=in.readInt(),type=in.readInt();double z=in.readDouble();boolean flying=in.readBoolean(),active=in.readBoolean(),typeFlying=in.readBoolean();
                if(reported==phase){Unit3DState p=pose(u);check(id==u.id&&type==u.type.id&&!typeFlying&&p!=null&&Double.isFinite(z)&&Math.abs(z-p.z())<.45&&flying==p.flying()&&active==modeActive(u),"client observes current authoritative creator identity/XYZ/flags");}
            }
            if(reported==phase&&REPORT_PHASES.contains(phase)){check(permission==network.creatorFlight().allowed(),"client permission matches host");if(phase>=3)check(gpu,"client submitted both runtime-flight exhausts to GPU");reports.add(player.id);}
            return true;});
        }catch(Throwable error){asyncFailure=error;}
    }
    private byte[] plan(){return NetProtocol.bytes(out->{out.writeInt(phase);out.writeBoolean(network.creatorFlight().allowed());if(phase>0)for(Unit u:new Unit[]{dagger,atrax}){var p=pose(u);out.writeInt(u.id);out.writeInt(u.type.id);out.writeDouble(p==null?0:p.z());out.writeBoolean(modeActive(u));out.writeBoolean(p!=null&&p.flying());}});}
    private void acceptPlan(byte[] bytes){if(!Vars.net.client())return;NetProtocol.read(bytes,in->{int next=in.readInt();expectedAllowed=in.readBoolean();if(next!=phase){phase=next;clientPhaseAt=seconds();if(phase==13)exhaustVerified=false;}if(phase>0)for(int i=0;i<2;i++){ids[i]=in.readInt();types[i]=in.readInt();expectedZ[i]=in.readDouble();expectedActive[i]=in.readBoolean();expectedFlying[i]=in.readBoolean();}return true;});}
    private Unit spawn(Player player,mindustry.type.UnitType type,double x,double y,boolean removePrevious){Unit old=player.unit(),u=type.create(player.team());u.set((float)x*8,(float)y*8);u.rotation=90;u.add();player.unit(u);if(removePrevious&&old!=null&&old!=u)old.remove();return u;}
    private boolean modeActive(Unit u){return phase>=11?network.nativeBoost().active(u):network.creatorFlight().active(u);}
    private Unit3DState pose(Unit u){return network.fixtureWorld().unitState(u);}
    private void observeHealth(){
        if(dagger!=null&&dagger.health!=observedDaggerHealth){Log.info("[FPV-NET-CREATOR] Health phase @ dagger @",phase,describe(dagger));observedDaggerHealth=dagger.health;}
        if(atrax!=null&&atrax.health!=observedAtraxHealth){Log.info("[FPV-NET-CREATOR] Health phase @ atrax @",phase,describe(atrax));observedAtraxHealth=atrax.health;}
        if(dagger!=null&&!dagger.dead&&dagger.isAdded())lastDagger=describe(dagger);
        if(atrax!=null&&!atrax.dead&&atrax.isAdded())lastAtrax=describe(atrax);
    }
    private String describe(Unit u){if(u==null)return "none";var p=pose(u);var tile=u.tileOn();return u.type.name+"#"+u.id+" dead="+u.dead+" added="+u.isAdded()+" hp="+u.health+" nativeXY="+u.x/8+","+u.y/8+" elevation="+u.elevation+" passable="+u.canPassOn()+" block="+(tile==null?"outside":tile.block().name)+" controller="+u.controller()+" active="+network.creatorFlight().active(u)+" landing="+network.creatorFlight().landing(u)+" pose="+p+" "+network.creatorFlight().describeForSmoke(u);}
    private void next(int next){phase=next;phaseAt=seconds();reports.clear();result.add("phase",Jval.valueOf(phase));Log.info("[FPV-NET-CREATOR] Phase @",phase);}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private double age(){return seconds()-phaseAt;}
    private static double seconds(){return System.nanoTime()/1e9;}
}
