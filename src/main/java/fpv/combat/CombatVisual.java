package fpv.combat;

/** Immutable renderer view. Coordinates are gameplay XYZ, never renderer axes. */
public record CombatVisual(long id,Kind kind,int teamId,
                           double ax,double ay,double az,double bx,double by,double bz,
                           double radius,float r,float g,float b,float alpha) {
    public enum Kind { PROJECTILE, BEAM, HIT, EXPLOSION, HEAL }
}
