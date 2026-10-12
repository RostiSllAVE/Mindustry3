package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import arc.util.Time;
import fpv.combat.*;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.*;
import java.util.*;

/** Actual shipping renderer, one pose per SDL frame so the body cache's build budget is respected. */
public final class AircraftWeaponVisualChecks implements AutoCloseable {
    private record Content(UnitType type,Weapon[] weapons,float range,float maxRange){}
    private final ArrayList<Content> content=new ArrayList<>();
    private final ArrayList<Unit> units=new ArrayList<>();
    private final IdentityHashMap<Unit,Unit3DState> poses=new IdentityHashMap<>();
    private final boolean walkers=Boolean.getBoolean("fpv.smoke.walkerWeaponsOnly");
    private final UnitType[] types=walkers?new UnitType[]{UnitTypes.atrax,UnitTypes.spiroct,UnitTypes.arkyid,UnitTypes.toxopid}:new UnitType[]{UnitTypes.flare,UnitTypes.zenith,UnitTypes.antumbra,UnitTypes.eclipse,UnitTypes.horizon,UnitTypes.quad};
    private final FpvWorldRenderer renderer;
    private final UnitAltitudeSystem originalAltitudes,alt;
    private final Combat3DSystem originalCombat,combat;
    private final Unit pilot;
    private final Camera3D camera=new Camera3D();
    private Unit unit;
    private WorldCoordinates.Point sight=new WorldCoordinates.Point(1,0,0);
    private int typeIndex,step=-3,checks;
    private long stableBuilds,maximumBytes;
    private int maximumModels;
    private boolean closed,done;

    public AircraftWeaponVisualChecks(FpvWorldRenderer renderer,UnitAltitudeSystem originalAltitudes,Combat3DSystem originalCombat){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Isolated aircraft GL lab required");
        this.renderer=renderer;this.originalAltitudes=originalAltitudes;this.originalCombat=originalCombat;pilot=Vars.player.unit();
        for(var t:Vars.content.units())content.add(new Content(t,t.weapons.toArray(Weapon.class),t.range,t.maxRange));
        alt=new UnitAltitudeSystem(renderer.terrain()::heightAt);alt.setExternalStateProvider(poses::get);
        var world=new World3DAdapter(renderer.terrain(),renderer::surfaceHeight,alt);
        if(walkers)alt.setGroundFamily(new fpv.units.ground.GroundFamilyLocomotion(world));
        combat=new Combat3DSystem(world,()->true,u->u==unit?sight:null);combat.install();
        renderer.setUnitAltitudes(alt);renderer.setCombat(combat);renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(true);
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.015f;camera.far=300;camera.fov=65;
    }
    public boolean update(){
        if(done)return true;
        combat.update(Math.min(.1,Time.delta/60.0));
        if(unit==null){unit=types[typeIndex].create(Team.sharded);unit.set(64*8,64*8);unit.add();units.add(unit);Vars.player.unit(unit);step=-3;}
        unit.set(64*8,64*8);unit.rotation=0;unit.vel.setZero();unit.controlWeapons(false,false);
        var mount=unit.mounts[0];boolean bomber=combat.aircraftWeapons().profile(unit,mount).bomber();
        renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(true);
        double[] rolls=walkers?new double[]{0,-12,12,0,12,0}:new double[]{0,-45,45,90,180,0};double pitch=step>=0&&step<12?(step%2==0?(walkers?-12:-30):(walkers?12:30)):0,roll=step>=0&&step<12?rolls[step/2]:0;
        pose(unit,64,64,pitch,roll);
        sight=AircraftWeaponGeometry.direction(AircraftWeaponGeometry.frame(0,pitch,roll),0,bomber?-80:30);
        camera.position.set(70,13,-56);camera.lookAt(64,10,-64);camera.up.set(0,1,0);camera.update();
        if(step<0){renderer.render(camera);step++;return false;}
        float health=unit.health;
        if(step<12){
            renderer.render(camera);check(renderer.unitGeometry(unit.type.region)!=null,"native body cache warmed for "+unit.type.name);check(renderer.aircraftStats().draws()>0,"shipping world renderer submits guns");
            if(walkers)check(renderer.groundFamily().nativeLimbStats().draws()>0,"existing articulated legs remain rendered");
            check(combat.aircraftWeapons().visual(unit,mount).side()==(bomber?-1:1),"local side under bank/pitch/inversion");if(bomber)check(renderer.aircraftStats().ports()>0,"real underside port drawn");
            capture(unit.type.name+"-aircraft-p"+(int)pitch+"-r"+(int)roll+".png");
        }else if(step==12){
            camera.position.set(64,10,-70);camera.lookAt(64,10,-64);camera.update();renderer.render(camera);stableBuilds=renderer.aircraftStats().builds();capture(unit.type.name+"-aircraft-side.png");
        }else if(step==13){
            camera.position.set(64,25,-64);camera.direction.set(0,-1,0);camera.up.set(0,0,-1);camera.update();renderer.render(camera);capture(unit.type.name+"-aircraft-top.png");
        }else if(step==14){
            renderer.setFirstPersonActive(true);renderer.setShowControlledUnit(false);camera.position.set(64,10,-64);camera.direction.set(1,0,0);camera.up.set(0,1,0);camera.update();renderer.render(camera);
            int eligible=0;for(var m:unit.mounts)if(combat.aircraftWeapons().profile(unit,m)!=null)eligible++;
            check(renderer.firstPersonRig().stats().weapons()==eligible,"one active viewmodel per native weapon");check(!renderer.wasUnitBodySubmitted(unit)&&renderer.aircraftStats().draws()==0,"no world/viewmodel duplicate");capture(unit.type.name+"-aircraft-fpv.png");
        }else if(step==15){
            float delta=Time.delta;Time.delta=1;Time.clear();
            try{for(var m:unit.mounts){if(combat.aircraftWeapons().profile(unit,m)==null)continue;float minimum=m.weapon.minShootVelocity;
                try{m.weapon.minShootVelocity=0;m.shoot=true;m.side=m.weapon.flipSprite;m.reload=0;m.aimX=unit.x+100;m.aimY=unit.y;int count=m.totalShots;m.weapon.update(unit,m);check(m.totalShots-count==m.weapon.shoot.shots,"real native firing pattern not doubled");}
                finally{m.weapon.minShootVelocity=minimum;m.shoot=false;}
            }for(int tick=0;tick<20;tick++)Time.update();}finally{Time.delta=delta;}
            renderer.render(camera);capture(unit.type.name+"-aircraft-firing.png");
            for(var b:Groups.bullet.copy())if(b.owner==unit){b.hit=true;b.remove();}
            check(unit.mounts[0].recoil>0,"actual native shot sets recoil");
        }else if(step==16){
            pose(unit,66,64,0,0);unit.set(66*8,64*8);renderer.render(camera);check(renderer.wasUnitBodySubmitted(unit),"moving physical pose stays rendered");capture(unit.type.name+"-aircraft-moving.png");
        }else if(step==17){
            if(!bomber){pose(unit,64,64,0,walkers?12:180);sight=walkers?AircraftWeaponGeometry.direction(AircraftWeaponGeometry.frame(0,0,12),0,-30):new WorldCoordinates.Point(1,0,.5);renderer.render(camera);check(combat.aircraftWeapons().visual(unit,mount).side()==-1,"body-local lower mount");capture(unit.type.name+"-aircraft-inverted-sky.png");}
        }else if(step==18){
            if(!bomber){
                sight=AircraftWeaponGeometry.direction(AircraftWeaponGeometry.frame(0,0,0),0,-89);
                float delta=Time.delta;Time.delta=1;Time.clear();
                try{for(var m:unit.mounts){if(combat.aircraftWeapons().profile(unit,m)==null)continue;float minimum=m.weapon.minShootVelocity;
                    try{m.weapon.minShootVelocity=0;m.shoot=true;m.side=m.weapon.flipSprite;m.reload=0;m.aimX=unit.x+100;m.aimY=unit.y;unit.aimX=m.aimX;unit.aimY=m.aimY;int before=m.totalShots;m.weapon.update(unit,m);check(m.totalShots-before==m.weapon.shoot.shots,"past lower pitch stop still fires one original cycle");check(combat.aircraftWeapons().visual(unit,m).pitch()==-combat.aircraftWeapons().profile(unit,m).pitchLimit(),"physical model uses expanded lower stop");}
                    finally{m.weapon.minShootVelocity=minimum;m.shoot=false;}
                }for(int t=0;t<20;t++)Time.update();}finally{Time.delta=delta;}
                renderer.render(camera);check(combat.aircraftWeapons().visual(unit,mount).side()==-1,"normal ground selects lower mount");capture(unit.type.name+"-aircraft-lower.png");
                renderer.setFirstPersonActive(true);renderer.setShowControlledUnit(false);camera.position.set(64,10,-64);camera.direction.set((float)sight.x(),(float)sight.z(),(float)-sight.y());camera.up.set(0,1,0);camera.update();renderer.render(camera);capture(unit.type.name+"-fpv-past-lower-stop.png");check(renderer.firstPersonRig().stats().weapons()>0,"clamped weapons remain visible in downward FPV");
                for(var b:Groups.bullet.copy())if(b.owner==unit){b.hit=true;b.remove();}
            }
        }else{
            var copies=new ArrayList<Unit>();try{for(int n=0;n<4;n++){var copy=unit.type.create(Team.sharded);copy.set((48+n*10)*8,76*8);copy.rotation=0;copy.add();units.add(copy);copies.add(copy);pose(copy,48+n*10,76,0,0);}
                camera.position.set(64,30,-54);camera.lookAt(64,10,-70);camera.up.set(0,1,0);camera.fov=80;camera.update();renderer.render(camera);
                check(renderer.aircraftStats().builds()==stableBuilds,"instances and all poses reuse cached weapon meshes");check(renderer.aircraftStats().draws()>unit.mounts.length,"multiple units share rendered guns");capture(unit.type.name+"-aircraft-group.png");camera.fov=65;
            }finally{for(var copy:copies){copy.remove();poses.remove(copy);}}
            var stats=renderer.aircraftStats();maximumBytes=Math.max(maximumBytes,stats.bytes());maximumModels=Math.max(maximumModels,stats.models());
            unit.remove();poses.remove(unit);unit=null;combat.clear();typeIndex++;if(typeIndex==types.length){check(Gl.getError()==0,"OpenGL valid");done=true;return true;}
        }
        check(unit==null||unit.health==health,"rendering does not mutate native HP");step++;return false;
    }
    private void pose(Unit u,double x,double y,double pitch,double roll){double h=walkers&&step>=0&&step<12?4+step:10;poses.put(u,new Unit3DState(u.id,u.type.id,u.team.id,x,y,h,0,0,0,0,pitch,roll,h,u.hitSize/16,walkers?GroundFamilyRenderer.bodyHalfDepth(u):.3,!walkers));}
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError("Aircraft GPU: "+label);}
    private void capture(String name){ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name));}
    public String result(){return "PASS: "+checks+(walkers?" four purple walkers stance/tilt/legs":" six native aircraft bank/inversion")+" shipping XYZ/world/FPV/top/side/lower/moving/firing/group GPU checks; warmed body caches, real native shots, no duplicate viewmodels; weapon meshes="+maximumModels+", cached bytes="+maximumBytes;}
    public void close(){if(closed)return;closed=true;for(var u:units)if(u.isAdded())u.remove();Vars.player.unit(pilot);combat.close();alt.close();for(var c:content){c.type.weapons.clear();for(var w:c.weapons)c.type.weapons.add(w);c.type.range=c.range;c.type.maxRange=c.maxRange;}renderer.setUnitAltitudes(originalAltitudes);renderer.setCombat(originalCombat);renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(false);renderer.invalidate();}
}
