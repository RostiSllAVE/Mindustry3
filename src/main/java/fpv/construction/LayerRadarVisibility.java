package fpv.construction;

import arc.math.Mathf;
import mindustry.Vars;
import mindustry.world.blocks.defense.Radar;
import mindustry.world.blocks.defense.Radar.RadarBuild;
import mindustry.world.meta.BlockFlag;

/** Registers only a fog source; upper radar never enters the native tile or entity indexes. */
final class LayerRadarVisibility {
    static void update(RadarBuild build){
        if(Vars.indexer!=null){var sources=Vars.indexer.getFlagged(build.team,BlockFlag.hasFogRadius);if(sources!=null&&!sources.contains(build,true))sources.add(build);}
        Radar type=(Radar)build.block;
        build.smoothEfficiency=Mathf.lerpDelta(build.smoothEfficiency,build.efficiency,.05f);
        if(Math.abs(build.fogRadius()-build.lastRadius)>=.5f){if(Vars.fogControl!=null)Vars.fogControl.forceUpdate(build.team,build);build.lastRadius=build.fogRadius();}
        build.progress=Mathf.clamp(build.progress+build.edelta()/type.discoveryTime);build.totalProgress+=build.efficiency*build.edelta();
    }
    static void remove(RadarBuild build){
        if(Vars.indexer!=null){var sources=Vars.indexer.getFlagged(build.team,BlockFlag.hasFogRadius);if(sources!=null)sources.remove(build,true);}
        if(Vars.fogControl!=null)Vars.fogControl.forceUpdate(build.team,build);
    }
}
