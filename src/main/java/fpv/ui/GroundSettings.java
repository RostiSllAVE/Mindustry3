package fpv.ui;

import arc.Core;
import fpv.units.ground.GroundUnitProfiles;
import mindustry.Vars;

/** Per-family rules share the host's existing persistent settings and replication channel. */
public final class GroundSettings {
    private GroundSettings(){}
    public static void install(){
        String[] names={"atrax","spiroct","arkyid","toxopid"};
        for(String name:names)Core.bundle.getProperties().put("setting.fpv-ground-"+name+"-extension.name",
            Character.toUpperCase(name.charAt(0))+name.substring(1)+": maximum leg extension");
        Core.bundle.getProperties().put("setting.fpv-ground-min-speed-percent.name","Speed at maximum leg extension");
        Core.bundle.getProperties().put("setting.fpv-green-walker-height-percent.name","Vela / Corvus: maximum standing height multiplier");
        var defaults=GroundUnitProfiles.defaults();
        double[] heights={defaults.atraxExtension(),defaults.spiroctExtension(),defaults.arkyidExtension(),defaults.toxopidExtension()};
        Vars.ui.settings.addCategory("3D ground units",table->{
            table.add("Purple family: automatic grounded climbing.\nMultiplayer uses the host's values.\nA large physical wall is 1.65 tiles high.").wrap().width(460).row();
            for(int i=0;i<names.length;i++){
                final int index=i;
                table.sliderPref("fpv-ground-"+names[i]+"-extension",(int)Math.round(heights[i]*10),0,5120,1,
                    value->Vars.net.client()?String.format(java.util.Locale.ROOT,"%.1f tiles / server",extension(GroundUnitProfiles.settings(),index)):
                        String.format(java.util.Locale.ROOT,"%.1f tiles",value/10.0));
            }
            table.sliderPref("fpv-ground-min-speed-percent",(int)Math.round(defaults.minSpeedFraction()*100),5,100,1,
                value->Vars.net.client()?Math.round(GroundUnitProfiles.settings().minSpeedFraction()*100)+"% / server":value+"%");
            table.sliderPref("fpv-green-walker-height-percent",(int)Math.round(defaults.greenWalkerHeightMultiplier()*100),100,800,25,
                value->Vars.net.client()?String.format(java.util.Locale.ROOT,"%.2fx / server",GroundUnitProfiles.settings().greenWalkerHeightMultiplier()):String.format(java.util.Locale.ROOT,"%.2fx",value/100.0));
            table.add("Normal stance keeps full speed; taller extension gradually slows movement.\nCrawler stays low on four wheels.").wrap().width(460).row();
        });
    }
    private static double extension(GroundUnitProfiles.Settings value,int i){return switch(i){case 0->value.atraxExtension();case 1->value.spiroctExtension();case 2->value.arkyidExtension();default->value.toxopidExtension();};}
}
