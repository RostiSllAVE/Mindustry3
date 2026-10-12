package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.net.HeadlessGeometry;
import fpv.world.NaturalProps;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.ShockMine;
import java.util.ArrayList;

/** Read-only rendering verification on explicitly placed/removed fixtures in the isolated generated lab. */
public final class MaintenanceVisualChecks {
    private MaintenanceVisualChecks(){}
    public static String run(FpvWorldRenderer renderer,World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.world.width()==128,"dedicated generated lab only");
        ArrayList<Tile> owned=new ArrayList<>();
        Block[] props={Blocks.pine,Blocks.sporePine,Blocks.whiteTree,Blocks.whiteTreeDead,Blocks.shrubs,Blocks.sporeCluster,Blocks.boulder,Blocks.snowBoulder};
        float mineDamage=((ShockMine)Blocks.shockMine).damage;
        try{
            require(NaturalProps.kind(Blocks.stoneWall)==null&&NaturalProps.kind(Blocks.iceWall)==null,"ordinary natural StaticWall must remain mountain terrain, despite inheriting Prop");
            for(int i=0;i<props.length;i++){
                Tile tile=place(owned,80+i*3,96,props[i]);
                require(NaturalProps.supported(props[i]),"actual native decoration classified: "+props[i].name);
                require(!renderer.terrain().naturalWall(tile),"decoration not inflated into mountain: "+props[i].name);
            }
            Tile wall=place(owned,105,96,Blocks.stoneWall);
            require(renderer.terrain().naturalWall(wall),"actual native stone wall still enters elevation source mask");
            Tile mine=place(owned,88,90,Blocks.shockMine),conveyor=place(owned,91,90,Blocks.conveyor);
            double height=HeadlessGeometry.heightFor(Blocks.shockMine);
            require(Math.abs(height-HeadlessGeometry.heightFor(Blocks.conveyor))<1e-7,"generic ShockMine height equals conveyor slab");
            require(height<HeadlessGeometry.heightFor(Blocks.mechanicalDrill),"mine capability does not flatten drills");
            renderer.invalidate();renderer.terrain().invalidate();
            require(Math.abs(world.surfaceHeight(mine.x,mine.y)-height)<1e-7&&Math.abs(renderer.surfaceHeight(mine.x,-mine.y)-height)<1e-7,"CPU and GPU surface queries agree on mine height");
            require(world.traceSegment(86,90,height*.5,90,90,height*.5,0).hit(),"low physical segment hits mine slab");
            require(!world.traceSegment(86,90,height+.04,90,90,height+.04,0).hit(),"segment above mine is no longer blocked by obsolete tall hitbox");
            long fingerprint=fingerprint();Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=50;camera.near=.04f;camera.far=400;
            camera.up.set(0,1,0);camera.position.set(92,9,-80);camera.lookAt(92,.7f,-96);camera.update();
            long before=renderer.organicPropSubmissions();renderer.render(camera);renderer.render(camera);
            require(renderer.organicPropSubmissions()>=before+props.length,"every native decoration goes through cached organic geometry");
            capture("native-organic-props-overview.png");
            camera.up.set(0,1,0);camera.position.set(91,2.4f,-87);camera.lookAt(91,.65f,-96);camera.update();renderer.render(camera);capture("native-organic-props-side.png");
            camera.up.set(0,1,0);camera.position.set(86,1.8f,-92);camera.lookAt(86,.85f,-96);camera.update();renderer.render(camera);capture("native-white-tree-close.png");
            camera.up.set(0,1,0);camera.position.set(89.5f,1,-87);camera.lookAt(89.5f,.12f,-90);camera.update();renderer.render(camera);capture("native-mine-conveyor-height.png");
            long built=renderer.organicPropSubmissions();renderer.render(camera);
            require(renderer.organicPropSubmissions()==built,"stationary close view reuses organic chunk geometry");
            require(fingerprint()==fingerprint,"renderer leaves floor/block/overlay untouched");
            require(((ShockMine)Blocks.shockMine).damage==mineDamage&&mine.build!=null&&conveyor.build!=null,"native mine damage and native placement remain intact");
            require(Gl.getError()==0,"organic templates and Mech changes produce no GL errors");
            return "PASS: native trees/shrubs/rocks organic cached atlas geometry; stone/ice remain mountains; mine visual and CPU heights equal conveyor, low/high XYZ traces; immutable native map/damage; GPU overview/side/mine captures";
        }finally{
            for(Tile tile:owned)tile.setBlock(Blocks.air);
            renderer.invalidate();renderer.terrain().invalidate();
        }
    }
    private static Tile place(ArrayList<Tile> owned,int x,int y,Block block){
        Tile tile=Vars.world.tile(x,y);require(tile!=null&&tile.block()==Blocks.air,"owned empty native fixture tile");
        tile.setBlock(block,Vars.player.team(),0);owned.add(tile);return tile;
    }
    private static long fingerprint(){long value=17;for(Tile tile:Vars.world.tiles)value=value*31+tile.blockID()*71L+tile.floorID()*17L+tile.overlayID();return value;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Maintenance visual smoke: "+message);}
}
