package fpv.combat;

import mindustry.game.Team;
import mindustry.gen.Bullet;

/** Canonical XYZ contact boundary shared by all authoritative combat rays and projectiles. */
public interface ProjectedDefense {
    record Contact(double fraction,double x,double y,double z,Object token) {}
    Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius);
    default Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,Bullet emitter){return trace(team,ax,ay,az,bx,by,bz,radius);}
    default Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls,Bullet emitter){return traceLaser(team,ax,ay,az,bx,by,bz,radius,absorbLaserWalls);}
    /** Beam-specific native absorber policy; ordinary shield systems preserve their existing protection. */
    default Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){return trace(team,ax,ay,az,bx,by,bz,radius);}
    /** Native line families may opt out of laser-absorbing walls (e.g. Rail and flame).
     * Force fields keep their own protection: only wall projections interpret this flag. */
    default Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls){
        return traceLaser(team,ax,ay,az,bx,by,bz,radius);
    }
    /** False means a stale/collapsed token; no native building damage is implied. */
    boolean hit(Contact contact,Bullet bullet,float damage);
    /** Physical construction surfaces trigger native projectile payloads; force shields absorb them. */
    default boolean physicalSurface(Contact contact){return false;}
}
