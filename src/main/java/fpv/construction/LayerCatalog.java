package fpv.construction;

import arc.Core;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.ctype.ContentType;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.defense.Wall;
import java.util.IdentityHashMap;

/** Explicit supported categories. Original content, requirements and health remain the source of truth. */
public final class LayerCatalog {
    public static final String PLATFORM="fpv-platform";
    private final IdentityHashMap<Block,Boolean> nativeSupport=new IdentityHashMap<>();
    public record Entry(String name,Block block,int size,double health,double mass,double height,ItemStack[] requirements,
                        LayerComponent.SupportProfile support){}
    public Entry entry(String name,boolean platform,double spacing){
        if(platform){
            int copper=boundedInt("fpv-platform-copper",2,0,1000),lead=boundedInt("fpv-platform-lead",1,0,1000);
            return new Entry(PLATFORM,null,1,250,1,LayerCoordinates.PLATFORM_THICKNESS,
                new ItemStack[]{new ItemStack(Items.copper,copper),new ItemStack(Items.lead,lead)},LayerComponent.SupportProfile.NONE);
        }
        Block block=Vars.content.getByName(ContentType.block,name);
        if(block==null||block.size<1||block.size>16)return null;
        if(!(block instanceof Wall)){
            if(!nativeSupport.computeIfAbsent(block,LayerBuildingAdapter::supports))return null;
            return new Entry(block.name,block,block.size,block.health,Math.max(1,block.size*block.size*2.0),
                fpv.net.HeadlessGeometry.heightFor(block),block.requirements,LayerComponent.SupportProfile.NONE);
        }
        // Doors and mod-specific wall behavior need explicit lifecycle/configuration adapters.
        if(block.getClass()!=Wall.class&&block.getClass().getSuperclass()!=Wall.class&&!nativeSupport.computeIfAbsent(block,LayerBuildingAdapter::supports))return null;
        var support=profile(block);
        return new Entry(block.name,block,block.size,block.health,Math.max(1,block.size*block.size*2.0),wallHeight(block.size,spacing),block.requirements,support);
    }
    /** Whole storeys: a tall wall's solid top is also its construction socket. */
    public static double wallHeight(int size,double spacing){return Math.max(1,Math.ceil(size/spacing-1e-7))*spacing;}
    public LayerComponent.SupportProfile profile(Block block){
        if(!(block instanceof Wall))return LayerComponent.SupportProfile.NONE;
        String name=block.name;String material=null;int span=0,capacity=0;
        if(name.startsWith("titanium-wall")){material="titanium";span=7;capacity=80;}
        else if(name.startsWith("surge-wall")){material="surge";span=6;capacity=160;}
        else if(name.startsWith("phase-wall")){material="phase";span=5;capacity=64;}
        else if(name.startsWith("plastanium-wall")){material="plastanium";span=4;capacity=56;}
        else if(name.startsWith("scrap-wall")){material="scrap";span=3;capacity=36;}
        else if(name.startsWith("thorium-wall")){material="thorium";span=2;capacity=48;}
        else if(name.startsWith("copper-wall")){material="copper";span=1;capacity=24;}
        if(material==null)return LayerComponent.SupportProfile.NONE;
        span=boundedInt("fpv-support-"+material+"-span",span,0,64);
        capacity=boundedInt("fpv-support-"+material+"-capacity",capacity,1,100000);
        return new LayerComponent.SupportProfile(span,capacity*(block.size>=2?2:1));
    }
    public static int boundedInt(String key,int fallback,int min,int max){return Math.max(min,Math.min(max,Core.settings==null?fallback:Core.settings.getInt(key,fallback)));}
}
