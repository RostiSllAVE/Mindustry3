package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import java.util.HashMap;
import java.util.Map;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.ContentLoader;
import mindustry.core.FileTree;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;

/** Executable isolation check. Run only in its own JVM; never from a live game or save. */
public final class LayerNativeProbe {
    private static int checks;
    private LayerNativeProbe(){}
    public static void main(String[] args){
        if(Vars.world!=null)throw new IllegalStateException("Probe requires a fresh synthetic JVM");
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        Vars.state=new GameState();Vars.world=new World();Vars.world.tiles=new Tiles(12,12);Vars.world.setGenerating(true);
        Vars.world.tiles.fill();
        // Non-building synthetic blocks isolate the multiblock footprint write from every lifecycle hook.
        Block marker=new Block("layer-probe-ground-marker");marker.solid=true;
        Block multi=new Block("layer-probe-multiblock");multi.size=2;
        Tile victim=Vars.world.tile(5,4);victim.setBlock(marker);
        Tile detached=new Tile(4,4);detached.setBlock(multi,Team.sharded,0);
        require(victim.block()==multi,"native detached placement mutates the Z0 neighbour");
        require(Vars.world.tile(4,4).block()==Blocks.air,"native detached centre does not even occupy its global cell");

        for(Tile tile:Vars.world.tiles)tile.setBlock(marker);
        Block[] before=new Block[144];for(int i=0;i<144;i++)before[i]=Vars.world.tiles.geti(i).block();
        Map<String,LayerTile> layers=new HashMap<>();
        LayerTile.Lookup lookup=(x,y,layer)->layers.get(x+":"+y+":"+layer);
        for(int layer=1;layer<=2;layer++)for(int x=3;x<=6;x++)for(int y=3;y<=6;y++)layers.put(x+":"+y+":"+layer,new LayerTile(x,y,layer,lookup));
        LayerTile upper=lookup.tile(4,4,1),stacked=lookup.tile(4,4,2);
        Block functional=new Block("layer-probe-safe-building");functional.size=2;functional.health=180;functional.hasItems=true;functional.itemCapacity=30;
        Building instance=Building.create();upper.attach(functional,instance);instance.init(upper,Team.sharded,false,2);
        upper.getLinkedTilesAs(functional,t->((LayerTile)t).attach(functional,instance));
        require(instance.tile==upper&&instance.block==functional&&instance.rotation==2,"native detached creation retains content, tile and rotation");
        require(instance.items!=null&&instance.health==180,"native item state and health initialized");
        require(lookup.tile(5,4,1).build==instance,"safe multiblock footprint stays in selected layer");
        require(stacked.build==null&&stacked.block()==Blocks.air,"same XY in a second layer is independent");
        require(upper.nearby(1,0)==lookup.tile(5,4,1),"neighbours stay on their own layer");
        require(upper.getLinkedTiles(new arc.struct.Seq<>()).size==4,"linked footprint has expected four local cells");
        try{upper.setBlock(multi);throw new AssertionError("unsafe mutation was accepted");}catch(UnsupportedOperationException expected){checks++;}
        for(int i=0;i<144;i++)require(before[i]==Vars.world.tiles.geti(i).block(),"safe detached view leaves every Z0 tile unchanged");
        System.out.println("LayerNativeProbe: "+checks+" checks passed; detached native multiblock corruption reproduced and safe local view isolated");
    }
    private static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
