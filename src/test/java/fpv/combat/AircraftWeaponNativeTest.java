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
public final class AircraftWeaponNativeTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();Vars.world=new World();Vars.world.tiles=new Tiles(96,96);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,768,768);Time.clear();Time.setDeltaProvider(()->1);Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        var states=new IdentityHashMap<Unit,Unit3DState>();var alt=new UnitAltitudeSystem((x,y)->0);alt.setExternalStateProvider(states::get);boolean[] muzzleWall={false};var world=new World3DAdapter(new TerrainElevation(),(x,y)->muzzleWall[0]?30:0,alt);Unit[] controlled={null};WorldCoordinates.Point[] aim={new WorldCoordinates.Point(1,0,0)};
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?aim[0]:null);combat.install();var air=combat.aircraftWeapons();
        check(air.supportedWeapons()>10,"generic metadata eligibility");
        for(var type:List.of(UnitTypes.flare,UnitTypes.zenith,UnitTypes.antumbra,UnitTypes.eclipse,UnitTypes.horizon,UnitTypes.quad)){
            var unit=type.create(Team.sharded);unit.set(32*8,32*8);unit.add();controlled[0]=unit;
            var mount=unit.mounts[0];var weapon=mount.weapon;var source=combat.originalType(weapon.bullet);float damage=source.damage,reload=weapon.reload,speed=source.speed,life=source.lifetime,minVelocity=weapon.minShootVelocity;int count=type.weapons.size;
            weapon.minShootVelocity=0; // Fixture has no native movement update; production velocity restrictions are unchanged.
            try{
                for(double roll:new double[]{0,45,-45,90,180,0})for(double pitch:new double[]{-30,0,30}){
                    Time.clear();states.put(unit,pose(unit,pitch,roll));var frame=AircraftWeaponGeometry.frame(0,pitch,roll);
                    aim[0]=AircraftWeaponGeometry.direction(frame,0,air.profile(unit,mount).bomber()?-80:30);Time.time++;
                    mount.shoot=true;mount.aimX=unit.x+100;mount.aimY=unit.y;unit.aimX=mount.aimX;unit.aimY=mount.aimY;mount.reload=0;
                    int before=mount.totalShots;weapon.update(unit,mount);var solution=air.visual(unit,mount);
                    check(solution!=null&&solution.allowed(),"supported local aim "+type.name);
                    check(solution.side()==(solution.bomber()?-1:1),"local side follows aircraft, not world sky");
                    check(mount.totalShots-before==weapon.shoot.shots,"one native pattern, no mirrored double-fire "+type.name);
                    var bullet=mount.bullet;var shot=combat.projectileState(bullet);check(shot!=null,"real native shot is XYZ-managed");
                    var nativeMount=(AircraftWeapons.AirWeapon)weapon;var expectedMuzzle=AircraftWeaponGeometry.add(solution.muzzle(),AircraftWeaponGeometry.add(AircraftWeaponGeometry.scale(solution.right(),nativeMount.nativeSide-weapon.shootX/Vars.tilesize),AircraftWeaponGeometry.scale(solution.direction(),nativeMount.nativeForward-Math.max(.15,weapon.shootY/Vars.tilesize))));
                    check(Math.abs(shot.x()-expectedMuzzle.x())+Math.abs(shot.y()-expectedMuzzle.y())+Math.abs(shot.z()-expectedMuzzle.z())<3e-5,"physical muzzle preserves native barrel/random offset under full attitude "+type.name);
                    if(!solution.bomber()){
                        var velocity=new WorldCoordinates.Point(shot.vx(),shot.vy(),shot.vz());
                        if(source instanceof mindustry.entities.bullet.LaserBulletType){var beam=combat.visuals().stream().filter(v->v.kind()==CombatVisual.Kind.BEAM).max(java.util.Comparator.comparingLong(CombatVisual::id)).orElseThrow();velocity=new WorldCoordinates.Point(beam.bx()-beam.ax(),beam.by()-beam.ay(),beam.bz()-beam.az());}
                        var local=frame.local(velocity);check(Math.abs(AircraftWeaponGeometry.pitch(local)-30)<1e-3,"projectile/beam pitches with visible gun "+type.name);
                    }
                    if(type==UnitTypes.quad)check(Math.hypot(Math.hypot(shot.vx(),shot.vy()),shot.vz())<1e-9,"zero-speed bomb preserved");
                    for(int t=0;t<20;t++)Time.update();
                    check(Groups.bullet.size()==weapon.shoot.shots,"all delayed native bullets fire exactly once "+type.name);
                    check(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.HIT&&Math.abs(v.ax()-expectedMuzzle.x())+Math.abs(v.ay()-expectedMuzzle.y())+Math.abs(v.az()-expectedMuzzle.z())<3e-5),"flash is at active XYZ muzzle");
                    for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                }
                if(!air.profile(unit,mount).bomber()){
                    aim[0]=new WorldCoordinates.Point(0,0,1);mount.reload=0;mount.side=weapon.flipSprite;int before=mount.totalShots;Time.time++;weapon.update(unit,mount);check(mount.totalShots-before==weapon.shoot.shots&&air.visual(unit,mount).pitch()==air.profile(unit,mount).pitchLimit(),"player above pitch stop fires once at clamp");for(int t=0;t<20;t++)Time.update();for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                    states.put(unit,pose(unit,0,180));aim[0]=new WorldCoordinates.Point(1,0,.5);var s=air.visual(unit,mount);check(s.side()==-1,"inverted world sky selects lower gun");
                }
                if(!air.profile(unit,mount).bomber()){
                    controlled[0]=null;var target=UnitTypes.flare.create(Team.crux);target.set(42*8,32*8);target.add();
                    float inaccurate=weapon.inaccuracy,bulletSpread=source.inaccuracy;weapon.inaccuracy=0;source.inaccuracy=0;
                    try{for(double roll:new double[]{0,90,180}){
                        states.put(unit,pose(unit,0,roll));var f=AircraftWeaponGeometry.frame(0,0,roll);var end=AircraftWeaponGeometry.add(new WorldCoordinates.Point(32,32,20),AircraftWeaponGeometry.direction(f,0,25));end=AircraftWeaponGeometry.add(new WorldCoordinates.Point(32,32,20),AircraftWeaponGeometry.scale(AircraftWeaponGeometry.difference(end,new WorldCoordinates.Point(32,32,20)),10));
                        states.put(target,new Unit3DState(target.id,target.type.id,target.team.id,end.x(),end.y(),end.z(),0,0,0,0,0,0,end.z(),target.hitSize/16,.2,true));mount.target=target;mount.shoot=false;mount.aimX=(float)(end.x()*8);mount.aimY=(float)(end.y()*8);
                        for(int step=0;step<100;step++){Time.time++;weapon.update(unit,mount);}var s=air.visual(unit,mount);var to=AircraftWeaponGeometry.difference(end,s.muzzle());
                        double len=Math.sqrt(AircraftWeaponGeometry.dot(to,to));check(s.allowed()&&AircraftWeaponGeometry.dot(to,s.direction())/len>.99999,"AI mounted gun converges on actual XYZ target "+type.name);
                        mount.shoot=true;mount.reload=0;mount.side=weapon.flipSprite;int before=mount.totalShots;Time.clear();Time.time++;weapon.update(unit,mount);check(mount.totalShots-before==weapon.shoot.shots,"AI uses one native firing cycle "+type.name);for(int t=0;t<20;t++)Time.update();for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                    }
                    if(type==UnitTypes.flare){states.put(unit,pose(unit,0,0));states.put(target,new Unit3DState(target.id,target.type.id,target.team.id,42,32,20,0,0,2,0,0,0,20,target.hitSize/16,.2,true));mount.shoot=false;
                        for(int step=0;step<100;step++){Time.time++;weapon.update(unit,mount);}check(air.visual(unit,mount).pitch()>1,"native predictive aim leads a target's actual vertical velocity");
                    }
                    }finally{weapon.inaccuracy=inaccurate;source.inaccuracy=bulletSpread;mount.target=null;target.remove();states.remove(target);controlled[0]=unit;}
                }
                else{
                    controlled[0]=null;var target=UnitTypes.dagger.create(Team.crux);target.set(33*8,32*8);target.add();states.put(target,new Unit3DState(target.id,target.type.id,target.team.id,33,32,.2,0,0,0,0,0,0,.2,.5,.2,false));
                    try{states.put(unit,pose(unit,0,0));mount.target=target;mount.aimX=target.x;mount.aimY=target.y;mount.shoot=true;mount.side=weapon.flipSprite;mount.reload=0;Time.clear();Time.time++;int before=mount.totalShots;weapon.update(unit,mount);var s=air.visual(unit,mount);
                        check(s.allowed()&&s.side()==-1,"AI bomber engages near-vertical ground target");check(mount.totalShots-before==weapon.shoot.shots,"AI bomber keeps native projectile count");
                        var p=combat.projectileState(mount.bullet);check(p!=null&&Math.abs(p.vz())<1e-8,"bomb release does not auto-steer toward ground target");for(int t=0;t<20;t++)Time.update();for(var b:Groups.bullet.copy()){b.hit=true;b.remove();}
                    }finally{mount.target=null;target.remove();states.remove(target);controlled[0]=unit;}
                }
                if(type==UnitTypes.eclipse){
                    states.put(unit,pose(unit,0,0));aim[0]=new WorldCoordinates.Point(1,0,.1);mount.shoot=false;for(int tick=0;tick<100;tick++){Time.time++;weapon.update(unit,mount);}check(air.visual(unit,mount).allowed(),"settled native gimbal can fire before obstacle insertion");mount.shoot=true;mount.reload=0;mount.side=weapon.flipSprite;Time.time++;
                    Vars.world.tile(33,31).setBlock(Blocks.copperWall,Team.sharded,0);muzzleWall[0]=true;int before=mount.totalShots;weapon.update(unit,mount);
                    check(mount.totalShots==before&&mount.reload==0&&!air.visual(unit,mount).allowed(),"muzzle cannot skip a solid wall between aircraft and barrel tip");
                    Vars.world.tile(33,31).setBlock(Blocks.air);muzzleWall[0]=false;check(air.visual(unit,mount).allowed(),"same cached aim responds immediately when obstacle is removed");
                }
                check(source.damage==damage&&source.speed==speed&&source.lifetime==life&&weapon.reload==reload&&type.weapons.size==count,"native stats and number of weapons unchanged");
                var bytes=combat.weaponAimReplication();var decoded=AircraftWeaponCodec.decode(bytes);combat.applyWeaponAimReplication(bytes);check(decoded.aircraft().stream().anyMatch(e->e.id()==unit.id),"shared existing channel carries aircraft state");
                var nativeNet=Vars.net;try{
                    Vars.net=new Net(null){@Override public boolean client(){return true;}};Core.settings.put("fpv-aircraft-fixed-pitch",5);
                    var angles=new float[unit.mounts.length];var pitches=new float[angles.length];var sides=new byte[angles.length];Arrays.fill(pitches,-22);Arrays.fill(sides,(byte)-1);
                    combat.applyWeaponAimReplication(AircraftWeaponCodec.encode(List.of(),List.of(new AircraftWeaponCodec.Entry(unit.id,type.id,unit.team.id,angles,pitches,sides)),AircraftWeaponCodec.Limits.DEFAULT));
                    var remote=air.visual(unit,mount);check(remote.pitch()==-22&&remote.side()==-1,"observer uses replicated local gun angles");check(air.limits().fixedPitch()==35,"host limit overrides disagreeing client preference");
                    combat.applyWeaponAimReplication(AircraftWeaponCodec.encode(List.of(),List.of(new AircraftWeaponCodec.Entry(unit.id,type.id+1,unit.team.id,angles,pitches,sides))));check(air.visual(unit,mount).pitch()!=-22,"recycled unit type cannot inherit old replicated gun angles");
                }finally{Vars.net=nativeNet;Core.settings.put("fpv-aircraft-fixed-pitch",35);}
            }finally{weapon.minShootVelocity=minVelocity;unit.remove();states.remove(unit);}
        }
        check(!air.supports(UnitTypes.dagger.create(Team.sharded))&&!air.supports(UnitTypes.risso.create(Team.sharded))&&!air.supports(UnitTypes.vela.create(Team.sharded)),"ground/naval/walker/boost models excluded");combat.close();alt.close();
        System.out.println("AircraftWeaponNativeTest: "+checks+" six aircraft/native firing counts/sectors/full XYZ origins/stats/bombs/replication checks passed");
    }
    private static Unit3DState pose(Unit u,double pitch,double roll){return new Unit3DState(u.id,u.type.id,u.team.id,32,32,20,0,0,0,0,pitch,roll,20,u.hitSize/16,.2,true);}
}
