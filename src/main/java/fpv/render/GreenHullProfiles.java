package fpv.render;

import java.util.List;

/** Four native silhouettes, individual armor contours; no movement/flight/weapon metadata changes. */
public final class GreenHullProfiles {
    private GreenHullProfiles(){}
    public static LayeredHullProfile forName(String name,float size){
        if(!Float.isFinite(size)||size<=0)throw new IllegalArgumentException("Finite native unit size required");
        return switch(name){
            case "nova" -> hull(clamp(size*.23f,.18f,.28f),.56f,.78f,.48f,.22f,.30f);
            case "pulsar" -> hull(clamp(size*.21f,.24f,.36f),.64f,.86f,.54f,.25f,.32f);
            case "quasar" -> hull(clamp(size*.22f,.30f,.46f),.72f,.92f,.62f,.28f,.34f);
            // Narrow underside clears native hip sockets, broad upper deck retains the T5 art.
            case "corvus" -> hull(clamp(size*.255f,.72f,1.10f),.54f,.84f,.46f,.25f,.35f);
            default -> throw new IllegalArgumentException("Only the four green models are supported");
        };
    }
    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    private static LayeredHullProfile hull(float depth,float low,float middle,float chassis,float lowerFraction,float middleFraction){
        return new LayeredHullProfile(List.of(new LayeredHullProfile.Band(low,depth*lowerFraction),
            new LayeredHullProfile.Band(middle,depth*middleFraction),new LayeredHullProfile.Band(1,depth*(1-lowerFraction-middleFraction))),chassis,0,true);
    }
}
