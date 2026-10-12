package fpv.world;

import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.blocks.environment.*;

/** Native decoration capabilities shared by terrain classification and visual shape selection. */
public final class NaturalProps {
    public enum Kind { PINE, LEAF_TREE, DEAD_TREE, BUSH, ROCK }
    private NaturalProps(){}
    public static Kind kind(Block block){
        if(block==null)return null;
        if(block instanceof StaticTree)return Kind.PINE;
        if(block instanceof TreeBlock)return block==Blocks.whiteTreeDead?Kind.DEAD_TREE:Kind.LEAF_TREE;
        if(block==Blocks.shrubs||block==Blocks.sporeCluster||block instanceof SeaBush||block instanceof Seaweed)return Kind.BUSH;
        // Crystal spires keep their separate native identity rather than being rounded into stones.
        // StaticWall itself extends Prop: exclude terrain walls after the explicit tree/shrub cases.
        if(block instanceof Prop&&!(block instanceof StaticWall)&&!(block instanceof TallBlock))return Kind.ROCK;
        return null;
    }
    public static boolean supported(Block block){return kind(block)!=null;}
}
