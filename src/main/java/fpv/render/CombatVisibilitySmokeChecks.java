package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import fpv.combat.Combat3DSystem;
import fpv.combat.CombatVisual;
import mindustry.Vars;

import java.util.ArrayList;
import java.util.HashSet;

/** Inspects the native managed fixtures left by CombatSmokeChecks through the production GPU path. */
public final class CombatVisibilitySmokeChecks {
    private CombatVisibilitySmokeChecks() {}

    /** Invoke immediately after CombatSmokeChecks.run, before simulation ages its retained beam events. */
    public static String verify(FpvWorldRenderer renderer,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"loaded isolated lab required");
        var visuals=new ArrayList<>(combat.visuals());
        require(!visuals.isEmpty(),"managed native shot fixtures must be present");
        var projectileTeams=new HashSet<Integer>();var beamTeams=new HashSet<Integer>();
        int projectiles=0,beams=0,vertical=0;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(CombatVisual visual:visuals){
            minX=Math.min(minX,Math.min(visual.ax(),visual.bx()));maxX=Math.max(maxX,Math.max(visual.ax(),visual.bx()));
            minY=Math.min(minY,Math.min(visual.ay(),visual.by()));maxY=Math.max(maxY,Math.max(visual.ay(),visual.by()));
            minZ=Math.min(minZ,Math.min(visual.az(),visual.bz()));maxZ=Math.max(maxZ,Math.max(visual.az(),visual.bz()));
            if(visual.kind()==CombatVisual.Kind.PROJECTILE){projectiles++;projectileTeams.add(visual.teamId());}
            if(visual.kind()==CombatVisual.Kind.BEAM){
                beams++;beamTeams.add(visual.teamId());
                if(Math.hypot(visual.bx()-visual.ax(),visual.by()-visual.ay())<.0001&&Math.abs(visual.bz()-visual.az())>.1)vertical++;
            }
        }
        require(projectileTeams.size()>=2&&beamTeams.size()>=2,"both native teams provide managed projectile and beam fixtures");
        require(vertical>0,"an exactly vertical native-managed beam exercises arbitrary-axis geometry");
        float x=(float)((minX+maxX)*.5),height=(float)((minZ+maxZ)*.5),z=(float)(-(minY+maxY)*.5);
        double radius=Math.max(3,Math.sqrt(square(maxX-minX)+square(maxY-minY)+square(maxZ-minZ))*.5+2);
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());
        camera.fov=48;camera.near=.05f;
        double halfAngle=Math.atan(Math.tan(Math.toRadians(camera.fov)*.5)*Math.min(1,camera.width/camera.height));
        float distance=(float)(radius/Math.sin(halfAngle));
        camera.position.set(x+distance*.30f,height+distance*.35f,z+distance*.8874f);
        camera.lookAt(x,height,z);camera.far=Math.max(1000,distance*3);camera.update();
        renderer.render(camera);
        for(CombatVisual visual:visuals)require(renderer.wasCombatVisualSubmitted(visual.id()),"completed GPU submission for "+visual.kind()+" team="+visual.teamId()+" id="+visual.id());
        arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("combat.png"));
        return "PASS: "+projectiles+" managed projectiles and "+beams+" beams from both teams, "+vertical+" exactly vertical rays, actual completed-frame GPU submissions";
    }
    private static double square(double value){return value*value;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Combat visibility smoke: "+message);}
}
