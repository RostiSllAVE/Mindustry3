package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import fpv.render.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.net.Net;
import mindustry.world.*;
import java.util.*;

/** Native events at raw over-limit aim: no test-side clamping or synthetic bullets. */
public final class WeaponPolishNativeTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,768,768);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        var states=new IdentityHashMap<Unit,Unit3DState>();var alt=new UnitAltitudeSystem((x,y)->0);alt.setExternalStateProvider(states::get);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,alt);Unit[] controlled={null};WorldCoordinates.Point[] sight={new WorldCoordinates.Point(1,0,0)};var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?sight[0]:null);combat.install();
        var types=List.of(UnitTypes.dagger,UnitTypes.mace,UnitTypes.fortress,UnitTypes.scepter,UnitTypes.reign,UnitTypes.nova,UnitTypes.pulsar,UnitTypes.quasar,UnitTypes.flare,UnitTypes.eclipse,UnitTypes.risso,UnitTypes.minke,UnitTypes.sei,UnitTypes.navanax,UnitTypes.atrax,UnitTypes.spiroct,UnitTypes.arkyid,UnitTypes.toxopid);
        for(var type:types){
            var u=type.create(Team.sharded);u.set(256,256);u.rotation=0;u.add();u.controller(new UnitController(){Unit owner;public void unit(Unit n){owner=n;}public Unit unit(){return owner;}});controlled[0]=u;states.put(u,new Unit3DState(u.id,type.id,u.team.id,32,32,20,0,0,0,0,0,0,20,u.hitSize/16,.3,type.flying));
            var levels=new HashMap<String,Double>();
            for(var m:u.mounts){
                var w=m.weapon;var source=combat.originalType(w.bullet);String key=w.name+":"+source.getClass().getName();double offset=WeaponHeightStagger.offset(u,m);var previous=levels.putIfAbsent(key,offset);check(previous==null||previous==offset,"mirrored weapons keep equal height "+type.name);check(offset>=0&&offset<=.36,"bounded small stagger");
                if(!w.controllable||w.noAttack||w.shootOnDeath||source.killShooter||w.continuous||source.spawnUnit!=null)continue;
                float damage=source.damage,reload=w.reload,spread=w.inaccuracy,bspread=source.inaccuracy,minimum=w.minShootVelocity;w.inaccuracy=source.inaccuracy=0;w.minShootVelocity=0;
                try{for(double raw:new double[]{89,-89,90,-90}){
                    sight[0]=AircraftWeaponGeometry.direction(AircraftWeaponGeometry.frame(0,0,0),0,raw);m.target=null;m.rotate=true;m.shoot=false;m.aimX=u.x+100;m.aimY=u.y;u.aimX=m.aimX;u.aimY=m.aimY;u.isShooting=false;for(int t=0;t<120;t++){Time.time++;w.update(u,m);}m.shoot=true;u.isShooting=true;m.side=w.flipSprite;m.reload=0;m.warmup=1;Time.clear();Time.time++;int before=m.totalShots;w.update(u,m);for(int t=0;t<180;t++)Time.update();
                    check(m.totalShots-before==w.shoot.shots,"raw pitch past stop keeps one native firing event "+type.name+" "+w.name+" "+raw);
                    var shot=combat.projectileState(m.bullet);check(shot!=null,"native fired object has XYZ state "+type.name);
                    double expected;
                    if(AircraftWeapons.dual(w)){var s=combat.aircraftWeapons().visual(u,m);expected=Math.copySign(combat.aircraftWeapons().profile(u,m).pitchLimit(),raw);check(Math.abs(s.pitch()-expected)<1e-8,"dual model clamps raw input");check(s.side()==(raw>0?1:-1),"same local hemisphere after clamp");check(Math.abs(shot.z()-s.muzzle().z())<1e-4,"clamped dual muzzle matches visible model");}
                    else if(MechWeaponAim3D.supports(u)){var s=combat.mechWeapons().visual(u,m);expected=raw>0?MechWeaponPitch.MAX:MechWeaponPitch.MIN;check(s.pitch()==expected,"mech model uses expanded mechanical stop");check(Math.abs(shot.z()-s.muzzle().z())<1e-4,"mech shot and model share raised hinge");}
                    else{var profile=NavalHullProfile.forType(type);expected=raw>0?profile.maximumPitch():profile.minimumPitch();check(Math.abs(combat.navalWeapons().visual(u,m,profile).pitch()-expected)<1e-8,"naval model clamps raw input");}
                    if(Math.hypot(Math.hypot(shot.vx(),shot.vy()),shot.vz())>1e-9)check(Math.abs(Math.toDegrees(Math.atan2(shot.vz(),Math.hypot(shot.vx(),shot.vy())))-expected)<.01,"physical projectile uses the visible stop "+type.name);
                    check(source.damage==damage&&w.reload==reload,"damage and reload unchanged");for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}combat.update(1);
                }}finally{w.inaccuracy=spread;source.inaccuracy=bspread;w.minShootVelocity=minimum;}
            }
            if(levels.size()>1)check(new HashSet<>(levels.values()).size()>1,"different native weapons use different heights "+type.name);
            var packet=combat.weaponAimReplication();combat.applyWeaponAimReplication(packet);check(packet.length>0,"new extreme pitches pass bounded production codecs");u.remove();states.remove(u);combat.clear();
        }
        check(WeaponPitchLimits.expanded(35)==45.5&&WeaponPitchLimits.expanded(45)==58.5&&WeaponPitchLimits.expanded(70)==85,"thirty-percent tuning with safe cap");
        for(double h:new double[]{1,10,50})for(double pitch:new double[]{-12,0,12}){double bottom=LegPlatePose.underside(h,.4,-.2,2,pitch,12),depth=LegPlatePose.depth(.32),center=LegPlatePose.centerBelow(bottom,depth);check(center+depth*.5<=bottom-LegPlatePose.GAP+1e-9,"upper plate entirely below actual hull envelope");check(LegPlatePose.width(2)<2&&depth<.32,"armour remains visible but narrower/thinner");}
        combat.close();alt.close();System.out.println("WeaponPolishNativeTest: "+checks+" real native player clamp/count/muzzle/direction/stagger/codec/leg-clearance checks across18 types passed");
    }
}
