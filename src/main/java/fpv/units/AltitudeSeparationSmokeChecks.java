package fpv.units;

import arc.util.Time;
import fpv.world.Unit3DState;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import mindustry.Vars;
import mindustry.async.PhysicsProcess;
import mindustry.async.PhysicsProcess.PhysicsWorld;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Unit;

/** Isolated native lifecycle tests; fixtures use the real pinned process without advancing the map. */
public final class AltitudeSeparationSmokeChecks {
    private static int checks;
    private AltitudeSeparationSmokeChecks() {}

    public static String verify() {
        require(Boolean.getBoolean("fpv.smoke") && !Vars.net.active(), "isolated offline smoke required");
        checks = 0;
        separatedAndRestored();
        overlappingAndMassWeighted();
        sharedFlyingRadius();
        pendingLegacyCorrection();
        deferredBodyRegistration();
        toggleBeforeNativeRegistration();
        spawnAndRemoveDuringActiveSeparation();
        poseResetDiscardsOldCorrection();
        idleNativeWobble();
        return "PASS: " + checks + " native assertions; altitude-gated passive separation, single body registration across deferred spawn/toggle/removal, restored native worlds, pending-correction isolation, idle AI wobble and commanded heading";
    }

    /** 160.5 adds immediately; 160.7 flushes pendingAdds at the next native begin. */
    private static void deferredBodyRegistration() {
        try(Fixture f=new Fixture(true,10)) {
            f.adapter.syncActive();
            require(bodyCount(worlds(f.process))==2,"initial wrapper contains each live ref once");
            f.step();f.step();
            require(bodyCount(worlds(f.process))==2,"native deferred flush does not duplicate seeded bodies");
            require(nativeBodyCount(f.originals)==2,"native original receives each deferred body exactly once");
            f.a.remove();f.step();
            require(bodyCount(worlds(f.process))==1,"removed unit leaves no duplicate wrapper body");
            require(nativeBodyCount(f.originals)==1,"removed unit leaves no native ghost body");
            f.b.remove();f.step();
            require(bodyCount(worlds(f.process))==0,"all removed units leave empty wrapper layers");
            f.enabled=false;f.adapter.syncActive();f.assertOriginalWorlds();
            require(nativeBodyCount(f.originals)==0,"restoring original physics leaves no removed bodies");
        }
    }

    private static void toggleBeforeNativeRegistration() {
        try(Fixture f=new Fixture(true,10)) {
            // Toggle twice while 160.7 still has refs that have not reached its native layers.
            f.adapter.syncActive();f.enabled=false;f.adapter.syncActive();f.assertOriginalWorlds();
            f.enabled=true;f.adapter.syncActive();f.step();
            require(bodyCount(worlds(f.process))==2,"re-enable before deferred flush keeps one body per unit");
            require(nativeBodyCount(f.originals)==2,"repeated pre-flush toggles preserve native registration");
            f.enabled=false;f.adapter.syncActive();f.step();
            f.assertOriginalWorlds();
            require(nativeBodyCount(f.originals)==2,"native restoration preserves two real bodies");
        }
    }

    private static void spawnAndRemoveDuringActiveSeparation() {
        try(Fixture f=new Fixture(true,10)) {
            f.step();
            Unit born=UnitTypes.flare.create(Team.sharded);
            born.set(f.a.x+40,f.a.y);born.spawnedByCore=true;f.heights.put(born,20.0);
            PhysicsProcess previous=Vars.unitPhysics;
            try {
                Vars.unitPhysics=f.process;born.add();
            } finally {Vars.unitPhysics=previous;}
            try {
                f.step();
                require(bodyCount(worlds(f.process))==3,"spawn during active separation registers once");
                require(nativeBodyCount(f.originals)==3,"spawn remains registered for later native restoration");
                born.remove();f.step();
                require(bodyCount(worlds(f.process))==2,"removal during active separation releases body");
                require(nativeBodyCount(f.originals)==2,"removal releases preserved native body");
                // A unit can die or enter a payload before the native queue has been flushed.
                Vars.unitPhysics=f.process;born.add();born.remove();Vars.unitPhysics=previous;
                f.step();
                require(bodyCount(worlds(f.process))==2,"spawn and removal before begin leaves no wrapper ghost");
                require(nativeBodyCount(f.originals)==2,"spawn and removal before begin leaves no native ghost");
            } finally {
                Vars.unitPhysics=previous;
                if(born.isAdded())born.remove();
            }
        }
    }

    private static int bodyCount(PhysicsWorld[] worlds) {
        int total=0;
        for(PhysicsWorld world:worlds) {
            try {
                Field bodies=world.getClass().getDeclaredField("bodies");bodies.setAccessible(true);
                total+=((arc.struct.Seq<?>)bodies.get(world)).size;
            } catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
        }
        return total;
    }
    private static int nativeBodyCount(PhysicsWorld[] worlds) {
        int total=0;
        try {
            Field bodies=PhysicsWorld.class.getDeclaredField("bodies");bodies.setAccessible(true);
            for(PhysicsWorld world:worlds)total+=((arc.struct.Seq<?>)bodies.get(world)).size;
        } catch(ReflectiveOperationException error) {throw new IllegalStateException(error);}
        return total;
    }

    private static void separatedAndRestored() {
        try (Fixture f = new Fixture(true, 10)) {
            float ax=f.a.x, ay=f.a.y, bx=f.b.x, by=f.b.y;
            f.step(); f.step();
            close(f.a.x,ax,0,"separated Z leaves first native X unchanged");
            close(f.a.y,ay,0,"separated Z leaves first native Y unchanged");
            close(f.b.x,bx,0,"separated Z leaves second native X unchanged");
            close(f.b.y,by,0,"separated Z leaves second native Y unchanged");
            require(f.adapter.active(),"local FPV wraps native worlds");
            f.enabled=false;f.adapter.syncActive();
            require(!f.adapter.active(),"disabled FPV restores native path");
            f.assertOriginalWorlds();
            f.step();f.step();
            require(f.distance()>bx-ax+.01,"original 2D separation returns despite different physical Z");
            f.assertUntouchedControls();
        }
    }

    private static void overlappingAndMassWeighted() {
        try (Fixture f = new Fixture(true, .2)) {
            double initial=f.distance(), midpoint=(f.a.x+f.b.x)*.5;
            f.step();f.step();
            require(f.distance()>initial+.01,"overlapping Z retains actual native XY separation");
            close((f.a.x+f.b.x)*.5,midpoint,.0001,"equal native masses preserve horizontal midpoint");
            f.assertUntouchedControls();
            f.adapter.close();f.assertOriginalWorlds();
            require(!f.adapter.active(),"close returns original world identities");
        }
    }

    private static void pendingLegacyCorrection() {
        try (Fixture f = new Fixture(false, 10)) {
            float ax=f.a.x,bx=f.b.x;
            f.step(); // Original worker has produced a correction, not yet applied to Unit.
            close(f.a.x,ax,0,"native separation has its original one-frame delay");
            f.enabled=true;
            f.step();f.step();
            close(f.a.x,ax,0,"enabling XYZ discards pending legacy correction for first unit");
            close(f.b.x,bx,0,"enabling XYZ discards pending legacy correction for second unit");
            f.assertUntouchedControls();
        }
    }

    private static void sharedFlyingRadius() {
        try(Fixture f=new Fixture(true,0)) {
            f.radiusScale=.5;f.b.set(f.a.x+f.a.hitSize*1.1f,f.a.y);
            double initial=f.distance();
            f.step();f.step();
            close(f.distance(),initial,.0001,"native oversized circle cannot preempt the shared flying ram hull");
            f.enabled=false;f.adapter.syncActive();f.step();f.step();
            require(f.distance()>initial+.01,"disabled adapter restores the untouched larger native radius");
            f.assertUntouchedControls();
        }
        try(Fixture f=new Fixture(true,0)) {
            f.radiusScale=.9;f.b.set(f.a.x+f.a.hitSize*1.65f,f.a.y);
            double initial=f.distance(),midpoint=(f.a.x+f.b.x)*.5;
            f.step();f.step();
            require(f.distance()>initial+.01,"quadtree admits shared hulls larger than native indexed circles");
            close((f.a.x+f.b.x)*.5,midpoint,.0001,"larger physical hull retains equal-mass native response");
            f.assertUntouchedControls();
        }
        try(Fixture f=new Fixture(true,0,UnitTypes.dagger)) {
            f.radiusScale=.2;f.b.set(f.a.x+f.a.hitSize*1.1f,f.a.y);
            double initial=f.distance();f.step();f.step();
            require(f.distance()>initial+.01,"actual ground units retain their native separation radius");
            f.assertUntouchedControls();
        }
    }

    private static void idleNativeWobble() {
        try (Fixture f = new Fixture(true, 10);
             UnitAltitudeSystem altitude = new UnitAltitudeSystem((x,z)->0)) {
            Unit unit=f.a;
            var controller=unit.controller();
            require(controller instanceof AIController,"native fixture retains native AI controller");
            AIController ai=(AIController)controller;
            unit.rotation=73;unit.vel.setZero();
            boolean wobble=unit.type.wobble,circleTarget=unit.type.circleTarget;
            float oldTime=Time.time,oldDelta=Time.delta;
            double startX=unit.x,startY=unit.y,maxWobble=0;
            try {
                altitude.update(1.0/60);
                Time.delta=1;
                for(int i=0;i<240;i++) {
                    Time.time=oldTime+i;
                    ai.updateVisuals(); // Calls actual Unit.wobble and native preferred-heading logic.
                    float actualX=unit.x,actualY=unit.y;
                    altitude.update(1.0/60);
                    var pose=altitude.pose(unit);
                    close(pose.yaw(),73,.0001,"actual idle wobble cannot rotate model heading");
                    close(pose.pitch(),0,.0001,"actual idle wobble keeps level pitch");
                    close(pose.roll(),0,.0001,"actual idle wobble keeps level bank");
                    require(unit.x==actualX&&unit.y==actualY,"altitude adapter never rewrites native wobble XY");
                    maxWobble=Math.max(maxWobble,Math.hypot(unit.x-startX,unit.y-startY));
                }
                require(maxWobble>.01,"fixture actually exercised native XY wobble");
                unit.vel.set(1,0);
                for(int i=0;i<240;i++) altitude.update(1.0/60);
                close(altitude.pose(unit).yaw(),0,.0001,"native commanded velocity still changes travel heading");
                require(unit.controller()==controller&&unit.type.wobble==wobble&&unit.type.circleTarget==circleTarget,
                    "AI controller, native wobble flag and combat-circle policy stay unchanged");
            } finally {
                Time.time=oldTime;Time.delta=oldDelta;
            }
        }
    }

    private static void poseResetDiscardsOldCorrection() {
        try (Fixture f = new Fixture(true, .2)) {
            float ax=f.a.x,bx=f.b.x;
            f.step(); // Pending XYZ push used the old, overlapping controlled height.
            f.heights.put(f.b,13.0);
            f.adapter.rebase();
            f.step();f.step();
            close(f.a.x,ax,0,"pose reset discards previous-altitude pending first-unit correction");
            close(f.b.x,bx,0,"pose reset discards previous-altitude pending second-unit correction");
            f.assertUntouchedControls();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PhysicsProcess process=new PhysicsProcess();
        final IdentityHashMap<Unit,Double> heights=new IdentityHashMap<>();
        final AltitudeSeparation adapter=new AltitudeSeparation(process,this::state,()->this.enabled);
        final PhysicsWorld[] originals;
        final Unit a,b;
        final Object aiA,aiB;
        boolean enabled;
        double radiusScale=.6;

        Fixture(boolean enabled,double separation) {
            this(enabled,separation,UnitTypes.flare);
        }
        Fixture(boolean enabled,double separation,mindustry.type.UnitType type) {
            this.enabled=enabled;
            a=type.create(Team.sharded);b=type.create(Team.sharded);aiA=a.controller();aiB=b.controller();
            process.init();originals=worlds(process).clone();
            float x=Vars.world.width()*Vars.tilesize*.5f,y=Vars.world.height()*Vars.tilesize*.5f;
            if(!type.flying) {
                boolean found=false;
                for(int ty=1;ty<Vars.world.height()-1&&!found;ty++)for(int tx=1;tx<Vars.world.width()-3;tx++) {
                    if(a.canPass(tx,ty)&&a.canPass(tx+1,ty)&&a.canPass(tx+2,ty)) {
                        x=tx*Vars.tilesize;y=ty*Vars.tilesize;found=true;break;
                    }
                }
                require(found,"ground fixture has an unchanged passable native corridor");
            }
            a.set(x,y);b.set(x+1,y);
            a.spawnedByCore=true;b.spawnedByCore=true;
            a.vel.set(.4f,.2f);b.vel.set(-.4f,-.2f);
            heights.put(a,3.0);heights.put(b,3.0+separation);
            PhysicsProcess previous=Vars.unitPhysics;
            try {
                // Only these fixture registrations use the isolated original process.
                Vars.unitPhysics=process;a.add();b.add();
            } finally { Vars.unitPhysics=previous; }
            require(a.isAdded()&&b.isAdded()&&a.isFlying()==type.flying&&b.isFlying()==type.flying,"live native fixture mobility");
            require(a.hasPhysicsRef&&b.hasPhysicsRef,"original process owns native refs");
        }

        Unit3DState state(Unit unit) {
            Double height=heights.get(unit);
            return height==null?null:new Unit3DState(unit.id,unit.type.id,unit.team.id,
                unit.x/(double)Vars.tilesize,unit.y/(double)Vars.tilesize,height,
                unit.vel.x*60.0/Vars.tilesize,unit.vel.y*60.0/Vars.tilesize,0,
                unit.rotation,0,0,height,unit.hitSize/(double)Vars.tilesize*radiusScale,.3,unit.isFlying());
        }
        void step() { adapter.begin();process.begin();adapter.awaitPending(); }
        double distance() { return Math.hypot(a.x-b.x,a.y-b.y); }
        void assertOriginalWorlds() {
            PhysicsWorld[] current=worlds(process);
            for(int i=0;i<originals.length;i++)require(current[i]==originals[i],"original PhysicsWorld identity restored at layer "+i);
        }
        void assertUntouchedControls() {
            AltitudeSeparationSmokeChecks.close(a.vel.x,.4f,0,"passive separation leaves first horizontal velocity unchanged");
            AltitudeSeparationSmokeChecks.close(a.vel.y,.2f,0,"passive separation leaves first second-axis velocity unchanged");
            AltitudeSeparationSmokeChecks.close(b.vel.x,-.4f,0,"passive separation leaves second horizontal velocity unchanged");
            AltitudeSeparationSmokeChecks.close(b.vel.y,-.2f,0,"passive separation leaves second second-axis velocity unchanged");
            require(a.controller()==aiA&&b.controller()==aiB,"passive separation preserves actual AI controllers");
        }
        @Override public void close() {
            adapter.close();
            if(a.isAdded())a.remove();if(b.isAdded())b.remove();
            process.reset();
        }
    }

    private static PhysicsWorld[] worlds(PhysicsProcess process) {
        try { Field field=PhysicsProcess.class.getDeclaredField("physics");field.setAccessible(true);return (PhysicsWorld[])field.get(process); }
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private static void close(double actual,double expected,double tolerance,String message) {
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,message+": "+actual+" expected "+expected);
    }
    private static void require(boolean value,String message) {
        checks++;if(!value)throw new IllegalStateException("Altitude separation smoke: "+message);
    }
}
