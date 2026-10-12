package fpv.combat;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import arc.util.Time;
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

/** Real native content with no atlas/GPU: the server must use the same mech muzzle envelope. */
public final class MechWeaponPitchNativeTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.indexer=new mindustry.ai.BlockIndexer();Groups.resize(0,0,512,512);Time.clear();Time.setDeltaProvider(()->1);
        Vars.content.init();Vars.state.rules.unitCap=1000;Vars.state.set(GameState.State.playing);
        Map<Unit,Unit3DState> states=new IdentityHashMap<>();var altitudes=new UnitAltitudeSystem((x,y)->0);altitudes.setExternalStateProvider(states::get);
        var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitudes);Unit[] controlled={null};double[] pitch={35};
        var combat=new Combat3DSystem(world,()->true,u->u==controlled[0]?MechWeaponPitch.direction(0,pitch[0]):null);combat.install();
        try{
            var target=UnitTypes.dagger.create(Team.crux);target.set(24*8,16*8);target.add();pose(states,target,5);
            for(var type:new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.mace,UnitTypes.fortress,UnitTypes.scepter,UnitTypes.reign}){
                var unit=type.create(Team.sharded);unit.set(16*8,16*8);unit.rotation=0;unit.add();pose(states,unit,.25);
                unit.controller(new AIController(){@Override public void updateUnit(){}});controlled[0]=unit;
                for(var mount:unit.mounts){
                    mount.target=target;mount.aimX=target.x;mount.aimY=target.y;
                    var source=combat.originalType(mount.weapon.bullet);float damage=source.damage,reload=mount.weapon.reload,range=source.range;
                    var solution=combat.mechWeapons().visual(unit,mount);
                    check(solution!=null&&Math.abs(solution.pitch()-35)<1e-7,"headless pitch "+type.name+" solution="+solution+" world="+world.unitState(unit)+" active="+combat.coverage());
                    check(Core.atlas==null,"no hidden atlas dependency");
                    var bullet=mount.weapon.bullet.create(unit,unit,unit.team,
                        (float)(fpv.render.ProceduralUnitModelProfile.weaponX(unit.x,unit.rotation,mount.weapon.x,mount.weapon.y)+mount.weapon.shootY),
                        (float)fpv.render.ProceduralUnitModelProfile.weaponY(unit.y,unit.rotation,mount.weapon.x,mount.weapon.y),0,-1,1,1,null,null,target.x,target.y,target);
                    check(combat.managed(bullet),"real native mount proxy "+type.name);
                    var shot=combat.projectileState(bullet);
                    check(Math.abs(Math.toDegrees(Math.atan2(shot.vz(),Math.hypot(shot.vx(),shot.vy())))-35)<1e-6,"native launch follows pitch");
                    check(Math.abs(shot.z()-solution.muzzle().z())<1e-6,"same trunnion/muzzle Z without graphics");
                    check(source.damage==damage&&mount.weapon.reload==reload&&source.range==range,"no balance mutation");
                    bullet.hit=true;bullet.remove();
                    controlled[0]=null;var ai=combat.mechWeapons().visual(unit,mount);
                    check(ai.pitch()>5&&ai.pitch()<=MechWeaponPitch.MAX,"AI true-height target "+type.name);
                    controlled[0]=unit;pitch[0]=-85;var down=combat.mechWeapons().visual(unit,mount);check(down.pitch()==MechWeaponPitch.MIN,"downward stop");pitch[0]=35;
                }
                unit.remove();states.remove(unit);
            }
            for(var type:new mindustry.type.UnitType[]{UnitTypes.nova,UnitTypes.pulsar,UnitTypes.quasar}){
                var unit=type.create(Team.sharded);unit.set(16*8,16*8);unit.rotation=0;unit.add();pose(states,unit,.25);controlled[0]=unit;pitch[0]=0;
                double oldSupport=Math.max(.12,type.hitSize/Vars.tilesize*.07);
                for(var mount:unit.mounts){
                    var gun=combat.mechWeapons().visual(unit,mount);
                    check(gun!=null&&Math.abs(gun.center().z()-(.25+oldSupport+.1))<1e-6,"green visual profile retains pre-model flat muzzle height "+type.name);
                    check(gun.pitch()==0&&gun.depth()==.14,"green native pitch and weapon envelope unchanged");
                }
                unit.remove();states.remove(unit);
            }
            target.remove();check(!MechWeaponAim3D.supports(UnitTypes.flare.create(Team.sharded)),"flyer unchanged");check(!MechWeaponAim3D.supports(UnitTypes.vela.create(Team.sharded)),"Vela unchanged");
            System.out.println("PASS: "+checks+" headless native mech mount/XYZ muzzle/AI/player checks without an atlas; native damage/reload/range retained");
        }finally{combat.close();altitudes.close();}
    }
    private static void pose(Map<Unit,Unit3DState> states,Unit u,double z){double depth=Math.max(.12,u.hitSize/Vars.tilesize*.07);
        states.put(u,new Unit3DState(u.id,u.type.id,u.team.id,u.x/Vars.tilesize,u.y/Vars.tilesize,z,0,0,0,u.rotation,0,0,z,u.hitSize/Vars.tilesize*.5,depth,false));}
}
