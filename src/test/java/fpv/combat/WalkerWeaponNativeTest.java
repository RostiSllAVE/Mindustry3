package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.net.Net;
import mindustry.world.*;
import java.util.*;

/** Real native content and Weapon callbacks, not a second simulated weapon loop. */
public final class WalkerWeaponNativeTest {
    private static int checks;
    private static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    private static Unit3DState pose(Unit u,double height,double yaw,double pitch,double roll){return new Unit3DState(u.id,u.type.id,u.team.id,32,32,height,0,0,0,yaw,pitch,roll,height,u.hitSize/16,.45,false);}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,768,768);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        var states=new IdentityHashMap<Unit,Unit3DState>();var alt=new UnitAltitudeSystem((x,y)->0);alt.setExternalStateProvider(states::get);boolean[] wall={false};var world=new World3DAdapter(new TerrainElevation(),(x,y)->wall[0]?40:0,alt);Unit[] controlled={null};WorldCoordinates.Point[] aim={new WorldCoordinates.Point(1,0,0)};
        var types=List.of(UnitTypes.atrax,UnitTypes.spiroct,UnitTypes.arkyid,UnitTypes.toxopid);int[] counts=types.stream().mapToInt(t->t.weapons.size).toArray();var crawler=UnitTypes.crawler.weapons.first();float crawlerDamage=crawler.bullet.damage;boolean kill=crawler.bullet.killShooter;
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?aim[0]:null);combat.install();var mounts=combat.aircraftWeapons();int eligible=0;
        for(int ti=0;ti<types.size();ti++){
            var type=types.get(ti);var unit=type.create(Team.sharded);unit.set(256,256);unit.add();controlled[0]=unit;
            check(unit.mounts.length==counts[ti],"native mount count unchanged "+type.name);
            float health=type.health,speed=type.speed;int legs=type.legCount;
            for(var mount:unit.mounts){
                var w=mount.weapon;var source=combat.originalType(w.bullet);check(AircraftWeapons.dual(w),"every native walker gun uses shared geometry "+type.name);eligible++;
                float damage=source.damage,reload=w.reload,lifetime=source.lifetime,inaccuracy=w.inaccuracy,spread=source.inaccuracy;w.inaccuracy=source.inaccuracy=0;
                try{
                    for(double height:new double[]{4,20,50})for(double attitude:new double[]{0,12})for(double p:new double[]{20,-20}){
                        unit.rotation=(float)(attitude==0?0:70);states.put(unit,pose(unit,height,unit.rotation,attitude,attitude));var frame=AircraftWeaponGeometry.frame(unit.rotation,attitude,attitude);aim[0]=AircraftWeaponGeometry.direction(frame,0,p);
                        mount.target=null;mount.shoot=true;mount.rotate=true;mount.aimX=unit.x+(float)aim[0].x()*100;mount.aimY=unit.y+(float)aim[0].y()*100;unit.aimX=mount.aimX;unit.aimY=mount.aimY;unit.isShooting=true;
                        mount.side=w.flipSprite;mount.reload=0;int before=mount.totalShots;Time.clear();Time.time++;w.update(unit,mount);var s=mounts.visual(unit,mount);
                        check(s!=null&&s.allowed(),"valid local arc "+type.name+" "+w.name);check(s.side()==(p>0?1:-1),"local upper/lower selection");
                        check(mount.totalShots-before==w.shoot.shots,"one original firing pattern "+type.name);
                        check(mount.reload==w.reload,"one native reload cost");
                        for(int t=0;t<20;t++)Time.update();
                        var shot=combat.projectileState(mount.bullet);check(shot!=null,"native emitted shot is physical XYZ");
                        check(Math.abs(shot.x()-s.muzzle().x())+Math.abs(shot.y()-s.muzzle().y())+Math.abs(shot.z()-s.muzzle().z())<.001,"native shot starts at active visible barrel");
                        var direction=new WorldCoordinates.Point(shot.vx(),shot.vy(),shot.vz());
                        if(source instanceof mindustry.entities.bullet.SapBulletType||source instanceof mindustry.entities.bullet.ShrapnelBulletType){var beam=combat.visuals().stream().filter(v->v.kind()==CombatVisual.Kind.BEAM).max(Comparator.comparingLong(CombatVisual::id)).orElseThrow();direction=new WorldCoordinates.Point(beam.bx()-beam.ax(),beam.by()-beam.ay(),beam.bz()-beam.az());}
                        check(Math.abs(AircraftWeaponGeometry.pitch(frame.local(direction))-p)<.01,"shot/beam pitch matches visible gun");
                        for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}combat.update(1);
                    }
                    states.put(unit,pose(unit,20,0,0,0));unit.rotation=0;mount.shoot=false;
                    double[] boundary={-6,-2,2,6,2,-2};for(int k=0;k<boundary.length;k++){aim[0]=AircraftWeaponGeometry.direction(AircraftWeaponGeometry.frame(0,0,0),0,boundary[k]);check(mounts.visual(unit,mount).side()==(k<3?-1:1),"hysteresis retains prior mount within dead zone");}
                    aim[0]=new WorldCoordinates.Point(0,0,1);mount.shoot=true;mount.reload=0;mount.side=w.flipSprite;int before=mount.totalShots;Time.time++;w.update(unit,mount);check(mount.totalShots-before==w.shoot.shots&&mounts.visual(unit,mount).pitch()==mounts.profile(unit,mount).pitchLimit(),"player above pitch stop fires once at clamp");for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                    mounts.override(w,new AircraftWeaponGeometry.Profile(false,10,15,3));check(mounts.profile(unit,mount).pitchLimit()==15,"per-weapon configurable profile");mounts.override(w,new AircraftWeaponGeometry.Profile(false,w.rotate?Math.min(90,w.rotationLimit*.5):Math.min(12,Math.max(1,w.shootCone)),w.rotate?45:35,5));
                    check(source.damage==damage&&source.lifetime==lifetime&&w.reload==reload,"damage/reload/lifetime unchanged");
                }finally{w.inaccuracy=inaccuracy;source.inaccuracy=spread;}
            }
            check(type.health==health&&type.speed==speed&&type.legCount==legs,"body and locomotion metadata unchanged");
            controlled[0]=null;var enemy=UnitTypes.dagger.create(Team.crux);enemy.set(416,256);enemy.health=1000000;enemy.add();states.put(enemy,new Unit3DState(enemy.id,enemy.type.id,enemy.team.id,52,32,24,0,0,0,0,0,0,24,.5,.3,false));states.put(unit,pose(unit,20,0,0,0));
            for(var mount:unit.mounts)for(double z:new double[]{22,16}){double tx=32+combat.unitRanges().reach(unit,combat.originalType(mount.weapon.bullet))*combat.unitRanges().horizontal(type)*.6;enemy.set((float)tx*8,256);states.put(enemy,new Unit3DState(enemy.id,enemy.type.id,enemy.team.id,tx,32,z,0,0,0,0,0,0,z,.5,.3,false));mount.target=enemy;mount.aimX=enemy.x;mount.aimY=enemy.y;mount.shoot=false;for(int t=0;t<100;t++){Time.time++;mount.weapon.update(unit,mount);}var s=mounts.visual(unit,mount);check(s!=null&&s.allowed(),"native AI settles in XYZ arc "+type.name+" "+s+" target="+mount.target+" health="+enemy.health);
                if(!(combat.originalType(mount.weapon.bullet) instanceof mindustry.entities.bullet.ArtilleryBulletType)){var end=new WorldCoordinates.Point(tx,32,z);var to=AircraftWeaponGeometry.difference(end,s.muzzle());check(AircraftWeaponGeometry.dot(to,s.direction())/Math.sqrt(AircraftWeaponGeometry.dot(to,to))>.9999,"AI gun converges on physical target "+type.name+" "+mount.weapon.name+" "+s);check(s.side()==(z>20?1:-1),"AI uses upper/lower local hemisphere");}else{check(s.launchSpeed()>0,"AI artillery retains ballistic launch");var target=new WorldCoordinates.Point(tx,32,z);var delta=AircraftWeaponGeometry.difference(target,s.muzzle());double t=Math.hypot(delta.x(),delta.y())/(s.launchSpeed()*Math.hypot(s.direction().x(),s.direction().y()));var end=ProjectileDynamics.integrate(s.direction().x()*s.launchSpeed(),s.direction().y()*s.launchSpeed(),s.direction().z()*s.launchSpeed(),t,true,0);check(Math.abs(s.muzzle().z()+end.dz()-z)<.01,"visible AI artillery trajectory lands at XYZ target");}
            }
            enemy.remove();states.remove(enemy);controlled[0]=unit;
            var mount=unit.mounts[0];mount.target=null;mount.shoot=false;aim[0]=new WorldCoordinates.Point(1,0,.2);for(int t=0;t<100;t++){Time.time++;mount.weapon.update(unit,mount);}var muzzle=mounts.visual(unit,mount).muzzle();int wx=(int)Math.floor(muzzle.x()+.5),wy=(int)Math.floor(muzzle.y()+.5);Vars.world.tile(wx,wy).setBlock(Blocks.copperWall,Team.sharded);wall[0]=true;check(!mounts.visual(unit,mount).allowed(),"solid building/ceiling blocks muzzle");Vars.world.tile(wx,wy).setBlock(Blocks.air);wall[0]=false;check(mounts.visual(unit,mount).allowed(),"cached aim revalidates removed obstacle");
            var packet=combat.weaponAimReplication();check(AircraftWeaponCodec.decode(packet).aircraft().stream().anyMatch(e->e.id()==unit.id),"existing bounded channel replicates walker side and XYZ angles");combat.applyWeaponAimReplication(packet);
            var nativeNet=Vars.net;try{Vars.net=new Net(null){@Override public boolean client(){return true;}};controlled[0]=null;float[] yaw=new float[unit.mounts.length],pitch=new float[yaw.length];byte[] side=new byte[yaw.length];Arrays.fill(pitch,-22);Arrays.fill(side,(byte)-1);combat.applyWeaponAimReplication(AircraftWeaponCodec.encode(List.of(),List.of(new AircraftWeaponCodec.Entry(unit.id,type.id,unit.team.id,yaw,pitch,side))));var s=mounts.visual(unit,mount);check(s.pitch()==-22&&s.side()==-1,"remote walker renders authoritative local pitch and side");}finally{Vars.net=nativeNet;controlled[0]=unit;}
            if(type==UnitTypes.atrax){var aircraft=UnitTypes.flare.create(Team.crux);aircraft.set(280,256);aircraft.add();mount.target=aircraft;aim[0]=new WorldCoordinates.Point(1,0,.2);check(!mounts.visual(unit,mount).allowed(),"Atrax retains native ground-only target restriction");mount.target=null;aircraft.remove();}
            unit.remove();states.remove(unit);for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}combat.clear();
        }
        check(eligible==17,"17 original walker weapons, 34 visual mounts");var c=UnitTypes.crawler.create(Team.sharded);check(!mounts.supports(c)&&UnitTypes.crawler.weapons.first().bullet.killShooter==kill&&combat.originalType(UnitTypes.crawler.weapons.first().bullet).damage==crawlerDamage,"Crawler suicide emitter preserved, no artificial guns");
        combat.close();alt.close();System.out.println("WalkerWeaponNativeTest: "+checks+" native player/AI/stance/tilt/firing-count/muzzle/beam/ballistic/obstacle/replication checks passed");
    }
}
