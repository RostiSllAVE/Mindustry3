package fpv.defense;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import fpv.render.FpvWorldRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Door;

/** Native regression for the optimized broadphase and local retained geometry, isolated smoke only. */
public final class ProjectedWallOptimizationChecks {
    private ProjectedWallOptimizationChecks(){}
    public static String verify(ProjectedWallSystem walls,FpvWorldRenderer renderer){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated loaded smoke world required");
        int compared=compareQueries(walls,2048);
        Tile site=findSite();require(site!=null,"empty 46x9 area for adjacent-wall cache fixture");
        ArrayList<Building> owned=new ArrayList<>();
        try{
            for(int i=0;i<40;i++)owned.add(place(site.x+2+i,site.y+3,Blocks.copperWall));
            owned.add(place(site.x+3,site.y+6,Blocks.door));walls.update(0);
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.fov=70;
            camera.position.set(site.x+22,20,-site.y+24);camera.up.set(0,1,0);camera.lookAt(site.x+22,4,-site.y-3);camera.far=1000;camera.update();
            renderer.render(camera);long built=renderer.projectedCacheBuildCount();require(built>0&&renderer.projectedCacheBytes()>0,"retained chunk surface data built");
            renderer.render(camera);require(renderer.projectedCacheBuildCount()==built,"unchanged visible chunks do not rebuild");
            Building target=owned.get(20);var before=walls.volume(target);float health=target.health;
            var contact=walls.trace(Team.crux,before.x(),before.y(),before.topZ()+10,before.x(),before.y(),before.baseZ()+.1,0);
            require(contact!=null&&walls.hit(contact,null,before.maxHp()*.25f),"merged surface maps to correct logical wall token");
            require(walls.volume(target).hp()<before.hp()&&target.health==health,"own pool shrinks without changing native health");
            for(int i=0;i<40;i++)if(i!=20)require(walls.volume(owned.get(i)).hp()==walls.volume(owned.get(i)).maxHp(),"neighbor pools remain independent");
            renderer.render(camera);long rebuilt=renderer.projectedCacheBuildCount()-built;
            require(rebuilt>0&&rebuilt<=9,"one pool edit rebuilds only local/adjacent cached chunks: "+rebuilt);
            built=renderer.projectedCacheBuildCount();renderer.render(camera);require(renderer.projectedCacheBuildCount()==built,"damaged but unchanged geometry stays cached");
            var door=(Door.DoorBuild)owned.get(40);door.open=true;walls.update(0);renderer.render(camera);require(!renderer.wasProjectionSubmitted(door),"door opening invalidates retained surface without tile event");
            door.open=false;walls.update(0);renderer.render(camera);require(renderer.wasProjectionSubmitted(door),"door closing restores retained surface");
            Building changeTeam=owned.get(7);Team original=changeTeam.team;changeTeam.team=Team.crux;walls.update(0);renderer.render(camera);
            var changed=walls.volume(changeTeam);require(changed!=null&&changed.building().team==Team.crux,"team change refreshes native anchor state");
            require(walls.trace(Team.crux,changed.x(),changed.y(),changed.topZ()+4,changed.x(),changed.y(),changed.baseZ()+.1,0)==null,"new owning team passes through changed projection");
            changeTeam.team=original;walls.update(0);
            compared+=compareQueries(walls,512);
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("walls-merged-cache.png"));
            return "PASS: "+compared+" optimized XYZ queries equal exhaustive per-wall slabs; forty adjacent walls retain independent pools; steady frames reuse surfaces; one damaged pool rebuilds "+rebuilt+" local chunks; door/team transitions invalidate correctly";
        }finally{
            for(Building b:owned)if(b.tile!=null&&b.tile.build==b){b.noSleep();b.tile.setBlock(Blocks.air);}walls.update(0);renderer.invalidate();
        }
    }
    /** Includes long diagonals, short shots, grazing grid corners and large-radius queries. */
    public static int compareQueries(ProjectedWallSystem walls,int count){
        List<ProjectedWallSystem.Volume> volumes=walls.volumes();SplittableRandom random=new SplittableRandom(0x57414c4c394cL);
        double width=Vars.world.width(),height=Vars.world.height();
        for(int i=0;i<count;i++){
            double ax=random.nextDouble(-32,width+32),ay=random.nextDouble(-32,height+32),az=random.nextDouble(0,18);
            double bx=random.nextDouble(-32,width+32),by=random.nextDouble(-32,height+32),bz=random.nextDouble(0,18),radius=(i%11==0?20:i%5==0?2.5:i%3==0?.12:0);
            if(i%4==0){bx=ax+random.nextDouble(-8,8);by=ay+random.nextDouble(-8,8);bz=az+random.nextDouble(-2,2);}
            if(i%13==0){ax=Math.floor(ax/16)*16;ay=Math.floor(ay/16)*16;bx=ax+64;by=ay+64;az=bz=4;radius=0;}
            Team team=i%2==0?Team.crux:Team.sharded;double expected=Double.POSITIVE_INFINITY;
            for(int j=0;j<volumes.size();j++){
                var v=volumes.get(j);Building b=v.building();if(v.hp()<=0||b.team==team||!ProjectedWallSystem.eligible(b)||(!b.block.solid&&!b.checkSolid()))continue;
                double f=ProjectionMath.sweep(ax,ay,az,bx,by,bz,v.x()-v.halfWidth(),v.y()-v.halfWidth(),v.baseZ(),v.x()+v.halfWidth(),v.y()+v.halfWidth(),v.topZ(),radius);
                if(f>=0)expected=Math.min(expected,f);
            }
            var result=walls.trace(team,ax,ay,az,bx,by,bz,radius);double actual=result==null?Double.POSITIVE_INFINITY:result.fraction();
            require(actual==expected||Math.abs(actual-expected)<1e-8,"query "+i+" equals exhaustive lookup (expected "+expected+", actual "+actual+")");
        }
        return count;
    }
    private static Building place(int x,int y,mindustry.world.Block block){Tile tile=Vars.world.tile(x,y);tile.setBlock(block,Vars.player.team(),0);return tile.build;}
    private static Tile findSite(){for(int y=2;y+9<Vars.world.height();y++)for(int x=2;x+46<Vars.world.width();x++){
        boolean empty=true;scan:for(int dy=0;dy<9;dy++)for(int dx=0;dx<46;dx++){Tile t=Vars.world.tile(x+dx,y+dy);if(t==null||t.build!=null||t.block()!=Blocks.air||t.floor().solid){empty=false;break scan;}}
        if(empty)return Vars.world.tile(x,y);
    }return null;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Projected wall optimization smoke: "+message);}
}
