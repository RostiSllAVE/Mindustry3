package fpv.construction;

import mindustry.Vars;
import mindustry.gen.*;
import mindustry.world.blocks.defense.TargetDummy;
import mindustry.world.blocks.defense.TargetDummy.TargetDummyBuild;

/** Native target dummy update can run once its missing layer/XYZ spawn boundary is supplied. */
final class LayerTargetDummyBehavior {
    private LayerTargetDummyBehavior(){}
    static void reconcile(TargetDummyBuild build){
        if(build.unit!=null&&(build.unit.dead||!build.unit.isAdded()))build.unit=null;
        if(build.readUnitId!=-1){Unit candidate=Groups.unit.getByID(build.readUnitId);
            if(candidate instanceof TargetDummyUnit&&candidate.type==((TargetDummy)build.block).unitType){build.unit=candidate;build.readUnitId=-1;}
            else if(!Vars.net.client())build.readUnitId=-1;
        }
        // Unit native network references are packed XY; authoritative layer state supplies the true owner.
        if(build.unit instanceof TargetDummyUnit dummy)dummy.building(build);
    }
    static boolean prepare(LayerBuildingAdapter owner,TargetDummyBuild build){
        if(owner.restoringState())return false;reconcile(build);
        if(build.unit==null&&!Vars.net.client()){
            Unit unit=((TargetDummy)build.block).unitType.create(build.team);unit.rotation=90;unit.hitSize=build.dummySize;unit.elevation=build.boosting?1:0;unit.armor(build.unitArmor);
            if(!(unit instanceof TargetDummyUnit dummy)||!owner.releaseUnit(build,unit,build.x,build.y))return false;
            dummy.building(build);build.unit=unit;
        }
        // This precondition prevents native updateTile from entering its packed Tile spawn RPC branch.
        return build.unit!=null&&build.unit.isAdded()&&!build.unit.dead;
    }
    static boolean configure(TargetDummyBuild build,Object config){
        if(!(config instanceof int[] values)||values.length!=5||values[0]<0||values[0]>1||values[1]<0||values[1]>1||values[2]<0||values[2]>100000||values[3]<0||values[3]>216000000)return false;
        float size=Float.intBitsToFloat(values[4]);if(!Float.isFinite(size)||size<.1f||size>240)return false;
        build.block.configurations.get(int[].class).get(build,values.clone());return true;
    }
    static void validate(TargetDummyBuild build){
        if(!Float.isFinite(build.dummySize)||build.dummySize<.1f||build.dummySize>240||!Float.isFinite(build.unitArmor)||build.unitArmor<0||build.unitArmor>100000||!Float.isFinite(build.resetTime)||build.resetTime<0||build.resetTime>216000000||build.readUnitId< -1)throw new IllegalArgumentException("Invalid upper target dummy state");
    }
}
