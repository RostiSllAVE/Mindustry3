package fpv.construction;

import java.util.ArrayList;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.world.Block;
import mindustry.world.blocks.defense.Wall;

/** Conservative local-save upgrade: preserve floors, inventories, load ratings and occupied rooms. */
public final class LayerWallMigration {
    private LayerWallMigration(){}
    public record Result(int upgraded,int occupied){}
    public static Result upgradeLegacyWalls(LayerWorld world){
        if(world==null||Vars.content==null)return new Result(0,0);
        int upgraded=0,occupied=0;double spacing=world.coordinates().spacing();
        for(var old:new ArrayList<>(world.components())){
            if(old.kind()!=LayerComponent.Kind.BLOCK||old.size()<2||old.height()>=LayerCatalog.wallHeight(old.size(),spacing)-1e-7)continue;
            Block block=Vars.content.getByName(ContentType.block,old.blockName());if(!(block instanceof Wall)||block.size!=old.size())continue;
            double height=LayerCatalog.wallHeight(old.size(),spacing);if(height<=old.height()+1e-7)continue;
            var next=new LayerComponent(old.id(),old.kind(),old.x(),old.y(),old.layer(),old.blockName(),old.size(),old.rotation(),old.team(),old.mode(),
                old.health(),old.maxHealth(),old.mass(),height,old.support(),old.configuration(),old.state());
            // Do not move an existing floor or its inventory to make room for a taller column.
            // Old decorative caps penetrating an occupied next storey are trimmed to that
            // storey's plane. Exposed caps become whole-storey columns instead.
            try{world.replace(next);upgraded++;}catch(IllegalArgumentException blocked){
                occupied++;
                if(old.height()>spacing+1e-7){
                    var trimmed=new LayerComponent(old.id(),old.kind(),old.x(),old.y(),old.layer(),old.blockName(),old.size(),old.rotation(),old.team(),old.mode(),old.health(),old.maxHealth(),old.mass(),spacing,old.support(),old.configuration(),old.state());
                    world.replace(trimmed);
                }
            }
        }
        return new Result(upgraded,occupied);
    }
}
