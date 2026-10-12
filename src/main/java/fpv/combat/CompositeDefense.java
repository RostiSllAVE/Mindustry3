package fpv.combat;

import java.util.List;
import mindustry.game.Team;
import mindustry.gen.Bullet;

/** A single nearest-contact query keeps independent shield systems in physical ray order. */
public final class CompositeDefense implements ProjectedDefense {
    private record Routed(ProjectedDefense owner,Contact contact){}
    private final List<ProjectedDefense> systems;
    public CompositeDefense(ProjectedDefense... systems){this.systems=List.of(systems);}
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){
        return traceKind(team,ax,ay,az,bx,by,bz,radius,false,true,null);
    }
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){return traceLaser(team,ax,ay,az,bx,by,bz,radius,true);}
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls){
        return traceKind(team,ax,ay,az,bx,by,bz,radius,true,absorbLaserWalls,null);
    }
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,Bullet emitter){return traceKind(team,ax,ay,az,bx,by,bz,radius,false,true,emitter);}
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls,Bullet emitter){return traceKind(team,ax,ay,az,bx,by,bz,radius,true,absorbLaserWalls,emitter);}
    private Contact traceKind(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean laser,boolean absorbLaserWalls,Bullet emitter){
        Contact closest=null;ProjectedDefense owner=null;
        for(var system:systems){Contact next=laser?system.traceLaser(team,ax,ay,az,bx,by,bz,radius,absorbLaserWalls,emitter):system.trace(team,ax,ay,az,bx,by,bz,radius,emitter);
            if(next!=null&&Double.isFinite(next.fraction())&&next.fraction()>=0&&next.fraction()<=1&&
                (closest==null||next.fraction()<closest.fraction())){closest=next;owner=system;}}
        return closest==null?null:new Contact(closest.fraction(),closest.x(),closest.y(),closest.z(),new Routed(owner,closest));
    }
    @Override public boolean hit(Contact contact,Bullet bullet,float damage){
        return contact!=null&&contact.token() instanceof Routed r&&systems.contains(r.owner)&&r.owner.hit(r.contact,bullet,damage);
    }
    @Override public boolean physicalSurface(Contact contact){return contact!=null&&contact.token() instanceof Routed r&&systems.contains(r.owner)&&r.owner.physicalSurface(r.contact);}
}
