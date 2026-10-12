package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.Mechc;
import mindustry.gen.Unit;
import java.util.ArrayList;

/** Opt-in native SDL A/B fixture, using the actual production renderer, atlas, poses and weapons. */
public final class DaggerModelVisualChecks {
    private DaggerModelVisualChecks(){}
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes,Unit unit){
        require(Boolean.getBoolean("fpv.smoke"),"isolated lab only");
        require(unit.type==UnitTypes.dagger&&unit instanceof Mechc,"native Dagger whole-leg mech");
        var family=renderer.groundFamily();var profile=ProceduralUnitModelProfile.forType(unit.type);
        for(var type:Vars.content.units())require((ProceduralUnitModelProfile.forType(type)!=null)==(type==UnitTypes.dagger||type==UnitTypes.mace||type==UnitTypes.fortress||type==UnitTypes.scepter||type==UnitTypes.reign||type==UnitTypes.vela||ProceduralUnitModelProfile.greenVisual(type)),"accepted mechs and four green hull profiles only");
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=2000;camera.fov=44;
        long map=fingerprint();float hp=unit.health,x=unit.x,y=unit.y,yaw=unit.rotation;
        var before=altitudes.unitState(unit);require(before!=null&&!before.flying(),"canonical ground pose");
        require(profile.hull()!=null&&profile.hull().bands().size()==3,"three cached thin silhouette bands");
        require(Math.abs(profile.groundClearance()+profile.bodyCenterLift(before.halfHeight())-profile.bodyThickness()*.5+before.halfHeight()-.10)<1e-6,"hull seats on underside chassis at Z.10");
        double top=profile.topOffset(before.halfHeight(),1,1.5,0,0);
        require(Math.abs(top+before.z()-.40)<1e-6,"compact hull roof at Z.40");
        require(Math.abs(profile.weaponUpOffset()-profile.weaponThickness()*.5-top-.02)<1e-6,"guns lower faces .02 above hull roof");
        require(Math.abs(profile.cameraTopOffset(before.halfHeight(),1,1.5,0,0)-.60)<1e-6,"existing ground eye reference retained");
        int checks=7;ArrayList<Unit> crowd=new ArrayList<>();
        try{
            String[] views={"top","side","low-fpv","oblique"};
            byte[] oldSide=null,newSide=null;
            for(int angle=0;angle<views.length;angle++){
                view(camera,before.x(),before.y(),before.z(),yaw,angle);
                family.prototypeEnabled(false);renderer.render(camera);capture("dagger-old-"+views[angle]+".png");
                if(angle==1)oldSide=pixels();
                family.prototypeEnabled(true);renderer.render(camera);capture("dagger-new-"+views[angle]+".png");
                if(angle==1)newSide=pixels();
                require(family.wasSubmitted(unit),"native mesh visible at "+views[angle]);checks++;
            }
            require(changed(oldSide,newSide)>100,"new volume/mounts visibly change side pixels");checks++;
            var body=renderer.unitGeometry(unit.type.region);var weapon=renderer.unitGeometry(unit.mounts[0].weapon.region);
            require(body!=null&&body.sideQuads()>4&&body.topQuads()>1&&body.topQuads()==body.bottomQuads(),"silhouette caps/side faces, not bounding box");checks++;
            require(weapon!=null&&weapon.sideQuads()>4&&family.prototypeWeaponDraws()>1,"both original mirrored weapons extruded");checks++;
            long bodyBuilds=renderer.extrusionStats().builds(),limbBuilds=family.nativeLimbStats().builds();
            for(int i=0;i<12;i++){
                Unit other=UnitTypes.dagger.create(unit.team);other.set(x+(i%4-1.5f)*Vars.tilesize*2.5f,y+(i/4+2)*Vars.tilesize*2.5f);
                other.rotation=30*i;((Mechc)other).baseRotation(other.rotation);other.controller(new Stationary());other.add();altitudes.pose(other);crowd.add(other);
            }
            camera.position.set((float)before.x()+8,9,(float)-before.y()+10);camera.up.set(0,1,0);
            camera.lookAt((float)before.x(),.2f,(float)-before.y()-4);camera.update();renderer.render(camera);capture("dagger-new-crowd.png");
            for(Unit other:crowd){require(family.wasSubmitted(other),"crowd Dagger XYZ pose visible");checks++;}
            for(int i=0;i<8;i++)renderer.render(camera);
            require(bodyBuilds==renderer.extrusionStats().builds()&&limbBuilds==family.nativeLimbStats().builds(),"all Dagger instances reuse body/weapon/limb caches");checks++;
            require(altitudes.unitState(unit).equals(before)&&unit.health==hp&&unit.x==x&&unit.y==y,"render never mutates collision, position or HP");checks++;
            // Real native move and rotation; no renderer-owned movement/controller implementation.
            unit.move(Vars.tilesize*.5f,Vars.tilesize*.25f);unit.rotation=yaw+55;((Mechc)unit).baseRotation(unit.rotation);altitudes.update(1/60.0);
            var moved=altitudes.unitState(unit);require(Math.abs(moved.x()-before.x())>.1&&Math.abs(moved.yaw()-before.yaw())>10,"real native move/turn updates XYZ render pose");checks++;
            view(camera,moved.x(),moved.y(),moved.z(),unit.rotation,1);renderer.render(camera);capture("dagger-new-moved.png");
            double aim=Math.toRadians(unit.rotation);
            unit.aim(unit.x+(float)Math.cos(aim)*Vars.tilesize*12,unit.y+(float)Math.sin(aim)*Vars.tilesize*12);unit.controlWeapons(true,true);
            int bullets=0;for(int frame=0;frame<60;frame++)for(var mount:unit.mounts)mount.weapon.update(unit,mount);
            for(var bullet:mindustry.gen.Groups.bullet)if(bullet.owner==unit)bullets++;
            require(bullets>0,"native Dagger weapon actually spawns shots");checks++;
            renderer.render(camera);capture("dagger-new-firing.png");unit.controlWeapons(false,false);
            require(fingerprint()==map,"native terrain/block map unchanged");checks++;
            require(Gl.getError()==0,"cached model shaders, native atlas and mirrored mounts GL error0");checks++;
            int assemblyVertices=body.vertices()+2*weapon.vertices()+family.nativeLimbVertices(unit.type);
            String result="PASS: "+checks+" checks; three tapered silhouette bands, native underside hub, original Dagger body/cell, two native whole legs, two mirrored large-weapon mounts .02 tiles above roof; top/side/low-FPV/oblique old/new captures; native move/rotation/shots; 13 Daggers share caches; body="+body+", weapon="+weapon+", assembly="+assemblyVertices/3+" triangles ("+assemblyVertices+" unindexed vertices); collision/map unchanged; GL0";
            output().child("dagger-model-verification.txt").writeString(result+"\nPhysical before="+before+"\nMoved="+moved+"\nProfile="+profile+"\nNative assets="+NativeLimbRenderer.assetSummary(unit)+"\n");
            return result;
        }finally{
            family.prototypeEnabled(true);for(Unit other:crowd)if(other.isAdded())other.remove();
            var shots=new ArrayList<mindustry.gen.Bullet>();for(var bullet:mindustry.gen.Groups.bullet)if(bullet.owner==unit)shots.add(bullet);
            for(var bullet:shots){bullet.hit=true;bullet.remove();}
            unit.set(x,y);unit.rotation=yaw;((Mechc)unit).baseRotation(yaw);unit.controlWeapons(false,false);altitudes.update(1/60.0);
        }
    }
    private static void view(Camera3D camera,double x,double y,double h,double yaw,int angle){
        camera.up.set(0,1,0);double a=Math.toRadians(yaw+90);
        if(angle==0){camera.position.set((float)x,(float)h+3.6f,(float)-y+.001f);camera.up.set(0,0,-1);}
        else if(angle==3){camera.position.set((float)x+2.3f,(float)h+1.8f,(float)-y+2.3f);}
        else{float distance=angle==1?3.2f:2.6f;camera.position.set((float)(x+Math.cos(a)*distance),angle==1?.38f:.12f,(float)(-y-Math.sin(a)*distance));}
        camera.lookAt((float)x,(float)h,(float)-y);camera.update();
    }
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,Core.graphics.getWidth(),Core.graphics.getHeight(),false);}
    private static int changed(byte[] a,byte[] b){int count=0;for(int i=0;i<a.length;i+=4)if(Math.abs((a[i]&255)-(b[i]&255))+Math.abs((a[i+1]&255)-(b[i+1]&255))+Math.abs((a[i+2]&255)-(b[i+2]&255))>20)count++;return count;}
    private static long fingerprint(){long result=17;for(var tile:Vars.world.tiles)result=result*31+tile.blockID()*71L+tile.floorID()*17L+tile.overlayID();return result;}
    private static Fi output(){return new Fi(System.getProperty("fpv.smoke.output"));}
    private static void capture(String name){ScreenUtils.saveScreenshot(output().child(name));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Dagger model GPU: "+message);}
    private static final class Stationary implements UnitController{
        private Unit unit;public Unit unit(){return unit;}public void unit(Unit value){unit=value;}
        public void updateUnit(){unit.vel.setZero();unit.controlWeapons(false,false);}
    }
}
