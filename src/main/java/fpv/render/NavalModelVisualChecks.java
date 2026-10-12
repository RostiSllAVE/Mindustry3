package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.generated.ExtrusionConfig;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;
import java.util.ArrayList;

/** Isolated native atlas/opaque water comparison and measured cache reuse. */
public final class NavalModelVisualChecks {
    private NavalModelVisualChecks(){}
    private static int checks;
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes,Unit unit){
        checks=0;check(Boolean.getBoolean("fpv.smoke")&&NavalUnitRenderer.supports(unit),"isolated native naval unit");
        var naval=renderer.naval();var profile=naval.profile(unit);var pose=altitudes.unitState(unit);
        boolean first=renderer.firstPersonActive();renderer.setFirstPersonActive(false);
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.015f;camera.far=2000;camera.fov=44;
        var crowd=new ArrayList<Unit>();var minke=UnitTypes.minke.create(unit.team);minke.set(unit.x+12*Vars.tilesize,unit.y+4*Vars.tilesize);minke.rotation=unit.rotation;
        String prefix=unit.type.name; StringBuilder metrics=new StringBuilder(prefix+" native art "+unit.type.region.width+"x"+unit.type.region.height+", hitSize="+unit.hitSize+", profile="+profile+"\n");
        try{
            var art=Core.atlas.getPixmap(unit.type.region);byte[] alpha=new byte[art.width*art.height];
            for(int y=0;y<art.height;y++)for(int x=0;x<art.width;x++)alpha[x+y*art.width]=(byte)art.getA(x,y);
            var hull=profile.body().hull();var oldHull=new LayeredHullProfile(hull.bands(),hull.chassisScale(),0);
            var old=geometry(alpha,art.width,art.height,oldHull);var base=geometry(alpha,art.width,art.height,hull);
            var mesh=NavalBodyGeometry.generate(base,profile.sections(),profile.body().bodyThickness(),(int)ExtrusionConfig.MAX_VERTICES);
            check(mesh.vertexCount()<old.vertexCount()&&mesh.sideQuads()>4,"alpha-shaped exterior without stacked hidden caps");
            metrics.append("Vertices=").append(mesh.vertexCount()).append(", triangles=").append(mesh.vertexCount()/3).append(", CPU+GPU bytes=").append(mesh.vertexBytes()*2)
                .append("; naive same-section stack vertices=").append(old.vertexCount()).append("; one hull mesh/native atlas\n");
            String[] names={"top","waterline","oblique","underwater"};
            for(int angle=0;angle<4;angle++){
                view(camera,unit,pose,angle);naval.enabled(false);renderer.render(camera);capture(prefix+"-before-"+names[angle]+".png");
                naval.enabled(true);long weapons=naval.weaponDraws();renderer.render(camera);capture(prefix+"-hull-"+names[angle]+".png");
                check(renderer.wasUnitSubmitted(unit),"Risso submitted "+names[angle]);
                check(naval.weaponDraws()-weapons==java.util.Arrays.stream(unit.mounts).filter(m->m.weapon!=null&&!m.weapon.shootOnDeath&&NativeAtlasMesh.valid(m.weapon.region)).count(),"original textured mounts visible "+names[angle]);
            }
            double line=profile.waterline(pose.z(),pose.halfHeight(),renderer.surfaceHeight(pose.x(),-pose.y()),true);
            check(Math.abs(line)<1e-5&&profile.keel()<0&&profile.deck()>0,"water Z0 crosses hull");
            check(renderer.unitGeometry(unit.type.region).vertices()==mesh.vertexCount(),"production mesh matches measurements");
            boolean raised=false;for(int v=1;v<mesh.vertices().length;v+=8)if(mesh.vertices()[v]>.5f)raised=true;
            check(raised,"actual native central sprite areas produce elevated roofs");
            double nativeWidth=unit.type.region.width*unit.type.region.scl()/Vars.tilesize,nativeLength=unit.type.region.height*unit.type.region.scl()/Vars.tilesize;
            if(unit.type==UnitTypes.risso)check(profile.mountLift(unit.mounts[2].weapon.x/Vars.tilesize,unit.mounts[2].weapon.y/Vars.tilesize,nativeWidth,nativeLength)>.2,"rear missile mounted on raised stern deckhouse");
            minke.add();altitudes.pose(minke);view(camera,minke,altitudes.unitState(minke),2);renderer.render(camera);byte[] otherBefore=pixels();capture(prefix+"-reference-before.png");
            for(int i=0;i<8;i++){
                float spacing=Math.max(3,unit.hitSize/Vars.tilesize*1.5f);var ship=unit.type.create(unit.team);ship.set(unit.x+(i%3-1)*spacing*Vars.tilesize,unit.y+(i/3+1)*spacing*Vars.tilesize);ship.rotation=i*30;ship.add();altitudes.pose(ship);crowd.add(ship);
            }
            camera.up.set(0,1,0);float distance=Math.max(14,unit.hitSize/Vars.tilesize*6);camera.position.set((float)pose.x()+distance,distance,(float)-pose.y()+distance);camera.lookAt((float)pose.x(),0,(float)-pose.y()-unit.hitSize/Vars.tilesize*2);camera.update();renderer.render(camera);capture(prefix+"-hull-crowd.png");
            for(var ship:crowd)check(renderer.wasUnitSubmitted(ship),"nine Risso instances submitted");
            long builds=renderer.extrusionStats().builds();for(int i=0;i<8;i++)renderer.render(camera);check(renderer.extrusionStats().builds()==builds,"no per-frame/per-instance meshes");
            for(var ship:crowd)ship.remove();crowd.clear();
            view(camera,minke,altitudes.unitState(minke),2);renderer.render(camera);capture(prefix+"-reference-after.png");check(java.util.Arrays.equals(otherBefore,pixels()),"Minke pixels unchanged");minke.remove();
            view(camera,unit,pose,2);long draws=renderer.extrusionStats().rendered();renderer.render(camera);
            metrics.append("Isolated draws=").append(renderer.extrusionStats().rendered()-draws).append(" (hull + native textured weapons; saddle draws separate); native mounts:\n");
            for(var m:unit.mounts)metrics.append(m.weapon.name).append(" at ").append(m.weapon.x).append(',').append(m.weapon.y).append(" rotates=").append(m.weapon.rotate).append(" recoil=").append(m.weapon.recoil).append('\n');
            check(altitudes.unitState(unit).equals(pose)&&Gl.getError()==0,"canonical XYZ unchanged and GL0");
            String result="PASS: "+checks+" native "+prefix+" top/waterline/oblique/underwater/crowd GPU checks, partial submersion, original mounts, cache reuse and stable reference pixels";
            output().child(prefix+"-model-verification.txt").writeString(result+"\n"+metrics);return result;
        }finally{naval.enabled(true);renderer.setFirstPersonActive(first);minke.remove();for(var ship:crowd)if(ship.isAdded())ship.remove();}
    }
    private static SpriteExtrusionGeometry.Result geometry(byte[] alpha,int w,int h,LayeredHullProfile hull){return LayeredHullGeometry.generate(alpha,w,h,hull,
        (int)ExtrusionConfig.MAX_GRID_SIDE,(int)ExtrusionConfig.MIN_GRID_SIDE,(int)ExtrusionConfig.ALPHA_THRESHOLD,ExtrusionConfig.MIN_CELL_COVERAGE,(int)ExtrusionConfig.MAX_VERTICES);}
    public static void view(Camera3D c,Unit u,Unit3DState p,int angle){
        float span=Math.max(1.4f,SpriteExtrusionRenderer.bodyRadius(u)*2);c.up.set(0,1,0);
        if(angle==0){c.position.set((float)p.x(),span*3,(float)-p.y()+.001f);c.up.set(0,0,-1);}
        else if(angle==1)c.position.set((float)p.x()+span*2.8f,.10f,(float)-p.y());
        else if(angle==3)c.position.set((float)p.x()+span*2.5f,-.22f,(float)-p.y());
        else c.position.set((float)p.x()+span*1.8f,span*1.25f,(float)-p.y()+span*1.8f);
        c.lookAt((float)p.x(),.08f,(float)-p.y());c.update();
    }
    private static Fi output(){return new Fi(System.getProperty("fpv.smoke.output"));}
    private static void capture(String name){ScreenUtils.saveScreenshot(output().child(name));}
    private static byte[] pixels(){return ScreenUtils.getFrameBufferPixels(0,0,Core.graphics.getWidth(),Core.graphics.getHeight(),false);}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Risso visual: "+label);}
}
