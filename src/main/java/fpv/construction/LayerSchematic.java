package fpv.construction;

import arc.Core;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.serialization.Jval;
import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.power.PowerNode;

/** One horizontal layer in the normal .msch format; optional 2D slab positions are ordinary tags. */
public final class LayerSchematic {
    public static final int MAX_PLANS=4096,MAX_ENCODED=LayerConfiguration.MAX_BYTES;
    public static final String PLATFORMS="fpv-platforms";
    private LayerSchematic(){}
    public static final class PlatformPlan extends BuildPlan {public PlatformPlan(int x,int y){super(x,y,0,Blocks.air);}}
    public static boolean platform(BuildPlan p){return p instanceof PlatformPlan;}
    public record Stamp(Schematic schematic,int x,int y){}
    public static Schematic capture(LayerRuntime runtime,int layer,int ax,int ay,int bx,int by){
        if(layer==0)return Vars.schematics.create(ax,ay,bx,by);
        int left=Math.max(0,Math.min(ax,bx)),bottom=Math.max(0,Math.min(ay,by));
        int right=Math.min(Vars.world.width()-1,Math.min(left+127,Math.max(ax,bx))),top=Math.min(Vars.world.height()-1,Math.min(bottom+127,Math.max(ay,by)));
        var found=new LinkedHashMap<Long,LayerComponent>();
        for(int x=left;x<=right;x++)for(int y=bottom;y<=top;y++)for(var kind:LayerComponent.Kind.values()){
            var c=runtime.grid().at(x,y,layer,kind);if(c==null||c.health()<=0)continue;
            var b=runtime.building(c.id());
            if(Vars.state.rules.fog&&Vars.state.rules.staticFog&&!Vars.fogControl.isDiscovered(Vars.player.team(),x,y)||b!=null&&b.inFogTo(Vars.player.team()))continue;
            found.put(c.id(),c);
        }
        if(found.isEmpty())return new Schematic(new Seq<>(),new StringMap(),1,1);
        int minX=32767,minY=32767,maxX=0,maxY=0;
        var selected=new HashSet<Integer>();
        for(var c:found.values()){int x=LayerCoordinates.minCell(c.x(),c.size()),y=LayerCoordinates.minCell(c.y(),c.size());minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x+c.size()-1);maxY=Math.max(maxY,y+c.size()-1);if(c.kind()==LayerComponent.Kind.BLOCK)selected.add(Point2.pack(c.x(),c.y()));}
        if(maxX-minX>=128||maxY-minY>=128||found.size()>MAX_PLANS)throw new IllegalArgumentException("Selection exceeds schematic limits (128 tiles / 4096 entries)");
        var tiles=new Seq<Schematic.Stile>();var slabs=Jval.newArray();int omitted=0;
        for(var c:found.values()){
            if(c.kind()==LayerComponent.Kind.PLATFORM){slabs.add(Jval.newArray().add(c.x()-minX).add(c.y()-minY));continue;}
            Block block=Vars.content.getByName(mindustry.ctype.ContentType.block,c.blockName());if(block==null||!block.isVisible()&&!(block instanceof mindustry.world.blocks.storage.CoreBlock))continue;
            Building b=runtime.building(c.id());Object config=b==null?null:b.config();
            if(block instanceof PowerNode){var points=new ArrayList<Point2>();for(var link:runtime.nativeBuildings().powerLinks()){
                if(link.vertical())continue;long other=link.first()==c.id()?link.second():link.second()==c.id()?link.first():0;
                var target=runtime.grid().get(other);if(target!=null&&selected.contains(Point2.pack(target.x(),target.y())))points.add(new Point2(target.x()-c.x(),target.y()-c.y()));
            }config=points.toArray(Point2[]::new);}
            try{config=internalConfig(block,config,c.x(),c.y(),selected);if(!supported(config))throw new IllegalArgumentException();}
            catch(RuntimeException invalid){config=null;omitted++;}
            tiles.add(new Schematic.Stile(block,c.x()-minX,c.y()-minY,config,(byte)c.rotation()));
        }
        var tags=new StringMap();if(slabs.asArray().size>0)tags.put(PLATFORMS,slabs.toString(Jval.Jformat.plain));
        if(omitted>0)tags.put("fpv-copy-warning",omitted+" unsupported configurations omitted");
        return decode(encode(new Schematic(tiles,tags,maxX-minX+1,maxY-minY+1)));
    }
    public static Object internalConfig(Block block,Object config,int x,int y,Set<Integer> included){
        if(config instanceof Point2 p&&!included.contains(Point2.pack(x+p.x,y+p.y)))return null;
        if(config instanceof Point2[] points)return Arrays.stream(points).filter(p->included.contains(Point2.pack(x+p.x,y+p.y))).map(Point2::cpy).toArray(Point2[]::new);
        return BuildPlan.pointConfig(block,config,p->{if(!included.contains(Point2.pack(x+p.x,y+p.y)))p.set(0,0);});
    }
    public static boolean supported(Object config){if(config instanceof Point2[] p)return p.length<=255&&Arrays.stream(p).allMatch(LayerConfiguration::validOffset);try{LayerConfiguration.encode(config);return true;}catch(RuntimeException unsupported){return false;}}
    public static Seq<BuildPlan> plans(Schematic s,int x,int y){
        var out=new Seq<BuildPlan>();for(var t:s.tiles)out.add(new BuildPlan(t.x+x-s.width/2,t.y+y-s.height/2,t.rotation,t.block,t.config));
        out.sort(Comparator.comparingInt(p->-p.block.schematicPriority));
        for(var p:slabs(s))out.insert(0,new PlatformPlan(p.x+x-s.width/2,p.y+y-s.height/2));return out;
    }
    public static Seq<Point2> slabs(Schematic s){
        var out=new Seq<Point2>();String json=s.tags.get(PLATFORMS);if(json==null)return out;
        try{var values=Jval.read(json).asArray();if(values.size>MAX_PLANS)throw new IllegalArgumentException("Too many platforms");var seen=new HashSet<Integer>();
            for(var value:values){var pair=value.asArray();if(pair.size!=2)throw new IllegalArgumentException("Platform coordinates");double x=pair.get(0).asDouble(),y=pair.get(1).asDouble();
                if(!Double.isFinite(x+y)||x!=Math.rint(x)||y!=Math.rint(y)||x<0||y<0||x>=s.width||y>=s.height)throw new IllegalArgumentException("Platform outside schematic");
                var p=new Point2((int)x,(int)y);if(!seen.add(p.pack()))throw new IllegalArgumentException("Duplicate platform");out.add(p);
            }return out;
        }catch(StackOverflowError|RuntimeException bad){throw new IllegalArgumentException("Invalid platform metadata",bad);}
    }
    /** Native encoding creates independent config values; no inventories, health or live modules are serialized. */
    public static byte[] encode(Schematic s){try{var out=new ByteArrayOutputStream();Schematics.write(s,out);byte[] bytes=out.toByteArray();if(bytes.length>MAX_ENCODED)throw new IllegalArgumentException("Schematic exceeds 16 KiB upper request limit");return bytes;}catch(IOException bad){throw new IllegalArgumentException("Schematic encoding failed",bad);}}
    public static Schematic decode(byte[] bytes){
        if(bytes==null||bytes.length<6||bytes.length>MAX_ENCODED)throw new IllegalArgumentException("Schematic size limit");
        try{
            // Bound expansion before the host parser allocates anything; keep the native format.
            byte[] expanded;try(var inflate=new InflaterInputStream(new ByteArrayInputStream(bytes,5,bytes.length-5))){expanded=inflate.readNBytes(1024*1024+1);if(expanded.length>1024*1024)throw new IllegalArgumentException("Schematic expansion limit");}
            preflight(expanded);
            var s=Schematics.read(new ByteArrayInputStream(bytes));if(s.width<1||s.height<1||s.width>128||s.height>128||s.tiles.size>MAX_PLANS)throw new IllegalArgumentException("Schematic dimensions/count limit");
            var occupied=new HashSet<Integer>();for(var t:s.tiles){if(t.block==null||t.block==Blocks.air||t.rotation<0||t.rotation>3||!supported(t.config))throw new IllegalArgumentException("Unsupported schematic block/configuration");int mx=LayerCoordinates.minCell(t.x,t.block.size),my=LayerCoordinates.minCell(t.y,t.block.size);
                if(mx<0||my<0||mx+t.block.size>s.width||my+t.block.size>s.height)throw new IllegalArgumentException("Schematic footprint outside bounds");
                for(int x=mx;x<mx+t.block.size;x++)for(int y=my;y<my+t.block.size;y++)if(!occupied.add(Point2.pack(x,y)))throw new IllegalArgumentException("Overlapping schematic footprints");
            }if(s.tiles.size+slabs(s).size>MAX_PLANS)throw new IllegalArgumentException("Too many schematic entries");return s;
        }catch(StackOverflowError|IOException|RuntimeException bad){throw new IllegalArgumentException("Invalid or unsupported native schematic",bad);}
    }
    /** Validate native JSON tag shapes before the host's polymorphic JSON reader sees network input. */
    private static void preflight(byte[] expanded)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(expanded))){
            int w=in.readShort(),h=in.readShort();if(w<1||h<1||w>128||h>128)throw new IOException("Schematic dimensions");
            int tags=in.readUnsignedByte();for(int i=0;i<tags;i++){
                String key=in.readUTF(),value=in.readUTF();if(!key.equals("contentMap")&&!key.equals("labels"))continue;
                int depth=0;boolean quoted=false,escape=false;for(char c:value.toCharArray()){
                    if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='\"'){quoted=!quoted;continue;}
                    if(!quoted){if(c=='['||c=='{'){if(++depth>8)throw new IOException("Schematic metadata nesting");}else if(c==']'||c=='}')depth--;}
                }
                var json=Jval.read(value);
                if(key.equals("labels")){for(var label:json.asArray())if(!label.isString())throw new IOException("Schematic label type");}
                else for(var entry:json.asObject()){
                    int type=Integer.parseInt(entry.key);if(type<0||type>=mindustry.ctype.ContentType.all.length)throw new IOException("Content type");
                    for(var content:entry.value.asObject()){double id=content.value.asDouble();if(!content.key.matches("[a-zA-Z0-9_.-]{1,128}")||id<0||id>32767||id!=Math.rint(id))throw new IOException("Content mapping");}
                }
            }
            int blocks=in.readUnsignedByte();for(int i=0;i<blocks;i++)in.readUTF();int count=in.readInt();if(count<0||count>MAX_PLANS)throw new IOException("Schematic entry count");
        }
    }
    public static Stamp stamp(Seq<BuildPlan> plans){
        if(plans.isEmpty())throw new IllegalArgumentException("Empty schematic");int minX=32767,minY=32767,maxX=0,maxY=0;
        for(var p:plans){int x=LayerCoordinates.minCell(p.x,p.block.size),y=LayerCoordinates.minCell(p.y,p.block.size);minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x+p.block.size-1);maxY=Math.max(maxY,y+p.block.size-1);}
        var tiles=new Seq<Schematic.Stile>();var platforms=Jval.newArray();for(var p:plans)if(platform(p))platforms.add(Jval.newArray().add(p.x-minX).add(p.y-minY));else tiles.add(new Schematic.Stile(p.block,p.x-minX,p.y-minY,p.config,(byte)p.rotation));
        var tags=new StringMap();if(platforms.asArray().size>0)tags.put(PLATFORMS,platforms.toString(Jval.Jformat.plain));
        var s=new Schematic(tiles,tags,maxX-minX+1,maxY-minY+1);return new Stamp(s,minX+s.width/2,minY+s.height/2);
    }
}
