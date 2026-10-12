package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
import fpv.render.NavalHullProfile;
import fpv.terrain.TerrainElevation;
import fpv.units.UnitAltitudeSystem;
import fpv.world.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import java.util.*;

/** Real native mounts/XYZ targets/ballistics and existing snapshot schema without a GPU. */
public final class NavalWeaponPitchNativeTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        Map<Unit,Unit3DState> states=new IdentityHashMap<>();var altitudes=new UnitAltitudeSystem((x,y)->0);altitudes.setExternalStateProvider(states::get);
        var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitudes);Unit[] controlled={null};double[] pitch={35};
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?MechWeaponPitch.direction(0,pitch[0]):null);combat.install();
        var target=UnitTypes.flare.create(Team.crux);target.set(24*8,16*8);target.add();pose(states,target,5);
        try{
            for(var type:NavalHullProfile.types()){
                var profile=NavalHullProfile.forType(type);var unit=type.create(Team.sharded);unit.set(16*8,16*8);unit.rotation=0;unit.add();pose(states,unit,.25);
                unit.controller(new AIController(){@Override public void updateUnit(){}});controlled[0]=unit;
                for(var mount:unit.mounts){
                    mount.target=target;mount.aimX=target.x;mount.aimY=target.y;
                    var source=combat.originalType(mount.weapon.bullet);float damage=source.damage,reload=mount.weapon.reload,range=source.range;
                    mount.rotation=22;var gun=combat.navalWeapons().visual(unit,mount,profile);
                    check(gun!=null&&Double.isFinite(gun.pitch()),"finite native mount "+type.name);
                    check(gun.yaw()==fpv.render.ProceduralUnitModelProfile.weaponYaw(unit.rotation,mount.weapon.rotate,22,mount.weapon.baseRotation),"native yaw retained");
                    if(mount.weapon.controllable)check(Math.abs(gun.pitch()-35)<1e-6,"player upward pitch");
                    controlled[0]=null;var ai=combat.navalWeapons().visual(unit,mount,profile);check(ai.pitch()>0&&ai.pitch()<=profile.maximumPitch(),"AI elevated target "+type.name);
                    controlled[0]=unit;pitch[0]=-20;var down=combat.navalWeapons().visual(unit,mount,profile);
                    if(mount.weapon.controllable)check(down.pitch()==profile.minimumPitch(),"downward clamp");pitch[0]=35;
                    check(source.damage==damage&&mount.weapon.reload==reload&&source.range==range,"no stat mutation");
                    var bullet=mount.weapon.bullet.create(unit,unit,unit.team,unit.x+8,unit.y,0,-1,1,1,null,null,target.x,target.y,target);
                    var shot=combat.projectileState(bullet);
                    if(shot!=null){check(Math.abs(shot.x()-17)<1e-6&&Math.abs(shot.y()-16)<1e-6,"original native muzzle XY untouched");
                        check(Math.abs(shot.z()-Math.max(.25+WeaponHeightStagger.offset(unit,mount),Math.max(.025,Math.min(8,source.hitSize/(2.0*Vars.tilesize)))+.025))<1e-6,"native muzzle Z plus shared weapon stagger");}
                    bullet.hit=true;bullet.remove();
                }
                var bytes=MechWeaponPitchCodec.encode(combat.weaponPitchSnapshot());var entries=MechWeaponPitchCodec.decode(bytes);combat.applyWeaponPitchSnapshot(entries);
                for(var mount:unit.mounts){check(combat.navalWeapons().remotePitch(unit,mount)!=null,"existing channel covers every naval mount");
                    check(Math.abs(combat.navalWeapons().remotePitch(unit,mount)-combat.navalWeapons().visual(unit,mount,profile).pitch())<1e-4,"replicated visual elevation");}
                var originalNet=Vars.net;Vars.net=new Net(null){@Override public boolean client(){return true;}};pitch[0]=-20;
                for(var mount:unit.mounts)check(Math.abs(combat.navalWeapons().visual(unit,mount,profile).pitch()-combat.navalWeapons().remotePitch(unit,mount))<1e-6,"observer uses authoritative remote pitch instead of local aim");
                Vars.net=originalNet;pitch[0]=35;
                var other=type.create(Team.crux);other.id=unit.id;check(combat.navalWeapons().remotePitch(other,other.mounts[0])==null,"team identity gate");
                unit.remove();states.remove(unit);
            }
            check(combat.navalWeapons().visual(UnitTypes.dagger.create(Team.sharded),null,null)==null,"other families unchanged");
            combat.clear();check(combat.weaponPitchSnapshot().stream().noneMatch(e->e.unitId()==target.id),"no non-naval pitch data");
            System.out.println("NavalWeaponPitchNativeTest: "+checks+" ten ships/player/AI/native yaw/original projectile origins/balance/snapshot checks passed");
        }finally{target.remove();combat.close();altitudes.close();}
    }
    private static void pose(Map<Unit,Unit3DState> states,Unit u,double z){states.put(u,new Unit3DState(u.id,u.type.id,u.team.id,u.x/8,u.y/8,z,0,0,0,u.rotation,0,0,z,u.hitSize/16,.2,u.type.flying));}
}
