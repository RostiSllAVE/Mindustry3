package fpv.render;

import java.util.List;

/** Cosmetic naval section profile. Width/length come from the original atlas, never the hitbox. */
public record NavalHullProfile(ProceduralUnitModelProfile body,float submergedFraction,float weaponGap,float rotationOffset,List<NavalBodyGeometry.Section> sections,float minimumPitch,float maximumPitch){
    private record Cached(float size,NavalHullProfile profile){}
    private static final java.util.IdentityHashMap<mindustry.type.UnitType,Cached> profiles=new java.util.IdentityHashMap<>();
    public NavalHullProfile(ProceduralUnitModelProfile body,float submergedFraction,float weaponGap,float rotationOffset,List<NavalBodyGeometry.Section> sections){this(body,submergedFraction,weaponGap,rotationOffset,sections,-15.6f,71.5f);}
    public NavalHullProfile(ProceduralUnitModelProfile body,float submergedFraction,float weaponGap,float rotationOffset){this(body,submergedFraction,weaponGap,rotationOffset,List.of());}
    public NavalHullProfile {
        sections=List.copyOf(sections);if(sections.size()>3)throw new IllegalArgumentException("At most three raised ship areas");
        if(body==null||body.hull()==null||!body.hull().optimized()||!Float.isFinite(submergedFraction)||submergedFraction<=0||submergedFraction>=1
            ||!Float.isFinite(weaponGap)||weaponGap<0||!Float.isFinite(rotationOffset)||!Float.isFinite(minimumPitch+maximumPitch)
            ||minimumPitch< -26||minimumPitch>0||maximumPitch<0||maximumPitch>85)throw new IllegalArgumentException("Finite naval hull profile required");
    }
    /** Explicit vanilla water-class whitelist; custom units retain their existing renderer. */
    public static List<mindustry.type.UnitType> types(){return List.of(mindustry.content.UnitTypes.risso,mindustry.content.UnitTypes.minke,
        mindustry.content.UnitTypes.bryde,mindustry.content.UnitTypes.sei,mindustry.content.UnitTypes.omura,mindustry.content.UnitTypes.retusa,
        mindustry.content.UnitTypes.oxynoe,mindustry.content.UnitTypes.cyerce,mindustry.content.UnitTypes.aegires,mindustry.content.UnitTypes.navanax);}
    public static NavalHullProfile forType(mindustry.type.UnitType type){
        if(!supports(type))return null;
        float size=type.hitSize/mindustry.Vars.tilesize;
        var cached=profiles.get(type);if(cached!=null&&cached.size()==size)return cached.profile();
        // Different lower contours/selected sprite regions, not a uniform enlargement of Risso.
        NavalHullProfile profile=switch(type.name){
            case "risso" -> risso(size);
            case "minke" -> ship(size,.33f,.40f,.70f,.39f,.56f,.81f,.16f,-10,58,List.of(section(.34f,.55f,.32f,.25f,.24f),section(.42f,.22f,.16f,.14f,.12f)));
            case "bryde" -> ship(size,.27f,.52f,.85f,.40f,.58f,.84f,.20f,-10,60,List.of(section(.33f,.47f,.34f,.28f,.27f),section(.40f,.18f,.20f,.17f,.15f)));
            case "sei" -> ship(size,.185f,.70f,1.10f,.42f,.64f,.87f,.23f,-12,55,List.of(section(.34f,.37f,.32f,.27f,.36f),section(.39f,.16f,.22f,.15f,.20f),section(.41f,.76f,.18f,.12f,.16f)));
            case "omura" -> ship(size,.165f,.90f,1.24f,.43f,.66f,.89f,.28f,-8,50,List.of(section(.36f,.38f,.28f,.29f,.42f),section(.42f,.16f,.16f,.17f,.24f),section(.39f,.77f,.22f,.11f,.20f)));
            case "retusa" -> ship(size,.33f,.36f,.65f,.36f,.54f,.79f,.14f,-14,55,List.of(section(.35f,.57f,.30f,.26f,.20f),section(.42f,.22f,.16f,.15f,.10f)));
            case "oxynoe" -> ship(size,.30f,.42f,.74f,.38f,.58f,.82f,.16f,-14,58,List.of(section(.32f,.44f,.36f,.25f,.24f),section(.40f,.73f,.20f,.12f,.12f)));
            case "cyerce" -> ship(size,.26f,.52f,.84f,.40f,.60f,.84f,.19f,-12,58,List.of(section(.34f,.44f,.32f,.29f,.29f),section(.39f,.20f,.22f,.14f,.14f)));
            case "aegires" -> ship(size,.18f,.72f,1.16f,.42f,.68f,.90f,.21f,-15,60,List.of(section(.34f,.35f,.32f,.31f,.38f),section(.40f,.16f,.20f,.14f,.19f),section(.40f,.75f,.20f,.13f,.20f)));
            case "navanax" -> ship(size,.16f,.90f,1.24f,.43f,.70f,.91f,.26f,-12,60,List.of(section(.34f,.37f,.32f,.30f,.43f),section(.40f,.16f,.20f,.15f,.24f),section(.38f,.76f,.24f,.12f,.21f)));
            default -> null;
        };
        profiles.put(type,new Cached(size,profile));return profile;
    }
    public static boolean supports(mindustry.type.UnitType type){return type!=null&&(type==mindustry.content.UnitTypes.risso||type==mindustry.content.UnitTypes.minke||type==mindustry.content.UnitTypes.bryde||type==mindustry.content.UnitTypes.sei||type==mindustry.content.UnitTypes.omura||type==mindustry.content.UnitTypes.retusa||type==mindustry.content.UnitTypes.oxynoe||type==mindustry.content.UnitTypes.cyerce||type==mindustry.content.UnitTypes.aegires||type==mindustry.content.UnitTypes.navanax);}
    private static NavalBodyGeometry.Section section(float x,float y,float width,float length,float height){return new NavalBodyGeometry.Section(x,y,width,length,height,.18f);}
    private static NavalHullProfile ship(float size,float factor,float min,float max,float submerged,float lower,float middle,float weaponDepth,float down,float up,List<NavalBodyGeometry.Section> sections){
        if(!Float.isFinite(size)||size<=0)throw new IllegalArgumentException("Finite native ship size required");
        float depth=Math.max(min,Math.min(max,size*factor)),gap=.025f;
        var hull=new LayeredHullProfile(List.of(new LayeredHullProfile.Band(lower,depth*.28f),new LayeredHullProfile.Band(middle,depth*.36f),new LayeredHullProfile.Band(1,depth*.36f)),lower-.06f,0,true);
        var model=new ProceduralUnitModelProfile(depth,1,.95f,0,0,-depth*submerged,weaponDepth,0,0,depth*(1-submerged)+weaponDepth*.5f+gap,0,hull);
        return new NavalHullProfile(model,submerged,gap,0,sections,(float)fpv.combat.WeaponPitchLimits.expanded(down),(float)fpv.combat.WeaponPitchLimits.expanded(up));
    }
    public double clampPitch(double pitch){return Double.isFinite(pitch)?Math.max(minimumPitch,Math.min(maximumPitch,pitch)):0;}
    public static NavalHullProfile risso(float size){
        if(!Float.isFinite(size)||size<=0)throw new IllegalArgumentException("Finite native ship size required");
        float depth=Math.max(.34f,Math.min(.60f,size*.352f)),submerged=.38f;
        var hull=new LayeredHullProfile(List.of(new LayeredHullProfile.Band(.50f,depth*.22f),
            new LayeredHullProfile.Band(.78f,depth*.38f),new LayeredHullProfile.Band(1,depth*.40f)),.44f,0,true);
        var model=new ProceduralUnitModelProfile(depth,1,.95f,0,0,-depth*submerged,.14f,0,0,depth*(1-submerged)+.07f+.02f,0,hull);
        return new NavalHullProfile(model,submerged,.02f,0,List.of(
            new NavalBodyGeometry.Section(.34f,.63f,.32f,.22f,.23f,.18f),
            new NavalBodyGeometry.Section(.42f,.24f,.16f,.12f,.10f,.20f)));
    }
    public double mountLift(double right,double forward,double width,double length){
        if(!Double.isFinite(right+forward+width+length)||width<=0||length<=0)throw new IllegalArgumentException("Finite native hull proportions required");
        double height=0;for(int i=0;i<sections.size();i++){var section=sections.get(i);if(section.contains(.5+right/width,.5-forward/length))height=Math.max(height,section.height());}return height;
    }
    public double keel(){return -body.bodyThickness()*submergedFraction;}
    public double deck(){return body.bodyThickness()*(1-submergedFraction);}
    /** Water only seats an already floating pose. A lifted/airborne ship keeps its actual XYZ support. */
    public double waterline(double canonicalZ,double halfHeight,double surface,boolean afloat){
        if(!Double.isFinite(canonicalZ)||!Double.isFinite(halfHeight)||halfHeight<0||!Double.isFinite(surface))throw new IllegalArgumentException("Finite ship pose required");
        double support=canonicalZ-halfHeight;
        return afloat&&Math.abs(support-surface)<.10?surface:support;
    }
}
