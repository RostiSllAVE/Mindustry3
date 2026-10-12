package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.defense.ProjectedWallSystem;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;

/** Actual native wall art/cache/compositor captures and hidden-wall gameplay checks. */
public final class ProjectedWallViewChecks {
    private ProjectedWallViewChecks(){}
    public static String verify(ProjectedWallSystem walls,FpvWorldRenderer renderer){
        if(!Boolean.getBoolean("fpv.smoke")||!Vars.state.isGame())throw new IllegalStateException("Loaded isolated smoke world required");
        Tile site=findSite();require(site!=null,"empty native wall presentation area");
        var owned=new ArrayList<Building>();Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=800;camera.fov=64;
        Fi output=new Fi(System.getProperty("fpv.smoke.output",System.getProperty("fpv.wallProfile.output")));
        try{
            owned.add(place(site.x+8,site.y+8,Blocks.copperWall));
            owned.add(place(site.x+8,site.y+12,Blocks.thoriumWallLarge));
            owned.add(place(site.x+5,site.y+17,Blocks.surgeWallLarge));
            owned.add(place(site.x+10,site.y+17,Blocks.surgeWallLarge));
            walls.update(0);var front=walls.volume(owned.get(0));var rear=walls.volume(owned.get(1));
            double x=front.x(),y=front.y(),z=(front.baseZ()+front.topZ())*.48;
            pose(camera,x,y-7,z,x,y+8,z);renderer.render(camera);capture(output,"wall-nearest-front.png");
            require(renderer.wasProjectionSubmitted(owned.get(0))&&renderer.wasProjectionSubmitted(owned.get(1)),"front and hidden rear remain submitted for per-pixel GPU selection");
            Team enemy=owned.get(1).team==Team.crux?Team.sharded:Team.crux;
            double rz=(rear.baseZ()+rear.topZ())*.5;
            var hiddenHit=walls.trace(enemy,rear.x(),rear.y(),rear.topZ()+1,rear.x(),rear.y(),rear.baseZ()+.01,0);
            float frontHp=front.hp(),rearHp=rear.hp();require(hiddenHit!=null&&walls.hit(hiddenHit,null,1),"visually hidden rear projection still accepts hostile damage");
            require(walls.volume(owned.get(1)).hp()==rearHp-1&&walls.volume(owned.get(0)).hp()==frontHp,"hidden wall keeps independent logical health");
            require(walls.trace(owned.get(1).team,rear.x(),rear.y(),rear.topZ()+1,rear.x(),rear.y(),rear.baseZ()+.01,0)==null,"hidden rear still permits friendly fire");
            var frontHit=walls.trace(enemy,front.x(),front.y(),front.topZ()+1,front.x(),front.y(),front.baseZ()+.01,0);
            require(frontHit!=null&&walls.hit(frontHit,null,front.maxHp()),"front pool can collapse independently");
            renderer.render(camera);capture(output,"wall-nearest-front-collapsed-native.png");
            require(!renderer.wasProjectionSubmitted(owned.get(0))&&renderer.wasProjectionSubmitted(owned.get(1)),"front collapse reveals surviving rear projection");
            pose(camera,x+10,y+9,z,x,y+10,z);renderer.render(camera);capture(output,"wall-nearest-side.png");
            pose(camera,x,y+10,rear.topZ()+12,x,y+10,0);renderer.render(camera);capture(output,"wall-nearest-above.png");
            pose(camera,rear.x(),rear.y(),rz,rear.x(),rear.y()+5,rz);renderer.render(camera);capture(output,"wall-nearest-inside.png");
            require(renderer.wasProjectionSubmitted(owned.get(1)),"inside-volume view keeps back-facing nearest boundary available");
            require(owned.get(0).health==owned.get(0).maxHealth&&owned.get(1).health==owned.get(1).maxHealth,"visual/compositor fixtures preserve physical base health");
            return "PASS: native copper/thorium/surge walls front, collapsed-front, side, above and inside GPU captures; hidden rear projectile defense/friendly pass and independent pools preserved; base HP unchanged";
        }finally{
            for(Building b:owned)if(b.tile!=null&&b.tile.build==b){b.noSleep();b.tile.setBlock(Blocks.air);}walls.update(0);renderer.invalidate();
        }
    }
    private static void pose(Camera3D c,double x,double y,double z,double tx,double ty,double tz){c.position.set((float)x,(float)z,(float)-y);c.up.set(0,1,0);if(Math.abs(x-tx)+Math.abs(y-ty)<.01)c.up.set(0,0,-1);c.lookAt((float)tx,(float)tz,(float)-ty);c.update();}
    private static void capture(Fi output,String name){ScreenUtils.saveScreenshot(output.child(name));}
    private static Building place(int x,int y,mindustry.world.Block block){Tile t=Vars.world.tile(x,y);t.setBlock(block,Vars.player.team(),0);return t.build;}
    private static Tile findSite(){for(int y=2;y+24<Vars.world.height();y++)for(int x=2;x+18<Vars.world.width();x++){
        boolean empty=true;scan:for(int dy=0;dy<24;dy++)for(int dx=0;dx<18;dx++){Tile t=Vars.world.tile(x+dx,y+dy);if(t==null||t.build!=null||t.block()!=Blocks.air||t.floor().solid){empty=false;break scan;}}
        if(empty)return Vars.world.tile(x,y);
    }return null;}
    private static void require(boolean value,String text){if(!value)throw new IllegalStateException("Native wall visibility smoke: "+text);}
}
