package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.net.HeadlessGeometry;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;
import java.util.ArrayList;

/** Isolated lab evidence that larger vegetation is presentation-only. */
public final class VegetationVisualChecks {
    private VegetationVisualChecks(){}
    public static String run(FpvWorldRenderer renderer,World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.world.width()==128,"dedicated generated lab only");
        Block[] blocks={Blocks.pine,Blocks.shrubs,Blocks.boulder,Blocks.conveyor};
        float[] heights=new float[blocks.length];boolean[] solid=new boolean[blocks.length];
        for(int i=0;i<blocks.length;i++){heights[i]=HeadlessGeometry.heightFor(blocks[i]);solid[i]=blocks[i].solid;}
        ArrayList<Tile> owned=new ArrayList<>();
        try{
            for(int i=0;i<blocks.length;i++){
                Tile tile=Vars.world.tile(76+i*10,96);require(tile!=null&&tile.block()==Blocks.air,"owned empty vegetation fixture tile");
                tile.setBlock(blocks[i],Vars.player.team(),0);owned.add(tile);
                require(VegetationVisualScale.scale(blocks[i])==(i<2?5:1),"only native tree and bush visual scale changes");
            }
            require(VegetationVisualScale.scale(Blocks.stoneWall)==1&&VegetationVisualScale.scale(Blocks.mechanicalDrill)==1,"terrain and buildings retain visual dimensions");
            renderer.invalidate();renderer.terrain().invalidate();
            for(int i=0;i<blocks.length;i++){
                Tile tile=owned.get(i);double physical=tile.build!=null||solid[i]?heights[i]:0;
                require(Math.abs(world.surfaceHeight(tile.x,tile.y)-physical)<1e-7,"CPU physical surface remains original for "+blocks[i].name);
                require(HeadlessGeometry.heightFor(blocks[i])==heights[i]&&blocks[i].solid==solid[i],"native height/solidity unchanged");
            }
            Tile pine=owned.get(0);double height=heights[0];
            require(world.traceSegment(pine.x-2,pine.y,height*.5,pine.x+2,pine.y,height*.5,0).hit(),"original low tree collision remains");
            require(!world.traceSegment(pine.x-2,pine.y,height+.2,pine.x+2,pine.y,height+.2,0).hit(),"enlarged visual canopy does not enlarge collision");
            long fingerprint=fingerprint();Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.04f;camera.far=300;camera.fov=55;
            camera.up.set(0,1,0);camera.position.set(91,14,-69);camera.lookAt(91,2,-96);camera.update();renderer.render(camera);renderer.render(camera);capture("vegetation-5x-overview.png");
            camera.up.set(0,1,0);camera.position.set(81,3,-85);camera.lookAt(81,3,-96);camera.update();renderer.render(camera);capture("vegetation-5x-low-angle.png");
            long baked=renderer.organicPropSubmissions();renderer.render(camera);
            require(baked==renderer.organicPropSubmissions(),"enlarged vegetation still reuses cached chunks");
            require(fingerprint()==fingerprint,"render does not change native tiles");require(Gl.getError()==0,"vegetation GPU pass has no GL errors");
            return "PASS: native tree/bush5x visual XYZ; rock/conveyor/building/wall dimensions unchanged; original physical height/solidity and low/high collision traces; base-anchored cached geometry; two GPU captures";
        }finally{for(Tile tile:owned)tile.setBlock(Blocks.air);renderer.invalidate();renderer.terrain().invalidate();}
    }
    private static long fingerprint(){long value=17;for(Tile tile:Vars.world.tiles)value=value*31+tile.blockID()*71L+tile.floorID()*17L+tile.overlayID();return value;}
    private static void capture(String name){ScreenUtils.saveScreenshot(new Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Vegetation visual smoke: "+message);}
}
