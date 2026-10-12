package fpv.render;

import mindustry.content.UnitTypes;
import mindustry.type.UnitType;

/** Presentation-only native-atlas extrusion parameters. No UnitType or physical pose mutation. */
public record ProceduralUnitModelProfile(float bodyThickness,float bodyScale,float sideShade,
        float originRight,float originForward,float groundClearance,float weaponThickness,
        float weaponRightOffset,float weaponForwardOffset,float weaponUpOffset,float movementUpOffset,LayeredHullProfile hull) {
    public ProceduralUnitModelProfile(float thickness,float scale,float shade,float right,float forward,float clearance,float weaponDepth,
            float weaponRight,float weaponForward,float weaponUp,float movementUp){
        this(thickness,scale,shade,right,forward,clearance,weaponDepth,weaponRight,weaponForward,weaponUp,movementUp,null);
    }
    public ProceduralUnitModelProfile {
        float[] values={bodyThickness,bodyScale,sideShade,originRight,originForward,groundClearance,
            weaponThickness,weaponRightOffset,weaponForwardOffset,weaponUpOffset,movementUpOffset};
        for(float value:values)if(!Float.isFinite(value))throw new IllegalArgumentException("Finite model profile required");
        if(bodyThickness<=0||bodyThickness>4||bodyScale<=0||bodyScale>4||sideShade<0||sideShade>1
            ||weaponThickness<=0||weaponThickness>4)throw new IllegalArgumentException("Model profile bounds");
        if(hull!=null&&Math.abs(hull.depth()-bodyThickness)>1e-5)throw new IllegalArgumentException("Band heights must match hull depth");
    }
    // Native Mechc whole-leg artwork and mirrored large-weapon mounts are retained.
    private static final ProceduralUnitModelProfile DAGGER=new ProceduralUnitModelProfile(
        .30f,1f,.95f,0,0,.10f,.12f,0,0,.36f,0,LayeredHullProfile.dagger());
    public static final float MACE_THICKNESS_FACTOR=.34f;
    private static ProceduralUnitModelProfile mace;
    private static float maceSize,maceSupport;
    public static final float FORTRESS_THICKNESS_FACTOR=.40f;
    private static ProceduralUnitModelProfile fortress;
    private static float fortressSize,fortressSupport;
    public static final float SCEPTER_THICKNESS_FACTOR=.30f;
    private static ProceduralUnitModelProfile scepter;
    private static float scepterSize,scepterSupport;
    public static final float REIGN_THICKNESS_FACTOR=.30f;
    private static ProceduralUnitModelProfile reign;
    private static float reignSize,reignSupport;
    public static final float VELA_THICKNESS_FACTOR=.32f;
    private static ProceduralUnitModelProfile vela;
    private static float velaSize,velaSupport;
    private record GreenCache(float size,float support,ProceduralUnitModelProfile profile){}
    private static final java.util.IdentityHashMap<UnitType,GreenCache> green=new java.util.IdentityHashMap<>();
    public static boolean greenVisual(UnitType type){return type!=null&&(type==UnitTypes.nova||type==UnitTypes.pulsar||type==UnitTypes.quasar||type==UnitTypes.corvus);}
    /** These four cosmetic hulls must not move the already working authoritative muzzle. */
    public static ProceduralUnitModelProfile aimingProfile(UnitType type){return greenVisual(type)?null:forType(type);}
    public static ProceduralUnitModelProfile forType(UnitType type){
        if(type==null)return null;
        if(type==UnitTypes.dagger)return DAGGER;
        if(type==UnitTypes.fortress)return fortressProfile(type);
        if(type==UnitTypes.scepter)return scepterProfile(type);
        if(type==UnitTypes.reign)return reignProfile(type);
        if(type==UnitTypes.vela)return velaProfile(type);
        if(greenVisual(type))return greenProfile(type);
        if(type!=UnitTypes.mace)return null;
        float size=type.hitSize/mindustry.Vars.tilesize;
        var region=type.region;
        double sprite=region==null?0:Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5;
        float support=(float)Math.max(sprite,Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        if(mace==null||maceSize!=size||maceSupport!=support){
            var hull=LayeredHullProfile.mace(size,MACE_THICKNESS_FACTOR);float depth=(float)hull.depth(),clearance=.08f,weaponDepth=.14f;
            mace=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,weaponDepth,0,0,
                clearance+depth-support+weaponDepth*.5f+.025f,0,hull);
            maceSize=size;maceSupport=support;
        }
        return mace;
    }
    private static ProceduralUnitModelProfile greenProfile(UnitType type){
        float size=type.hitSize/mindustry.Vars.tilesize;
        var region=type.region;
        float sprite=region==null?0:(float)(Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5);
        float support=Math.max(sprite,(float)Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        var cached=green.get(type);if(cached!=null&&cached.size==size&&cached.support==support)return cached.profile;
        var hull=GreenHullProfiles.forName(type.name,size);
        float depth=(float)hull.depth();
        // Corvus grows beneath its original roof, preserving all four existing hip transforms.
        float clearance=type==UnitTypes.corvus?2*sprite-depth:.025f;
        float weaponUp=type==UnitTypes.corvus?sprite+.14f:support+.1f;
        var profile=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,.14f,0,0,weaponUp,0,hull);
        green.put(type,new GreenCache(size,support,profile));return profile;
    }
    private static ProceduralUnitModelProfile fortressProfile(UnitType type){
        float size=type.hitSize/mindustry.Vars.tilesize;var region=type.region;
        double sprite=region==null?0:Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5;
        float support=(float)Math.max(sprite,Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        if(fortress==null||fortressSize!=size||fortressSupport!=support){
            var hull=LayeredHullProfile.fortress(size,FORTRESS_THICKNESS_FACTOR);float depth=(float)hull.depth(),clearance=.10f,weaponDepth=.18f;
            fortress=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,weaponDepth,0,0,
                clearance+depth-support+weaponDepth*.5f+.035f,0,hull);
            fortressSize=size;fortressSupport=support;
        }
        return fortress;
    }
    private static ProceduralUnitModelProfile scepterProfile(UnitType type){
        float size=type.hitSize/mindustry.Vars.tilesize;var region=type.region;
        double sprite=region==null?0:Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5;
        float support=(float)Math.max(sprite,Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        if(scepter==null||scepterSize!=size||scepterSupport!=support){
            var hull=LayeredHullProfile.scepter(size,SCEPTER_THICKNESS_FACTOR);float depth=(float)hull.depth(),clearance=.12f,weaponDepth=.22f;
            scepter=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,weaponDepth,0,0,
                clearance+depth-support+weaponDepth*.5f+.045f,0,hull);
            scepterSize=size;scepterSupport=support;
        }
        return scepter;
    }
    private static ProceduralUnitModelProfile reignProfile(UnitType type){
        float size=type.hitSize/mindustry.Vars.tilesize;var region=type.region;
        double sprite=region==null?0:Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5;
        float support=(float)Math.max(sprite,Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        if(reign==null||reignSize!=size||reignSupport!=support){
            var hull=LayeredHullProfile.reign(size,REIGN_THICKNESS_FACTOR);float depth=(float)hull.depth(),clearance=.14f,weaponDepth=.28f;
            reign=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,weaponDepth,0,0,
                clearance+depth-support+weaponDepth*.5f+.055f,0,hull);
            reignSize=size;reignSupport=support;
        }
        return reign;
    }
    private static ProceduralUnitModelProfile velaProfile(UnitType type){
        float size=type.hitSize/mindustry.Vars.tilesize;var region=type.region;
        double sprite=region==null?0:Math.max(fpv.generated.ExtrusionConfig.MIN_DEPTH_TILES,
            Math.min(fpv.generated.ExtrusionConfig.MAX_DEPTH_TILES,Math.min(region.width,region.height)*region.scl()/mindustry.Vars.tilesize*fpv.generated.ExtrusionConfig.DEPTH_FRACTION))*.5;
        float support=(float)Math.max(sprite,Math.min(fpv.generated.UnitsConfig.MAX_BODY_OFFSET,
            Math.max(fpv.generated.UnitsConfig.MIN_BODY_HALF_DEPTH,size*fpv.generated.UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        if(vela==null||velaSize!=size||velaSupport!=support){
            var hull=LayeredHullProfile.vela(size,VELA_THICKNESS_FACTOR);float depth=(float)hull.depth(),clearance=.10f,weaponDepth=.20f;
            vela=new ProceduralUnitModelProfile(depth,1,.95f,0,0,clearance,weaponDepth,0,0,
                clearance+depth-support+weaponDepth*.5f+.035f,0,hull);
            velaSize=size;velaSupport=support;
        }
        return vela;
    }
    /** Grow upward from the existing lower face; presentation depth never changes a hitbox. */
    public float bodyCenterLift(double physicalHalfDepth){
        if(!Double.isFinite(physicalHalfDepth)||physicalHalfDepth<0)throw new IllegalArgumentException("Finite physical envelope required");
        return (float)(bodyThickness*.5-physicalHalfDepth);
    }
    /** Render-only upper envelope, shared by eye clearance, culling and the existing overhead marker. */
    public double topOffset(double supportHalfDepth,double width,double length,double pitch,double roll){
        double p=Math.toRadians(pitch),r=Math.toRadians(roll),up=Math.cos(p)*Math.cos(r);
        return groundClearance+bodyCenterLift(supportHalfDepth)*up+bodyThickness*.5*Math.abs(up)
            +width*bodyScale*.5*Math.abs(Math.cos(p)*Math.sin(r))+length*bodyScale*.5*Math.abs(Math.sin(p));
    }
    /** Preserve the proven eye placement independently of changing cosmetic hull bands. */
    public double cameraTopOffset(double supportHalfDepth,double width,double length,double pitch,double roll){
        if(hull!=null&&hull.cameraReferenceDepth()==0)return 0; // Explicitly retain the pre-prototype Mace eye.
        double actual=topOffset(supportHalfDepth,width,length,pitch,roll);if(hull==null)return actual;
        double p=Math.toRadians(pitch),r=Math.toRadians(roll),up=Math.cos(p)*Math.cos(r),depth=hull.cameraReferenceDepth();
        double reference=(depth*.5-supportHalfDepth)*up+depth*.5*Math.abs(up)
            +width*bodyScale*.5*Math.abs(Math.cos(p)*Math.sin(r))+length*bodyScale*.5*Math.abs(Math.sin(p));
        return Math.max(actual,reference);
    }
    /** Native Weapon.draw uses this same rotation and recoil, in native world units. */
    public static double weaponYaw(double unitYaw,boolean rotates,double mountYaw,double baseYaw){
        return unitYaw+(rotates?mountYaw:baseYaw);
    }
    public static double weaponX(double unitX,double yaw,double nativeX,double nativeY){
        double a=Math.toRadians(yaw-90);return unitX+Math.cos(a)*nativeX-Math.sin(a)*nativeY;
    }
    public static double weaponY(double unitY,double yaw,double nativeX,double nativeY){
        double a=Math.toRadians(yaw-90);return unitY+Math.sin(a)*nativeX+Math.cos(a)*nativeY;
    }
}
