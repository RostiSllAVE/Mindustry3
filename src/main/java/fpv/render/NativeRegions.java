package fpv.render;

import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import mindustry.Vars;
import mindustry.world.*;
import mindustry.world.blocks.TileBitmask;
import mindustry.world.blocks.environment.*;
import java.lang.reflect.Field;
import java.util.*;

/** Read-only, version-pinned access to native variant/autotile selection. Missing data falls back visibly. */
final class NativeRegions {
    private static final Map<String,Field> fields=new HashMap<>();
    static TextureRegion floor(Floor floor,Tile tile){
        if(floor.tilingVariants>0){
            TextureRegion[][][] all=(TextureRegion[][][])read(floor,Floor.class,"tilingRegions");
            if(all!=null&&all.length>0&&all[0].length>0){
                int size=all[0].length;
                int variant=Mathf.randomSeed(Point2.pack(tile.x/size,tile.y/size),0,all.length-1);
                return all[variant][tile.x%size][size-1-tile.y%size];
            }
        }
        if(floor.autotile){
            int bits=0;
            for(int i=0;i<8;i++)if(floor.checkAutotileSame(tile,tile.nearby(Geometry.d8[i])))bits|=1<<i;
            int bit=TileBitmask.values[bits];
            TextureRegion[] regions=(TextureRegion[])read(floor,Floor.class,"autotileRegions");
            if(floor.autotileVariants>1){
                TextureRegion[][] variants=(TextureRegion[][])read(floor,Floor.class,"autotileVariantRegions");
                if(variants!=null)regions=variants[floor.variant(tile.x,tile.y,variants.length)];
            }
            TextureRegion[] mid=(TextureRegion[])read(floor,Floor.class,"autotileMidRegions");
            if(bit==13&&floor.autotileMidVariants>1&&mid!=null)return mid[floor.variant(tile.x,tile.y,mid.length)];
            if(regions!=null&&bit<regions.length)return regions[bit];
        }
        TextureRegion[] regions=floor.variantRegions;
        return regions!=null&&regions.length>0?regions[floor.variant(tile.x,tile.y,regions.length)]:floor.region;
    }
    static TextureRegion wall(Block block,Tile tile){
        if(block instanceof StaticWall wall&&wall.autotile){
            int bits=0;
            for(int i=0;i<8;i++)if(wall.checkAutotileSame(tile,tile.nearby(Geometry.d8[i])))bits|=1<<i;
            int bit=TileBitmask.values[bits];
            TextureRegion[] regions=(TextureRegion[])read(wall,StaticWall.class,"autotileRegions");
            TextureRegion[] mid=(TextureRegion[])read(wall,StaticWall.class,"autotileMidRegions");
            if(bit==13&&wall.autotileMidVariants>1&&mid!=null)return mid[wall.variant(tile.x,tile.y,mid.length)];
            if(regions!=null&&bit<regions.length)return regions[bit];
        }
        if(block instanceof StaticWall wall && !wall.autotile && NativeAtlasMesh.valid(wall.large)
            && wall.split != null && wall.split.length >= 2 && wall.split[0].length >= 2 && wall.split[1].length >= 2){
            int rx=tile.x/2*2, ry=tile.y/2*2;
            // Match StaticWall.drawBase's native 2x2 selection without invoking its draw callback.
            if(rx<Vars.world.width()-1 && ry<Vars.world.height()-1
                && Vars.world.tile(rx,ry).block()==block && Vars.world.tile(rx+1,ry).block()==block
                && Vars.world.tile(rx,ry+1).block()==block && Vars.world.tile(rx+1,ry+1).block()==block
                && Mathf.randomSeed(Point2.pack(rx,ry))<.5f)
                return wall.split[tile.x%2][1-tile.y%2];
        }
        TextureRegion[] variants=block.variantRegions;
        return variants!=null&&variants.length>0?variants[Mathf.randomSeed(tile.pos(),0,variants.length-1)]:block.region;
    }
    static TextureRegion block(Block block){
        return NativeAtlasMesh.valid(block.region)?block.region:block.fullIcon;
    }
    static TextureRegion turret(Block block){
        if(NativeAtlasMesh.valid(block.region))return block.region;
        if(block instanceof mindustry.world.blocks.defense.turrets.Turret t&&t.drawer instanceof mindustry.world.draw.DrawTurret drawer&&NativeAtlasMesh.valid(drawer.preview))return drawer.preview;
        return block.fullIcon;
    }
    private static Object read(Object object,Class<?> type,String name){
        try{
            String key=type.getName()+"#"+name;
            Field field=fields.get(key);
            if(field==null){field=type.getDeclaredField(name);field.setAccessible(true);fields.put(key,field);}
            return field.get(object);
        }catch(ReflectiveOperationException|RuntimeException ignored){return null;}
    }
}
