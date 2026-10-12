package fpv.combat;

import arc.func.Cons;
import arc.util.Time;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import fpv.world.Unit3DState;
import java.util.IdentityHashMap;

/** Uses native team quadtrees per shot; one shared body/speed-bound scan per simulation update. */
final class CombatTargetIndex {
    private final World3DAdapter world;
    private long stamp=Long.MIN_VALUE;
    private double maxRadius,maxSpeed,maxOffset;
    private record LastPose(Unit3DState pose,long stamp) {}
    private final IdentityHashMap<Unit,LastPose> poses=new IdentityHashMap<>();
    CombatTargetIndex(World3DAdapter world){this.world=world;}
    void invalidate(){stamp=Long.MIN_VALUE;poses.clear();}
    Unit3DState ownerPose(Unit unit){var live=world.unitState(unit);if(live!=null){poses.put(unit,new LastPose(live,Vars.state.updateId));return live;}
        var last=poses.get(unit);return last!=null&&last.pose.unitId()==unit.id&&Vars.state.updateId-last.stamp<=3?last.pose:null;}
    void capture(){
        long current=Vars.state.updateId;
        if(stamp!=current){
            maxRadius=maxSpeed=maxOffset=0;
            for(Unit unit:Groups.unit){
                if(unit.dead||unit.type==null)continue;
                var body=world.unitState(unit);if(body==null)continue;
                poses.put(unit,new LastPose(body,current));
                maxRadius=Math.max(maxRadius,body.radius());
                maxSpeed=Math.max(maxSpeed,Math.hypot(body.vx(),body.vy()));
                maxOffset=Math.max(maxOffset,Math.hypot(body.x()-unit.x/Vars.tilesize,body.y()-unit.y/Vars.tilesize));
            }
            stamp=current;
            poses.entrySet().removeIf(entry->current-entry.getValue().stamp>3);
        }
    }
    void nearby(Team team,double ax,double ay,double bx,double by,double projectileRadius,double seconds,Cons<Unit> consumer){
        capture();
        // Native team trees precede entity movement; include previous-tree and relative-sweep motion.
        double padding=projectileRadius+maxRadius+maxOffset+maxSpeed*Math.max(seconds,Math.max(0,Time.delta/60.0))*2+.05;
        double minX=Math.min(ax,bx)-padding,minY=Math.min(ay,by)-padding;
        double width=Math.abs(bx-ax)+padding*2,height=Math.abs(by-ay)+padding*2;
        Units.nearbyEnemies(team,(float)(minX*Vars.tilesize),(float)(minY*Vars.tilesize),(float)(width*Vars.tilesize),(float)(height*Vars.tilesize),consumer);
    }
}
