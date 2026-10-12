package fpv.control;

import arc.math.geom.Vec2;
import fpv.net.NetworkControls;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.gen.*;

/** Shared offline/server ground and fixed-building input. Native movePref preserves movement restrictions. */
public final class GroundedPilot {
    private final Vec2 planar=new Vec2();
    public void step(Player player,Unit unit,NetworkControls controls,UnitAltitudeSystem altitudes,double dt){
        boolean moving=controls.active()&&controls.mode()!=2;
        if(!UnitControlCapabilities.stationary(unit)){
            double f=moving?controls.forward():0,r=moving?controls.right():0,h=controls.headingRadians();
            planar.set((float)(f*Math.sin(h)+r*Math.cos(h)),(float)(f*Math.cos(h)-r*Math.sin(h))).limit(1).scl(unit.speed());
            unit.movePref(planar);
            altitudes.setGroundStanceControl(unit,true,moving?controls.climb():0,dt);
            if(moving&&controls.shooting()&&unit.type.omniMovement&&unit.type.faceTarget)unit.lookAt((float)Math.toDegrees(Math.atan2(controls.aimY(),controls.aimX())));
            else if(planar.len2()>.00001f)unit.lookAt(planar.angle());
        }else unit.vel.setZero();
        weapons(player,unit,controls);
    }
    public static void weapons(Player player,Unit unit,NetworkControls c){
        boolean shoot=c.active()&&c.mode()!=2&&c.shooting()&&unit.canShoot();
        player.shooting=shoot;unit.controlWeapons(true,shoot);
        // Native turret rotation/reload/ammo and every unit mount continue to run normally.
        double dx=c.aimX(),dy=c.aimY(),n=Math.hypot(dx,dy);
        if(n<1e-6){dx=Math.cos(Math.toRadians(unit.rotation));dy=Math.sin(Math.toRadians(unit.rotation));n=1;}
        unit.aim((float)(unit.x+dx/n*10000),(float)(unit.y+dy/n*10000),true);
        player.mouseX=unit.aimX();player.mouseY=unit.aimY();
    }
    public static Unit3DState fixedPose(Unit unit,World3DAdapter world){
        double x=unit.x/(double)Vars.tilesize,y=unit.y/(double)Vars.tilesize;
        Building building=LayerTurretUnit.actual(unit);
        double height=(building==null?world.surfaceHeight(x,y):world.buildingTop(building))+.12;
        return new Unit3DState(unit.id,unit.type.id,unit.team.id,x,y,height,0,0,0,unit.rotation,0,0,height,unit.hitSize/Vars.tilesize*.5,.1,false);
    }
}
