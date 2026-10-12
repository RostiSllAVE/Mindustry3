package fpv.render;

import fpv.world.NaturalProps;
import fpv.world.NaturalProps.Kind;
import mindustry.world.Block;

/** Presentation dimensions only. Native tile placement, height queries and collision never use this scale. */
public final class VegetationVisualScale {
    public static final float MULTIPLIER=5f;
    private VegetationVisualScale(){}
    public static float scale(Kind kind){
        return kind==Kind.PINE||kind==Kind.LEAF_TREE||kind==Kind.DEAD_TREE||kind==Kind.BUSH?MULTIPLIER:1f;
    }
    public static float scale(Block block){return scale(NaturalProps.kind(block));}
    public static float dimension(Kind kind,float original){return original*scale(kind);}
    public static float height(Block block,float physicalHeight){return physicalHeight*scale(block);}
    /** Conservative extra reach from a source tile beyond its ordinary half-tile chunk border. */
    public static float extentPadding(Block block){
        return block==null||scale(block)==1f?0f:Math.max(0,block.size*.5f*scale(block)-.5f);
    }
}
