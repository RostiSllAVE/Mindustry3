package fpv.render;

import arc.graphics.Color;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.gen.Unit;

/** Three batched primitives, native engine colors, no lights or particle allocation. Caller owns flight permission. */
public final class CreatorFlightVisuals {
    private final double[] basis=new double[9];
    public boolean render(Unit unit,Unit3DState pose,Camera3D camera,CombatVisualRenderer.Sink sink){
        if(unit==null||pose==null||!pose.flying()||unit.type.flying||sink==null||camera==null)return false;
        double dx=pose.x()-camera.position.x,dy=pose.z()-camera.position.y,dz=-pose.y()-camera.position.z;
        double distance=dx*dx+dy*dy+dz*dz;if(distance>300*300)return false;
        double size=Math.max(.15,unit.hitSize/(double)Vars.tilesize*.16),depth=Math.max(.12,GroundFamilyRenderer.bodyHalfDepth(unit));
        SpriteExtrusionGeometry.basis(pose.yaw(),pose.pitch(),pose.roll(),basis);
        if(unit.type.canBoost){
            // Use the actual native exhaust layout, so a folded rear foot cannot bury a
            // generic center jet. Existing Creator-only artificial jets retain their old layout.
            int count=Math.min(8,unit.type.engines.size);
            if(count==0)nativeJet(unit,pose,camera,sink,0,-unit.type.engineOffset/Vars.tilesize,Math.max(.12,unit.type.engineSize/Vars.tilesize),depth,null,distance);
            else for(int i=0;i<count;i++){
                var engine=unit.type.engines.get(i);
                nativeJet(unit,pose,camera,sink,engine.x/Vars.tilesize,engine.y/Vars.tilesize,Math.max(.12,engine.radius/Vars.tilesize),depth,engine.color,distance);
            }
            return true;
        }
        double bx=pose.x()-basis[3]*(depth+.05),by=pose.z()-basis[4]*(depth+.05),bz=-pose.y()-basis[5]*(depth+.05);
        double length=size*(2+.18*Math.sin(Time.time*.24+unit.id));
        Color color=unit.type.engineColor==null?unit.team.color:unit.type.engineColor,inner=unit.type.engineColorInner;
        sink.beam((float)bx,(float)by,(float)bz,(float)(bx-basis[3]*length),(float)(by-basis[4]*length),(float)(bz-basis[5]*length),(float)(size*.5),color.r,color.g,color.b);
        if(distance<100*100)sink.beam((float)bx,(float)by,(float)bz,(float)(bx-basis[3]*length*.52),(float)(by-basis[4]*length*.52),(float)(bz-basis[5]*length*.52),(float)(size*.27),inner.r,inner.g,inner.b);
        sink.marker((float)bx,(float)by,(float)bz,(float)(size*.23),inner.r,inner.g,inner.b);return true;
    }
    private void nativeJet(Unit unit,Unit3DState pose,Camera3D camera,CombatVisualRenderer.Sink sink,double right,double forward,double size,double depth,Color override,double distance){
        double up=-depth-.05;
        double x=pose.x()+basis[0]*right-basis[6]*forward+basis[3]*up;
        double y=pose.z()+basis[1]*right-basis[7]*forward+basis[4]*up;
        double z=-pose.y()+basis[2]*right-basis[8]*forward+basis[5]*up;
        double length=size*(2+.18*Math.sin(Time.time*.24+unit.id));
        Color color=override!=null?override:unit.type.engineColor==null?unit.team.color:unit.type.engineColor,inner=unit.type.engineColorInner;
        sink.beam((float)x,(float)y,(float)z,(float)(x-basis[3]*length),(float)(y-basis[4]*length),(float)(z-basis[5]*length),(float)(size*.5),color.r,color.g,color.b);
        if(distance<100*100)sink.beam((float)x,(float)y,(float)z,(float)(x-basis[3]*length*.52),(float)(y-basis[4]*length*.52),(float)(z-basis[5]*length*.52),(float)(size*.27),inner.r,inner.g,inner.b);
        sink.marker((float)x,(float)y,(float)z,(float)(size*.23),inner.r,inner.g,inner.b);
    }
}
