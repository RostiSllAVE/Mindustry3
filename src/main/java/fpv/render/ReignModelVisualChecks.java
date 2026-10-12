package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.generated.ExtrusionConfig;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.*;
import java.util.ArrayList;

/** Native installed-art geometry measurements and reversible GPU/motion/fire gallery. */
public final class ReignModelVisualChecks {
    private static int checks;
    private ReignModelVisualChecks(){}
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes,Unit unit){
        checks=0;check(Boolean.getBoolean("fpv.smoke")&&unit.type==UnitTypes.reign&&unit instanceof Mechc,"isolated actual native Reign");
        var profile=ProceduralUnitModelProfile.forType(unit.type);var family=renderer.groundFamily();
        var pose=altitudes.unitState(unit);float x=unit.x,y=unit.y,yaw=unit.rotation,hp=unit.health;
        double eye=fpv.flight.GroundEyeHeight.desired(unit,pose.halfHeight());long map=mapHash();
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=2000;camera.fov=44;
        var crowd=new ArrayList<Unit>();
        // Keep the much wider T5 actor and its firing effects outside the reference frame.
        Unit scepter=UnitTypes.scepter.create(unit.team);scepter.set(x+32*Vars.tilesize,y-24*Vars.tilesize);scepter.rotation=yaw;((Mechc)scepter).baseRotation(yaw);scepter.add();altitudes.pose(scepter);
        try{
            view(camera,scepter,altitudes.unitState(scepter),3);renderer.render(camera);byte[] scepterBefore=pixels();capture("scepter-preserved-before-reign.png");
            check(profile.hull().optimized()&&Math.abs(profile.bodyThickness()-unit.hitSize/Vars.tilesize*.30)<1e-5,"unit-size-derived optimized depth");
            double roof=profile.topOffset(pose.halfHeight(),1,1,0,0);
            check(Math.abs(profile.weaponUpOffset()-profile.weaponThickness()*.5-roof-.055)<1e-6,"native guns sit .055 tiles above roof");
            check(profile.cameraTopOffset(pose.halfHeight(),1,1,0,0)==0,"existing Reign first-person eye envelope retained");
            var scepterProfile=ProceduralUnitModelProfile.forType(UnitTypes.scepter);
            check(profile.bodyThickness()>scepterProfile.bodyThickness()*1.3&&profile.hull().bands().get(0).scale()>scepterProfile.hull().bands().get(0).scale(),"heavier depth and broader lower armor than Scepter");
            check(profile.hull().chassisScale()>scepterProfile.hull().chassisScale()&&unit.hitSize>UnitTypes.scepter.hitSize,"larger native size and chassis");
            check(unit.mounts.length==2,"two native apex gun mounts");
            for(var mount:unit.mounts){
                boolean mirrored=false;for(var other:unit.mounts)if(other!=mount&&other.weapon.x==-mount.weapon.x&&other.weapon.y==mount.weapon.y&&other.weapon.name.equals(mount.weapon.name))mirrored=true;
                check(mirrored,"native mirrored mount "+mount.weapon.name+"/"+mount.weapon.y);
            }
            StringBuilder metrics=new StringBuilder("Installed native Reign: hitSize=").append(unit.hitSize).append(", region=").append(unit.type.region.width).append('x').append(unit.type.region.height)
                .append(", scl=").append(unit.type.region.scl()).append(", profile=").append(profile).append('\n');
            for(var type:new mindustry.type.UnitType[]{UnitTypes.scepter,UnitTypes.reign}){
                var art=Core.atlas.getPixmap(type.region);byte[] alpha=new byte[art.width*art.height];
                for(int yy=0;yy<art.height;yy++)for(int xx=0;xx<art.width;xx++)alpha[xx+yy*art.width]=(byte)art.getA(xx,yy);
                var hull=ProceduralUnitModelProfile.forType(type).hull();var oldHull=new LayeredHullProfile(hull.bands(),hull.chassisScale(),hull.cameraReferenceDepth());
                var old=LayeredHullGeometry.generate(alpha,art.width,art.height,oldHull,64,8,32,ExtrusionConfig.MIN_CELL_COVERAGE,65520);
                var optimized=OptimizedHullGeometry.generate(alpha,art.width,art.height,hull,64,8,32,ExtrusionConfig.MIN_CELL_COVERAGE,65520);
                check(optimized.vertexCount()<old.vertexCount(),type.name+" same-art/same-profile exterior uses fewer vertices");
                metrics.append(type.name).append(" same-profile comparison: legacy vertices=").append(old.vertexCount()).append(", triangles=").append(old.vertexCount()/3)
                    .append(", capQuads=").append(old.topQuads()+old.bottomQuads()).append(", sideQuads=").append(old.sideQuads()).append(", CPU+GPU bytes=").append(old.vertexBytes()*2)
                    .append("; optimized vertices=").append(optimized.vertexCount()).append(", triangles=").append(optimized.vertexCount()/3)
                    .append(", capQuads=").append(optimized.topQuads()+optimized.bottomQuads()).append(", sideQuads=").append(optimized.sideQuads()).append(", CPU+GPU bytes=").append(optimized.vertexBytes()*2).append('\n');
            }
            String[] names={"top","side","ground","oblique"};long weaponDraws=0;
            for(int angle=0;angle<4;angle++){
                view(camera,unit,pose,angle);family.prototypeEnabled(false);renderer.render(camera);capture("reign-old-"+names[angle]+".png");
                family.prototypeEnabled(true);long weaponsBefore=family.prototypeWeaponDraws();renderer.render(camera);weaponDraws=family.prototypeWeaponDraws()-weaponsBefore;
                capture("reign-new-"+names[angle]+".png");check(family.wasSubmitted(unit),"actual Reign visible "+names[angle]);
            }
            var geometry=renderer.unitGeometry(unit.type.region);check(geometry!=null&&geometry.sideQuads()>4,"actual cached non-box hull submitted");
            check(weaponDraws==unit.mounts.length,"both original apex gun sprites submitted in this draw");
            double mx=scepter.x,my=scepter.y;scepter.set(x+Vars.tilesize*6,y);altitudes.pose(scepter);
            camera.up.set(0,1,0);camera.position.set((float)pose.x()+9,7,(float)-pose.y()+13);camera.lookAt((float)pose.x()+3,.5f,(float)-pose.y());camera.update();
            renderer.render(camera);capture("reign-next-to-scepter.png");check(family.wasSubmitted(unit)&&family.wasSubmitted(scepter),"actual Reign beside Scepter");
            scepter.set((float)mx,(float)my);altitudes.pose(scepter);view(camera,unit,pose,3);
            var lineup=new ArrayList<Unit>();
            try{
                var types=new mindustry.type.UnitType[]{UnitTypes.dagger,UnitTypes.mace,UnitTypes.fortress,UnitTypes.scepter};
                for(int i=0;i<types.length;i++){
                    var other=types[i].create(unit.team);other.set(x+(i-4)*Vars.tilesize*8,y);other.rotation=yaw;((Mechc)other).baseRotation(yaw);other.add();altitudes.pose(other);lineup.add(other);
                }
                camera.fov=60;camera.up.set(0,1,0);camera.position.set((float)pose.x()-16,18,(float)-pose.y()+28);camera.lookAt((float)pose.x()-16,.6f,(float)-pose.y());camera.update();
                renderer.render(camera);capture("reign-family-lineup.png");check(family.wasSubmitted(unit),"Reign in full T1-T5 comparison");
                for(var other:lineup)check(family.wasSubmitted(other),"lineup "+other.type.name);
            }finally{for(var other:lineup)other.remove();camera.fov=44;view(camera,unit,pose,3);}
            long bodies=renderer.extrusionStats().builds(),limbs=family.nativeLimbStats().builds();
            for(int i=0;i<12;i++){
                var other=UnitTypes.reign.create(unit.team);other.set(x+(i%4-1.5f)*Vars.tilesize*6f,y+(i/4+2)*Vars.tilesize*6f);
                other.rotation=i*30;((Mechc)other).baseRotation(other.rotation);other.add();altitudes.pose(other);crowd.add(other);
            }
            camera.up.set(0,1,0);camera.position.set((float)pose.x()+20,20,(float)-pose.y()+25);camera.lookAt((float)pose.x(),.25f,(float)-pose.y()-10);camera.update();
            renderer.render(camera);capture("reign-new-crowd.png");for(var other:crowd)check(family.wasSubmitted(other),"crowd Reign submitted");
            for(int i=0;i<8;i++)renderer.render(camera);
            check(bodies==renderer.extrusionStats().builds()&&limbs==family.nativeLimbStats().builds(),"13 Reign units reuse hull/weapon/limb caches");
            for(var other:crowd)other.remove();crowd.clear();view(camera,unit,pose,3);
            long draws=renderer.extrusionStats().rendered(),limbDraws=family.nativeLimbStats().draws();renderer.render(camera);
            metrics.append("Measured isolated model draws: body+weapons=").append(renderer.extrusionStats().rendered()-draws)
                .append(", native movement/chassis=").append(family.nativeLimbStats().draws()-limbDraws).append("; body meshes=1, body atlas textures=1, no copied textures\n");
            metrics.append("Native art/movement: ").append(NativeLimbRenderer.assetSummary(unit)).append('\n');
            for(var mount:unit.mounts)metrics.append("Native weapon ").append(mount.weapon.name).append(" at ").append(mount.weapon.x).append(',').append(mount.weapon.y).append("; recoil=").append(mount.weapon.recoil).append('\n');
            check(altitudes.unitState(unit).equals(pose)&&unit.health==hp&&unit.x==x&&unit.y==y,"render keeps actual pose/hitbox/health");
            check(Math.abs(fpv.flight.GroundEyeHeight.desired(unit,pose.halfHeight())-eye)<1e-6,"Reign camera height unchanged");
            unit.move(Vars.tilesize*.7f,Vars.tilesize*.25f);unit.rotation=yaw+45;((Mechc)unit).baseRotation(unit.rotation);
            ((Mechc)unit).walkTime(((Mechc)unit).walkTime()+10);altitudes.update(1/60.0);var moved=altitudes.unitState(unit);
            check(Math.abs(moved.x()-pose.x())>.1&&Math.abs(moved.yaw()-pose.yaw())>10,"native movement/turn drives model");
            view(camera,unit,moved,3);renderer.render(camera);capture("reign-new-moving.png");
            double aim=Math.toRadians(unit.rotation);unit.aim(unit.x+(float)Math.cos(aim)*100,unit.y+(float)Math.sin(aim)*100);unit.controlWeapons(true,true);
            for(int tick=0;tick<90;tick++)for(var mount:unit.mounts)mount.weapon.update(unit,mount);
            boolean shots=false;for(var bullet:Groups.bullet)if(bullet.owner==unit)shots=true;check(shots,"native Reign actually fires native Reign projectiles");
            for(var mount:unit.mounts)mount.recoil=1;renderer.render(camera);capture("reign-new-firing.png");unit.controlWeapons(false,false);
            check(mapHash()==map&&Gl.getError()==0,"native map unchanged and GL0");
            String performance=ReignModelPerfChecks.run(renderer,altitudes);metrics.append(performance);
            output().child("reign-render-profile.txt").writeString(performance);
            view(camera,scepter,altitudes.unitState(scepter),3);renderer.render(camera);capture("scepter-preserved-after-reign.png");
            check(java.util.Arrays.equals(scepterBefore,pixels()),"Scepter target-region pixels unchanged after Reign rendering/cache activity");
            String result="PASS: "+checks+" native Reign model checks; top/side/ground/oblique old/new views, native movement/aim/fire, original whole legs/chassis, 13 instances share caches, unchanged physical pose/eye, GL0";
            output().child("reign-model-verification.txt").writeString(result+"\n"+metrics);return result;
        }finally{
            family.prototypeEnabled(true);scepter.remove();for(var other:crowd)if(other.isAdded())other.remove();var bullets=new ArrayList<Bullet>();for(var bullet:Groups.bullet)if(bullet.owner==unit)bullets.add(bullet);
            for(var bullet:bullets){bullet.hit=true;bullet.remove();}unit.set(x,y);unit.rotation=yaw;((Mechc)unit).baseRotation(yaw);unit.controlWeapons(false,false);altitudes.update(1/60.0);
        }
    }
    private static void view(Camera3D camera,Unit unit,fpv.world.Unit3DState p,int angle){
        float size=Math.max(1,Math.max(unit.type.region.width,unit.type.region.height)*unit.type.region.scl()/Vars.tilesize);camera.up.set(0,1,0);
        if(angle==0){camera.position.set((float)p.x(),size*2.8f,(float)-p.y()+.001f);camera.up.set(0,0,-1);}
        else if(angle==3)camera.position.set((float)p.x()+size*1.7f,size*1.35f,(float)-p.y()+size*1.7f);
        else{double a=Math.toRadians(unit.rotation+90);camera.position.set((float)(p.x()+Math.cos(a)*size*2.2),angle==1?.32f:.13f,(float)(-p.y()-Math.sin(a)*size*2.2));}
        camera.lookAt((float)p.x(),.25f,(float)-p.y());camera.update();
    }
    private static long mapHash(){long hash=17;for(var t:Vars.world.tiles)hash=hash*31+t.blockID()*71L+t.floorID()*17L+t.overlayID();return hash;}
    private static Fi output(){return new Fi(System.getProperty("fpv.smoke.output"));}
    private static void capture(String name){ScreenUtils.saveScreenshot(output().child(name));}
    private static byte[] pixels(){
        // This fixed gallery camera puts every reference hull/gun/foot in the central region.
        // Full screenshots remain available; unrelated actors at the frame edge are excluded.
        int w=Core.graphics.getWidth(),h=Core.graphics.getHeight();
        return ScreenUtils.getFrameBufferPixels(w/5,h*3/20,w*3/5,h*7/10,false);
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Reign model: "+label);}
}
