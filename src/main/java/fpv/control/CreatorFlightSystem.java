package fpv.control;

import arc.Core;
import arc.util.Time;
import fpv.flight.*;
import fpv.generated.FlightProfiles;
import fpv.generated.RuntimeConfig;
import fpv.net.NetworkControls;
import fpv.net.NetProtocol;
import fpv.physics.CrashSystem;
import fpv.effects.RadialExplosion;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.io.SaveVersion;
import mindustry.io.SaveFileReader;
import fpv.units.ground.GroundUnitProfiles;
import fpv.units.ground.NativeLegLayout;
import java.io.*;
import mindustry.entities.units.AIController;
import mindustry.entities.units.UnitController;
import mindustry.gen.*;
import java.util.*;

/** Host-only, per-instance flight and safe landing. Shared UnitType definitions are never changed. */
public class CreatorFlightSystem {
    public static final String SETTING="fpv-allow-ground-flight";
    private final World3DAdapter world;
    private final UnitAltitudeSystem altitudes;
    private final RadialExplosion explosions;
    private final boolean nativeBoost;
    private final IdentityHashMap<Unit,Entry> entries=new IdentityHashMap<>();
    private final HashMap<Integer,int[]> remoteEntries=new HashMap<>();
    private boolean remoteAllowed;
    private CrashSystem.ContactPolicy contactPolicy;
    private static final int SAVE_VERSION=1,MAX_SAVED=16384,MAX_SAVE_BYTES=8*1024*1024;
    public void setContactPolicy(CrashSystem.ContactPolicy value){contactPolicy=value;for(Entry e:entries.values())e.crash.setContactPolicy(value);}
    /** Immutable guarded save record. Loading always resumes descent, not unattended creator controls. */
    public record Saved(int id,String typeName,int team,double nativeX,double nativeY,float oldElevation,
                        double radius,double halfDepth,double x,double y,double z,double vx,double vy,double vz,
                        double qx,double qy,double qz,double qw,double wx,double wy,double wz){
        public Saved{
            double[] bounded={nativeX,nativeY,x,y,z,vx,vy,vz,wx,wy,wz};
            for(double value:bounded)if(!Double.isFinite(value)||Math.abs(value)>100000)throw new IllegalArgumentException("Creator save coordinate");
            double norm=qx*qx+qy*qy+qz*qz+qw*qw;
            if(typeName==null||typeName.isBlank()||typeName.length()>128||team<0||team>255||!Float.isFinite(oldElevation)||oldElevation!=0||
                !Double.isFinite(radius+halfDepth)||radius<=0||radius>128||halfDepth<=0||halfDepth>128||!Double.isFinite(norm)||Math.abs(norm-1)>.01)
                throw new IllegalArgumentException("Creator save identity/geometry");
        }
    }
    public List<Saved> snapshot(){
        var result=new ArrayList<Saved>();
        for(Entry e:entries.values())if(e.unit.id==e.id&&!e.unit.dead&&e.unit.isAdded()){
            var s=e.flight.state();result.add(new Saved(e.id,e.unit.type.name,e.unit.team.id,e.unit.x/(double)Vars.tilesize,e.unit.y/(double)Vars.tilesize,e.oldElevation,
                e.radius,e.halfDepth,s.x,s.y,s.z,s.vx,s.vy,s.vz,s.qx,s.qy,s.qz,s.qw,s.wx,s.wy,s.wz));
        }
        return List.copyOf(result);
    }
    public void restoreSaved(List<Saved> saved){
        if(saved==null||saved.size()>MAX_SAVED)throw new IllegalArgumentException("Creator save count");
        clear();if(Vars.net.client())return;
        for(Saved s:saved){
            Unit u=Groups.unit.getByID(s.id());
            if(u==null||!eligible(u)||!u.type.name.equals(s.typeName())||u.team.id!=s.team()||
                Math.hypot(u.x/(double)Vars.tilesize-s.nativeX(),u.y/(double)Vars.tilesize-s.nativeY())>.05)continue;
            Entry e=new Entry(null,u,s);entries.put(u,e);
        }
    }
    public static void writeSaved(DataOutput out,List<Saved> saved)throws IOException{
        if(saved==null||saved.size()>MAX_SAVED)throw new IllegalArgumentException("Creator save count");
        out.writeInt(SAVE_VERSION);out.writeInt(saved.size());
        for(Saved s:saved){out.writeInt(s.id());out.writeUTF(s.typeName());out.writeByte(s.team());out.writeDouble(s.nativeX());out.writeDouble(s.nativeY());out.writeFloat(s.oldElevation());
            for(double value:new double[]{s.radius(),s.halfDepth(),s.x(),s.y(),s.z(),s.vx(),s.vy(),s.vz(),s.qx(),s.qy(),s.qz(),s.qw(),s.wx(),s.wy(),s.wz()})out.writeDouble(value);}
    }
    public static List<Saved> readSaved(DataInput in)throws IOException{
        int version=in.readInt(),count=in.readInt();if(version!=SAVE_VERSION||count<0||count>MAX_SAVED)throw new IOException("Creator save version/count");
        var saved=new ArrayList<Saved>(count);var ids=new HashSet<Integer>();
        for(int i=0;i<count;i++)try{
            Saved s=new Saved(in.readInt(),in.readUTF(),in.readUnsignedByte(),in.readDouble(),in.readDouble(),in.readFloat(),
                in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),
                in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
            if(!ids.add(s.id()))throw new IOException("Duplicate creator identity");saved.add(s);
        }catch(IllegalArgumentException bad){throw new IOException("Invalid creator save",bad);}
        return List.copyOf(saved);
    }
    private void installPersistence(){
        SaveVersion.addCustomChunk(nativeBoost?"fpv-native-boost-bodies":"fpv-creator-bodies",new SaveFileReader.CustomChunk(){
            public boolean shouldWrite(){return !entries.isEmpty();}
            public boolean writeNet(){return false;}
            public void write(DataOutput out)throws IOException{writeSaved(out,snapshot());}
            public void read(DataInput in)throws IOException{restoreSaved(readSaved(in));}
            public void read(DataInput in,int length)throws IOException{if(length<8||length>MAX_SAVE_BYTES)throw new IOException("Creator chunk length");read(in);}
        });
    }
    public CreatorFlightSystem(World3DAdapter world,UnitAltitudeSystem altitudes,RadialExplosion explosions){this(world,altitudes,explosions,false);}
    protected CreatorFlightSystem(World3DAdapter world,UnitAltitudeSystem altitudes,RadialExplosion explosions,boolean nativeBoost){this.world=world;this.altitudes=altitudes;this.explosions=explosions;this.nativeBoost=nativeBoost;installPersistence();}
    private boolean eligible(Unit unit){return nativeBoost?UnitControlCapabilities.boostEligible(unit):UnitControlCapabilities.creatorEligible(unit);}
    public boolean allowed(){return nativeBoost|| (Vars.net.client()?remoteAllowed:Core.settings.getBool(SETTING,false));}
    public void acceptPermission(boolean value){if(Vars.net.client())remoteAllowed=value;}
    public boolean active(Unit unit){if(unit==null)return false;if(Vars.net.client()){int[] r=remoteEntries.get(unit.id);return r!=null&&r[0]==unit.type.id&&r[1]==unit.team.id;}Entry e=entries.get(unit);return e!=null&&!e.groundedHold;}
    public boolean landing(Unit unit){if(Vars.net.client()){int[] r=unit==null?null:remoteEntries.get(unit.id);return active(unit)&&r[2]!=0;}Entry e=entries.get(unit);return e!=null&&e.landing&&!e.groundedHold;}
    public boolean landingBlocked(Unit unit){if(Vars.net.client()){int[] r=unit==null?null:remoteEntries.get(unit.id);return active(unit)&&r[3]!=0;}Entry e=entries.get(unit);return e!=null&&e.landing&&e.siteChecked&&!e.siteFound;}
    public byte[] replication(){return NetProtocol.bytes(out->{out.writeBoolean(allowed());out.writeInt((int)entries.values().stream().filter(e->!e.groundedHold).count());for(Entry e:entries.values()){if(e.groundedHold)continue;out.writeInt(e.id);out.writeInt(e.unit.type.id);out.writeByte(e.unit.team.id);out.writeBoolean(e.landing);out.writeBoolean(e.siteChecked&&!e.siteFound);}});}
    public void applyReplication(byte[] bytes){
        if(!Vars.net.client())return;
        NetProtocol.read(bytes,in->{boolean permission=in.readBoolean();int count=in.readInt();if(count<0||count>16384)throw new java.io.IOException("Creator count");
            var next=new HashMap<Integer,int[]>();for(int i=0;i<count;i++){int id=in.readInt(),type=in.readInt(),team=in.readUnsignedByte();if(type<0||next.put(id,new int[]{type,team,in.readBoolean()?1:0,in.readBoolean()?1:0})!=null)throw new java.io.IOException("Creator identity");}
            remoteEntries.clear();remoteEntries.putAll(next);remoteAllowed=permission;return null;});
    }
    public Unit3DState state(Unit unit){Entry e=entries.get(unit);return e==null||e.id!=unit.id||unit.dead?null:e.body();}
    public boolean addVerticalVelocity(Unit unit,double delta){Entry e=entries.get(unit);if(e==null||e.groundedHold)return false;e.flight.state().vy+=Math.max(-30,Math.min(30,delta));return true;}
    public boolean copy(Unit unit,FlightController target){Entry e=entries.get(unit);if(e==null)return false;target.configureProfile(e.flight.profile());NetProtocol.copy(e.flight.state(),target.state());return true;}
    /** Called with authenticated axes for the player's actually possessed unit. */
    public void control(Player player,Unit unit,NetworkControls controls){
        if(Vars.net.client())return;
        boolean requested=(nativeBoost?controls.boostFlight():controls.creatorFlight())&&allowed()&&eligible(unit)&&UnitControlCapabilities.validOwner(player,unit);
        Entry entry=entries.get(unit);
        if(requested){
            if(entry==null){entry=new Entry(player,unit);entries.put(unit,entry);}
            else if(entry.groundedHold){entry.groundedHold=false;entry.landing=false;entry.siteFound=false;entry.siteChecked=false;unit.elevation=1;}
            if(nativeBoost&&entry.landing){entry.landing=false;entry.siteChecked=false;entry.siteFound=false;}
            if(!entry.landing){entry.owner=player;entry.controls=controls;entry.touched=true;}
        }else if(entry!=null){entry.land();if(nativeBoost){entry.controls=controls;entry.touched=true;}}
    }
    public void release(Unit unit){Entry e=entries.get(unit);if(e==null)return;
        if(nativeBoost&&!unit.isPlayer()&&fpv.units.ground.GroundFamilyLocomotion.explicitMechBoost(unit)){
            var body=e.body();e.restore();entries.remove(unit);unit.elevation=1;
            unit.vel.set((float)(body.vx()*Vars.tilesize/60),(float)(body.vy()*Vars.tilesize/60));
            altitudes.seedAirFromBoost(unit,body.z(),body.vz());
        }else e.land();
    }
    public void before(double dt){
        if(Vars.net.client()||!Vars.state.isPlaying()||Vars.state.isPaused())return;
        for(Entry e:entries.values()){
            if(e.unit.id!=e.id||e.unit.dead||!e.unit.isAdded())continue;
            if(e.groundedHold){e.holdGround(dt);continue;}
            if(!allowed()||!e.touched||!UnitControlCapabilities.validOwner(e.owner,e.unit))e.land();
            e.touched=false;e.step(dt);
        }
    }
    public void after(){
        if(Vars.net.client())return;
        var iterator=entries.values().iterator();
        while(iterator.hasNext()){
            Entry e=iterator.next();
            if(e.unit.id!=e.id||e.unit.dead||!e.unit.isAdded()){
                // A contact already resolved before native combat still damages its target.
                if(e.unit.id==e.id)e.crash.applyPending();
                e.restore();iterator.remove();continue;
            }
            if(e.groundedHold){e.holdPosition();continue;}
            e.motion.afterSimulation(e.unit,e.flight.state());e.crash.applyPending();
            e.unit.elevation=1f;
            if(e.landing&&e.arrived()){
                if(e.roofFallback){e.restore();e.groundedHold=true;e.holdPosition();}
                else {double z=e.flight.state().y;e.restore();iterator.remove();if(nativeBoost)altitudes.seedGroundFromBoost(e.unit,z);else altitudes.seedGroundFromAir(e.unit,z);}
            }
        }
    }
    public void clear(){for(Entry e:entries.values())e.restore();entries.clear();remoteEntries.clear();remoteAllowed=false;}
    /** Read-only context for the explicitly isolated native/network regression harness. */
    public String describeForSmoke(Unit unit){
        if(!Boolean.getBoolean("fpv.smoke")&&(System.getProperty("fpv.netSmokeRole","").isEmpty()||System.getProperty("fpv.netSmokeRoot","").isBlank()))throw new IllegalStateException("Isolated fixture only");
        Entry e=entries.get(unit);if(e==null)return "entry=none";
        return "entryId="+e.id+" landing="+e.landing+" held="+e.groundedHold+" site="+e.landX+","+e.landY+","+e.landHeight+" siteMode="+e.siteMode+" siteFound="+e.siteFound+" body="+e.body()+" lastImpact="+e.crash.lastImpact()+" feedback="+e.crash.lastFeedback();
    }
    CrashSystem crashForSmoke(Unit unit){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated smoke fixture only");
        Entry e=entries.get(unit);return e==null?null:e.crash;
    }
    private final class Entry {
        final Unit unit;final int id;Player owner;boolean landing,touched;
        final FlightController flight=new FlightController(FlightProfiles.create());
        final FlightSettings settings=new FlightSettings(FlightProfiles.create());
        final StabilizedFlight stabilized=new StabilizedFlight();final UnitMotionBridge motion=new UnitMotionBridge();
        final BuildNavigator builder=new BuildNavigator(flight.profile().metersPerTile);
        final CrashSystem crash=new CrashSystem((x,z)->world.surfaceHeight(x,-z),explosions,flight.profile().metersPerTile);
        final double radius,halfDepth;final float oldElevation;
        final AIController landingController=new AIController(){@Override public void updateUnit(){}};
        UnitController previousController;NetworkControls controls=NetworkControls.hold();
        double landX,landY,landHeight,retrySeconds;boolean siteFound,siteChecked,roofFallback,groundedHold;int siteChanges=-1,siteMode;
        FlightController.Profile boostSource,boostProfile;double boostSpeed=-1;final double[] direction=new double[3];
        Entry(Player player,Unit unit){this(player,unit,null);}
        Entry(Player player,Unit unit,Saved saved){
            this.unit=unit;id=unit.id;owner=player;oldElevation=saved==null?0:saved.oldElevation();
            Unit3DState body=world.unitState(unit);radius=saved!=null?saved.radius():body==null?Math.max(.2,unit.hitSize/Vars.tilesize*.4):Math.max(.2,body.radius());halfDepth=saved!=null?saved.halfDepth():body==null?.2:Math.max(.05,body.halfHeight());
            double z=body==null?world.surfaceHeight(unit.x/8d,unit.y/8d)+halfDepth:body.z();
            flight.reset(unit.x/8d,z,-unit.y/8d,90-unit.rotation);
            configure();crash.setWorld(world);crash.setContactPolicy(contactPolicy);unit.elevation=1;
            if(saved!=null){
                var s=flight.state();s.x=saved.x();s.y=saved.y();s.z=saved.z();s.vx=saved.vx();s.vy=saved.vy();s.vz=saved.vz();
                s.qx=saved.qx();s.qy=saved.qy();s.qz=saved.qz();s.qw=saved.qw();s.wx=saved.wx();s.wy=saved.wy();s.wz=saved.wz();
                // The native bridge imports these once on first step; retain saved momentum exactly.
                unit.vel.set((float)(s.vx*Vars.tilesize/60),(float)(-s.vz*Vars.tilesize/60));landing=true;
            }
        }
        Unit3DState body(){
            var b=Unit3DState.fromRenderFlight(unit.id,unit.type.id,unit.team.id,flight.state(),radius,halfDepth);
            return !groundedHold?b:new Unit3DState(b.unitId(),b.typeId(),b.teamId(),b.x(),b.y(),b.z(),0,0,0,b.yaw(),0,0,b.z(),radius,halfDepth,false);
        }
        void land(){landing=true;}
        double configure(){
            var performance=settings.configure(flight,unit);crash.setControlledMass(performance.massKg());
            if(!nativeBoost)return performance.practicalSpeedMps();
            double speed=NativeBoostProfile.speed(unit.type.speed,unit.type.boostMultiplier,flight.profile().metersPerTile,Math.max(.25,Math.min(3,Core.settings.getInt("fpv-flight-speed",100)/100d)));
            if(boostSource!=performance.profile()||boostSpeed!=speed){boostSource=performance.profile();boostSpeed=speed;boostProfile=NativeBoostProfile.resolve(boostSource,speed);}
            flight.configureProfile(boostProfile);return speed;
        }
        void step(double dt){
            unit.lastUpdated=0;unit.elevation=1;
            if(landing&&!unit.isPlayer()&&unit.controller()!=landingController){previousController=unit.controller();unit.controller(landingController);}
            double speed=configure();
            motion.syncFromSimulation(unit,flight.state());var s=flight.state();
            double f=0,r=0,c=0,h=controls.headingRadians();
            if(!landing&&controls.active()&&controls.mode()!=2){f=controls.forward();r=controls.right();c=controls.climb();}
            if(nativeBoost&&!landing&&controls.active()&&controls.mode()==3&&s.y<supportHeight(s.x,-s.z)+halfDepth+RuntimeConfig.START_HEIGHT)c=Math.max(c,.3);
            if(nativeBoost&&landing){
                landX=s.x;landY=-s.z;landHeight=NativeBoostLanding.height(world,altitudes,unit,landX,landY,s.y,radius,halfDepth);
                siteChecked=true;siteFound=Double.isFinite(landHeight);
                if(siteFound)c=CreatorLandingMotion.descentAxis(s.y-landHeight,flight.profile(),speed);
                else if(controls.active()&&controls.mode()!=2){f=controls.forward();r=controls.right();c=Math.max(0,controls.climb());}
            }else if(landing){
                retrySeconds-=dt;
                if(!siteChecked)chooseSite();
                else if(retrySeconds<=0&&siteChanges!=Vars.world.tileChanges){
                    if(roofFallback||!validSite((int)Math.round(landX),(int)Math.round(landY),siteMode))chooseSite();
                    else {siteChanges=Vars.world.tileChanges;retrySeconds=2;}
                }
                landHeight=supportHeight(landX,landY)+halfDepth+.035;
                double dx=landX-s.x,dy=landY+s.z,distance=Math.hypot(dx,dy);
                h=Math.atan2(dx,dy);speed=Math.min(25,speed);
                // Clear the local solid skyline before returning to a valid native landing tile.
                double travelHeight=Math.max(landHeight+2,supportHeight(s.x,-s.z)+halfDepth+2);
                if(distance>.2){c=s.y<travelHeight?.6:0;f=s.y>=travelHeight-.3?Math.min(1,distance):0;}
                else {c=CreatorLandingMotion.descentAxis(s.y-landHeight,flight.profile(),speed);}
            }
            FlightController.StepObserver collision=(state,x,y,z,step,elapsed)->{
                state.x=Math.max(.1,Math.min(Vars.world.width()-1.1,state.x));state.z=Math.max(-(Vars.world.height()-1.1),Math.min(-.1,state.z));
                double ceiling=Math.max(RuntimeConfig.MAX_HEIGHT,world.maximumTerrainHeight()+RuntimeConfig.START_HEIGHT);
                if(state.y>ceiling){state.y=ceiling;state.vy=Math.min(0,state.vy);}
                crash.resolve(unit,state,x,y,z,step,Core.settings.getBool("fpv-crash-damage",true),elapsed);
                // Final descent targets actual ground clearance, not the unit's default airborne start height.
                if(landing&&siteFound&&Math.hypot(state.x-landX,-state.z-landY)<.25&&state.y<landHeight
                    &&CreatorLandingMotion.gentleTouchdown(state.vy,flight.profile())){state.y=landHeight;state.vy=0;}
            };
            if(nativeBoost&&!landing&&controls.active()&&controls.mode()==2){
                double x=s.x,y=s.y,z=s.z;
                builder.step(s,dt,controls.forward(),controls.right(),controls.climb(),controls.turn(),Math.max(0,Math.min(60,Core.settings.getInt("fpv-build-speed",12))));
                collision.afterStep(s,x,y,z,dt,0);
            }else if(nativeBoost&&!landing&&controls.active()&&controls.mode()==0)flight.step(dt,controls.throttle(),controls.pitch(),controls.roll(),controls.yaw(),collision);
            else stabilized.step(s,flight.profile(),dt,f,r,c,speed,h,collision);
            flight.getForward(direction);unit.rotation=(float)Math.toDegrees(Math.atan2(-direction[2],direction[0]));
            if(nativeBoost&&owner!=null&&owner.unit()==unit)owner.boosting=true;
            motion.beforeSimulation(unit,s,Time.delta,false);
        }
        boolean validSite(int x,int y,int mode){
            // Check every tile intersecting the collision disk, not only its center. Leg feet have
            // their own real surfaces and may rest on walls; the body may not intersect those walls.
            int extent=(int)Math.ceil(radius+.5);
            float elevation=unit.elevation;unit.elevation=oldElevation;
            try{
                for(int oy=-extent;oy<=extent;oy++)for(int ox=-extent;ox<=extent;ox++){
                    double dx=Math.max(0,Math.abs(ox)-.5),dy=Math.max(0,Math.abs(oy)-.5);
                    if(dx*dx+dy*dy>radius*radius)continue;
                    var tile=Vars.world.tile(x+ox,y+oy);if(tile==null)return false;
                    if(mode==0){
                        if(tile.block().solid||!unit.canPass(x+ox,y+oy)||
                            tile.floor().isDeep()&&!unit.type.hovering&&!(unit instanceof WaterMovec)&&!(unit instanceof WaterCrawlc))return false;
                    }else if(tile.build!=null&&(tile.block().solid||tile.build.checkSolid()))return false;
                }
                var p=GroundUnitProfiles.profile(unit);
                if(p!=null&&p.legged()){
                    double lowest=Double.POSITIVE_INFINITY,highest=Double.NEGATIVE_INFINITY;
                    for(int i=0;i<p.legCount();i++){
                        double fx=x+NativeLegLayout.footX(unit.type,i,p.legCount(),unit.rotation),fy=y+NativeLegLayout.footY(unit.type,i,p.legCount(),unit.rotation);
                        if(fx<0||fy<0||fx>Vars.world.width()-1||fy>Vars.world.height()-1)return false;
                        double foot=world.surfaceHeight(fx,fy);lowest=Math.min(lowest,foot);highest=Math.max(highest,foot);
                    }
                    if(highest-lowest>p.maximumClearance()||supportHeight(x,y)-lowest>p.maximumClearance())return false;
                }
                return true;
            }finally{unit.elevation=elevation;}
        }
        double supportHeight(double x,double y){
            double support=Math.max(world.surfaceHeight(x,y),world.terrainHeight(x,y));
            int extent=(int)Math.ceil(radius+.5);
            for(int oy=-extent;oy<=extent;oy++)for(int ox=-extent;ox<=extent;ox++){
                double dx=Math.max(0,Math.abs(ox)-.5),dy=Math.max(0,Math.abs(oy)-.5);
                if(dx*dx+dy*dy<=radius*radius)support=Math.max(support,world.surfaceHeight(x+ox,y+oy));
            }
            var p=GroundUnitProfiles.profile(unit);
            if(p!=null&&p.legged())for(int i=0;i<p.legCount();i++){
                double fx=x+NativeLegLayout.footX(unit.type,i,p.legCount(),unit.rotation),fy=y+NativeLegLayout.footY(unit.type,i,p.legCount(),unit.rotation);
                support=Math.max(support,world.surfaceHeight(fx,fy));
            }
            return support;
        }
        boolean searchSite(int mode){
            var s=flight.state();double best=Double.POSITIVE_INFINITY;
            for(int y=0;y<Vars.world.height();y++)for(int x=0;x<Vars.world.width();x++){
                double d=(x-s.x)*(x-s.x)+(y+s.z)*(y+s.z);if(d>=best||!validSite(x,y,mode))continue;
                best=d;landX=x;landY=y;
            }
            return Double.isFinite(best);
        }
        void chooseSite(){
            // A failed map search is cached too. Only map edits can make an impossible map valid.
            siteChecked=true;siteChanges=Vars.world.tileChanges;retrySeconds=2;roofFallback=false;siteMode=0;
            if(!searchSite(0)){
                siteMode=1;
                if(!searchSite(1)){
                    roofFallback=true;var s=flight.state();
                    landX=Math.max(.1,Math.min(Vars.world.width()-1.1,s.x));landY=Math.max(.1,Math.min(Vars.world.height()-1.1,-s.z));
                }
            }
            siteFound=true;landHeight=supportHeight(landX,landY)+halfDepth+.035;
        }
        void holdPosition(){
            unit.elevation=oldElevation;unit.vel.setZero();unit.set((float)(landX*Vars.tilesize),(float)(landY*Vars.tilesize));
            var s=flight.state();s.x=landX;s.z=-landY;s.y=supportHeight(landX,landY)+halfDepth+.035;s.vx=s.vy=s.vz=0;
        }
        void holdGround(double dt){
            holdPosition();retrySeconds-=dt;
            // Fully solid artificial map: physically grounded, vulnerable and movement-locked.
            // No permission/exhaust/flying classification; a subsequent terrain edit retries recovery.
            if(retrySeconds<=0&&siteChanges!=Vars.world.tileChanges){
                chooseSite();if(!roofFallback){groundedHold=false;unit.elevation=1;}
            }
        }
        boolean arrived(){var s=flight.state();return landing&&siteFound&&Math.hypot(s.x-landX,-s.z-landY)<.25&&s.y<=landHeight+.06&&Math.abs(s.vy)<.8;}
        void restore(){motion.release(unit,flight.state());if(unit.id!=id)return;unit.elevation=oldElevation;unit.vel.setZero();if(nativeBoost&&owner!=null&&owner.unit()==unit)owner.boosting=false;if(unit.controller()==landingController&&previousController!=null)unit.controller(previousController);crash.reset();}
    }
}
