package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;

import java.util.ArrayList;

/** Native connection lifecycle plus actual GPU span submission, only in the isolated lab. */
public final class NativeBridgeVisibilitySmokeChecks {
    private NativeBridgeVisibilitySmokeChecks() {}
    public static String verify(FpvWorldRenderer renderer){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"loaded isolated lab required");
        Tile site=findSite();require(site!=null,"empty 18 by 14 bridge fixture area");
        Block[] types={Blocks.itemBridge,Blocks.bridgeConduit,Blocks.phaseConveyor,Blocks.phaseConduit,
            Blocks.ductBridge,Blocks.reinforcedBridgeConduit};
        var owned=new ArrayList<Building>();
        Building[] sources=new Building[types.length],targets=new Building[types.length];
        Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());
        camera.position.set(site.x+8.5f,38,-site.y-6.5f);camera.direction.set(0,-1,0);camera.up.set(0,0,-1);
        camera.near=.05f;camera.far=1000;camera.update();
        float delta=Time.delta;
        int checks=0;
        try{
            for(int i=0;i<types.length;i++){
                int x=site.x+2+(i%2)*8,y=site.y+2+(i/2)*4;
                Tile source=Vars.world.tile(x,y),target=Vars.world.tile(x+4,y);
                source.setBlock(types[i],Vars.player.team(),0);owned.add(source.build);sources[i]=source.build;
                target.setBlock(types[i],Vars.player.team(),0);owned.add(target.build);targets[i]=target.build;
                if(source.build instanceof ItemBridge.ItemBridgeBuild)source.build.configureAny(target.pos());
                var link=new NativeBridgeLinks.Link();
                require(NativeBridgeLinks.resolve(source.build,link)&&link.target==target.build&&link.physicalSpan(),"native valid link: "+types[i].name);
            }
            checks+=submit(renderer,camera,sources,"idle");
            for(Building building:owned){building.enabled=false;building.efficiency=0;}
            checks+=submit(renderer,camera,sources,"disabled/unpowered");
            Time.delta=61;
            for(Building building:owned){building.noSleep();building.sleep();require(!building.isAdded(),"native sleep fixture");}
            Time.delta=delta;
            checks+=submit(renderer,camera,sources,"native-sleep");
            for(Building building:owned)require(!building.isAdded(),"span rendering cannot wake simulation");
            for(Building building:owned)building.noSleep();

            sources[0].configureAny(-1);sources[4].rotation=1;
            renderer.render(camera);
            require(!renderer.wasBridgeSubmitted(sources[0]),"cleared native item link removes span");
            require(!renderer.wasBridgeSubmitted(sources[4]),"rotated native direction removes old span");
            sources[0].configureAny(targets[0].pos());sources[4].rotation=0;
            checks+=submit(renderer,camera,sources,"relink");

            // A narrow camera sees the middle of a real link while neither endpoint's box is visible.
            camera.position.set((sources[0].x+targets[0].x)/(2*Vars.tilesize),3,-sources[0].y/Vars.tilesize);
            camera.fov=12;camera.far=8;camera.update();renderer.render(camera);
            require(renderer.wasBridgeSubmitted(sources[0]),"middle of span survives independent frustum culling");
            require(!renderer.wasBuildingSubmitted(sources[0])&&!renderer.wasBuildingSubmitted(targets[0]),"both endpoint boxes lie outside narrow view");

            camera.position.set(site.x+19,17,-site.y+9);camera.up.set(0,1,0);
            camera.lookAt(site.x+8,0,-site.y-6);camera.fov=48;camera.far=1000;camera.update();
            renderer.render(camera);
            for(Building source:sources)require(renderer.wasBridgeSubmitted(source),"oblique capture includes every bridge family");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("bridges.png"));
            targets[1].tile.setBlock(Blocks.air);renderer.render(camera);
            require(!renderer.wasBridgeSubmitted(sources[1]),"removed liquid endpoint cannot leave stale span");
            return "PASS: six native bridge families, "+checks+" idle/disabled/sleep/relink submissions, endpoint-outside-frustum span, disconnect/rotation/removal";
        }finally{
            Time.delta=delta;
            for(Building building:owned)if(building!=null&&building.tile.build==building){building.noSleep();building.tile.setBlock(Blocks.air);}
            renderer.invalidate();
        }
    }
    private static int submit(FpvWorldRenderer renderer,Camera3D camera,Building[] sources,String phase){
        renderer.render(camera);
        for(Building building:sources)require(renderer.wasBridgeSubmitted(building),phase+" GPU span: "+building.block.name);
        return sources.length;
    }
    private static Tile findSite(){
        for(int y=1;y+14<Vars.world.height();y++)for(int x=1;x+18<Vars.world.width();x++){
            boolean empty=true;
            scan:for(int dy=0;dy<14;dy++)for(int dx=0;dx<18;dx++){
                Tile tile=Vars.world.tile(x+dx,y+dy);
                if(tile==null||tile.build!=null||tile.block()!=Blocks.air){empty=false;break scan;}
            }
            if(empty)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Native bridge smoke: "+message);}
}
