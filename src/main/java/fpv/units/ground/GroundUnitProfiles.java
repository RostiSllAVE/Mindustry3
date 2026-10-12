package fpv.units.ground;

import arc.Core;
import arc.util.Time;
import fpv.generated.GroundConfig;
import fpv.generated.RenderConfig;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.type.UnitType;
import fpv.generated.DefenseConfig;
import java.util.IdentityHashMap;
import java.util.function.DoubleSupplier;
import java.nio.ByteBuffer;

/** Family registry shared by headless physics and the renderer. No UnitType mutation. */
public final class GroundUnitProfiles {
    public record Settings(double atraxExtension,double spiroctExtension,double arkyidExtension,double toxopidExtension,double minSpeedFraction,double greenWalkerHeightMultiplier){
        public Settings(double atrax,double spiroct,double arkyid,double toxopid,double minimumSpeed){this(atrax,spiroct,arkyid,toxopid,minimumSpeed,GroundConfig.GREEN_WALKER_HEIGHT_MULTIPLIER);}
        public Settings{
            double[] heights={atraxExtension,spiroctExtension,arkyidExtension,toxopidExtension};
            for(double h:heights)if(!Double.isFinite(h)||h<0||h>512)throw new IllegalArgumentException("Extension outside 0..512 tiles");
            if(!Double.isFinite(greenWalkerHeightMultiplier)||greenWalkerHeightMultiplier<1||greenWalkerHeightMultiplier>8)throw new IllegalArgumentException("Green walker height multiplier outside 1..8");
            if(!Double.isFinite(minSpeedFraction)||minSpeedFraction<.05||minSpeedFraction>1)throw new IllegalArgumentException("Speed fraction outside .05..1");
        }
    }
    private static Settings remote,cached,profileSettings;
    private static DoubleSupplier nativeStepMultiplier;
    /** Host or already-replicated projection tuning; independent of a client preference. */
    public static DoubleSupplier setNativeStepMultiplierSupplier(DoubleSupplier supplier){DoubleSupplier previous=nativeStepMultiplier;nativeStepMultiplier=supplier;cachedProfiles.clear();return previous;}
    private record Cached(double size,double reach,int legs,GroundUnit3DModelProfile profile){}
    private static final IdentityHashMap<UnitType,Cached> cachedProfiles=new IdentityHashMap<>();
    private static long settingsFrame=Long.MIN_VALUE;
    private GroundUnitProfiles(){}
    public static boolean supported(String name){return "crawler".equals(name)||"atrax".equals(name)||"spiroct".equals(name)||"arkyid".equals(name)||"toxopid".equals(name);}
    public static boolean tallGreenWalker(String name){return "vela".equals(name)||"corvus".equals(name);}
    /** Multiply the existing total clearance, preserving its unextended minimum. */
    public static double scaledGreenExtension(String name,double normal,double extension,Settings settings){
        return tallGreenWalker(name)?(normal+extension)*settings.greenWalkerHeightMultiplier()-normal:extension;
    }
    public static boolean extensibleMech(Unit unit){return unit!=null&&unit.type!=null&&unit instanceof Mechc&&"vela".equals(unit.type.name)&&!unit.type.flying;}
    public static boolean stanceCapable(Unit unit){return nativeLegged(unit)||extensibleMech(unit);}
    public static boolean nativeLegged(Unit unit){return unit!=null&&unit.type!=null&&unit instanceof Legsc&&!unit.type.flying&&unit.type.legCount>0;}
    public static GroundUnit3DModelProfile profile(Unit unit){
        if(unit==null||unit.type==null||unit.type.flying)return null;
        boolean explicit=supported(unit.type.name);
        boolean mech=extensibleMech(unit);
        if(!explicit&&!nativeLegged(unit)&&!mech)return null;
        Settings current=settings();double size=unit.hitSize/(double)Vars.tilesize;
        if(!current.equals(profileSettings)){cachedProfiles.clear();profileSettings=current;}
        double nativeReach=mech?NativeMechLayout.reach(unit.type):unit.type.legLength*unit.type.legLengthScl/Vars.tilesize;
        double scale=nativeStepMultiplier!=null?nativeStepMultiplier.getAsDouble():Core.settings==null?DefenseConfig.LEG_STEP_HEIGHT_MULTIPLIER:
            Math.max(50,Math.min(300,Core.settings.getInt("fpv-leg-step-percent",(int)Math.round(DefenseConfig.LEG_STEP_HEIGHT_MULTIPLIER*100))))/100.0;
        if(!Double.isFinite(scale))throw new IllegalArgumentException("Finite native leg-step multiplier required");scale=Math.max(.5,Math.min(3,scale));
        // Capability is from native type/body, never temporary creator-flight elevation.
        double reach=explicit?0:Math.max(.1,(unit.type.allowLegStep||mech)?Math.min(DefenseConfig.MAX_LEG_STEP_HEIGHT,nativeReach*scale):nativeReach);
        int nativeCount=mech?2:unit.type.legCount;
        Cached cached=cachedProfiles.get(unit.type);
        if(cached==null||cached.size!=size||cached.reach!=reach||cached.legs!=nativeCount){
            GroundUnit3DModelProfile value;
            if(explicit)value=profile(unit.type.name,size,current);
            else value=new GroundUnit3DModelProfile(unit.type.name,GroundUnit3DModelProfile.BodyStyle.LEGGED,0,legCount(nativeCount),
                1,Math.max(.15,size*.5*GroundConfig.NORMAL_STANCE_PER_RADIUS),
                scaledGreenExtension(unit.type.name,Math.max(.15,size*.5*GroundConfig.NORMAL_STANCE_PER_RADIUS),reach,current),current.minSpeedFraction,1);
            if(value.legged()){
                double spread=mech?NativeMechLayout.spread(unit.type):NativeLegLayout.spread(unit.type,value.legCount());
                value=new GroundUnit3DModelProfile(value.unitName(),value.bodyStyle(),0,value.legCount(),value.thicknessMultiplier(),
                    value.normalStance(),value.maxExtension(),value.minSpeedFraction(),Math.max(.25,Math.min(8,spread/Math.max(.05,size*.5))));
            }
            cached=new Cached(size,reach,nativeCount,value);cachedProfiles.put(unit.type,cached);
        }
        return cached.profile;
    }
    public static Settings defaults(){
        double wall=RenderConfig.WALL_HEIGHT_BASE+RenderConfig.WALL_HEIGHT_PER_SIZE;
        return new Settings(wall*GroundConfig.ATRAX_WALL_HEIGHTS,wall*GroundConfig.SPIROCT_WALL_HEIGHTS,
            wall*GroundConfig.ARKYID_WALL_HEIGHTS,wall*GroundConfig.TOXOPID_WALL_HEIGHTS,GroundConfig.MIN_EXTENDED_SPEED_FRACTION,GroundConfig.GREEN_WALKER_HEIGHT_MULTIPLIER);
    }
    public static Settings settings(){
        if(Vars.net!=null&&Vars.net.client()&&remote!=null)return remote;
        long frame=Core.graphics==null?Float.floatToIntBits(Time.time):Core.graphics.getFrameId();
        if(cached==null||frame!=settingsFrame){
            Settings d=defaults();
            cached=Core.settings==null?d:new Settings(read("atrax",d.atraxExtension),read("spiroct",d.spiroctExtension),
                read("arkyid",d.arkyidExtension),read("toxopid",d.toxopidExtension),
                Math.max(5,Math.min(100,Core.settings.getInt("fpv-ground-min-speed-percent",(int)Math.round(d.minSpeedFraction*100))))/100.0,
                Math.max(100,Math.min(800,Core.settings.getInt("fpv-green-walker-height-percent",(int)Math.round(d.greenWalkerHeightMultiplier*100))))/100.0);
            settingsFrame=frame;
        }
        return cached;
    }
    private static double read(String name,double fallback){return Math.max(0,Math.min(5120,Core.settings.getInt("fpv-ground-"+name+"-extension",(int)Math.round(fallback*10))))/10.0;}
    public static void refresh(){settingsFrame=Long.MIN_VALUE;cached=null;}
    public static void applyRemoteSettings(Settings settings){if(settings==null)throw new IllegalArgumentException("Settings required");remote=settings;}
    public static void clearRemoteSettings(){remote=null;refresh();}
    public static final int ENCODED_BYTES=48;
    public static byte[] encode(Settings value){return ByteBuffer.allocate(ENCODED_BYTES).putDouble(value.atraxExtension).putDouble(value.spiroctExtension).putDouble(value.arkyidExtension).putDouble(value.toxopidExtension).putDouble(value.minSpeedFraction).putDouble(value.greenWalkerHeightMultiplier).array();}
    public static Settings decode(byte[] bytes){if(bytes==null||bytes.length!=ENCODED_BYTES)throw new IllegalArgumentException("Ground settings payload length");ByteBuffer b=ByteBuffer.wrap(bytes);return new Settings(b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble());}
    private static int legCount(double value){
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<2||value>8||((int)value)%2!=0)
            throw new IllegalArgumentException("Ground rig supports an even leg count from 2 through 8");
        return (int)value;
    }
    public static GroundUnit3DModelProfile profile(String name,double hitSizeTiles,Settings config){
        if(!supported(name))return null;
        if(!Double.isFinite(hitSizeTiles)||hitSizeTiles<=0||config==null)throw new IllegalArgumentException("Valid unit size/settings required");
        double radius=hitSizeTiles*.5;
        if("crawler".equals(name))return new GroundUnit3DModelProfile(name,GroundUnit3DModelProfile.BodyStyle.WHEELED,4,0,
            GroundConfig.CRAWLER_THICKNESS_MULTIPLIER,GroundConfig.CRAWLER_STANCE_TILES,0,1,GroundConfig.CRAWLER_FOOTPRINT_SCALE);
        int legs=legCount(switch(name){case "atrax"->GroundConfig.ATRAX_LEG_COUNT;case "spiroct"->GroundConfig.SPIROCT_LEG_COUNT;
            case "arkyid"->GroundConfig.ARKYID_LEG_COUNT;default->GroundConfig.TOXOPID_LEG_COUNT;});
        double extension=switch(name){case "atrax"->config.atraxExtension;case "spiroct"->config.spiroctExtension;case "arkyid"->config.arkyidExtension;default->config.toxopidExtension;};
        return new GroundUnit3DModelProfile(name,GroundUnit3DModelProfile.BodyStyle.LEGGED,0,legs,
            GroundConfig.SPIDER_THICKNESS_MULTIPLIER,Math.max(.15,radius*GroundConfig.NORMAL_STANCE_PER_RADIUS),
            extension,config.minSpeedFraction,GroundConfig.SPIDER_FOOTPRINT_SCALE);
    }
}

