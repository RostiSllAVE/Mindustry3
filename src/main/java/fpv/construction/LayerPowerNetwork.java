package fpv.construction;

import arc.struct.Seq;
import java.util.*;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;

/** Topology adapter only: native PowerGraph owns all energy, consumption and battery calculations.
 * Identity traversal is essential: packed XY cannot distinguish buildings on different floors. */
final class LayerPowerNetwork {
    private final LayerBuildingAdapter owner;
    private final Set<Building> ground=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayList<PowerGraph> graphs=new ArrayList<>();
    private long tileRevision=Long.MIN_VALUE;
    private boolean dirty;
    private long rebuilds;
    LayerPowerNetwork(LayerBuildingAdapter owner){this.owner=owner;}
    long rebuilds(){return rebuilds;}
    boolean changed(){
        if(dirty||tileRevision!=Vars.world.tileChanges)return true;
        for(Building b:ground)if(!present(b)||!(b.power.graph instanceof Managed graph)||graph.owner()!=this)return true;
        return false;
    }
    private boolean present(Building b){return b!=null&&b.power!=null&&!b.dead&&b.health>0&&Vars.world.build(b.pos())==b;}
    private final class Managed extends PowerGraph {
        private boolean deferNativeTick;
        Managed(boolean deferNativeTick){super(true);this.deferNativeTick=deferNativeTick;}
        @Override public void update(){
            // The native ground updater already ran earlier in this simulation tick. Its first
            // hybrid tick must not charge batteries again when that updater is taken over.
            if(deferNativeTick){deferNativeTick=false;return;}super.update();
        }
        LayerPowerNetwork owner(){return LayerPowerNetwork.this;}
        // Native adjacency/node edits must invalidate XYZ topology, never reflow through packed XY.
        @Override public void addGraph(PowerGraph other){
            if(other==this)return;
            for(Building b:other.all.copy())add(b);
            other.clear();dirty=true;
        }
        @Override public void remove(Building b){removeList(b);dirty=true;}
        @Override public void reflow(Building b){dirty=true;}
    }
    void rebuild(){
        rebuilds++;
        var edges=new IdentityHashMap<Building,Set<Building>>();
        var upper=Collections.newSetFromMap(new IdentityHashMap<Building,Boolean>());
        ArrayDeque<Building> pending=new ArrayDeque<>();
        for(var e:owner.entries())if(e.building().power!=null&&!e.building().dead){
            upper.add(e.building());edges.put(e.building(),Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        // Previously attached ground branches must also be partitioned after disconnection.
        for(Building b:ground)if(present(b))pending.add(b);
        for(var link:owner.powerLinks()){
            Building a=owner.powerEndpoint(link.first()),b=owner.powerEndpoint(link.second());
            if(a==null||b==null)continue;
            if(link.first()<0)pending.add(a);if(link.second()<0)pending.add(b);
            edges.computeIfAbsent(a,k->Collections.newSetFromMap(new IdentityHashMap<>()));
            edges.computeIfAbsent(b,k->Collections.newSetFromMap(new IdentityHashMap<>()));
            edges.get(a).add(b);edges.get(b).add(a);
        }
        Set<Building> visitedGround=Collections.newSetFromMap(new IdentityHashMap<>());
        Seq<Building> nativeNeighbours=new Seq<>();
        while(!pending.isEmpty()){
            Building b=pending.remove();if(!present(b)||!visitedGround.add(b))continue;
            edges.computeIfAbsent(b,k->Collections.newSetFromMap(new IdentityHashMap<>()));
            // Ground connections are still exactly vanilla's adjacency, diode and link rules.
            for(Building next:b.getPowerConnections(nativeNeighbours))if(present(next)){
                edges.computeIfAbsent(next,k->Collections.newSetFromMap(new IdentityHashMap<>()));
                edges.get(b).add(next);edges.get(next).add(b);pending.add(next);
            }
        }
        for(Building b:upper)for(Building next:b.proximity)if(upper.contains(next)&&LayerBuildingAdapter.conducts(b,next)){
            edges.get(b).add(next);edges.get(next).add(b);
        }
        // Remove old updaters before assigning members. No ground generator is simulated twice.
        Set<Building> nativeTick=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Building b:visitedGround)if(!(b.power.graph instanceof Managed graph)||graph.owner()!=this)nativeTick.add(b);
        Set<PowerGraph> oldGraphs=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Building b:edges.keySet())oldGraphs.add(b.power.graph);
        for(PowerGraph old:oldGraphs)old.clear();
        graphs.clear();ground.clear();
        Set<Building> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Building start:edges.keySet())if(visited.add(start)){
            ArrayList<Building> members=new ArrayList<>();boolean hasUpper=false;pending.add(start);
            while(!pending.isEmpty()){
                Building b=pending.remove();members.add(b);hasUpper|=upper.contains(b);
                for(Building next:edges.get(b))if(visited.add(next))pending.add(next);
            }
            PowerGraph graph=hasUpper?new Managed(members.stream().anyMatch(nativeTick::contains)):new PowerGraph();
            for(Building b:members){b.power.init=false;graph.add(b);if(hasUpper&&!upper.contains(b))ground.add(b);}
            if(hasUpper)graphs.add(graph);else graph.checkAdd();
        }
        tileRevision=Vars.world.tileChanges;dirty=false;
    }
    void update(){for(PowerGraph graph:graphs)graph.update();}
    /** Upper registry is empty at this point; detached ground networks regain native updaters. */
    void clear(){if(!ground.isEmpty())rebuild();else for(PowerGraph graph:graphs)graph.clear();graphs.clear();ground.clear();}
}
