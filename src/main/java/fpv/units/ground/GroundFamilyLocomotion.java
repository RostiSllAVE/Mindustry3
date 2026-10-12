package fpv.units.ground;

import arc.struct.IntMap;
import fpv.generated.GroundConfig;
import fpv.generated.UnitsConfig;
import fpv.world.World3DAdapter;
import java.util.function.DoubleBinaryOperator;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.gen.Groups;
import mindustry.gen.Legsc;
import mindustry.io.SaveVersion;
import mindustry.io.SaveFileReader;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Authoritative adapter. It runs after native horizontal AI and never owns a separate render height. */
public final class GroundFamilyLocomotion {
    private static final class Tracked {
        final Unit unit;final Object type;GroundLocomotionModel.State state;GroundStanceState stance;double halfDepth;long seen;
        Tracked(Unit u,GroundLocomotionModel.State state,GroundStanceState stance,double halfDepth){unit=u;type=u.type;this.state=state;this.stance=stance;this.halfDepth=halfDepth;}
    }
    private static final class Intent {
        Unit unit;Object type;double x,y,dx,dy;long seen;boolean pending,groundedMech;float elevation;
    }
    // Never added, updated, serialized or exposed as a world unit; only native tile hitbox math is reused.
    private static final class NativeMechCollisionProbe extends mindustry.gen.MechUnit {}
    private final NativeMechCollisionProbe mechCollisionProbe=new NativeMechCollisionProbe();
    private final IntMap<Intent> intents=new IntMap<>();
    private long intentGeneration;
    private final World3DAdapter world;
    private final fpv.construction.LayerNavigationAdapter layerNavigation;
    private final IntMap<Tracked> states=new IntMap<>();
    private final GroundLocomotionModel model=new GroundLocomotionModel(GroundConfig.EXTENSION_CLIMB_SPEED,
        GroundConfig.EXTENSION_DESCENT_SPEED,GroundConfig.SWEEP_STEP_TILES,GroundConfig.CRAWLER_STEP_TILES);
    private DoubleBinaryOperator projectedObstacle;
    private long generation;
    private Unit supportUnit;
    private final IntMap<GroundStanceCodec.Entry> restored=new IntMap<>();
    private final IntMap<Control> controls=new IntMap<>();
    private record Control(Unit unit,boolean controlled,double axis,double dt){}
    private boolean saveInstalled,closed;
    private Runnable poseInvalidation=()->{};
    private final GroundLocomotionModel.Surface surface=new GroundLocomotionModel.Surface(){
        public double support(double x,double y){return physicalSupport(x,y);}
        public fpv.physics.SweptBodies.Hit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
            return world.construction()==null?null:world.construction().sweepUnit(supportUnit,ax,ay,az,bx,by,bz,radius,halfHeight);
        }
        public double footX(int index,int count,double yaw,double spread){return GroundUnitProfiles.extensibleMech(supportUnit)?NativeMechLayout.footX(supportUnit.type,index,yaw):supportUnit instanceof Legsc?NativeLegLayout.footX(supportUnit.type,index,count,yaw):GroundLocomotionModel.Surface.super.footX(index,count,yaw,spread);}
        public double footY(int index,int count,double yaw,double spread){return GroundUnitProfiles.extensibleMech(supportUnit)?NativeMechLayout.footY(supportUnit.type,index,yaw):supportUnit instanceof Legsc?NativeLegLayout.footY(supportUnit.type,index,count,yaw):GroundLocomotionModel.Surface.super.footY(index,count,yaw,spread);}
        public double obstruction(double x,double y){
            if(x<0||y<0||x>=Vars.world.width()||y>=Vars.world.height())return 1e6;
            double physical=physicalSupport(x,y);
            var tracked=supportUnit==null?null:states.get(supportUnit.id);
            if(world.construction()!=null&&tracked!=null)physical=world.construction().obstructionTop(x,y,tracked.state.z()+tracked.halfDepth,physical);
            return projectedObstacle==null?physical:Math.max(physical,projectedObstacle.applyAsDouble(x,y));
        }
    };
    public GroundFamilyLocomotion(World3DAdapter world){if(world==null)throw new IllegalArgumentException("World required");this.world=world;layerNavigation=new fpv.construction.LayerNavigationAdapter(world);}
    /** Optional live projection top, respecting blocksUnits. It is obstruction only, never a foot surface. */
    public void setProjectedObstacle(DoubleBinaryOperator height){projectedObstacle=height;}
    public double physicalSupport(double x,double y){
        double nativeHeight=Math.max(world.terrainHeight(x,y),world.surfaceHeight(x,y));
        var tracked=supportUnit==null?null:states.get(supportUnit.id);
        return world.construction()==null||tracked==null?nativeHeight:world.construction().supportBelow(x,y,tracked.state.z()-tracked.halfDepth+.04,nativeHeight);
    }
    public void installSavePersistence(){
        if(saveInstalled)return;saveInstalled=true;
        SaveVersion.addCustomChunk("fpv-ground-stances",new SaveFileReader.CustomChunk(){
            public boolean shouldWrite(){return !closed;}
            public boolean writeNet(){return false;}
            public void write(DataOutput out)throws IOException{GroundStanceCodec.write(out,snapshotStances());}
            public void read(DataInput in)throws IOException{restoreStances(GroundStanceCodec.read(in));}
            public void read(DataInput in,int length)throws IOException{if(length<8||length>GroundStanceCodec.MAX_BYTES)throw new IOException("Walker stance chunk length");read(in);}
        });
    }
    public List<GroundStanceCodec.Entry> snapshotStances(){
        ArrayList<GroundStanceCodec.Entry> result=new ArrayList<>();
        for(Tracked t:states.values()){
            if(!t.unit.isAdded()||t.unit.dead||t.unit.type!=t.type||!GroundUnitProfiles.stanceCapable(t.unit)||result.size()>=GroundStanceCodec.MAX_UNITS)continue;
            var p=GroundUnitProfiles.profile(t.unit);if(p==null)continue;
            result.add(new GroundStanceCodec.Entry(t.unit.id,t.unit.type.name,t.unit.team.id,t.unit.x/(double)Vars.tilesize,t.unit.y/(double)Vars.tilesize,
                t.stance.randomOffset(),t.stance.desired(p),Math.max(0,t.state.clearance(t.halfDepth)),t.stance.manual()));
        }
        return result;
    }
    public void setPoseInvalidation(Runnable listener){poseInvalidation=listener==null?()->{}:listener;}
    public void restoreStances(List<GroundStanceCodec.Entry> entries){
        states.clear();intents.clear();controls.clear();restored.clear();
        for(var entry:entries)restored.put(entry.unitId(),entry);poseInvalidation.run();
    }
    public void setStanceControl(Unit unit,boolean controlled,double axis,double dt){
        if(unit==null||unit.dead||Vars.net!=null&&Vars.net.client())return;
        if(!Double.isFinite(axis+dt)||Math.abs(axis)>1||dt<0)throw new IllegalArgumentException("Bounded authoritative stance input required");
        var p=GroundUnitProfiles.profile(unit);if(p==null||!p.legged())return;
        controls.put(unit.id,new Control(unit,controlled,axis,Math.min(.1,dt)));
    }
    public GroundStanceState stance(Unit unit){var t=unit==null?null:states.get(unit.id);return t!=null&&t.unit==unit?t.stance:null;}
    public void retain(Unit unit){var t=unit==null?null:states.get(unit.id);if(t!=null&&t.unit==unit){t.seen=generation;Control c=controls.remove(unit.id);if(c!=null&&c.unit==unit)t.stance.control(GroundUnitProfiles.profile(unit),c.controlled,c.axis,c.dt,4);}}
    public boolean seedFromAir(Unit unit,double height,double radius,double halfDepth){
        if(unit==null||!Double.isFinite(height))return false;var p=GroundUnitProfiles.profile(unit);if(p==null)return false;
        supportUnit=unit;var pose=state(unit,radius,halfDepth);
        model.reset(pose,p,unit.x/(double)Vars.tilesize,unit.y/(double)Vars.tilesize,unit.rotation,radius,halfDepth,surface);
        model.seedHeight(pose,p,height,halfDepth);return true;
    }
    /** Capture existing commanded velocity before native VelComp can erase it at a 2D-solid tile. */
    public void captureIntent(double dt){
        if(!Double.isFinite(dt)||dt<0)throw new IllegalArgumentException("Finite simulation delta required");
        if((Vars.net!=null&&Vars.net.client())||dt==0){intents.clear();return;}
        intentGeneration++;dt=Math.min(.1,dt);
        for(Unit unit:Groups.unit){
            var profile=GroundUnitProfiles.profile(unit);if(profile==null||!profile.legged()||unit.dead||unit.isFlying())continue;
            Intent intent=intents.get(unit.id);if(intent==null){intent=new Intent();intents.put(unit.id,intent);}
            intent.unit=unit;intent.type=unit.type;intent.x=unit.x/(double)Vars.tilesize;intent.y=unit.y/(double)Vars.tilesize;
            intent.elevation=unit.elevation;intent.groundedMech=GroundUnitProfiles.extensibleMech(unit)&&unit.elevation<=.001f&&!explicitMechBoost(unit);
            double factor=60*dt/Vars.tilesize;intent.dx=unit.vel.x*factor;intent.dy=unit.vel.y*factor;
            double length=Math.hypot(intent.dx,intent.dy),maximum=Math.min(4,Math.max(.05,unit.speed()*factor*2));
            if(length>maximum){intent.dx*=maximum/length;intent.dy*=maximum/length;}
            intent.pending=Double.isFinite(intent.dx+intent.dy)&&length>1e-8;intent.seen=intentGeneration;
        }
        var it=intents.entries();while(it.hasNext())if(it.next().value.seen!=intentGeneration)it.remove();
    }
    /** A supported Vela above a solid tile is still a walker; native XY solidity must not grant free boost. */
    public void restoreAutomaticMechBoost(Unit unit){
        if(Vars.net.client()||!GroundUnitProfiles.extensibleMech(unit)||unit.elevation<=.001f||explicitMechBoost(unit))return;
        Intent intent=intents.get(unit.id);Tracked tracked=states.get(unit.id);
        if(intent==null||intent.unit!=unit||intent.type!=unit.type||intent.seen!=intentGeneration||!intent.groundedMech||tracked==null||tracked.unit!=unit)return;
        double x=unit.x/(double)Vars.tilesize,y=unit.y/(double)Vars.tilesize;
        // Player.update can raise elevation before native XY collision pushes the body off its
        // starting tile. Both that partial rise and UnitComp's forced elevation=1 are automatic.
        boolean startSolid=mindustry.entities.EntityCollisions.solid((int)Math.floor(intent.x+.5),(int)Math.floor(intent.y+.5));
        if(!unit.onSolid()&&!startSolid)return;
        if(tracked.state.z()-tracked.halfDepth+1e-6<Math.max(physicalSupport(x,y),physicalSupport(intent.x,intent.y)))return;
        unit.elevation=intent.elevation;
    }
    /** Shared with the native weapon adapter: explicit boosts keep all original airborne restrictions. */
    public static boolean explicitMechBoost(Unit unit){
        if(unit.controller() instanceof mindustry.gen.Player player)return player.boosting;
        if(unit.controller() instanceof mindustry.ai.types.CommandAI command)
            return command.hasStance(mindustry.ai.UnitStance.boost)||command.currentCommand()!=mindustry.ai.UnitCommand.moveCommand;
        // Preserve arbitrary logic/mod controllers; only known native ground AI has no boost intent.
        return !(unit.controller() instanceof mindustry.ai.types.GroundAI);
    }
    public void beginUpdate(){generation++;GroundUnitProfiles.refresh();}
    public void endUpdate(){var it=states.entries();while(it.hasNext())if(it.next().value.seen!=generation)it.remove();
        var ci=controls.entries();while(ci.hasNext()){Control c=ci.next().value;if(c.unit.dead||!c.unit.isAdded())ci.remove();}}
    public GroundLocomotionModel.State pose(Unit unit){Tracked tracked=unit==null?null:states.get(unit.id);return tracked!=null&&tracked.unit==unit&&tracked.type==unit.type?tracked.state:null;}
    public GroundLocomotionModel.State state(Unit unit,double radius,double halfDepth){
        var profile=GroundUnitProfiles.profile(unit);if(profile==null)return null;
        supportUnit=unit;
        Tracked tracked=states.get(unit.id);
        if(tracked==null||tracked.unit!=unit||tracked.type!=unit.type){
            var saved=restored.remove(unit.id);
            boolean match=saved!=null&&saved.typeName().equals(unit.type.name)&&saved.teamId()==unit.team.id&&
                Math.hypot(saved.x()-unit.x/(double)Vars.tilesize,saved.y()-unit.y/(double)Vars.tilesize)<.05;
            var stance=new GroundStanceState(match?saved.offset():GroundStanceState.offset(unit.id,unit.type.id),profile);
            var pose=model.create(profile,unit.x/(double)Vars.tilesize,unit.y/(double)Vars.tilesize,unit.rotation,radius,halfDepth,surface);
            if(match){stance.restoreDesired(saved.desired(),saved.manual()&&unit.isPlayer());model.seedHeight(pose,profile,pose.supportHeight()+halfDepth+saved.clearance(),halfDepth);}
            tracked=new Tracked(unit,pose,stance,halfDepth);states.put(unit.id,tracked);
        }
        tracked.seen=generation;tracked.halfDepth=halfDepth;return tracked.state;
    }
    public GroundLocomotionModel.State update(Unit unit,double dt,double radius,double halfDepth){
        var profile=GroundUnitProfiles.profile(unit);if(profile==null)return null;
        var state=state(unit,radius,halfDepth);Tracked tracked=states.get(unit.id);
        Control control=controls.remove(unit.id);
        if(control!=null&&control.unit==unit)tracked.stance.control(profile,control.controlled,control.axis,control.dt,4);
        if(Vars.net!=null&&Vars.net.client())return state; // A client can only visualize server Unit3DState.
        double x=unit.x/(double)Vars.tilesize,y=unit.y/(double)Vars.tilesize;
        if(Math.hypot(x-state.x(),y-state.y())>UnitsConfig.TELEPORT_DISTANCE){
            model.reset(state,profile,x,y,unit.rotation,radius,halfDepth,surface);return state;
        }
        if(dt<=0)return state;
        Intent intent=intents.get(unit.id);
        if(intent!=null&&intent.pending&&intent.unit==unit&&intent.type==unit.type){
            intent.pending=false;
            // Only replace a displacement that native tile solidity actually rejected; preserve AI,
            // collisions with other units, external teleports and zero-input rest.
            double actual=Math.hypot(x-intent.x,y-intent.y),requested=Math.hypot(intent.dx,intent.dy);
            if(intent.groundedMech&&nativeMechCollisionMatches(unit,intent,x,y)){
                // Native XY rectangles push even a supported tall mech back out of a wall. Recognize
                // that exact displacement, then let the physical XYZ solver decide whether it clears.
                x=intent.x+intent.dx;y=intent.y+intent.dy;
            }else if(actual<=requested*2+.05&&nativeSolid(unit,intent.x+intent.dx,intent.y+intent.dy,radius)){
                x=GroundIntentMath.restore(intent.x,x,intent.dx,true);
                y=GroundIntentMath.restore(intent.y,y,intent.dy,true);
            }
        }
        var detour=layerNavigation.next(unit,state,x,y,radius,halfDepth,dt);if(detour!=null){x=detour.x();y=detour.y();}
        model.step(state,profile,x,y,unit.rotation,radius,halfDepth,dt,surface,profile.legged()?tracked.stance.desired(profile):profile.normalStance());
        double movedX=state.x()-unit.x/(double)Vars.tilesize,movedY=state.y()-unit.y/(double)Vars.tilesize;
        if(Math.abs(movedX)>1e-9||Math.abs(movedY)>1e-9){
            unit.set((float)(state.x()*Vars.tilesize),(float)(state.y()*Vars.tilesize));
            // Only displacement receives the speed fraction, so steady movement is not penalized twice.
            if(state.blocked())unit.vel.scl(.25f);
        }
        return state;
    }
    private boolean nativeMechCollisionMatches(Unit unit,Intent intent,double x,double y){
        if(!GroundUnitProfiles.extensibleMech(unit)||Math.hypot(x-intent.x-intent.dx,y-intent.y-intent.dy)<1e-5)return false;
        mechCollisionProbe.hitSize=unit.hitSize;
        mechCollisionProbe.set((float)(intent.x*Vars.tilesize),(float)(intent.y*Vars.tilesize));
        Vars.collisions.move(mechCollisionProbe,(float)(intent.dx*Vars.tilesize),(float)(intent.dy*Vars.tilesize),mindustry.entities.EntityCollisions::solid);
        // Unit separation, external impulses and teleports must not be mistaken for a wall push.
        return Math.hypot(mechCollisionProbe.x/Vars.tilesize-x,mechCollisionProbe.y/Vars.tilesize-y)<1e-4;
    }
    private static boolean nativeSolid(Unit unit,double x,double y,double radius){
        var solid=unit.solidity();if(solid==null)return false;
        double sample=Math.min(1.5,radius*.6);
        for(int i=0;i<5;i++){
            double px=x+(i==1?-sample:i==2?sample:0),py=y+(i==3?-sample:i==4?sample:0);
            if(solid.solid((int)Math.floor(px+.5),(int)Math.floor(py+.5)))return true;
        }
        return false;
    }
    /** A payload is not a live world body; discard any pose from before pickup/reconstruction. */
    public void forgetPayload(Unit unit){if(unit==null)return;states.remove(unit.id);intents.remove(unit.id);controls.remove(unit.id);restored.remove(unit.id);}
    public int trackedCount(){return states.size;}
    public void clear(){layerNavigation.clear();states.clear();intents.clear();restored.clear();controls.clear();generation=0;intentGeneration=0;}
    public void close(){closed=true;clear();}
}


