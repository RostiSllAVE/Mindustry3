package fpv.ui;

import arc.Core;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Table;
import fpv.construction.LayerCatalog;
import fpv.construction.LayerRuntime;
import mindustry.Vars;
import java.util.function.IntFunction;

/** Host-only construction rules in the existing native settings dialog. */
public final class LayerSettings {
    private LayerSettings(){}
    public static void install(LayerRuntime runtime){
        if(runtime==null)return;
        Vars.ui.settings.addCategory("3D construction",table->{
            table.defaults().width(460).pad(5);
            table.add("The host controls these rules in multiplayer.\nFree placement permission is in the Tab / Build panel.\nFree placement still uses the normal resource recipes.").wrap().left().row();
            table.label(()->"Current world: Z0–Z"+runtime.grid().coordinates().maxLayers()+" / floor spacing "+runtime.grid().coordinates().spacing()+" tiles").left().row();
            slider(table,"Maximum construction floor","fpv-layer-max",64,1,512,1,v->"Z"+v);
            slider(table,"Maximum elevated components","fpv-layer-max-components",16384,256,32768,256,v->Integer.toString(v));
            table.add("The component limit includes falling sections. A separate save/network memory budget may stop new construction earlier on very large or heavily modded worlds.").wrap().fontScale(.85f).left().row();
            slider(table,"Maximum vertical connector span","fpv-vertical-max-gap",16,1,64,1,v->v+" floors");
            table.add("Apply updates the current world. Lowering the floor limit never removes existing structures. Shortening connector span can disconnect longer links.").wrap().fontScale(.85f).left().row();
            table.button("Apply construction rules",()->runtime.applyHostSettings()).height(38).update(button->button.setDisabled(Vars.net.client())).row();
            table.label(runtime::status).wrap().fontScale(.85f).left().row();
            table.add("[accent]Collapse").left().row();
            slider(table,"Warning before collapse","fpv-collapse-grace-ms",2000,0,30000,100,v->String.format(java.util.Locale.ROOT,"%.1f s",v/1000.0));
            slider(table,"Maximum damage per falling section / target","fpv-collapse-max-damage",2500,0,100000,250,v->v+" HP");
            table.add("Impact damage depends on falling mass and speed, up to this limit. It affects friendly and enemy units and structures.").wrap().fontScale(.85f).left().row();
            table.add("[accent]Support materials").left().row();
            table.add("Span is the maximum horizontal extension. Capacity is the total structural load. Large walls have twice the capacity, with the same span.\nChanges apply to newly placed upper walls. Apply refreshes existing ground foundations; upper walls retain the profile saved when built.").wrap().fontScale(.85f).left().row();
            String[] materials={"titanium","surge","phase","plastanium","scrap","thorium","copper"};
            String[] labels={"Titanium","Surge alloy","Phase","Plastanium","Scrap","Thorium","Copper"};
            int[] spans={7,6,5,4,3,2,1},capacities={80,160,64,56,36,48,24};
            for(int i=0;i<materials.length;i++){
                slider(table,labels[i]+" span","fpv-support-"+materials[i]+"-span",spans[i],0,64,1,v->v+" tiles");
                slider(table,labels[i]+" load capacity","fpv-support-"+materials[i]+"-capacity",capacities[i],1,2000,1,v->v+" load");
            }
            table.add("[accent]New component recipes").left().row();
            slider(table,"Platform copper","fpv-platform-copper",2,0,1000,1,Integer::toString);
            slider(table,"Platform lead","fpv-platform-lead",1,0,1000,1,Integer::toString);
            slider(table,"Vertical connector copper","fpv-vertical-copper",4,0,1000,1,Integer::toString);
            slider(table,"Vertical connector lead","fpv-vertical-lead",2,0,1000,1,Integer::toString);
            table.add("Ordinary buildings keep their original recipes. New recipe values affect new purchases only; dismantling uses the amount actually paid and the world's refund rule.").wrap().fontScale(.85f).left().row();
        });
    }
    private static void slider(Table table,String title,String key,int fallback,int min,int max,int step,IntFunction<String> format){
        table.label(()->title+": "+(Vars.net.client()?"server controlled":format.apply(LayerCatalog.boundedInt(key,fallback,min,max)))).wrap().fontScale(.9f).left().row();
        Slider slider=new Slider(min,max,step,false);slider.setValue(LayerCatalog.boundedInt(key,fallback,min,max));
        slider.moved(value->{if(!Vars.net.client())Core.settings.put(key,Math.round(value));});
        slider.update(()->{slider.setDisabled(Vars.net.client());if(!slider.isDragging())slider.setValue(LayerCatalog.boundedInt(key,fallback,min,max));});
        table.add(slider).height(25).row();
    }
}
