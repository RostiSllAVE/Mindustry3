package fpv.construction;

import arc.math.geom.Point2;
import arc.struct.Seq;
import java.util.*;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.power.PowerNode;

/** Server-owned unpaid build plans. Complete via the ordinary PLACE validator, then configure new IDs only. */
public final class LayerPasteQueue {
    public record Preview(Seq<BuildPlan> plans,boolean[] valid,double[] distance,int ready,String reason){}
    private static final class Job {
        final Player player;final Unit unit;final int team,layer;final LayerComponent.Mode mode;
        final ArrayDeque<BuildPlan> pending=new ArrayDeque<>();final LinkedHashMap<Integer,Long> made=new LinkedHashMap<>();final ArrayList<BuildPlan> completed=new ArrayList<>();double work;
        Job(Player p,int layer,LayerComponent.Mode mode){player=p;unit=p.unit();team=p.team().id;this.layer=layer;this.mode=mode;}
    }
    private final LayerRuntime runtime;private final ArrayList<Job> jobs=new ArrayList<>();
    public LayerPasteQueue(LayerRuntime runtime){this.runtime=runtime;}
    public int pending(Player player){int total=0;for(var j:jobs)if(j.player==player)total+=j.pending.size();return total;}
    public void clear(){jobs.clear();}
    public void cancel(Player player){jobs.removeIf(j->j.player==player);}
    public Preview preview(Player player,LayerActions.Action action){
        var s=LayerSchematic.decode(action.configuration());var plans=LayerSchematic.plans(s,action.x(),action.y());
        boolean[] valid=new boolean[plans.size];double[] distance=new double[plans.size];Arrays.fill(distance,Double.POSITIVE_INFINITY);
        var trial=new LayerWorld(runtime.grid().coordinates());trial.apply(runtime.grid().snapshot());var added=new ArrayList<LayerComponent>();String reason="";
        long id=Math.max(trial.snapshot().nextId(),1);int ready=0;
        for(int i=0;i<plans.size;i++)try{
            var a=place(plans.get(i),action.layer(),action.mode());var check=runtime.previewSchematicCell(player,a,trial);
            if(!check.accepted()){if(reason.isEmpty())reason=check.reason();continue;}
            var c=runtime.schematicComponent(a,id++,player.team().id);trial.add(c);added.add(c);valid[i]=true;
        }catch(IllegalArgumentException bad){if(reason.isEmpty())reason="Outside map or invalid schematic footprint";}
        try(var solver=new StructuralSolver(trial,runtime::foundation)){
            solver.solveDirty();int addedIndex=0;
            for(int i=0;i<plans.size;i++)if(valid[i]){var c=added.get(addedIndex++);var support=solver.support(c.id());distance[i]=support.distance();
                if(action.mode()==LayerComponent.Mode.STRUCTURAL&&!support.supported()){valid[i]=false;if(reason.isEmpty())reason="Unsupported: "+support.reason();}
            }
            // A preview cannot authorize an added load that makes an existing supported structure collapse.
            if(action.mode()==LayerComponent.Mode.STRUCTURAL)for(var c:runtime.grid().components())if(runtime.solver().support(c.id()).supported()&&!solver.support(c.id()).supported()){
                Arrays.fill(valid,false);reason="Support load capacity exceeded by copied structure";break;
            }
        }
        if(!runtime.schematicBudget(added)){Arrays.fill(valid,false);reason="Construction save/network budget exceeded";}
        for(boolean v:valid)if(v)ready++;
        return new Preview(plans,valid,distance,ready,reason);
    }
    public LayerActions.Result enqueue(Player player,LayerActions.Action action){
        var preview=preview(player,action);if(preview.ready()==0)return LayerActions.Result.rejected(preview.reason().isEmpty()?"No valid schematic positions":preview.reason());
        if(jobs.size()>=64||pending(player)+preview.ready()>LayerSchematic.MAX_PLANS)return LayerActions.Result.rejected("Schematic build queue is full");
        var job=new Job(player,action.layer(),action.mode());var indices=new ArrayList<Integer>();for(int i=0;i<preview.plans().size;i++)if(preview.valid()[i])indices.add(i);
        indices.sort(Comparator.comparingDouble((Integer i)->preview.distance()[i]).thenComparingInt(i->LayerSchematic.platform(preview.plans().get(i))?0:1));
        for(int i:indices){var p=preview.plans().get(i);boolean duplicate=false;for(var other:jobs)if(other.player==player&&other.layer==job.layer)for(var q:other.pending)if(p.x==q.x&&p.y==q.y&&LayerSchematic.platform(p)==LayerSchematic.platform(q)){duplicate=true;break;}
            if(!duplicate)job.pending.add(p);
        }
        if(job.pending.isEmpty())return LayerActions.Result.rejected("These positions are already queued");jobs.add(job);
        String message="Queued "+job.pending.size()+" schematic entries on Z"+job.layer;
        if(preview.ready()<preview.plans().size)message+="; skipped "+(preview.plans().size-preview.ready())+": "+preview.reason();
        return new LayerActions.Result(true,false,message.length()>256?message.substring(0,256):message,0);
    }
    public void update(double dt){
        for(var iterator=jobs.iterator();iterator.hasNext();){var job=iterator.next();
            if(job.player.dead()||job.player.unit()!=job.unit||job.player.team().id!=job.team||!job.unit.isAdded()||!job.unit.canBuild()){iterator.remove();continue;}
            double speed=job.unit.type.buildSpeed*job.unit.buildSpeedMultiplier*Vars.state.rules.buildSpeed(job.player.team());
            if(!Double.isFinite(speed)||speed<=0)continue;job.work+=dt*60*speed;int completed=0;
            while(!job.pending.isEmpty()&&completed<32){var plan=job.pending.peek();var a=place(plan,job.layer,job.mode);var allowed=runtime.preview(job.player,a);
                if(!allowed.accepted()){
                    runtime.acceptResult(allowed);
                    if(allowed.reason().startsWith("Insufficient")||allowed.reason().startsWith("Outside builder range"))break;
                    job.pending.remove();continue;
                }
                double time=LayerSchematic.platform(plan)?60:Math.max(1,plan.block.buildTime*Vars.state.rules.buildCostMultiplier);
                if(!Vars.state.rules.instantBuild&&job.work<time)break;
                var result=runtime.apply(job.player,a);runtime.acceptResult(result);
                if(!result.accepted()){if(result.reason().startsWith("Insufficient")||result.reason().startsWith("Outside builder range"))break;job.pending.remove();continue;}
                job.work=Math.max(0,job.work-time);job.pending.remove();job.made.put(Point2.pack(plan.x,plan.y),result.componentId());job.completed.add(plan);completed++;
            }
            if(job.pending.isEmpty()){configure(job);iterator.remove();}
        }
    }
    private void configure(Job job){
        int failed=0;var selected=job.made.keySet();
        for(var p:job.completed){if(LayerSchematic.platform(p)||p.config==null)continue;long id=job.made.get(Point2.pack(p.x,p.y));
            var live=runtime.grid().get(id);if(live==null||live.team()!=job.team)continue;
            Object value=LayerSchematic.internalConfig(p.block,p.config,p.x,p.y,selected);
            if(value instanceof Point2[] points&&p.block instanceof PowerNode){for(var point:points){Long target=job.made.get(Point2.pack(p.x+point.x,p.y+point.y));if(target==null||!runtime.schematicPowerLink(job.player,id,target))failed++;}}
            else if(value!=null&&!runtime.apply(job.player,LayerActions.Action.configure(id,value)).accepted())failed++;
        }
        if(failed>0)runtime.acceptResult(LayerActions.Result.rejected(failed+" schematic configurations/links could not be applied"));
    }
    public static LayerActions.Action place(BuildPlan p,int layer,LayerComponent.Mode mode){return LayerActions.Action.place(p.x,p.y,layer,p.block.name,LayerSchematic.platform(p),p.rotation,mode);}
}
