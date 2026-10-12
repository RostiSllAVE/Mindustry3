package fpv.render;

import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.weapons.RepairBeamWeapon;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;

import java.util.ArrayList;
import java.util.List;

/** Small read-only server healing state. Does not create, heal or alter any native entity. */
public final class SupportEffectsState {
    public static final int TURRET=0,UNIT=1,TOWER=2,TOWER_TARGET=3,UNIT_TARGET=0,BUILDING_TARGET=1,NONE=2;
    public static final int UPPER_TURRET=4,UPPER_TOWER=5,UPPER_TOWER_TARGET=6,UPPER_BUILDING_TARGET=3;
    public static final int MAX_EFFECTS=4096;
    /** Building refs are native anchor positions; units use synchronized IDs. */
    public record Effect(int kind,int sourceRef,int sourceType,int teamId,int targetKind,int targetRef,
                         int targetType,int mount,float strength,float rotation,float progress){}
    private List<Effect> received=List.of();
    private Object worldTiles;
    private fpv.construction.LayerRuntime construction;
    public void setConstruction(fpv.construction.LayerRuntime value){construction=value;}
    public void clear(){received=List.of();worldTiles=null;}
    public void applySnapshot(List<Effect> state){validate(state);received=List.copyOf(state);worldTiles=Vars.world.tiles;}
    public List<Effect> remote(){
        if(worldTiles!=Vars.world.tiles)return List.of();
        return received;
    }
    public List<Effect> snapshot(){
        if(Vars.net.client())return List.of();
        var out=new ArrayList<Effect>();
        for(Building build:Groups.build){
            if(out.size()>=MAX_EFFECTS)break;
            if(build.dead||!build.enabled||build.efficiency<=0||build.tile==null||build.tile.build!=build)continue;
            if(build instanceof RepairTurret.RepairPointBuild repair&&repair.target!=null&&!repair.target.dead&&repair.target.isAdded()&&
                repair.target.team==repair.team&&repair.strength>.01f){
                out.add(new Effect(TURRET,build.pos(),build.block.id,build.team.id,UNIT_TARGET,repair.target.id,repair.target.type.id,-1,
                    repair.strength,repair.rotation,0));
            }else if(build instanceof RepairTower.RepairTowerBuild tower&&tower.warmup>.001f){
                float phase=tower.totalProgress-(float)Math.floor(tower.totalProgress);
                out.add(new Effect(TOWER,build.pos(),build.block.id,build.team.id,NONE,-1,-1,-1,tower.warmup,0,phase));
                for(Unit target:tower.targets){
                    if(out.size()>=MAX_EFFECTS)break;
                    if(target.dead||!target.isAdded()||target.team!=build.team)continue;
                    out.add(new Effect(TOWER_TARGET,build.pos(),build.block.id,build.team.id,UNIT_TARGET,target.id,target.type.id,-1,tower.warmup,0,phase));
                }
            }
        }
        if(construction!=null)for(var entry:construction.nativeBuildings().entries()){
            if(out.size()>=MAX_EFFECTS)break;Building build=entry.building();
            if(entry.id()<1||entry.id()>Integer.MAX_VALUE||build.dead||!build.enabled||build.efficiency<=0||build.tile==null||build.tile.build!=build)continue;
            int reference=(int)entry.id();
            if(build instanceof RepairTurret.RepairPointBuild repair&&repair.target!=null&&!repair.target.dead&&repair.target.isAdded()&&repair.target.team==build.team&&repair.strength>.01f){
                out.add(new Effect(UPPER_TURRET,reference,build.block.id,build.team.id,UNIT_TARGET,repair.target.id,repair.target.type.id,-1,repair.strength,repair.rotation,0));
            }else if(build instanceof RepairTower.RepairTowerBuild tower&&tower.warmup>.001f){
                float phase=tower.totalProgress-(float)Math.floor(tower.totalProgress);
                out.add(new Effect(UPPER_TOWER,reference,build.block.id,build.team.id,NONE,-1,-1,-1,tower.warmup,0,phase));
                for(Unit target:tower.targets){if(out.size()>=MAX_EFFECTS)break;if(target.dead||!target.isAdded()||target.team!=build.team)continue;
                    out.add(new Effect(UPPER_TOWER_TARGET,reference,build.block.id,build.team.id,UNIT_TARGET,target.id,target.type.id,-1,tower.warmup,0,phase));}
            }
        }
        for(Unit unit:Groups.unit){
            if(out.size()>=MAX_EFFECTS)break;
            if(unit.dead||!unit.isAdded()||!unit.canShoot())continue;
            for(int index=0;index<unit.mounts.length&&out.size()<MAX_EFFECTS;index++){
                var mount=unit.mounts[index];
                if(!(mount.weapon instanceof RepairBeamWeapon)||!(mount instanceof RepairBeamWeapon.HealBeamMount heal)||
                    heal.strength<=.01f||!heal.shoot||heal.target==null||heal.target.team()!=unit.team)continue;
                int kind,ref,type;
                if(heal.target instanceof Unit target&&!target.dead&&target.isAdded()){kind=UNIT_TARGET;ref=target.id;type=target.type.id;}
                else if(heal.target instanceof Building target&&!target.dead&&target.tile!=null&&target.tile.build==target){
                    kind=BUILDING_TARGET;ref=target.pos();type=target.block.id;
                    if(target.tile instanceof fpv.construction.LayerTile layer){var component=construction==null?null:construction.grid().at(layer.x,layer.y,layer.layer,fpv.construction.LayerComponent.Kind.BLOCK);if(component==null||component.id()>Integer.MAX_VALUE)continue;kind=UPPER_BUILDING_TARGET;ref=(int)component.id();}
                }
                else continue;
                out.add(new Effect(UNIT,unit.id,unit.type.id,unit.team.id,kind,ref,type,index,heal.strength,heal.rotation,0));
            }
        }
        validate(out);return List.copyOf(out);
    }
    public static void validate(List<Effect> effects){
        if(effects==null||effects.size()>MAX_EFFECTS)throw new IllegalArgumentException("Support effect count");
        for(Effect e:effects)if(e==null||e.kind<0||e.kind>6||e.sourceType<0||e.sourceType>65535||e.teamId<0||e.teamId>255||
            e.targetKind<0||e.targetKind>3||e.targetType< -1||e.targetType>65535||e.mount< -1||e.mount>4096||e.kind>=UPPER_TURRET&&e.sourceRef<1||e.targetKind==UPPER_BUILDING_TARGET&&e.targetRef<1||
            !Float.isFinite(e.strength)||e.strength<0||e.strength>16||!Float.isFinite(e.rotation)||Math.abs(e.rotation)>1_000_000||
            !Float.isFinite(e.progress)||e.progress<0||e.progress>1||e.kind==UNIT&&e.mount<0||e.kind!=UNIT&&e.mount!=-1)
            throw new IllegalArgumentException("Invalid support effect");
    }
}
