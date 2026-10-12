package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import arc.util.ScreenUtils;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import java.util.*;

/** Explicit native visual lab only: pixel reads are assertions, never a gameplay visibility query. */
public final class UnitThreatVisualChecks {
    private UnitThreatVisualChecks(){}
    private static int assertions;
    public static String run(FpvWorldRenderer ignored,UnitAltitudeSystem original){
        check(Boolean.getBoolean("fpv.smoke")&&Vars.world.width()==128,"isolated generated 128-tile lab required");
        assertions=0;boolean enabled=Core.settings.getBool(UnitThreatMarkerRenderer.SETTING,true);
        Unit pilot=Vars.player.unit();List<Unit> units=new ArrayList<>();List<Tile> tiles=new ArrayList<>();
        Map<Unit,Unit3DState> states=new IdentityHashMap<>();long before=fingerprint();
        FpvWorldRenderer renderer=new FpvWorldRenderer();
        UnitAltitudeSystem altitudes=new UnitAltitudeSystem(renderer.terrain()::heightAt);
        altitudes.setExternalStateProvider(states::get);renderer.setUnitAltitudes(altitudes);renderer.setShowControlledUnit(true);
        try{
            Camera3D camera=camera();UnitType[] types={UnitTypes.flare,UnitTypes.horizon,UnitTypes.zenith,UnitTypes.antumbra,UnitTypes.eclipse};
            for(int i=0;i<5;i++)units.add(spawn(types[i],i%2==0?Team.crux:Team.malis,44+i*10,64,5,states));
            Vars.player.unit(units.get(0));look(camera,64,9,-30,64,5,-64);
            byte[] off=world(renderer,camera,false,null),on=world(renderer,camera,true,"threat-tiers-teams-controlled.png");
            for(int i=0;i<5;i++){
                Unit unit=units.get(i);var d=renderer.threatMarkers().diagnostic(unit);
                check(d!=null&&d.submitted(),"tier "+(i+1)+" submits native visible unit");check(d.tier()==i+1,"actual progression tier for "+unit.type.name);
                check(Math.abs(d.r()-unit.team.color.r)<1e-6&&Math.abs(d.g()-unit.team.color.g)<1e-6&&Math.abs(d.b()-unit.team.color.b)<1e-6,"native team color "+i);
                check(changed(off,on,d)>10,"tier "+(i+1)+" has actual visible GPU pixels (not just submission)");
                check(teamPixels(off,on,d)>3,"tier "+(i+1)+" actual native-color fill pixels");
                check(d.sizePixels()>=22&&d.sizePixels()<=32,"readable bounded pixel size");
                check(d.anchorZ()>d.z()&&d.anchorX()==d.x()&&d.anchorY()==d.y(),"canonical XYZ anchor above model");
            }
            var controlled=renderer.threatMarkers().diagnostic(units.get(0));check(controlled.controlled(),"controlled identity");
            check(whiteChanged(off,on,controlled)>3,"controlled marker has real white outline pixels");
            check(renderer.threatMarkers().stats().draws()==1&&renderer.threatMarkers().stats().vertices()==renderer.threatMarkers().stats().submitted()*6,"single batched six-vertex glyph draw");
            check(renderer.threatMarkers().stats().depthCopies()==1,"single GPU depth copy");
            for(int i=1;i<units.size();i++)units.get(i).remove();
            Unit focus=units.get(0);set(focus,64,64,8,states);look(camera,64,8,-34,64,8,-64);
            off=world(renderer,camera,false,null);on=world(renderer,camera,true,"threat-front-billboard.png");var front=renderer.threatMarkers().diagnostic(focus);check(changed(off,on,front)>10,"front-facing billboard pixels");
            look(camera,34,8,-64,64,8,-64);off=world(renderer,camera,false,null);on=world(renderer,camera,true,"threat-side-billboard.png");var side=renderer.threatMarkers().diagnostic(focus);
            check(changed(off,on,side)>10&&Math.abs(side.sizePixels()-front.sizePixels())<.01,"90-degree camera rotation preserves billboard size and visibility");
            set(focus,64,64,20,states);look(camera,34,20,-64,64,20,-64);world(renderer,camera,true,"threat-elevated-xyz.png");var elevated=renderer.threatMarkers().diagnostic(focus);check(Math.abs(elevated.z()-20)<1e-8&&elevated.anchorZ()>20,"marker follows canonical vertical pose");
            set(focus,64,64,8,states);look(camera,64,8,16,64,8,-64);world(renderer,camera,true,null);var at80=renderer.threatMarkers().diagnostic(focus);check(at80!=null&&Math.abs(at80.alpha()-1)<.001,"80-tile boundary full opacity");
            look(camera,64,8,26,64,8,-64);world(renderer,camera,true,"threat-fade-90.png");var at90=renderer.threatMarkers().diagnostic(focus);check(at90!=null&&Math.abs(at90.alpha()-.5)<.001,"90-tile boundary half opacity");
            look(camera,64,8,37,64,8,-64);world(renderer,camera,true,null);check(!renderer.threatMarkers().wasSubmitted(focus),"101-tile horizontal culling");
            look(camera,64,109,-63.99f,64,8,-64);world(renderer,camera,true,null);check(!renderer.threatMarkers().wasSubmitted(focus),"true XYZ altitude distance culling");
            look(camera,64,8,-34,64,8,-20);world(renderer,camera,true,null);check(!renderer.threatMarkers().wasSubmitted(focus),"behind-camera culling");
            look(camera,64,8,-34,64,8,-64);for(int i=0;i<12;i++)units.add(spawn(UnitTypes.flare,Team.crux,64,64,8,states));
            world(renderer,camera,true,"threat-crowd-overlap.png");check(renderer.threatMarkers().wasSubmitted(focus),"controlled unit keeps priority in crowd");check(renderer.threatMarkers().stats().overlapSuppressed()>=12,"overlapping group markers suppressed");
            for(Unit unit:units)if(unit!=focus)unit.remove();
            syntheticDepth(focus,states,altitudes);
            // Actual production terrain/building depth, not merely the isolated synthetic gate.
            set(focus,64,70,.3,states);look(camera,64,.3f,-54,64,.3f,-70);
            off=world(renderer,camera,false,null);on=world(renderer,camera,true,"threat-low-visible-control.png");check(changed(off,on,renderer.threatMarkers().diagnostic(focus))>8,"low unit control remains visibly marked before blockers");
            for(int x=61;x<=67;x++){Tile tile=Vars.world.tile(x,64);check(tile.block()==Blocks.air,"owned empty blocker tile");tile.setBlock(Blocks.thoriumWall,Team.sharded,0);tiles.add(tile);}
            renderer.invalidate();off=world(renderer,camera,false,null);on=world(renderer,camera,true,"threat-native-building-occluded.png");check(changed(off,on,renderer.threatMarkers().diagnostic(focus))==0,"actual native solid building hides all marker pixels");
            for(Tile tile:tiles)tile.setBlock(Blocks.stoneWall);renderer.invalidate();renderer.terrain().invalidate();
            off=world(renderer,camera,false,null);on=world(renderer,camera,true,"threat-native-terrain-occluded.png");check(changed(off,on,renderer.threatMarkers().diagnostic(focus))==0,"actual generated mountain hides all marker pixels");
            check(Gl.getError()==0,"all marker renders leave OpenGL error zero");
            return "PASS: "+assertions+" native marker assertions; T1-T5/team/white pilot pixels; canonical XYZ and camera-facing size; 80/90/101-tile+vertical culling; one batched draw/depth copy; controlled crowd priority; GPU body and glyph depth gates; actual native wall and mountain occlusion";
        }finally{
            for(Unit unit:units)unit.remove();for(Tile tile:tiles)tile.setBlock(Blocks.air);Vars.player.unit(pilot);Core.settings.put(UnitThreatMarkerRenderer.SETTING,enabled);renderer.dispose();altitudes.close();
            check(fingerprint()==before,"native map unchanged after isolated marker fixture cleanup");
        }
    }
    private static void syntheticDepth(Unit focus,Map<Unit,Unit3DState> states,UnitAltitudeSystem altitudes){
        FrameBuffer scene=new FrameBuffer(512,512,true);UnitThreatMarkerRenderer markers=new UnitThreatMarkerRenderer();
        Shader shader=new Shader("attribute vec3 a_position;void main(){gl_Position=vec4(a_position,1.0);}","void main(){gl_FragColor=vec4(.12,.12,.12,1.0);}");Mesh mesh=new Mesh(false,6,0,VertexAttribute.position3);
        Camera3D camera=camera();camera.resize(512,512);set(focus,64,64,8,states);look(camera,64,8,-34,64,8,-64);
        try{
            scene.begin();try{
                Gl.disable(Gl.scissorTest);Gl.disable(Gl.cullFace);Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.clearColor(.02f,.03f,.04f,1);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
                markers.render(camera,altitudes,scene,true);var d=markers.diagnostic(focus);check(d!=null&&d.submitted(),"synthetic native-unit glyph submitted");
                byte[] visible=ScreenUtils.getFrameBufferPixels(0,0,512,512,false);check(bright(visible,512,d)>8,"unoccluded GPU glyph visible");
                Gl.depthMask(true);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);Gl.disable(Gl.blend);shader.bind();
                // A small opaque patch covers the BODY ray only. Its bounds do not cover the marker above it.
                quad(mesh,shader,-.025f,-.025f,.025f,.025f,-.5f);
                byte[] bodyBefore=ScreenUtils.getFrameBufferPixels(0,0,512,512,false);markers.render(camera,altitudes,scene,true);byte[] bodyAfter=ScreenUtils.getFrameBufferPixels(0,0,512,512,false);
                check(Arrays.equals(bodyBefore,bodyAfter),"opaque body-ray blocker suppresses entire above-wall glyph");
                Gl.depthMask(true);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);Gl.disable(Gl.blend);shader.bind();
                float x=d.screenX()/256-1,y=d.screenY()/256-1;quad(mesh,shader,x-.15f,y+.006f,x+.15f,y+.15f,-.5f);
                byte[] glyphBefore=ScreenUtils.getFrameBufferPixels(0,0,512,512,false);markers.render(camera,altitudes,scene,true);byte[] glyphAfter=ScreenUtils.getFrameBufferPixels(0,0,512,512,false);
                check(changed(glyphBefore,glyphAfter,d,512,512)>0&&changed(glyphBefore,glyphAfter,d,512,512)<bright(visible,512,d),"ordinary opaque depth clips glyph pixels with body ray clear");
            }finally{scene.end();}
        }finally{markers.close();scene.dispose();shader.dispose();mesh.dispose();}
    }
    private static void quad(Mesh mesh,Shader shader,float x0,float y0,float x1,float y1,float z){mesh.setVertices(new float[]{x0,y0,z,x1,y0,z,x1,y1,z,x1,y1,z,x0,y1,z,x0,y0,z});mesh.render(shader,Gl.triangles);}
    private static Unit spawn(UnitType type,Team team,double x,double y,double z,Map<Unit,Unit3DState> states){Unit unit=type.create(team);unit.set((float)(x*8),(float)(y*8));unit.add();set(unit,x,y,z,states);return unit;}
    private static void set(Unit u,double x,double y,double z,Map<Unit,Unit3DState> states){u.set((float)(x*8),(float)(y*8));states.put(u,new Unit3DState(u.id,u.type.id,u.team.id,x,y,z,0,0,0,90,0,0,z,Math.max(.2,u.hitSize/16),.12,true));}
    private static Camera3D camera(){Camera3D c=new Camera3D();c.resize(Core.graphics.getWidth(),Core.graphics.getHeight());c.fov=60;c.near=.04f;c.far=500;return c;}
    private static void look(Camera3D c,float x,float y,float z,float tx,float ty,float tz){c.up.set(0,1,0);c.position.set(x,y,z);c.lookAt(tx,ty,tz);c.update();}
    private static byte[] world(FpvWorldRenderer r,Camera3D c,boolean on,String filename){Core.settings.put(UnitThreatMarkerRenderer.SETTING,on);warm(r,c);r.render(c);byte[] result=ScreenUtils.getFrameBufferPixels(0,0,Core.graphics.getWidth(),Core.graphics.getHeight(),false);if(filename!=null)ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(filename));return result;}
    /** A synchronous smoke hook cannot wait for new app frames. Reset ONLY this private fixture
     * renderer's cache budget between prewarm passes; geometry, poses, shaders and final draws are native.
     * Final ON/OFF captures therefore cannot count a newly-built unit mesh as a marker pixel. */
    private static void warm(FpvWorldRenderer renderer,Camera3D camera){try{
        var field=FpvWorldRenderer.class.getDeclaredField("extrusion");field.setAccessible(true);Object extrusion=field.get(renderer);
        var budget=SpriteExtrusionRenderer.class.getDeclaredField("frameBuilds");budget.setAccessible(true);
        for(int i=0;i<128;i++){
            budget.setInt(extrusion,0);var before=renderer.extrusionStats();renderer.render(camera);var after=renderer.extrusionStats();
            if(after.builds()==before.builds()&&after.fallbacks()==before.fallbacks())return;
        }
        throw new IllegalStateException("fixture extrusion cache failed to settle");
    }catch(ReflectiveOperationException e){throw new IllegalStateException("fixture-only cache prewarm",e);}}
    private static int teamPixels(byte[] a,byte[] b,UnitThreatMarkerRenderer.Diagnostic d){
        int w=Core.graphics.getWidth(),h=Core.graphics.getHeight(),n=0;int r=Math.round(d.r()*255),g=Math.round(d.g()*255),blue=Math.round(d.b()*255);
        for(int y=Math.max(0,(int)(d.screenY()-18));y<Math.min(h,d.screenY()+18);y++)for(int x=Math.max(0,(int)(d.screenX()-18));x<Math.min(w,d.screenX()+18);x++){
            int p=(x+y*w)*4;
            if(Math.abs((b[p]&255)-r)<=10&&Math.abs((b[p+1]&255)-g)<=10&&Math.abs((b[p+2]&255)-blue)<=10&&Math.abs((a[p]&255)-(b[p]&255))+Math.abs((a[p+1]&255)-(b[p+1]&255))+Math.abs((a[p+2]&255)-(b[p+2]&255))>12)n++;
        }return n;
    }
    private static int changed(byte[] a,byte[] b,UnitThreatMarkerRenderer.Diagnostic d){return changed(a,b,d,Core.graphics.getWidth(),Core.graphics.getHeight());}
    private static int changed(byte[] a,byte[] b,UnitThreatMarkerRenderer.Diagnostic d,int w,int h){if(d==null||!d.submitted())return 0;int n=0;for(int y=Math.max(0,(int)(d.screenY()-18));y<Math.min(h,d.screenY()+18);y++)for(int x=Math.max(0,(int)(d.screenX()-18));x<Math.min(w,d.screenX()+18);x++){int p=(x+y*w)*4;if(Math.abs((a[p]&255)-(b[p]&255))+Math.abs((a[p+1]&255)-(b[p+1]&255))+Math.abs((a[p+2]&255)-(b[p+2]&255))>12)n++;}return n;}
    private static int whiteChanged(byte[] a,byte[] b,UnitThreatMarkerRenderer.Diagnostic d){int w=Core.graphics.getWidth(),h=Core.graphics.getHeight(),n=0;for(int y=Math.max(0,(int)(d.screenY()-18));y<Math.min(h,d.screenY()+18);y++)for(int x=Math.max(0,(int)(d.screenX()-18));x<Math.min(w,d.screenX()+18);x++){int p=(x+y*w)*4;if((b[p]&255)>220&&(b[p+1]&255)>220&&(b[p+2]&255)>220&&((a[p]&255)<180||(a[p+1]&255)<180||(a[p+2]&255)<180))n++;}return n;}
    private static int bright(byte[] p,int w,UnitThreatMarkerRenderer.Diagnostic d){int n=0;for(int y=Math.max(0,(int)(d.screenY()-18));y<Math.min(w,d.screenY()+18);y++)for(int x=Math.max(0,(int)(d.screenX()-18));x<Math.min(w,d.screenX()+18);x++){int i=(x+y*w)*4;if((p[i]&255)+(p[i+1]&255)+(p[i+2]&255)>70)n++;}return n;}
    private static long fingerprint(){long h=17;for(Tile t:Vars.world.tiles)h=h*31+t.blockID()*71L+t.floorID()*17L+t.overlayID();return h;}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException("Threat marker visual: "+message);assertions++;}
}
