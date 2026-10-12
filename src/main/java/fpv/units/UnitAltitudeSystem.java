package fpv.units;

import arc.struct.IntMap;
import fpv.generated.UnitsConfig;
import fpv.flight.FlightController;
import fpv.world.Unit3DState;
import fpv.units.ground.GroundFamilyLocomotion;
import fpv.units.ground.GroundUnitProfiles;
import java.util.function.DoubleBinaryOperator;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Physical-altitude authority shared with renderer/combat. Existing units retain native horizontal motion;
 * explicitly enabled ground families add bounded 3D traversal. No map, elevation or AI rewrites.
 * Call after the native entity update, using the actual scaled simulation seconds (zero on pause).
 */
public final class UnitAltitudeSystem implements AutoCloseable {
    private final DoubleBinaryOperator terrain;
    private final Supplier<Unit> excludedPlayer;
    private final ToDoubleFunction<Unit> meshHalfDepth;
    private final ToDoubleFunction<Unit> meshRadius;
    private final UnitAltitudeModel model = new UnitAltitudeModel(profile());
    private final GroundSurfaceAdapter ground;
    private final IntMap<Tracked> states = new IntMap<>();
    private Object worldTiles;
    private long generation;
    private Supplier<FlightController.State> controlledState;
    private Function<Unit,Unit3DState> externalState,runtimeState;
    private BiPredicate<Unit,Double> externalImpulse,runtimeImpulse;
    private BooleanSupplier networkGeometry;
    private GroundSupport groundSupport;
    private GroundFamilyLocomotion groundFamily;
    private fpv.world.World3DAdapter boostGroundWorld;
    public void setBoostGroundWorld(fpv.world.World3DAdapter value){boostGroundWorld=value;}
    public java.util.List<fpv.control.NativeBoostGroundSave.Entry> boostGroundSnapshot(){
        var result=new java.util.ArrayList<fpv.control.NativeBoostGroundSave.Entry>();
        for(var t:states.values())if(t.boostGround&&t.unit.isAdded()&&!t.unit.dead)result.add(new fpv.control.NativeBoostGroundSave.Entry(t.unit.id,t.unit.type.name,t.unit.team.id,t.unit.x/(double)Vars.tilesize,t.unit.y/(double)Vars.tilesize,t.pose.height()));
        return java.util.List.copyOf(result);
    }
    public boolean nativeBoostGrounded(Unit unit){var t=unit==null?null:states.get(unit.id);return t!=null&&t.unit==unit&&t.boostGround;}
    public void seedGroundFromBoost(Unit unit,double height){seedGroundFromAir(unit,height);var t=states.get(unit.id);if(t!=null&&GroundUnitProfiles.profile(unit)==null)t.boostGround=true;}
    // Aircraft barrier integration is independent of spawn initialization and grounded locomotion.
    private fpv.world.World3DAdapter.UnitBarrier aircraftBarrier;
    public void setAircraftBarrier(fpv.world.World3DAdapter.UnitBarrier value){aircraftBarrier=value;}
    private AircraftStructureClearance aircraftStructures;
    private DoubleBinaryOperator aircraftSurface;
    /** Canonical XY surface and upper volumes apply only to unpiloted AI aircraft, never ground/FPV. */
    public void setAircraftStructures(Supplier<fpv.construction.LayerWorld> source,DoubleBinaryOperator surface){
        aircraftStructures=source==null?null:new AircraftStructureClearance(source);aircraftSurface=surface;
    }

    /** Opt-in family integration; null preserves all existing ground and flying behavior. */
    public void setGroundFamily(GroundFamilyLocomotion value){
        if(groundFamily==value)return;
        if(groundFamily!=null)groundFamily.close();groundFamily=value;states.clear();
        if(value!=null){value.setPoseInvalidation(states::clear);value.installSavePersistence();}
    }
    public GroundFamilyLocomotion groundFamily(){return groundFamily;}
    public void captureGroundIntent(double simulationSeconds){
        if(groundFamily!=null)groundFamily.captureIntent(simulationSeconds);
        if(boostGroundWorld!=null&&!Vars.net.client())for(var t:states.values())if(t.boostGround&&t.unit.isAdded()&&!t.unit.dead){
            if(GroundFamilyLocomotion.explicitMechBoost(t.unit)){t.boostGround=false;continue;}
            // Native XY solidity must not push a physically supported rooftop mech down to Z0.
            // The combat adapter uses the canonical grounded body, never this transient move flag.
            if(t.pose.height()-bodyHalfDepth(t.unit)>boostGroundWorld.terrainHeight(t.x,-t.z)+.08)t.unit.elevation=1;
        }
    }
    public void setGroundStanceControl(Unit unit,boolean controlled,double axis,double seconds){if(groundFamily!=null)groundFamily.setStanceControl(unit,controlled,axis,seconds);}
    public void setRuntimeStateProvider(Function<Unit,Unit3DState> provider){runtimeState=provider;}
    public void setRuntimeVerticalImpulse(BiPredicate<Unit,Double> receiver){runtimeImpulse=receiver;}
    /** Native AI retains boost intent on possession release; transfer height/momentum without a snap. */
    public void seedAirFromBoost(Unit unit,double height,double vz){
        if(unit==null||!Double.isFinite(height+vz))return;ensureWorld();states.remove(unit.id);
        var tracked=track(unit);model.seedSpawnHeight(tracked.pose,height);model.addVerticalVelocity(tracked.pose,vz,UnitsConfig.MAX_VERTICAL_IMPULSE_TILES_PER_SECOND);
    }
    /** Root's creator controller clears its override only after safe descent and then seeds this pose. */
    public void seedGroundFromAir(Unit unit,double height){
        if(unit==null||unit.type==null||!Double.isFinite(height))return;
        ensureWorld();states.remove(unit.id);
        if(groundFamily!=null&&GroundUnitProfiles.profile(unit)!=null)
            groundFamily.seedFromAir(unit,height,Math.max(.01,unit.hitSize/(double)Vars.tilesize*.5),bodyHalfDepth(unit));
        Tracked tracked=track(unit);
        if(!tracked.pose.flying())model.alignGround(tracked.pose,Math.max(height,tracked.pose.height()),tracked.pose.pitch(),tracked.pose.roll());
        tracked.groundVerticalVelocity=0;
    }

    /** Side-effect-free collision hull and baseline height, available before UnitPayload adds the unit. */
    public Unit3DState previewPayloadRelease(Unit unit,double x,double y){
        if(unit==null||unit.type==null||!Double.isFinite(x+y))throw new IllegalArgumentException("Valid payload body required");
        double radius=footprintRadius(unit),half=bodyHalfDepth(unit);
        var pose=model.create(unit.id,unit.type.id,unit.hitSize/(double)Vars.tilesize,footprint(x,-y,radius),unit.rotation,
            unit.isFlying(),networkGeometry!=null&&networkGeometry.getAsBoolean()?0:meshHalfDepth.applyAsDouble(unit),
            networkGeometry!=null&&networkGeometry.getAsBoolean()?0:meshRadius.applyAsDouble(unit));
        var profile=groundFamily==null?null:GroundUnitProfiles.profile(unit);
        double z=unit.isFlying()?pose.height():terrain.applyAsDouble(x,-y)+half+(profile==null?0:profile.normalStance());
        return new Unit3DState(unit.id,unit.type.id,unit.team.id,x,y,z,0,0,0,unit.rotation,0,0,z,radius,half,unit.isFlying());
    }
    /** Called synchronously at successful native payload release, before any unit physics tick. */
    public void initializePayloadRelease(Unit unit,double height){
        if(unit==null||!unit.isAdded()||unit.dead||!Double.isFinite(height))throw new IllegalArgumentException("Live payload release required");
        ensureWorld();states.remove(unit.id);if(groundFamily!=null)groundFamily.forgetPayload(unit);
        if(groundFamily!=null&&!unit.isFlying()&&GroundUnitProfiles.profile(unit)!=null)
            groundFamily.seedFromAir(unit,height,Math.max(.01,unit.hitSize/(double)Vars.tilesize*.5),bodyHalfDepth(unit));
        Tracked tracked=track(unit);model.seedSpawnHeight(tracked.pose,Math.max(height,tracked.pose.height()));
        tracked.groundVerticalVelocity=0;tracked.hasPosition=true;tracked.seen=generation;
    }

    @FunctionalInterface public interface GroundSupport {
        GroundSurfaceAdapter.Contact contact(Unit unit,double x,double y,double yaw,double radius,double halfDepth);
    }
    public void setGroundSupport(GroundSupport provider){groundSupport=provider;states.clear();}
    private GroundSurfaceAdapter.Contact groundContact(Unit unit,double x,double y,double radius,double halfDepth){
        return groundSupport==null?ground.contact(x,y,unit.rotation,radius,halfDepth):
            groundSupport.contact(unit,x,y,unit.rotation,radius,halfDepth);
    }

    public UnitAltitudeSystem(DoubleBinaryOperator terrainHeight) {
        this(terrainHeight, () -> null);
    }

    /** Exclude the currently FPV-controlled unit so the flight controller owns its full pose. */
    public UnitAltitudeSystem(DoubleBinaryOperator terrainHeight, Supplier<Unit> excludedPlayer) {
        this(terrainHeight, excludedPlayer, unit -> 0, unit -> 0);
    }

    /** Optional renderer bounds keep large/custom sprites above the same terrain used for their mesh. */
    public UnitAltitudeSystem(DoubleBinaryOperator terrainHeight, Supplier<Unit> excludedPlayer,
                              ToDoubleFunction<Unit> meshHalfDepth, ToDoubleFunction<Unit> meshRadius) {
        if (terrainHeight == null || excludedPlayer == null || meshHalfDepth == null || meshRadius == null) {
            throw new IllegalArgumentException("Terrain, exclusion and mesh bounds providers are required");
        }
        terrain = terrainHeight;
        this.excludedPlayer = excludedPlayer;
        this.meshHalfDepth = meshHalfDepth;
        this.meshRadius = meshRadius;
        ground = new GroundSurfaceAdapter((x,y)->terrain.applyAsDouble(x,-y),UnitsConfig.GROUND_SLOPE_SAMPLE_TILES,
            UnitsConfig.GROUND_MAX_SLOPE_DEGREES,UnitsConfig.GROUND_SUPPORT_STEP_TILES);
    }

    public void setControlledStateProvider(Supplier<FlightController.State> provider){controlledState=provider;}

    /** Optional authoritative server-player states or read-only client replicas; null restores local behavior. */
    public void setExternalStateProvider(Function<Unit,Unit3DState> provider){externalState=provider;}
    public void setExternalVerticalImpulse(BiPredicate<Unit,Double> receiver){externalImpulse=receiver;}
    /** Online collisions use identical content metadata even without a server texture atlas. */
    public void setNetworkGeometry(BooleanSupplier active){networkGeometry=active;}

    private Unit3DState external(Unit unit){
        Unit3DState state=runtimeState==null?null:runtimeState.apply(unit);
        if(state!=null&&state.unitId()==unit.id&&state.typeId()==unit.type.id&&state.teamId()==unit.team.id)return state;
        state=externalState==null?null:externalState.apply(unit);
        return state!=null&&state.unitId()==unit.id&&state.typeId()==unit.type.id&&state.teamId()==unit.team.id?state:null;
    }
    private Unit excluded(){
        Unit unit=networkGeometry!=null&&networkGeometry.getAsBoolean()?null:excludedPlayer.get();
        // A grounded family member remains owned by grounded locomotion, even when piloted locally.
        return groundFamily!=null&&GroundUnitProfiles.profile(unit)!=null?null:unit;
    }

    /** Immutable canonical gameplay XYZ snapshot, including the real FPV-controlled unit when supplied. */
    public Unit3DState unitState(Unit unit){
        ensureWorld();
        if(!eligible(unit,null))return null;
        Unit3DState supplied=external(unit);
        if(supplied!=null)return supplied;
        double radius=footprintRadius(unit),halfDepth=bodyHalfDepth(unit);
        if(unit==excluded()){
            FlightController.State controlled=controlledState==null?null:controlledState.get();
            return controlled==null?null:Unit3DState.fromRenderFlight(unit.id,unit.type.id,unit.team.id,controlled,radius,halfDepth);
        }
        var tracked=track(unit);var pose=tracked.pose;
        double up=Math.cos(Math.toRadians(pose.pitch()))*Math.cos(Math.toRadians(pose.roll()));
        double extent=halfDepth*Math.abs(up)+radius*Math.sqrt(Math.max(0,1-up*up));
        return new Unit3DState(unit.id,unit.type.id,unit.team.id,unit.x/(double)Vars.tilesize,unit.y/(double)Vars.tilesize,
            pose.height(),groundFamily!=null&&groundFamily.pose(unit)!=null?groundFamily.pose(unit).velocityX():unit.vel.x*60.0/Vars.tilesize,
            groundFamily!=null&&groundFamily.pose(unit)!=null?groundFamily.pose(unit).velocityY():unit.vel.y*60.0/Vars.tilesize,
            pose.flying()?pose.verticalVelocity():tracked.groundVerticalVelocity,
            pose.yaw(),pose.pitch(),pose.roll(),groundFamily!=null&&groundFamily.pose(unit)!=null?groundFamily.pose(unit).desiredZ():pose.desiredHeight(),radius,extent,pose.flying());
    }

    /** Native XY impulse remains the caller's responsibility; this updates authoritative physical Z velocity. */
    public boolean addVerticalVelocity(Unit unit,double deltaVz){
        if(!Double.isFinite(deltaVz))throw new IllegalArgumentException("Finite vertical impulse required");
        ensureWorld();
        if(!eligible(unit,null))return false;
        double bound=UnitsConfig.MAX_VERTICAL_IMPULSE_TILES_PER_SECOND;
        double delta=Math.max(-bound,Math.min(bound,deltaVz));
        Unit3DState runtime=runtimeState==null?null:runtimeState.apply(unit);
        if(runtime!=null&&runtime.unitId()==unit.id&&runtime.typeId()==unit.type.id)return runtimeImpulse!=null&&runtimeImpulse.test(unit,delta);
        if(external(unit)!=null)return externalImpulse!=null&&externalImpulse.test(unit,delta);
        if(unit==excluded()){
            FlightController.State controlled=controlledState==null?null:controlledState.get();
            if(controlled==null)return false;
            controlled.vy+=delta;
            return true;
        }
        return model.addVerticalVelocity(track(unit).pose,delta,bound);
    }

    /** Live view, valid until unit removal/world change. Null means absent/dead/excluded unit. */
    public UnitAltitudeModel.Pose pose(Unit unit) {
        ensureWorld();
        if(!eligible(unit,null))return null;
        Unit3DState supplied=external(unit);
        if(supplied!=null)return new ExternalPose(supplied);
        if (!eligible(unit, excluded())) return null;
        return track(unit).pose;
    }

    public void update(double simulationSeconds) {
        if (!Double.isFinite(simulationSeconds) || simulationSeconds < 0) throw new IllegalArgumentException("Simulation seconds must be finite and nonnegative");
        ensureWorld();
        Unit excluded = excluded();
        generation++;
        if(groundFamily!=null)groundFamily.beginUpdate();
        for (Unit unit : Groups.unit) {
            if (!eligible(unit, excluded)) continue;
            if(external(unit)!=null){if(groundFamily!=null)groundFamily.retain(unit);continue;}
            if(groundFamily!=null)groundFamily.restoreAutomaticMechBoost(unit);
            Tracked tracked = track(unit);
            tracked.seen = generation;
            if(tracked.boostGround&&!GroundFamilyLocomotion.explicitMechBoost(unit))unit.elevation=0;
            if(groundFamily!=null&&!unit.isFlying()&&GroundUnitProfiles.profile(unit)!=null){
                var grounded=groundFamily.update(unit,simulationSeconds,Math.max(.01,unit.hitSize/(double)Vars.tilesize*.5),bodyHalfDepth(unit));
                model.update(tracked.pose,simulationSeconds,0,0,grounded.velocityX(),-grounded.velocityY(),unit.rotation,false,
                    grounded.pitch(),grounded.roll(),grounded.z());
                tracked.groundVerticalVelocity=grounded.verticalVelocity();tracked.x=grounded.x();tracked.z=-grounded.y();
                tracked.centerTerrain=terrain.applyAsDouble(tracked.x,tracked.z);tracked.hasPosition=true;
                continue;
            }
            double x = unit.x / (double)Vars.tilesize, z = -unit.y / (double)Vars.tilesize;
            double vx = unit.vel.x * 60.0 / Vars.tilesize, vz = -unit.vel.y * 60.0 / Vars.tilesize;
            double distance = Math.hypot(x - tracked.x, z - tracked.z);
            double previousHeight=tracked.pose.height();
            boolean teleported=distance>UnitsConfig.TELEPORT_DISTANCE;
            boolean terrainChanged=tracked.hasPosition&&Math.abs(terrain.applyAsDouble(tracked.x,tracked.z)-tracked.centerTerrain)>1e-6;
            if (teleported) {
                tracked.pose = createPose(unit, x, z);
            } else if (!unit.isFlying() && simulationSeconds > 0 && tracked.hasPosition) {
                // Ground support follows actual native displacement. Flyers use native velocity:
                // Unit.wobble() and passive separation move XY without a flight command, and must
                // not invent a travel heading for an otherwise stationary aircraft.
                vx = (x - tracked.x) / simulationSeconds;
                vz = (z - tracked.z) / simulationSeconds;
            }
            double radius = footprintRadius(unit);
            double previousHalfHeight=tiltedHalfHeight(tracked.pose,radius,bodyHalfDepth(unit));
            double floor = footprint(x, z, radius);
            double ahead = floor;
            double targetX=x,targetZ=z;
            if (unit.isFlying() && Math.hypot(vx, vz) >= UnitsConfig.MIN_TRAVEL_SPEED) {
                double speed = Math.hypot(vx, vz);
                double distanceAhead = Math.max(UnitsConfig.LOOK_AHEAD_MIN_DISTANCE, speed * UnitsConfig.LOOK_AHEAD_SECONDS);
                int samples = Math.max(1, (int)UnitsConfig.LOOK_AHEAD_SAMPLES);
                double dx = vx / speed * distanceAhead, dz = vz / speed * distanceAhead;
                targetX=x+dx;targetZ=z+dz;
                for (int i = 1; i <= samples; i++) {
                    double fraction = i / (double)samples;
                    ahead = Math.max(ahead, footprint(x + dx * fraction, z + dz * fraction, radius));
                }
            }
            if(unit.isFlying()&&aircraftStructures!=null){
                // Keep the anticipated route while a collision temporarily zeroes native velocity.
                // Native AI re-accelerates towards its target; this short hold lets it climb first.
                if(Math.hypot(vx,vz)>=UnitsConfig.MIN_TRAVEL_SPEED){tracked.routeX=targetX-x;tracked.routeZ=targetZ-z;tracked.routeSeconds=UnitsConfig.LOOK_AHEAD_SECONDS;}
                else if(tracked.routeSeconds>0){targetX=x+tracked.routeX;targetZ=z+tracked.routeZ;tracked.routeSeconds=Math.max(0,tracked.routeSeconds-simulationSeconds);}
                if(aircraftSurface!=null){
                    floor=Math.max(floor,aircraftFootprint(x,-z,radius));
                    ahead=Math.max(ahead,floor);
                    // Keep the native surface sampling budget bounded; the upper-grid query below
                    // is continuous, so even thin upper walls between sample positions are found.
                    int steps=Math.hypot(targetX-x,targetZ-z)<1e-8?0:Math.max(1,(int)UnitsConfig.LOOK_AHEAD_SAMPLES);
                    for(int i=1;i<=steps;i++){double f=i/(double)steps;ahead=Math.max(ahead,aircraftFootprint(x+(targetX-x)*f,-z-(targetZ-z)*f,radius));}
                }
                aircraftStructures.sample(x,-z,targetX,-targetZ,tracked.pose.height(),radius,previousHalfHeight);
                // Upper roofs are bounded volumes, not terrain: do not safety-project through
                // a slab or jump up by the cruise clearance as the leading wing crosses its edge.
                // Their top is a smooth target; the actual swept hull enforces hard clearance.
                ahead=Math.max(ahead,aircraftStructures.ahead());
            }
            if(unit.isFlying())model.update(tracked.pose,simulationSeconds,floor,ahead,vx,vz,unit.rotation,true);
            else{
                var contact=groundContact(unit,x,-z,radius,bodyHalfDepth(unit));
                if(tracked.boostGround&&boostGroundWorld!=null){
                    double supported=fpv.control.NativeBoostLanding.height(boostGroundWorld,this,unit,x,-z,previousHeight,radius,bodyHalfDepth(unit));
                    if(Double.isFinite(supported))contact=new GroundSurfaceAdapter.Contact(supported,0,0,0,0);
                    else {x=tracked.x;z=tracked.z;unit.set((float)(x*Vars.tilesize),(float)(-z*Vars.tilesize));unit.vel.setZero();contact=new GroundSurfaceAdapter.Contact(previousHeight,tracked.pose.pitch(),tracked.pose.roll(),0,0);}
                }
                model.update(tracked.pose,simulationSeconds,floor,ahead,vx,vz,unit.rotation,false,
                    contact.pitch(),contact.roll(),contact.centerHeight());
            }
            // Unpiloted authoritative aircraft only: excluded/external controller poses already
            // returned above, so FPV/creator crash solvers are never resolved twice.
            if(unit.isFlying()&&aircraftBarrier!=null&&simulationSeconds>0&&tracked.hasPosition&&!teleported){
                double collisionHalfHeight=Math.max(previousHalfHeight,tiltedHalfHeight(tracked.pose,radius,bodyHalfDepth(unit)));
                var hit=aircraftBarrier.trace(unit,tracked.x,-tracked.z,previousHeight,x,-z,tracked.pose.height(),radius,collisionHalfHeight);
                if(hit!=null){
                    double t=Math.max(0,hit.time()-1e-6),height=previousHeight+(tracked.pose.height()-previousHeight)*t;
                    x=tracked.x+(x-tracked.x)*t;z=tracked.z+(z-tracked.z)*t;
                    // A side collision stops forward travel, not the climb needed to clear the wall.
                    // Sweep the remaining vertical displacement independently so ceilings still win.
                    if(aircraftStructures!=null&&Math.abs(hit.nz())<.5&&tracked.pose.height()>height){
                        double safeX=x+hit.nx()*1e-5,safeY=-z+hit.ny()*1e-5;
                        var slide=aircraftBarrier.trace(unit,safeX,safeY,height,safeX,safeY,tracked.pose.height(),radius,collisionHalfHeight);
                        height=slide==null?tracked.pose.height():height+(tracked.pose.height()-height)*Math.max(0,slide.time()-1e-6);
                    }
                    double vertical=tracked.pose.verticalVelocity(),dot=vx*hit.nx()-vz*hit.ny()+vertical*hit.nz();
                    if(dot<0){vx-=dot*hit.nx();vz+=dot*hit.ny();vertical-=dot*hit.nz();}
                    unit.set((float)(x*Vars.tilesize),(float)(-z*Vars.tilesize));unit.vel.set((float)(vx*Vars.tilesize/60),(float)(-vz*Vars.tilesize/60));
                    model.resolveAirContact(tracked.pose,height,vertical);
                }
            }
            if(!unit.isFlying()&&simulationSeconds>0){
                double physicalVz=(tracked.pose.height()-previousHeight)/simulationSeconds;
                tracked.groundVerticalVelocity=!tracked.hasPosition||teleported||terrainChanged||!Double.isFinite(physicalVz)?0:physicalVz;
            }
            tracked.x = x;
            tracked.z = z;
            if(simulationSeconds>0)tracked.centerTerrain=terrain.applyAsDouble(x,z);
            tracked.hasPosition = true;
        }
        if(groundFamily!=null)groundFamily.endUpdate();
        IntMap.Entries<Tracked> iterator = states.entries();
        while (iterator.hasNext()) if (iterator.next().value.seen != generation) iterator.remove();
    }

    public int trackedCount() { return states.size; }

    public void clear() {
        states.clear();
        if(aircraftStructures!=null)aircraftStructures.clear();
        if(groundFamily!=null)groundFamily.clear();
        generation = 0;
        worldTiles = Vars.world == null ? null : Vars.world.tiles;
    }

    public void dispose() { clear();if(groundFamily!=null)groundFamily.close(); }
    @Override public void close() { dispose(); }

    private void ensureWorld() {
        Object current = Vars.world == null ? null : Vars.world.tiles;
        if (worldTiles != current) {
            states.clear();
            if(groundFamily!=null)groundFamily.clear();
            generation = 0;
            worldTiles = current;
        }
    }

    private Tracked track(Unit unit) {
        Tracked tracked = states.get(unit.id);
        if (tracked == null || tracked.unit != unit || tracked.type != unit.type) {
            double x = unit.x / (double)Vars.tilesize, z = -unit.y / (double)Vars.tilesize;
            tracked = new Tracked(unit, createPose(unit, x, z), x, z,terrain.applyAsDouble(x,z));
            states.put(unit.id, tracked);
        }
        return tracked;
    }

    private UnitAltitudeModel.State createPose(Unit unit, double x, double z) {
        var pose=model.create(unit.id, unit.type.id, unit.hitSize / Vars.tilesize,
            footprint(x, z, footprintRadius(unit)), unit.rotation, unit.isFlying(),
            meshHalfDepth.applyAsDouble(unit), meshRadius.applyAsDouble(unit));
        if(!unit.isFlying()){
            if(groundFamily!=null&&GroundUnitProfiles.profile(unit)!=null){
                var family=groundFamily.state(unit,Math.max(.01,unit.hitSize/(double)Vars.tilesize*.5),bodyHalfDepth(unit));
                model.alignGround(pose,family.z(),family.pitch(),family.roll());
            }else{
                var contact=groundContact(unit,x,-z,footprintRadius(unit),bodyHalfDepth(unit));
                model.alignGround(pose,contact.centerHeight(),contact.pitch(),contact.roll());
            }
        }
        return pose;
    }

    private double bodyHalfDepth(Unit unit){
        double depth=Math.max(networkGeometry!=null&&networkGeometry.getAsBoolean()?0:meshHalfDepth.applyAsDouble(unit),Math.min(UnitsConfig.MAX_BODY_OFFSET,
            Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,unit.hitSize/(double)Vars.tilesize*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        var profile=groundFamily==null?null:GroundUnitProfiles.profile(unit);
        return profile==null?depth:depth*profile.thicknessMultiplier();
    }
    private static double tiltedHalfHeight(UnitAltitudeModel.Pose pose,double radius,double halfDepth){
        double up=Math.cos(Math.toRadians(pose.pitch()))*Math.cos(Math.toRadians(pose.roll()));
        return halfDepth*Math.abs(up)+radius*Math.sqrt(Math.max(0,1-up*up));
    }

    private double footprintRadius(Unit unit) {
        return Math.max(networkGeometry!=null&&networkGeometry.getAsBoolean()?0:meshRadius.applyAsDouble(unit), Math.max(0, unit.hitSize / Vars.tilesize * UnitsConfig.FOOTPRINT_RADIUS_PER_SIZE));
    }

    private double footprint(double x, double z, double radius) {
        double max = terrain.applyAsDouble(x, z);
        max = Math.max(max, terrain.applyAsDouble(x - radius, z));
        max = Math.max(max, terrain.applyAsDouble(x + radius, z));
        max = Math.max(max, terrain.applyAsDouble(x, z - radius));
        max = Math.max(max, terrain.applyAsDouble(x, z + radius));
        // Diagonal samples cover silhouettes wider than the unit's cardinal hit radius.
        double diagonal = radius * Math.sqrt(0.5);
        max = Math.max(max, terrain.applyAsDouble(x - diagonal, z - diagonal));
        max = Math.max(max, terrain.applyAsDouble(x - diagonal, z + diagonal));
        max = Math.max(max, terrain.applyAsDouble(x + diagonal, z - diagonal));
        max = Math.max(max, terrain.applyAsDouble(x + diagonal, z + diagonal));
        return max;
    }
    private double aircraftFootprint(double x,double y,double radius){
        double height=aircraftSurface.applyAsDouble(x,y),d=radius*Math.sqrt(.5);
        height=Math.max(height,aircraftSurface.applyAsDouble(x-radius,y));height=Math.max(height,aircraftSurface.applyAsDouble(x+radius,y));
        height=Math.max(height,aircraftSurface.applyAsDouble(x,y-radius));height=Math.max(height,aircraftSurface.applyAsDouble(x,y+radius));
        height=Math.max(height,aircraftSurface.applyAsDouble(x-d,y-d));height=Math.max(height,aircraftSurface.applyAsDouble(x-d,y+d));
        height=Math.max(height,aircraftSurface.applyAsDouble(x+d,y-d));height=Math.max(height,aircraftSurface.applyAsDouble(x+d,y+d));return height;
    }

    private static boolean eligible(Unit unit, Unit excluded) {
        return unit != null && unit != excluded && unit.isAdded() && !unit.dead && unit.type != null;
    }

    private static final class Tracked {
        final Unit unit;
        final Object type;
        UnitAltitudeModel.State pose;
        double x, z,centerTerrain,groundVerticalVelocity,routeX,routeZ,routeSeconds;
        long seen;
        boolean hasPosition;
        boolean boostGround;
        Tracked(Unit unit, UnitAltitudeModel.State pose, double x, double z,double centerTerrain) {
            this.unit = unit;
            this.type = unit.type;
            this.pose = pose;
            this.x = x;
            this.z = z;
            this.centerTerrain=centerTerrain;
        }
    }

    private record ExternalPose(Unit3DState state) implements UnitAltitudeModel.Pose {
        public double height(){return state.z();}
        public double verticalVelocity(){return state.vz();}
        public double desiredHeight(){return state.desiredAltitude();}
        public double yaw(){return state.yaw();}
        public double pitch(){return state.pitch();}
        public double roll(){return state.roll();}
        public boolean flying(){return state.flying();}
    }

    public static UnitAltitudeModel.Profile profile() {
        return new UnitAltitudeModel.Profile(
            UnitsConfig.SMALL_MAX_SIZE, UnitsConfig.MEDIUM_MAX_SIZE,
            UnitsConfig.SMALL_CRUISE_MIN, UnitsConfig.SMALL_CRUISE_MAX,
            UnitsConfig.MEDIUM_CRUISE_MIN, UnitsConfig.MEDIUM_CRUISE_MAX,
            UnitsConfig.LARGE_CRUISE_MIN, UnitsConfig.LARGE_CRUISE_MAX,
            UnitsConfig.MIN_BODY_HALF_DEPTH, UnitsConfig.BODY_HALF_DEPTH_PER_SIZE,
            UnitsConfig.MIN_CLEARANCE, UnitsConfig.ALTITUDE_RESPONSE_SECONDS,
            UnitsConfig.MAX_CLIMB_SPEED, UnitsConfig.MAX_DESCENT_SPEED,
            UnitsConfig.ALTITUDE_VARIATION, UnitsConfig.ALTITUDE_VARIATION_SECONDS,
            UnitsConfig.ATTITUDE_RESPONSE_SECONDS, UnitsConfig.MAX_PITCH_DEGREES,
            UnitsConfig.MAX_ROLL_DEGREES, UnitsConfig.TURN_BANK_SECONDS,
            UnitsConfig.MIN_TRAVEL_SPEED, UnitsConfig.INTEGRATION_STEP_SECONDS,
            UnitsConfig.MAX_FRAME_SECONDS, UnitsConfig.MAX_BODY_OFFSET);
    }
}
