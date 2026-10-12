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
import mindustry.gen.*;
import java.util.ArrayList;

/** Isolated native-art GPU measurements, repeat-instance cache checks and reversible movement. */
public final class GreenModelVisualChecks {
    private GreenModelVisualChecks(){}
    private static int checks;
    public static String run(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes,Unit unit){
        checks=0;check(Boolean.getBoolean("fpv.smoke")&&ProceduralUnitModelProfile.greenVisual(unit.type),"isolated four-unit fixture");
        var family=renderer.groundFamily();var profile=ProceduralUnitModelProfile.forType(unit.type);var pose=altitudes.unitState(unit);
        boolean firstPerson=renderer.firstPersonActive();renderer.setFirstPersonActive(false);
        float x=unit.x,y=unit.y,yaw=unit.rotation,hp=unit.health,elevation=unit.elevation;
        long map=mapHash();var crowd=new ArrayList<Unit>();
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=2000;camera.fov=44;
        StringBuilder metrics=new StringBuilder(unit.type.name+": native hitSize="+unit.hitSize+", sprite="+unit.type.region.width+"x"+unit.type.region.height+", profile="+profile+"\n");
        try{
            var art=Core.atlas.getPixmap(unit.type.region);byte[] alpha=new byte[art.width*art.height];
            for(int yy=0;yy<art.height;yy++)for(int xx=0;xx<art.width;xx++)alpha[xx+yy*art.width]=(byte)art.getA(xx,yy);
            var h=profile.hull();var legacy=new LayeredHullProfile(h.bands(),h.chassisScale(),0);
            var old=LayeredHullGeometry.generate(alpha,art.width,art.height,legacy,(int)ExtrusionConfig.MAX_GRID_SIDE,(int)ExtrusionConfig.MIN_GRID_SIDE,(int)ExtrusionConfig.ALPHA_THRESHOLD,ExtrusionConfig.MIN_CELL_COVERAGE,(int)ExtrusionConfig.MAX_VERTICES);
            var shell=LayeredHullGeometry.generate(alpha,art.width,art.height,h,(int)ExtrusionConfig.MAX_GRID_SIDE,(int)ExtrusionConfig.MIN_GRID_SIDE,(int)ExtrusionConfig.ALPHA_THRESHOLD,ExtrusionConfig.MIN_CELL_COVERAGE,(int)ExtrusionConfig.MAX_VERTICES);
            check(shell.vertexCount()<old.vertexCount(),"same-art exterior eliminates redundant stack surfaces");
            metrics.append("Same-profile stacked comparator vertices=").append(old.vertexCount()).append("; optimized vertices=").append(shell.vertexCount()).append(", triangles=").append(shell.vertexCount()/3)
                .append(", CPU+GPU bytes=").append(shell.vertexBytes()*2).append("; one body mesh/atlas, original texture, shared per type\n");
            String[] names={"top","side","ground","oblique"};
            for(int angle=0;angle<4;angle++){
                view(camera,unit,pose,angle);family.prototypeEnabled(false);renderer.render(camera);capture(unit.type.name+"-before-"+names[angle]+".png");
                family.prototypeEnabled(true);renderer.render(camera);capture(unit.type.name+"-layered-"+names[angle]+".png");
                check(family.wasSubmitted(unit),"model visible "+names[angle]);
            }
            var actual=renderer.unitGeometry(unit.type.region);check(actual!=null&&actual.vertices()==shell.vertexCount(),"production cached mesh matches measured geometry");
            if(unit instanceof Legsc){
                check(unit.type==UnitTypes.corvus&&family.visualLegCount(unit)==4,"four original articulated Corvus chains");
                for(int leg=0;leg<4;leg++)check(Double.isFinite(family.footHeight(unit,leg)),"ground contact remains finite");
                check(Math.abs(profile.topOffset(GroundFamilyRenderer.bodyHalfDepth(unit),1,1,0,0)-SpriteExtrusionRenderer.halfDepth(unit))<1e-5,"Corvus original roof retained, underside thickened");
            }
            double spacing=Math.max(3,unit.hitSize/Vars.tilesize*3);
            for(int i=0;i<8;i++){
                var other=unit.type.create(unit.team);other.set(x+(float)((i%3-1)*spacing*Vars.tilesize),y+(float)((i/3+1)*spacing*Vars.tilesize));other.rotation=i*30;
                if(other instanceof Mechc m)m.baseRotation(other.rotation);other.add();altitudes.pose(other);crowd.add(other);
            }
            // Corvus gets the same default physical stance through the authoritative solver.
            for(int tick=0;tick<200;tick++)altitudes.update(1/60.0);
            var current=altitudes.unitState(unit);double span=Math.max(5,spacing*3);
            camera.up.set(0,1,0);camera.position.set((float)current.x(),(float)current.z()+(float)span*1.3f,(float)-current.y()+(float)span*1.3f);
            camera.lookAt((float)current.x(),(float)current.z(),(float)(-current.y()-spacing));camera.update();renderer.render(camera);capture(unit.type.name+"-layered-crowd.png");
            for(var other:crowd)check(family.wasSubmitted(other),"nine same-type bodies rendered");
            long bodies=renderer.extrusionStats().builds(),limbs=family.nativeLimbStats().builds();
            for(int i=0;i<8;i++)renderer.render(camera);
            check(bodies==renderer.extrusionStats().builds()&&limbs==family.nativeLimbStats().builds(),"no per-frame/per-instance mesh generation");
            for(var other:crowd)other.remove();crowd.clear();
            view(camera,unit,current,3);long draws=renderer.extrusionStats().rendered(),limbDraws=family.nativeLimbStats().draws();renderer.render(camera);
            metrics.append("Isolated draws: body/weapons=").append(renderer.extrusionStats().rendered()-draws).append(", native legs/chassis=").append(family.nativeLimbStats().draws()-limbDraws)
                .append("; native limb vertices=").append(family.nativeLimbVertices(unit.type)).append("; limb atlas batches=").append(family.nativeLimbBatches(unit.type)).append('\n');
            metrics.append(NativeLimbRenderer.assetSummary(unit)).append('\n');
            check(unit.health==hp&&unit.x==x&&unit.y==y&&unit.elevation==elevation,"render leaves native position/health/boost unchanged");
            unit.move(Vars.tilesize*.7f,Vars.tilesize*.25f);unit.rotation=yaw+35;
            if(unit instanceof Mechc mech){mech.baseRotation(unit.rotation);mech.walkTime(mech.walkTime()+10);}
            altitudes.update(1/60.0);var moved=altitudes.unitState(unit);view(camera,unit,moved,3);renderer.render(camera);capture(unit.type.name+"-layered-moving.png");
            check(Math.hypot(moved.x()-current.x(),moved.y()-current.y())>.1&&Math.abs(moved.yaw()-current.yaw())>10,"native movement/yaw drives body");
            check(mapHash()==map&&Gl.getError()==0,"map unchanged and GL0");
            String result="PASS: "+checks+" "+unit.type.name+" actual-atlas top/side/ground/oblique/crowd/movement and cache checks; no FPS claim";
            output().child(unit.type.name+"-green-model-verification.txt").writeString(result+"\n"+metrics);return result;
        }finally{renderer.setFirstPersonActive(firstPerson);family.prototypeEnabled(true);for(var other:crowd)if(other.isAdded())other.remove();unit.set(x,y);unit.rotation=yaw;if(unit instanceof Mechc mech)mech.baseRotation(yaw);altitudes.update(1/60.0);}
    }
    private static void view(Camera3D camera,Unit unit,Unit3DState p,int angle){
        float span=Math.max(1,SpriteExtrusionRenderer.bodyRadius(unit)*2);camera.up.set(0,1,0);
        if(angle==0){camera.position.set((float)p.x(),(float)p.z()+span*3,(float)-p.y()+.001f);camera.up.set(0,0,-1);}
        else if(angle==3)camera.position.set((float)p.x()+span*2,(float)p.z()+span*1.4f,(float)-p.y()+span*2);
        else camera.position.set((float)p.x()+span*3,angle==1?(float)p.z():.12f,(float)-p.y());
        camera.lookAt((float)p.x(),(float)p.z(),(float)-p.y());camera.update();
    }
    private static long mapHash(){long h=17;for(var t:Vars.world.tiles)h=h*31+t.blockID()*71L+t.floorID()*17L+t.overlayID();return h;}
    private static Fi output(){return new Fi(System.getProperty("fpv.smoke.output"));}
    private static void capture(String name){ScreenUtils.saveScreenshot(output().child(name));}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new IllegalStateException("Green hull: "+label);}
}
