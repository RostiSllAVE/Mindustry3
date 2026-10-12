package fpv.construction;

import java.util.Map;
import java.util.HashMap;
import mindustry.Vars;
import mindustry.logic.LExecutor;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor.*;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** Client-only drawing replay. An explicit instruction whitelist cannot mutate authoritative game state. */
public final class LayerClientLogicPresentation {
    private static final Map<Long,Presentation> states=new HashMap<>();
    private static Object worldIdentity;private static long frame;
    private static final class Presentation {
        LogicBuild source;LInstruction[] instructions;final LExecutor executor=new LExecutor();final String code;double credit;long seen;
        Presentation(LayerBuildingAdapter owner,LogicBuild build){
            code=build.code;var assembler=LAssembler.assemble(code,false);
            for(var variable:build.executor.vars){
                if(variable.name.equals("@counter"))continue;
                if(variable.constant)assembler.putConst(variable.name,variable.isobj?variable.objval:variable.numval);
                else {var local=assembler.getVar(variable.name);if(local!=null&&!local.constant)local.set(variable);}
            }
            executor.load(assembler);refresh(owner,build);
        }
        void refresh(LayerBuildingAdapter owner,LogicBuild build){
            source=build;instructions=build.executor.instructions;executor.team=build.team;executor.links=build.executor.links.clone();executor.linkIds.clear();
            for(var building:executor.links)if(building!=null)executor.linkIds.add(building.id);
            for(var variable:build.executor.vars)if(!variable.name.equals("@counter")){
                var local=executor.optionalVar(variable.name);if(local!=null){local.isobj=variable.isobj;local.objval=LayerLogicBehavior.presentationValue(owner,variable.objval);local.numval=variable.numval;}
            }
        }
    }
    private LayerClientLogicPresentation(){}
    public static void clear(){states.clear();worldIdentity=null;}
    public static void update(LayerBuildingAdapter owner,double seconds){
        if(owner==null||!Vars.net.client()||!Double.isFinite(seconds)||seconds<=0)return;
        if(worldIdentity!=Vars.world.tiles){clear();worldIdentity=Vars.world.tiles;}long current=++frame;int globalBudget=4096;
        for(var entry:owner.entries())if(entry.building() instanceof LogicBuild build&&!build.dead&&build.executor.initialized()){
            Presentation state=states.get(entry.id());
            if(state==null||!state.code.equals(build.code)){state=new Presentation(owner,build);states.put(entry.id(),state);}
            else if(state.source!=build||state.instructions!=build.executor.instructions)state.refresh(owner,build);
            state.seen=current;if(!build.enabled||globalBudget<=0||build.power!=null&&build.power.status<=0)continue;
            state.credit+=Math.min(.1,seconds)*60*Math.max(1,build.ipt);
            int count=Math.min(globalBudget,Math.min(128,(int)state.credit));state.credit=Math.min(128,state.credit-count);
            replay(state.executor,count);globalBudget-=count;
        }
        states.entrySet().removeIf(entry->entry.getValue().seen!=current);
    }
    static void replay(LExecutor executor,int maximum){
        if(executor==null||executor.instructions==null||executor.instructions.length==0)return;
        for(int i=0;i<Math.max(0,Math.min(4096,maximum));i++){
            double counter=executor.counter.numval;
            int index=!Double.isFinite(counter)||counter<0||counter>=executor.instructions.length?0:(int)counter;
            executor.counter.isobj=false;executor.counter.numval=index+1;
            var instruction=executor.instructions[index];
            if(presentation(instruction))instruction.run(executor);
            if(executor.yield){executor.yield=false;break;}
        }
    }
    private static boolean presentation(LInstruction instruction){
        Class<?> type=instruction.getClass();
        return type==SetI.class||type==OpI.class||type==SenseI.class||type==ReadI.class||type==JumpI.class||type==GetLinkI.class||
            type==DrawI.class||type==DrawFlushI.class||type==EndI.class||type==NoopI.class||type==LookupI.class||type==SelectI.class||
            type==PackColorI.class||type==UnpackColorI.class||type==PrintI.class||type==FormatI.class||type==PrintCharI.class;
    }
}
