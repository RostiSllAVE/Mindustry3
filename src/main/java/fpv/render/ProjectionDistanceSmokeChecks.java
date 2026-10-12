package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import fpv.defense.ProjectedWallSystem;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Tile;

/** Actual submission/cache/gameplay separation, guarded to an isolated native SDL fixture. */
public final class ProjectionDistanceSmokeChecks {
    private ProjectionDistanceSmokeChecks(){}
    public static String verify(ProjectedWallSystem walls,FpvWorldRenderer renderer){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame()&&!Vars.net.active(),"isolated offline world");
        Tile site=null;for(Tile t:Vars.world.tiles)if(t.x>4&&t.y>4&&t.x<Vars.world.width()-5&&t.y<Vars.world.height()-5&&t.block()==Blocks.air&&!t.floor().solid&&!t.floor().isDeep()){site=t;break;}
        require(site!=null,"native empty wall fixture tile");int oldDistance=Core.settings.getInt(ProjectionViewSettings.DISTANCE,100),oldFade=Core.settings.getInt(ProjectionViewSettings.FADE_START,80);
        site.setBlock(Blocks.copperWall,Vars.player.team(),0);var build=site.build;
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.far=5000;camera.fov=80;
        try{
            walls.update(0);var v=walls.volume(build);require(v!=null,"fixture projection exists");
            Core.settings.put(ProjectionViewSettings.DISTANCE,2000);Core.settings.put(ProjectionViewSettings.FADE_START,1600);
            double size=Math.max(Vars.world.width(),Vars.world.height());camera.position.set(Vars.world.width()*.5f,(float)(size*2+v.topZ()),-Vars.world.height()*.5f);camera.up.set(0,0,-1);
            camera.lookAt(Vars.world.width()*.5f,0,-Vars.world.height()*.5f);camera.update();renderer.render(camera);
            long cache=renderer.projectedCacheBuildCount();Core.settings.put(ProjectionViewSettings.DISTANCE,100);Core.settings.put(ProjectionViewSettings.FADE_START,80);
            view(camera,v.x(),v.y()-20,(v.baseZ()+v.topZ())*.5,v.x(),v.y(),v.baseZ());renderer.render(camera);require(renderer.wasProjectionSubmitted(build),"near projection submitted");capture("projection-distance-near.png");
            view(camera,v.x(),v.y()-90,(v.baseZ()+v.topZ())*.5,v.x(),v.y(),v.baseZ());renderer.render(camera);require(renderer.wasProjectionSubmitted(build),"fade-band projection submitted");capture("projection-distance-fade.png");
            view(camera,v.x(),v.y()-102,(v.baseZ()+v.topZ())*.5,v.x(),v.y(),v.baseZ());renderer.render(camera);require(!renderer.wasProjectionSubmitted(build),"beyond100 whole wall not submitted");
            view(camera,v.x(),v.y(),v.topZ()+101,v.x(),v.y(),v.baseZ());renderer.render(camera);require(!renderer.wasProjectionSubmitted(build),"true camera altitude excludes same-XY wall");capture("projection-distance-above.png");
            view(camera,v.x(),v.y()-20,v.topZ()+1,v.x(),v.y()-50,v.topZ()+1);renderer.render(camera);require(!renderer.wasProjectionSubmitted(build),"outside camera frustum not submitted");
            require(renderer.projectedCacheBuildCount()==cache,"camera/distance changes reuse all prewarmed wall meshes");
            Team enemy=build.team==Team.crux?Team.sharded:Team.crux;float hp=v.hp();
            var contact=walls.trace(enemy,v.x(),v.y(),v.topZ()+1,v.x(),v.y(),v.baseZ()+.01,0);
            require(contact!=null&&walls.hit(contact,null,1)&&walls.volume(build).hp()==hp-1,"invisible projection retains authoritative projectile collision and HP");
            require(walls.trace(build.team,v.x(),v.y(),v.topZ()+1,v.x(),v.y(),v.baseZ()+.01,0)==null,"invisible projection retains team pass");
            require(build.health==build.maxHealth,"presentation does not damage native base");
            return "PASS: near/fade/beyond100/camera-Z/frustum actual submissions; camera moves/settings reuse prewarmed cache; invisible wall still intercepts enemy shots with independent HP/team rules";
        }finally{Core.settings.put(ProjectionViewSettings.DISTANCE,oldDistance);Core.settings.put(ProjectionViewSettings.FADE_START,oldFade);if(site.build==build)site.setBlock(Blocks.air);walls.update(0);renderer.invalidate();}
    }
    private static void view(Camera3D c,double x,double y,double z,double tx,double ty,double tz){c.position.set((float)x,(float)z,(float)-y);c.up.set(0,1,0);if(Math.abs(x-tx)+Math.abs(y-ty)<.01)c.up.set(0,0,-1);c.lookAt((float)tx,(float)tz,(float)-ty);c.update();}
    private static void capture(String name){arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Projection distance smoke: "+message);}
}
