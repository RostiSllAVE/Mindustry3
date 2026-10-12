package fpv.physics;

import arc.util.Time;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.flight.FlightSettings;
import fpv.flight.UnitMotionBridge;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.gen.Unit;

/** Pass an owned, already spatially indexed flyer fixture from the isolated lab. */
public final class CollisionResponseSmokeChecks {
    private CollisionResponseSmokeChecks() {}
    public static String verify(World3DAdapter world,Unit target){
        require(Boolean.getBoolean("fpv.smoke")&&target!=null&&target.isAdded()&&!target.dead,"live lab target required");
        float oldVx=target.vel.x,oldVy=target.vel.y,targetHealth=target.health;
        Unit probe=UnitTypes.flare.create(target.team);
        probe.controller(new AIController(){@Override public void updateUnit(){}});
        probe.set(target.x-40,target.y);probe.add();
        var effects=new RadialExplosion(world);
        double[] floor={-100};
        var crash=new CrashSystem((x,z)->floor[0],effects,1);
        crash.setWorld(world);
        crash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(target)));
        try{
            target.vel.setZero();
            var state=approach(world,probe,target,2);
            double hoverVz=state.vy;
            double ox=state.x,oy=state.y,oz=state.z;
            advance(state,.002);
            float health=probe.health;
            require(crash.resolve(probe,state,ox,oy,oz,.002,true).hit(),"safe native body contact found");
            close(state.vx,1,"safe equal-mass own response");
            close(state.vy,hoverVz,"co-moving hover reference keeps contact horizontal");
            close(target.vel.x,0,"target impulse remains deferred");
            // Same target on the next fixed step must already have its predicted new velocity.
            ox=state.x;oy=state.y;oz=state.z;advance(state,.002);
            crash.resolve(probe,state,ox,oy,oz,.002,true,.002);
            close(state.vx,1,"later fixed step does not repeat stale-target impulse");
            reconcile(probe,state);
            require(!crash.applyPending(),"safe collision cannot be lethal");
            close(target.vel.x*60.0/Vars.tilesize,1,"safe target receives equal opposite native impulse");
            close(world.unitState(target).vz(),hoverVz,"horizontal contact leaves native target hover velocity unchanged");
            require(probe.health==health&&target.health==targetHealth,"safe contact changes no health");

            crash.reset();target.vel.setZero();
            state=approach(world,probe,target,20);ox=state.x;oy=state.y;oz=state.z;advance(state,.002);
            require(crash.resolve(probe,state,ox,oy,oz,.002,false).hit(),"damage-disabled body contact found");
            close(state.vx,10,"damage-disabled own response");
            reconcile(probe,state);crash.applyPending();
            close(target.vel.x*60.0/Vars.tilesize,10,"damage-disabled contact still exchanges target momentum");
            require(probe.health==health&&target.health==targetHealth,"damage-disabled contact changes no health");

            // Seed a small real terrain hit to start the damage cooldown without killing the probe.
            crash.reset();target.vel.setZero();floor[0]=0;
            state=new FlightController.State();state.x=target.x/(double)Vars.tilesize-20;
            state.z=-target.y/(double)Vars.tilesize;state.y=.01;state.vy=-4;
            require(crash.resolve(probe,state,state.x,2,state.z,.002,true).hit(),"cooldown seed ground contact");
            crash.applyPending();require(!probe.dead&&probe.health<health,"cooldown seed caused minor native damage");
            health=probe.health;floor[0]=-100;
            state=approach(world,probe,target,20);ox=state.x;oy=state.y;oz=state.z;advance(state,.002);
            require(crash.resolve(probe,state,ox,oy,oz,.002,true).hit(),"body contact during damage cooldown");
            reconcile(probe,state);crash.applyPending();
            close(target.vel.x*60.0/Vars.tilesize,10,"cooldown cannot suppress target physical response");
            require(probe.health==health&&target.health==targetHealth,"cooldown suppresses damage independently");
            return "PASS: native safe/cooldown/damage-disabled paired impulses, co-moving hover reference, deferred commit and later-step target prediction";
        }finally{
            crash.reset();effects.clear();target.vel.set(oldVx,oldVy);
            if(probe.isAdded())probe.remove();
        }
    }
    private static FlightController.State approach(World3DAdapter world,Unit probe,Unit target,double speed){
        var targetState=world.unitState(target);var own=world.unitState(probe);
        double radius=Math.max(.22,Math.min(8,own.radius()));
        var state=new FlightController.State();
        state.x=targetState.x()-radius-targetState.radius()-.001;
        state.z=-targetState.y();state.y=targetState.z();state.vx=speed;
        // Native XY velocity zero does not zero the physical altitude integrator's hover VZ.
        // Follow that velocity so this equal-mass analytic fixture is a horizontal collision.
        state.vy=targetState.vz();
        probe.set((float)state.x*Vars.tilesize,(float)-state.z*Vars.tilesize);probe.vel.setZero();
        return state;
    }
    private static void advance(FlightController.State state,double seconds){
        state.x+=state.vx*seconds;state.y+=state.vy*seconds;state.z+=state.vz*seconds;
    }
    private static void reconcile(Unit unit,FlightController.State state){
        var bridge=new UnitMotionBridge();var initial=new FlightController.State();bridge.syncFromSimulation(unit,initial);
        float delta=Time.delta;
        try{Time.delta=1;bridge.beforeSimulation(unit,state,1);unit.update();bridge.afterSimulation(unit,state);}
        finally{Time.delta=delta;}
    }
    private static void close(double value,double expected,String message){require(Math.abs(value-expected)<.0001,message+": "+value);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Collision response smoke: "+message);}
}
