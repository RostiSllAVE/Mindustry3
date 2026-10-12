package fpv.construction;

import java.util.*;
import fpv.construction.LayerCoordinates.Bounds;
import fpv.construction.LayerComponent.Mode;

/** Dirty connected-region support analysis. Native foundations are read-only external facts. */
public final class StructuralSolver implements AutoCloseable {
    @FunctionalInterface public interface Foundations {Foundation at(int x,int y,int team);}
    public record Foundation(long id,int team,double top,int span,double capacity){
        public Foundation {if(team<0||team>255||!Double.isFinite(top)||top<0||span<0||span>1024||!Double.isFinite(capacity)||capacity<=0||capacity>1e12)throw new IllegalArgumentException("Invalid native structural foundation");}
    }
    public enum Status {SUPPORTED,FREE,UNSUPPORTED}
    public record Support(Status status,long foundationId,double distance,double load,String reason){public boolean supported(){return status!=Status.UNSUPPORTED;}}
    private static final double EPS=1e-6;
    private static final int MAX_SEARCH_LABELS=500000;
    private final LayerWorld world;private final Foundations foundations;
    private final TreeSet<Long> dirty=new TreeSet<>();
    private final Map<Long,Support> results=new HashMap<>();
    private final LayerWorld.ChangeListener listener=this::changed;
    private int lastRecomputed,lastSearchLabels;
    private long invalidationRevision,sectionsRevision=-1;
    private List<List<Long>> cachedSections=List.of();
    private LayerComponent previewCandidate;private long previewRevision=-1,previewGeometryRevision=-1;private boolean previewResult;
    private static final class Root {final Foundation foundation;final TreeSet<Long> sources=new TreeSet<>();Root(Foundation f){foundation=f;}}
    private record Label(long node,long root,double distance,int budget,Label parent){}
    private record LabelKey(long node,int budget){}
    public StructuralSolver(LayerWorld world,Foundations foundations){this.world=Objects.requireNonNull(world);this.foundations=Objects.requireNonNull(foundations);world.addListener(listener);invalidateAll();}
    public void invalidateAll(){invalidationRevision++;for(var c:world.components())dirty.add(c.id());}
    public void invalidateColumn(int x,int y){
        invalidationRevision++;
        double top=world.coordinates().plane(world.coordinates().maxLayers())+256;
        for(var c:world.query(new Bounds(x-.500001,y-.500001,0,x+.500001,y+.500001,top)))dirty.add(c.id());
    }
    private void changed(LayerComponent before,LayerComponent after){
        if(before!=null&&after!=null&&sameStructure(before,after))return;
        invalidationRevision++;
        if(before!=null){results.remove(before.id());if(before.layer()<=world.coordinates().maxLayers())for(var c:world.query(world.coordinates().bounds(before).expanded(EPS)))dirty.add(c.id());}
        if(after!=null){dirty.add(after.id());for(var c:world.query(world.coordinates().bounds(after).expanded(EPS)))dirty.add(c.id());}
    }
    private static boolean sameStructure(LayerComponent a,LayerComponent b){return a.id()==b.id()&&a.kind()==b.kind()&&a.x()==b.x()&&a.y()==b.y()&&a.layer()==b.layer()&&a.size()==b.size()&&a.team()==b.team()&&a.mode()==b.mode()&&a.mass()==b.mass()&&a.height()==b.height()&&a.support().equals(b.support())&&(a.health()>0)==(b.health()>0);}
    public int lastRecomputed(){return lastRecomputed;}
    public int lastSearchLabels(){return lastSearchLabels;}
    public int solveDirty(){
        lastRecomputed=0;lastSearchLabels=0;
        while(!dirty.isEmpty()){
            long start=dirty.pollFirst();LayerComponent first=world.get(start);if(first==null){results.remove(start);continue;}
            if(first.mode()==Mode.FREE){results.put(start,new Support(Status.FREE,0,0,0,"Free construction"));lastRecomputed++;continue;}
            TreeMap<Long,LayerComponent> region=collect(first);dirty.removeAll(region.keySet());lastRecomputed+=region.size();solve(region);
        }
        return lastRecomputed;
    }
    public Support support(long id){solveDirty();return results.getOrDefault(id,new Support(Status.UNSUPPORTED,0,Double.POSITIVE_INFINITY,0,"No component"));}
    private TreeMap<Long,LayerComponent> collect(LayerComponent first){
        TreeMap<Long,LayerComponent> region=new TreeMap<>();ArrayDeque<LayerComponent> pending=new ArrayDeque<>();pending.add(first);region.put(first.id(),first);
        while(!pending.isEmpty()){LayerComponent c=pending.removeFirst();for(var neighbor:world.query(world.coordinates().bounds(c).expanded(EPS)))if(!region.containsKey(neighbor.id())&&connected(c,neighbor)){region.put(neighbor.id(),neighbor);pending.addLast(neighbor);}}
        return region;
    }
    private boolean connected(LayerComponent a,LayerComponent b){
        if(a.id()==b.id()||a.team()!=b.team()||a.mode()!=Mode.STRUCTURAL||b.mode()!=Mode.STRUCTURAL||a.health()<=0||b.health()<=0)return false;
        Bounds x=world.coordinates().bounds(a),y=world.coordinates().bounds(b);
        double ox=Math.min(x.maxX(),y.maxX())-Math.max(x.minX(),y.minX()),oy=Math.min(x.maxY(),y.maxY())-Math.max(x.minY(),y.minY()),oz=Math.min(x.maxZ(),y.maxZ())-Math.max(x.minZ(),y.minZ());
        return ox>=-EPS&&oy>=-EPS&&oz>=-EPS&&((ox>EPS&&oy>EPS)||(ox>EPS&&oz>EPS)||(oy>EPS&&oz>EPS));
    }
    private void solve(TreeMap<Long,LayerComponent> region){
        TreeMap<Long,Root> roots=new TreeMap<>();Map<Long,List<Long>> edges=new HashMap<>();
        for(var c:region.values()){
            results.remove(c.id());
            Bounds b=world.coordinates().bounds(c);ArrayList<Long> adjacent=new ArrayList<>();
            for(var n:world.query(b.expanded(EPS)))if(region.containsKey(n.id())&&connected(c,n))adjacent.add(n.id());edges.put(c.id(),adjacent);
            if(c.health()<=0)continue;
            int minX=LayerCoordinates.minCell(c.x(),c.size()),minY=LayerCoordinates.minCell(c.y(),c.size());
            for(int x=minX;x<minX+c.size();x++)for(int y=minY;y<minY+c.size();y++){
                Foundation f=foundations.at(x,y,c.team());if(f==null||f.team()!=c.team()||f.top()<b.minZ()-EPS||f.top()>b.maxZ()+EPS)continue;
                Root root=roots.computeIfAbsent(f.id(),id->new Root(f));
                if(!root.foundation.equals(f))throw new IllegalArgumentException("Native foundation ID has conflicting profiles");root.sources.add(c.id());
            }
        }
        Map<Long,List<Label>> paths=new HashMap<>();boolean exhausted=false;int regionSearchLabels=0;
        Comparator<Label> order=Comparator.comparingDouble(Label::distance).thenComparingLong(Label::node).thenComparingInt(Label::budget);
        for(Root root:roots.values()){
            PriorityQueue<Label> queue=new PriorityQueue<>(order);Map<LabelKey,Double> seen=new HashMap<>();
            for(long source:root.sources){LayerComponent c=region.get(source);int budget=c.column()?Math.min(root.foundation.span(),c.support().span()):root.foundation.span();queue.add(new Label(source,root.foundation.id(),0,budget,null));}
            while(!queue.isEmpty()){
                Label label=queue.remove();LabelKey key=new LabelKey(label.node(),label.budget());double known=seen.getOrDefault(key,Double.POSITIVE_INFINITY);if(label.distance()>known+EPS)continue;
                if(known<Double.POSITIVE_INFINITY&&label.distance()>=known-EPS)continue;seen.put(key,label.distance());
                lastSearchLabels++;if(++regionSearchLabels>MAX_SEARCH_LABELS){exhausted=true;break;}
                paths.computeIfAbsent(label.node(),id->new ArrayList<>()).add(label);
                LayerComponent c=region.get(label.node());Bounds bounds=world.coordinates().bounds(c);
                for(long neighbor:edges.get(c.id())){
                    LayerComponent n=region.get(neighbor);Bounds nb=world.coordinates().bounds(n);
                    // Vertical contact in the same footprint does not consume horizontal span.
                    double overlapX=Math.min(bounds.maxX(),nb.maxX())-Math.max(bounds.minX(),nb.minX()),overlapY=Math.min(bounds.maxY(),nb.maxY())-Math.max(bounds.minY(),nb.minY());
                    double horizontal=overlapX>EPS&&overlapY>EPS?0:Math.abs(bounds.centerX()-nb.centerX())+Math.abs(bounds.centerY()-nb.centerY());
                    double distance=label.distance()+horizontal;int budget=n.column()?Math.min(label.budget(),n.support().span()):label.budget();
                    if(distance<=budget+EPS&&distance<seen.getOrDefault(new LabelKey(neighbor,budget),Double.POSITIVE_INFINITY)-EPS)queue.add(new Label(neighbor,label.root(),distance,budget,label));
                }
            }
            if(exhausted)break;
        }
        ArrayList<LayerComponent> placementOrder=new ArrayList<>(region.values());
        placementOrder.sort(Comparator.comparingDouble((LayerComponent c)->minimumDistance(paths.get(c.id()))).thenComparingDouble(c->world.coordinates().bounds(c).minZ()).thenComparingLong(LayerComponent::id));
        Map<Long,Double> rootLoad=new HashMap<>(),columnLoad=new HashMap<>();
        boolean progress=true;while(progress&&!placementOrder.isEmpty()){
        progress=false;for(var iterator=placementOrder.iterator();iterator.hasNext();){var c=iterator.next();
            List<Label> choices=paths.get(c.id());Label chosen=null;double remaining=c.mass();
            Map<Long,Double> proposedRoots=new HashMap<>(),proposedColumns=new HashMap<>();
            if(!exhausted&&choices!=null){choices.sort(Comparator.comparingDouble(Label::distance).thenComparingLong(Label::root).thenComparingInt(l->-l.budget()));
                for(Label path:choices){
                    Root root=roots.get(path.root());double available=root.foundation.capacity()-rootLoad.getOrDefault(path.root(),0.0)-proposedRoots.getOrDefault(path.root(),0.0);if(available<=EPS)continue;
                    boolean allowed=true;ArrayList<Long> columns=new ArrayList<>();HashSet<Long> seenPath=new HashSet<>();
                    for(Label step=path;step!=null;step=step.parent()){
                        if(!seenPath.add(step.node())){allowed=false;break;}LayerComponent column=region.get(step.node());
                        if(step.node()!=c.id()){Support support=results.get(step.node());if(support==null||!support.supported()){allowed=false;break;}}
                        if(column.column()){columns.add(column.id());available=Math.min(available,column.support().capacity()-columnLoad.getOrDefault(column.id(),0.0)-proposedColumns.getOrDefault(column.id(),0.0));if(available<=EPS){allowed=false;break;}}
                    }
                    if(allowed){double amount=Math.min(available,remaining);proposedRoots.merge(path.root(),amount,Double::sum);for(long id:columns)proposedColumns.merge(id,amount,Double::sum);remaining-=amount;if(chosen==null)chosen=path;if(remaining<=EPS)break;}
                }
            }
            // Commit a complete supported load only; failed partial sharing never consumes capacity.
            if(chosen!=null&&remaining<=EPS){for(var load:proposedRoots.entrySet())rootLoad.merge(load.getKey(),load.getValue(),Double::sum);for(var load:proposedColumns.entrySet())columnLoad.merge(load.getKey(),load.getValue(),Double::sum);results.put(c.id(),new Support(Status.SUPPORTED,chosen.root(),chosen.distance(),0,"Ground-connected support"));iterator.remove();progress=true;}
        }}
        for(var c:placementOrder){var choices=paths.get(c.id());results.put(c.id(),new Support(Status.UNSUPPORTED,0,minimumDistance(choices),0,exhausted?"Structural search budget exceeded":choices==null?"No connected foundation within material span":"Support load capacity exceeded"));}
        for(var c:region.values()){Support s=results.get(c.id());if(s.status()==Status.SUPPORTED)results.put(c.id(),new Support(s.status(),s.foundationId(),s.distance(),c.column()?columnLoad.getOrDefault(c.id(),0.0):rootLoad.getOrDefault(s.foundationId(),0.0),s.reason()));}
    }
    private static double minimumDistance(List<Label> labels){double value=Double.POSITIVE_INFINITY;if(labels!=null)for(var label:labels)value=Math.min(value,label.distance());return value;}
    /** Local candidate preview. Neither live IDs, revision, grid nor cached support changes. */
    public boolean supports(LayerComponent candidate){
        if(previewRevision==invalidationRevision&&previewGeometryRevision==world.geometryRevision()&&Objects.equals(previewCandidate,candidate))return previewResult;
        boolean result=preview(candidate);previewCandidate=candidate;previewRevision=invalidationRevision;previewGeometryRevision=world.geometryRevision();previewResult=result;return result;
    }
    private boolean preview(LayerComponent candidate){
        if(!world.canAdd(candidate))return false;if(candidate.mode()==Mode.FREE)return true;solveDirty();
        TreeMap<Long,LayerComponent> affected=new TreeMap<>();for(var n:world.query(world.coordinates().bounds(candidate).expanded(EPS)))if(connected(candidate,n))affected.putAll(collect(n));
        LayerWorld preview=new LayerWorld(world.coordinates());for(var c:affected.values())preview.add(c);preview.add(candidate);
        try(StructuralSolver check=new StructuralSolver(preview,foundations)){
            check.solveDirty();if(!check.support(candidate.id()).supported())return false;
            for(var c:affected.values()){Support before=results.get(c.id());if(before!=null&&before.supported()&&!check.support(c.id()).supported())return false;}return true;
        }
    }
    public List<List<Long>> unsupportedSections(){
        solveDirty();if(sectionsRevision==invalidationRevision)return cachedSections;
        ArrayList<List<Long>> result=new ArrayList<>();HashSet<Long> visited=new HashSet<>();
        for(var c:world.components())if(c.mode()==Mode.STRUCTURAL&&results.get(c.id())!=null&&results.get(c.id()).status()==Status.UNSUPPORTED&&visited.add(c.id())){
            ArrayList<Long> section=new ArrayList<>();ArrayDeque<LayerComponent> pending=new ArrayDeque<>();pending.add(c);
            while(!pending.isEmpty()){var current=pending.removeFirst();section.add(current.id());for(var n:world.query(world.coordinates().bounds(current).expanded(EPS))){Support s=results.get(n.id());if(s!=null&&s.status()==Status.UNSUPPORTED&&connected(current,n)&&visited.add(n.id()))pending.addLast(n);}}
            section.sort(Long::compare);result.add(List.copyOf(section));
        }
        cachedSections=List.copyOf(result);sectionsRevision=invalidationRevision;return cachedSections;
    }
    @Override public void close(){world.removeListener(listener);dirty.clear();results.clear();}
}
