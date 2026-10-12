package fpv.construction;

import arc.func.Cons;
import arc.func.Prov;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;

/** A layer-local native tile view. It never writes or resolves through Vars.world. */
public final class LayerTile extends Tile {
    @FunctionalInterface public interface Lookup { LayerTile tile(int x,int y,int layer); }
    public final int layer;
    private final Lookup lookup;

    public LayerTile(int x,int y,int layer,Lookup lookup){
        super(x,y);
        if(layer<1||lookup==null)throw new IllegalArgumentException("Upper layer and lookup required");
        this.layer=layer;this.lookup=lookup;
    }
    /** Owner-only view assignment. Native setBlock performs dangerous global footprint writes. */
    public void attach(Block value,Building entity){
        if(value==null)throw new IllegalArgumentException("Block required");
        block=value;build=entity;
    }
    public void clearView(){block=Blocks.air;build=null;}
    @Override public Tile nearby(int dx,int dy){return lookup.tile(x+dx,y+dy,layer);}
    @Override public Tile nearby(Point2 delta){return nearby(delta.x,delta.y);}
    @Override public Tile nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
    @Override public Building nearbyBuild(int rotation){Tile value=nearby(rotation);return value==null?null:value.build;}
    @Override public void getLinkedTiles(Cons<Tile> out){getLinkedTilesAs(block,out);}
    @Override public Seq<Tile> getLinkedTiles(Seq<Tile> out){out.clear();getLinkedTiles(out::add);return out;}
    @Override public Seq<Tile> getLinkedTilesAs(Block value,Seq<Tile> out){out.clear();getLinkedTilesAs(value,out::add);return out;}
    @Override public void getLinkedTilesAs(Block value,Cons<Tile> out){
        int anchorX=build==null?x:build.tile.x,anchorY=build==null?y:build.tile.y;
        int offset=-(value.size-1)/2;
        for(int dx=0;dx<value.size;dx++)for(int dy=0;dy<value.size;dy++){
            LayerTile other=lookup.tile(anchorX+offset+dx,anchorY+offset+dy,layer);
            if(other!=null)out.get(other);
        }
    }
    @Override public void setBlock(Block value,Team team,int rotation,Prov<Building> factory){throw unsupported();}
    @Override public void setBlock(Block value,Team team,int rotation){throw unsupported();}
    @Override public void setBlock(Block value,Team team){throw unsupported();}
    @Override public void setBlock(Block value){throw unsupported();}
    @Override public void setAir(){throw unsupported();}
    @Override public void remove(){throw unsupported();}
    private static UnsupportedOperationException unsupported(){return new UnsupportedOperationException("Upper construction is owned by LayerWorld, never native Tile.setBlock/remove");}
}
