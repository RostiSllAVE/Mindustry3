package fpv.control;

import arc.graphics.g3d.Camera3D;
import fpv.combat.CombatGeometry;
import fpv.world.World3DAdapter;
import mindustry.gen.*;
import mindustry.world.blocks.ControlBlock;

/** Visual 3D selection only; the native unitControl RPC remains the possession authority. */
public final class PilotSelection {
    private PilotSelection(){}
    public static Unit pick(Camera3D camera,World3DAdapter world,Player player,float screenX,float screenY){
        if(camera==null||world==null||player==null)return null;
        var ray=camera.getPickRay(screenX,screenY);double ax=ray.origin.x,ay=-ray.origin.z,az=ray.origin.y;
        double reach=Math.min(2000,camera.far),bx=ax+ray.direction.x*reach,by=ay-ray.direction.z*reach,bz=az+ray.direction.y*reach;
        var opaque=world.traceSegment(ax,ay,az,bx,by,bz,0);double nearest=opaque.hit()?opaque.fraction()+.001:1;Unit result=null;
        for(Unit unit:Groups.unit){
            if(unit==player.unit()||unit.dead||unit.team!=player.team()||!unit.isAI()||!unit.playerControllable()||unit.inFogTo(player.team()))continue;
            var body=world.unitState(unit);if(body==null)continue;
            double fraction=CombatGeometry.cylinder(ax,ay,az,bx,by,bz,body.x(),body.y(),body.z(),body.radius()+.2,body.halfHeight()+.2);
            if(fraction<nearest){nearest=fraction;result=unit;}
        }
        if(result!=null)return result;
        if(opaque.hit()){
            if(world.construction()!=null){var upper=world.construction().sweep(ax,ay,az,bx,by,bz,0,0);
                if(upper!=null&&upper.fraction()<=opaque.fraction()+.001){var build=world.construction().building(upper.component().id());
                    if(build instanceof ControlBlock control&&control.canControl()&&build.team==player.team()&&!build.inFogTo(player.team())&&control.unit()!=player.unit()&&control.unit().isAI())return control.unit();
                    return null;
                }
            }
            double x=ax+(bx-ax)*opaque.fraction(),y=ay+(by-ay)*opaque.fraction();
            var tile=mindustry.Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));var build=tile==null?null:tile.build;
            if(build instanceof ControlBlock control&&control.canControl()&&build.team==player.team()&&!build.inFogTo(player.team())&&control.unit()!=player.unit()&&control.unit().isAI())return control.unit();
        }
        return null;
    }
}
