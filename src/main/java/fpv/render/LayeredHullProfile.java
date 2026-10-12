package fpv.render;

import java.util.List;

/** Thin hard-edged silhouette bands, listed from underside to roof. Geometry is generated once. */
public record LayeredHullProfile(List<Band> bands,float chassisScale,float cameraReferenceDepth,boolean optimized) {
    public LayeredHullProfile(List<Band> bands,float chassisScale,float cameraReferenceDepth){this(bands,chassisScale,cameraReferenceDepth,false);}
    public record Band(float scale,float height){
        public Band{if(!Float.isFinite(scale)||scale<=0||scale>1||!Float.isFinite(height)||height<=0||height>.45f)
            throw new IllegalArgumentException("Thin silhouette band required");}
    }
    public LayeredHullProfile{
        bands=List.copyOf(bands);
        if(bands.size()<2||bands.size()>3||!Float.isFinite(chassisScale)||chassisScale<=0
            ||chassisScale>=bands.get(0).scale()||!Float.isFinite(cameraReferenceDepth)||cameraReferenceDepth<0)
            throw new IllegalArgumentException("Layered hull bounds");
        float last=0;for(Band band:bands){if(band.scale()<=last)throw new IllegalArgumentException("Hull must narrow downward");last=band.scale();}
        if(last!=1)throw new IllegalArgumentException("Native top silhouette must retain full scale");
    }
    public double depth(){double sum=0;for(Band band:bands)sum+=band.height();return sum;}
    public static LayeredHullProfile dagger(){return new LayeredHullProfile(List.of(new Band(.66f,.08f),new Band(.84f,.10f),new Band(1,.12f)),.60f,.72f);}
    public static LayeredHullProfile mace(float unitSize,float thicknessFactor){
        if(!Float.isFinite(unitSize)||unitSize<=0||!Float.isFinite(thicknessFactor)||thicknessFactor<=0)throw new IllegalArgumentException("Finite hull size/factor required");
        float depth=Math.max(.24f,Math.min(.60f,unitSize*thicknessFactor));
        return new LayeredHullProfile(List.of(new Band(.62f,depth*.22f),new Band(.82f,depth*.30f),new Band(1,depth*.48f)),.54f,0,true);
    }
    public static LayeredHullProfile fortress(float unitSize,float thicknessFactor){
        if(!Float.isFinite(unitSize)||unitSize<=0||!Float.isFinite(thicknessFactor)||thicknessFactor<=0)throw new IllegalArgumentException("Finite hull size/factor required");
        float depth=Math.max(.36f,Math.min(.75f,unitSize*thicknessFactor));
        // Broader lower armor and chassis, rather than a uniformly enlarged Mace.
        return new LayeredHullProfile(List.of(new Band(.72f,depth*.28f),new Band(.90f,depth*.32f),new Band(1,depth*.40f)),.64f,0,true);
    }
    public static LayeredHullProfile scepter(float unitSize,float thicknessFactor){
        if(!Float.isFinite(unitSize)||unitSize<=0||!Float.isFinite(thicknessFactor)||thicknessFactor<=0)throw new IllegalArgumentException("Finite hull size/factor required");
        // A broad armored skirt and compact upper deck; native T4 art supplies the elite identity.
        float depth=Math.max(.54f,Math.min(.88f,unitSize*thicknessFactor));
        return new LayeredHullProfile(List.of(new Band(.78f,depth*.32f),new Band(.94f,depth*.34f),new Band(1,depth*.34f)),.70f,0,true);
    }
    public static LayeredHullProfile reign(float unitSize,float thicknessFactor){
        if(!Float.isFinite(unitSize)||unitSize<=0||!Float.isFinite(thicknessFactor)||thicknessFactor<=0)throw new IllegalArgumentException("Finite hull size/factor required");
        // Broad T5 armor and a substantial upper deck, still only three exterior contours.
        float depth=Math.max(.72f,Math.min(1.20f,unitSize*thicknessFactor));
        return new LayeredHullProfile(List.of(new Band(.84f,depth*.30f),new Band(.96f,depth*.34f),new Band(1,depth*.36f)),.76f,0,true);
    }
    public static LayeredHullProfile vela(float unitSize,float thicknessFactor){
        if(!Float.isFinite(unitSize)||unitSize<=0||!Float.isFinite(thicknessFactor)||thicknessFactor<=0)throw new IllegalArgumentException("Finite hull size/factor required");
        float depth=Math.max(.70f,Math.min(1.12f,unitSize*thicknessFactor));
        // Broad support-mech skirt, native green roof and substantial underside; no radial hull.
        return new LayeredHullProfile(List.of(new Band(.82f,depth*.28f),new Band(.96f,depth*.36f),new Band(1,depth*.36f)),.72f,depth,true);
    }
    public double chassisTopOffset(double supportHalfDepth,double clearance,double pitch,double roll){
        return clearance-supportHalfDepth*Math.cos(Math.toRadians(pitch))*Math.cos(Math.toRadians(roll))+.008;
    }
}
