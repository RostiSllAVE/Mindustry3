package fpv.net;

import fpv.generated.RenderConfig;
import fpv.terrain.TerrainElevation;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;

/** CPU-only physical heights shared by dedicated server and renderer; no atlas or GL access. */
public final class HeadlessGeometry {
    private HeadlessGeometry(){}
    public static double surface(TerrainElevation terrain,double x,double z){
        var tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(-z+.5));if(tile==null)return 0;
        if(terrain.naturalWall(tile))return terrain.heightAt(x,z);
        return tile.build!=null||tile.block().solid?heightFor(tile.block()):0;
    }
    public static float heightFor(Block block){
        if(block==Blocks.air)return 0;
        // Z0 retains the first-storey foundation socket. Upper walls use LayerCatalog's
        // whole-storey heights; both rendering and collision consume these same bounds.
        if(block instanceof mindustry.world.blocks.defense.Wall)return (float)fpv.construction.LayerCoordinates.DEFAULT.spacing();
        // Mine/trap capability, not drills or ore: rendering and every headless XYZ query share this slab.
        if(block instanceof mindustry.world.blocks.defense.ShockMine)return (float)(RenderConfig.FLAT_HEIGHT_BASE+Math.max(0,block.size-1)*RenderConfig.FLAT_HEIGHT_PER_SIZE);
        if(!block.update&&block.solid)return (float)(RenderConfig.WALL_HEIGHT_BASE+(block.size-1)*RenderConfig.WALL_HEIGHT_PER_SIZE);
        String category=block.category==null?"":block.category.name();
        if(category.equals("distribution")||category.equals("liquid"))return (float)(RenderConfig.FLAT_HEIGHT_BASE+Math.max(0,block.size-1)*RenderConfig.FLAT_HEIGHT_PER_SIZE);
        if(category.equals("turret"))return (float)(RenderConfig.TURRET_HEIGHT_BASE+block.size*RenderConfig.TURRET_HEIGHT_PER_SIZE);
        if(category.equals("power"))return (float)(RenderConfig.POWER_HEIGHT_BASE+block.size*RenderConfig.POWER_HEIGHT_PER_SIZE);
        return (float)Math.min(RenderConfig.MAX_BUILD_HEIGHT,RenderConfig.GENERIC_HEIGHT_BASE+block.size*RenderConfig.GENERIC_HEIGHT_PER_SIZE);
    }
}
