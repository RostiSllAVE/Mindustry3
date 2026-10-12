package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.abilities.ForceFieldAbility;
import mindustry.gen.Unit;

/** Owns only a temporary Oct in the explicitly isolated lab; no player possession or save writes. */
public final class NativeShieldCameraSmokeChecks {
    private NativeShieldCameraSmokeChecks() {}
    public static String verify(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"loaded isolated lab required");
        require(NativeForceFieldState.available(),"pinned native radiusScale/alpha fields readable");
        boolean previousVisible=renderer.showControlledUnit(),previousFirstPerson=renderer.firstPersonActive();float previousDelta=Time.delta;
        Unit oct=UnitTypes.oct.create(Vars.player.team());
        try{
            oct.set(Vars.world.width()*.5f*Vars.tilesize,Vars.world.height()*.5f*Vars.tilesize);oct.add();
            ForceFieldAbility field=null;for(var ability:oct.abilities)if(ability instanceof ForceFieldAbility candidate){field=candidate;break;}
            require(field!=null&&oct.shield>0,"native Oct initialized ForceFieldAbility shield");
            Time.delta=60;field.update(oct);Time.delta=previousDelta;
            float radius=NativeForceFieldState.radius(oct,field)/Vars.tilesize;
            require(radius>field.radius/Vars.tilesize*.9f,"native ability animation expanded the field");
            var pose=altitudes.unitState(oct);require(pose!=null,"physical Oct altitude available");
            float x=(float)pose.x(),y=(float)pose.z(),z=(float)-pose.y();
            float shieldBefore=oct.shield,nativeRadius=NativeForceFieldState.radius(oct,field),flash=NativeForceFieldState.flash(field);
            Camera3D camera=camera();camera.position.set(x+radius*1.65f,y+radius*.8f,z+radius*2);
            camera.lookAt(x,y,z);camera.far=renderer.environment().farPlane(camera);camera.update();renderer.render(camera);
            require(renderer.wasShieldSubmitted(oct)&&renderer.wasUnitSubmitted(oct),"outside camera submits native Oct and field");capture("shield-outside.png");
            camera.position.set(x+radius*.65f,y+radius*.1f,z+radius*.45f);camera.lookAt(x,y,z);camera.update();renderer.render(camera);
            require(renderer.wasShieldSubmitted(oct),"inside camera submits two-sided field");capture("shield-inside.png");
            require(oct.shield==shieldBefore&&NativeForceFieldState.radius(oct,field)==nativeRadius&&NativeForceFieldState.flash(field)==flash,
                "rendering does not advance or mutate native shield strength/radius/flash");
            oct.shield=0;renderer.render(camera);require(!renderer.wasShieldSubmitted(oct),"broken native shield disappears immediately");

            Unit controlled=Vars.player.unit();var controlledPose=altitudes.unitState(controlled);
            require(controlledPose!=null,"real controlled flight state available");
            x=(float)controlledPose.x();y=(float)controlledPose.z();z=(float)-controlledPose.y();
            float distance=Math.max(5,SpriteExtrusionRenderer.bodyRadius(controlled)*3.6f);
            camera.position.set(x+distance*.35f,y+distance*.45f,z+distance);camera.lookAt(x,y,z);
            camera.far=renderer.environment().farPlane(camera);camera.update();
            renderer.setShowControlledUnit(false);renderer.setFirstPersonActive(true);renderer.render(camera);
            require(!renderer.wasUnitBodySubmitted(controlled),"first-person hides enclosing torso while retaining the limbs-only render path");
            require(renderer.firstPersonRig().stats().weapons()>0,"first-person submits original perspective weapon/nose artwork");
            if(GroundFamilyRenderer.supportsVisual(controlled)){
                require(renderer.groundFamily().wasSubmitted(controlled),"first-person retains controlled ground-unit limbs");
            }else{
                require(!renderer.groundFamily().wasSubmitted(controlled),"flying first-person unit has no fabricated legs");
            }
            capture("first-person-native-rig.png");
            renderer.setFirstPersonActive(false);renderer.setShowControlledUnit(true);renderer.render(camera);
            require(renderer.wasUnitSubmitted(controlled)&&renderer.wasUnitBodySubmitted(controlled),"third-person submits real controlled body");
            require(renderer.firstPersonRig().stats().weapons()==0,"third-person does not duplicate first-person weapon artwork");capture("third-person.png");
            require(Vars.player.unit()==controlled,"render visibility never changes possession");

            camera.position.set((Vars.world.width()-1)*.5f,720,-(Vars.world.height()-1)*.5f);
            camera.direction.set(0,-1,0);camera.up.set(0,0,-1);camera.fov=67;
            camera.far=renderer.environment().farPlane(camera);camera.update();renderer.render(camera);
            require(camera.far>720,"camera altitude extends far plane beyond ground");
            require(renderer.stats().visibleChunks()>0,"terrain stays in high-altitude frustum");
            require(renderer.pick(camera,camera.width*.5f,camera.height*.5f)!=null,"high-altitude center ray reaches native map");capture("high-altitude-720.png");
            return "PASS: native Oct expanded/broken shield, inside/outside GPU submissions with immutable native state, first-person torso hidden with native weapon/limb rig, third-person full body without duplicate rig,720-tile far plane/picking";
        }finally{
            Time.delta=previousDelta;renderer.setShowControlledUnit(previousVisible);renderer.setFirstPersonActive(previousFirstPerson);if(oct.isAdded())oct.remove();
        }
    }
    private static Camera3D camera(){Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.fov=60;return camera;}
    private static void capture(String name){arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Shield/camera smoke: "+message);}
}
