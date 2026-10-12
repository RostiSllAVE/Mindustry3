package fpv;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.DoubleSupplier;

/** Real wrapper and native continuous-beam math, without global Trigger.update recursion or world edits. */
final class SimulationClockSmokeChecks {
    private int assertions,nativeTicks;
    static String run(){return new SimulationClockSmokeChecks().verify();}
    private String verify(){
        require(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated desktop clock fixture required");
        float oldDelta=Time.delta,oldSeconds=Core.graphics.getDeltaTime();
        State oldState=Vars.state.getState();
        try{
            Field backend=Core.graphics.getClass().getDeclaredField("deltaTime");backend.setAccessible(true);
            var type=(ContinuousLiquidTurret)Blocks.sublimate;
            require(type.aimChangeSpeed==Float.POSITIVE_INFINITY,"native Sublimate has instantaneous beam length tracking");
            var build=type.new ContinuousLiquidTurretBuild();build.block=type;build.team=Team.sharded;
            build.x=128;build.y=128;build.rotation=37;build.isShooting=false;
            Bullet bullet=Bullet.create();bullet.type=type.ammoTypes.get(Liquids.ozone);bullet.owner=build;bullet.lifetime=60;
            var entry=new Turret.BulletEntry(bullet,0,0,0,0);
            Method nativeBeam=ContinuousTurret.ContinuousTurretBuild.class.getDeclaredMethod("updateBullet",Turret.BulletEntry.class);nativeBeam.setAccessible(true);
            Runnable reset=()->{build.targetPos.set(208,148);build.lastLength=32;bullet.aimX=160;bullet.aimY=130;bullet.vel.setZero();bullet.rotation(37);bullet.set(128,128);};
            Runnable tick=()->{try{nativeBeam.invoke(build,entry);}catch(ReflectiveOperationException failure){throw new IllegalStateException("Native beam fixture failed",failure);}};
            reset.run();Time.delta=0;tick.run();
            require(!Float.isFinite(build.lastLength)&&(!Float.isFinite(bullet.aimX)||!Float.isFinite(bullet.aimY)),
                "actual native Infinity times zero creates the reported poisoned beam aim");
            double[] requested={0},next={0};boolean[] fail={false};SimulationClock[] clock={null};
            ApplicationListener logicBoundary=new ApplicationListener(){
                @Override public void update(){
                    // This is Logic's Trigger.update input point: the initial wrapper scale is stale.
                    requested[0]=next[0];
                    Vars.state.set(next[0]==0?State.paused:State.playing);
                    if(!Vars.state.isPaused()){
                        // Same method registered at native Trigger.beforeGameUpdate in production.
                        clock[0].refreshSimulationDelta();
                        require(Math.abs(Time.delta-next[0])<1e-6,"native frame uses refreshed scaled delta");
                        require(Math.abs(Core.graphics.getDeltaTime()-next[0]/60)<1e-7,"SDL seconds and native ticks use one refreshed scale");
                        require(Time.delta>0&&Float.isFinite(Time.delta),"native beam never runs with a zero or invalid delta");
                        tick.run();nativeTicks++;
                        require(Float.isFinite(build.lastLength)&&Float.isFinite(bullet.aimX)&&Float.isFinite(bullet.aimY),"native beam remains finite after clock transition");
                    }
                    if(fail[0])throw new FixtureFailure();
                }
            };
            Constructor<SimulationClock> constructor=SimulationClock.class.getDeclaredConstructor(ApplicationListener.class,DoubleSupplier.class);constructor.setAccessible(true);
            clock[0]=constructor.newInstance(logicBoundary,(DoubleSupplier)()->requested[0]);
            double[] scales={0,.05,.10,.25,.50,1};
            for(int repeat=0;repeat<8;repeat++)for(double from:scales)for(double to:scales){
                requested[0]=from;next[0]=to;reset.run();Time.delta=1;backend.setFloat(Core.graphics,1f/60);
                int before=nativeTicks;clock[0].update();
                require(nativeTicks-before==(to==0?0:1),"zero speed pauses, all positive speeds run exactly one native frame");
                require(Time.delta==1&&Core.graphics.getDeltaTime()==1f/60,"wrapper restores both caller clocks after each transition");
            }
            requested[0]=0;next[0]=.05;fail[0]=true;reset.run();Time.delta=1;backend.setFloat(Core.graphics,1f/60);
            boolean threw=false;try{clock[0].update();}catch(FixtureFailure expected){threw=true;}
            require(threw&&Time.delta==1&&Core.graphics.getDeltaTime()==1f/60,"clock restoration is guaranteed when the wrapped native listener throws");
            // An old/late callback outside the wrapper must not modify the following app module's time.
            Time.delta=.75f;backend.setFloat(Core.graphics,.02f);clock[0].refreshSimulationDelta();
            require(Time.delta==.75f&&Core.graphics.getDeltaTime()==.02f,"refresh callback is inert outside its owning update scope");
            return "PASS: "+assertions+" assertions; native Sublimate zero-delta NaN reproduced, 288 real-wrapper speed transitions (0/5/10/25/50/100%), "+nativeTicks+" finite native beam updates, pause semantics and finally clock restoration";
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Pinned clock/native beam fixture API changed",error);}
        finally{
            Time.delta=oldDelta;Vars.state.set(oldState);
            try{Field backend=Core.graphics.getClass().getDeclaredField("deltaTime");backend.setAccessible(true);backend.setFloat(Core.graphics,oldSeconds);}
            catch(ReflectiveOperationException error){throw new IllegalStateException("Could not restore fixture SDL clock",error);}
        }
    }
    private void require(boolean value,String description){assertions++;if(!value)throw new AssertionError("Simulation clock: "+description);}
    private static final class FixtureFailure extends RuntimeException{}
}
