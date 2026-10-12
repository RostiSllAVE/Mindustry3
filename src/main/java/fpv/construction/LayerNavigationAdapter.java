package fpv.construction;

import fpv.units.ground.GroundLocomotionModel;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import mindustry.Vars;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.AIController;
import mindustry.gen.Unit;
import java.util.*;

/** Bounded local detours layered over native AI intent; no replacement controller or free damage. */
public final class LayerNavigationAdapter {
    public record NavigationObstacle(long componentId,int team,double x,double y,double z,LayerCoordinates.Bounds bounds){}
    @FunctionalInterface public interface Passage {boolean clear(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight);}
    public record Route(List<WorldCoordinates.Point> points,int visited,boolean found){public Route{points=List.copyOf(points);}}
    private record Cell(int x,int y){}
    private record Search(Cell cell,double cost,double score,Search parent){}
    private static final class Entry {
        Unit unit;Object type;LayerWorld grid;long revision,seen;double retry,age;Route route;int waypoint;
        NavigationObstacle obstacle;final IdentityHashMap<BulletType,WorldCoordinates.Point> aims=new IdentityHashMap<>();
    }
    private static final Map<Integer,Entry> active=new HashMap<>();
    private final Map<Integer,Entry> local=new HashMap<>();
    private final World3DAdapter world;
    private long lastPrune;
    public LayerNavigationAdapter(World3DAdapter world){this.world=Objects.requireNonNull(world);}
    public static NavigationObstacle obstacle(Unit unit){Entry e=entry(unit);return e==null?null:e.obstacle;}
    public static WorldCoordinates.Point aim(Unit unit,BulletType original){Entry e=entry(unit);return e==null?null:e.aims.get(original);}
    public static void weaponAim(Unit unit,BulletType original,WorldCoordinates.Point target){Entry e=entry(unit);if(e==null)return;if(target==null)e.aims.remove(original);else e.aims.put(original,target);}
    private static Entry entry(Unit unit){
        Entry e=unit==null?null:active.get(unit.id);
        if(e==null||e.unit!=unit||e.type!=unit.type||unit.dead||!unit.isAdded()||unit.isPlayer()||!(unit.controller() instanceof AIController)||Vars.state==null||Vars.state.updateId-e.seen>3)return null;
        if(e.obstacle!=null){LayerComponent c=e.grid.get(e.obstacle.componentId());if(c==null||c.health()<=0||c.team()==unit.team.id){e.obstacle=null;e.aims.clear();}}
        return e;
    }
    /** Called immediately before the grounded sweep. Returns a small alternative displacement. */
    public WorldCoordinates.Point next(Unit unit,GroundLocomotionModel.State state,double nativeX,double nativeY,double radius,double halfHeight,double dt){
        if(Vars.state!=null&&Vars.state.updateId-lastPrune>=60){lastPrune=Vars.state.updateId;prune();}
        if(unit==null||unit.dead||unit.isPlayer()||unit.isFlying()||!(unit.controller() instanceof AIController)||unit.controller() instanceof mindustry.ai.types.LogicAI||Vars.net.client()||dt<=0||world.construction()==null){forget(unit);return null;}
        LayerWorld grid=world.construction().grid();if(grid==null||grid.size()==0){forget(unit);return null;}
        double dx=nativeX-state.x(),dy=nativeY-state.y(),length=Math.hypot(dx,dy);Entry e=local.get(unit.id);
        if(e!=null&&(e.unit!=unit||e.type!=unit.type||e.grid!=grid)){forget(unit);e=null;}
        double speed=Math.max(length/dt,Math.max(0,unit.speed())*60.0/Vars.tilesize);
        if(e==null){
            if(length<1e-5)return null;
            double look=Math.max(1.5,Math.min(5,radius+1));
            var obstruction=world.construction().sweep(state.x(),state.y(),state.z(),state.x()+dx/length*look,state.y()+dy/length*look,state.z(),radius,halfHeight);
            if(obstruction==null)return null;
            e=new Entry();e.unit=unit;e.type=unit.type;e.grid=grid;e.revision=-1;local.put(unit.id,e);active.put(unit.id,e);
        }
        e.seen=Vars.state.updateId;e.retry=Math.max(0,e.retry-dt);e.age+=dt;
        if(e.age>8||unit.speed()<=0){forget(unit);return null;}
        if(e.revision!=grid.geometryRevision()){e.route=null;e.retry=0;e.obstacle=null;e.aims.clear();e.revision=grid.geometryRevision();}
        if(e.route==null&&e.retry<=0&&length>=1e-5){
            double reach=Math.max(8,Math.min(16,radius*3+4));double gx=state.x()+dx/length*reach,gy=state.y()+dy/length*reach;
            gx=Math.max(radius,Math.min(Vars.world.width()-1-radius,gx));gy=Math.max(radius,Math.min(Vars.world.height()-1-radius,gy));
            double base=world.construction().supportBelow(state.x(),state.y(),state.z()-halfHeight+.04,Math.max(world.terrainHeight(state.x(),state.y()),world.surfaceHeight(state.x(),state.y())));
            final double floor=base;
            e.route=plan(state.x(),state.y(),state.z(),gx,gy,radius,halfHeight,24,1536,
                (ax,ay,az,bx,by,bz,r,h)->passage(unit,grid,ax,ay,az,bx,by,bz,r,h,floor));
            e.waypoint=0;e.retry=.6;e.obstacle=null;e.aims.clear();
            if(!e.route.found()){
                var hit=world.construction().sweep(state.x(),state.y(),state.z(),gx,gy,state.z(),radius,halfHeight);
                if(hit!=null&&hit.component().team()!=unit.team.id&&hit.component().health()>0){var bounds=grid.coordinates().bounds(hit.component());e.obstacle=new NavigationObstacle(hit.component().id(),hit.component().team(),bounds.centerX(),bounds.centerY(),Math.max(bounds.minZ()+.03,Math.min(bounds.maxZ()-.03,state.z())),bounds);}
            }
        }
        if(e.route==null)return null;
        if(!e.route.found()){if(e.retry<=0)e.route=null;return null;}
        while(e.waypoint<e.route.points().size()){
            var target=e.route.points().get(e.waypoint);double distance=Math.hypot(target.x()-state.x(),target.y()-state.y());
            if(distance<.18){e.waypoint++;continue;}
            double travel=Math.min(distance,Math.max(.001,speed*dt));double x=state.x()+(target.x()-state.x())/distance*travel,y=state.y()+(target.y()-state.y())/distance*travel;
            if(world.construction().sweep(state.x(),state.y(),state.z(),x,y,state.z(),radius,halfHeight)!=null){e.route=null;e.retry=0;return null;}
            return new WorldCoordinates.Point(x,y,state.z());
        }
        forget(unit);return null;
    }
    private boolean passage(Unit unit,LayerWorld grid,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight,double currentFloor){
        if(bx-radius<0||by-radius<0||bx+radius>=Vars.world.width()||by+radius>=Vars.world.height())return false;
        if(world.traceUnitBarrier(unit,ax,ay,az,bx,by,bz,radius,halfHeight)!=null)return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/.3));
        for(int i=0;i<=steps;i++){
            double t=i/(double)steps,x=ax+(bx-ax)*t,y=ay+(by-ay)*t,z=az+(bz-az)*t;
            var hull=new LayerCoordinates.Bounds(x-radius,y-radius,z-halfHeight+.001,x+radius,y+radius,z+halfHeight-.001);
            for(var c:grid.query(hull))if(hull.overlaps(grid.coordinates().bounds(c)))return false;
            for(int p=0;p<9;p++){
                double angle=(p-1)*Math.PI/4,px=x+(p==0?0:Math.cos(angle)*radius),py=y+(p==0?0:Math.sin(angle)*radius);
                double floor=Math.max(world.terrainHeight(px,py),world.surfaceHeight(px,py));floor=world.construction().supportBelow(px,py,z-halfHeight+.04,floor);
                if(floor>z-halfHeight+.04||floor<currentFloor-.3)return false;
            }
        }
        return true;
    }
    public void forget(Unit unit){if(unit==null)return;Entry e=local.remove(unit.id);if(e!=null)active.remove(unit.id,e);}
    public void prune(){for(var it=local.entrySet().iterator();it.hasNext();){var item=it.next();Entry e=item.getValue();if(e.unit.dead||!e.unit.isAdded()||e.unit.type!=e.type||e.unit.isPlayer()||Vars.state.updateId-e.seen>3){active.remove(item.getKey(),e);it.remove();}}}
    public void clear(){for(var e:local.entrySet())active.remove(e.getKey(),e.getValue());local.clear();lastPrune=0;}

    /** Pure bounded A*. Every edge is a complete physical body sweep, including diagonal passages. */
    public static Route plan(double x,double y,double z,double goalX,double goalY,double radius,double halfHeight,int searchRadius,int maximumNodes,Passage passage){
        if(!Double.isFinite(x+y+z+goalX+goalY+radius+halfHeight)||radius<=0||halfHeight<=0||searchRadius<1||searchRadius>64||maximumNodes<1||maximumNodes>16384||passage==null)throw new IllegalArgumentException("Bounded finite local navigation request required");
        Cell goal=new Cell((int)Math.round(goalX-x),(int)Math.round(goalY-y));if(Math.abs(goal.x)>searchRadius||Math.abs(goal.y)>searchRadius)return new Route(List.of(),0,false);
        Comparator<Search> order=Comparator.comparingDouble(Search::score).thenComparingDouble(Search::cost).thenComparingInt(n->n.cell.x).thenComparingInt(n->n.cell.y);
        PriorityQueue<Search> open=new PriorityQueue<>(order);Map<Cell,Double> best=new HashMap<>();Cell start=new Cell(0,0);open.add(new Search(start,0,Math.hypot(goal.x,goal.y),null));best.put(start,0.0);int visited=0;
        while(!open.isEmpty()&&visited<maximumNodes){
            Search current=open.remove();if(current.cost>best.getOrDefault(current.cell,Double.POSITIVE_INFINITY)+1e-9)continue;visited++;
            if(current.cell.equals(goal)){
                ArrayList<WorldCoordinates.Point> points=new ArrayList<>();for(Search step=current;step.parent!=null;step=step.parent)points.add(new WorldCoordinates.Point(x+step.cell.x,y+step.cell.y,z));Collections.reverse(points);return new Route(points,visited,true);
            }
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;Cell next=new Cell(current.cell.x+dx,current.cell.y+dy);if(Math.abs(next.x)>searchRadius||Math.abs(next.y)>searchRadius)continue;
                double cost=current.cost+Math.hypot(dx,dy);if(cost>=best.getOrDefault(next,Double.POSITIVE_INFINITY)-1e-9)continue;
                if(!passage.clear(x+current.cell.x,y+current.cell.y,z,x+next.x,y+next.y,z,radius,halfHeight))continue;
                best.put(next,cost);open.add(new Search(next,cost,cost+Math.hypot(next.x-goal.x,next.y-goal.y),current));
            }
        }
        return new Route(List.of(),visited,false);
    }
}
