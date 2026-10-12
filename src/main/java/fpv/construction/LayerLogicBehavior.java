package fpv.construction;

import arc.func.Cons;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.io.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.*;

/** Native assembler/executor with layer-local links, bounded configuration and XYZ item transfer. */
final class LayerLogicBehavior {
    private static final int MAX_CODE=100*1024,MAX_LINKS=6000,MAX_CONFIG=12288;
    private record Ref(boolean unit,long id){}
    private static final Map<Unit,Float> lastTransfer=new WeakHashMap<>();
    static Building lookup(LayerBuildingAdapter owner,LogicBuild build,int x,int y){var tile=owner.tile(x,y,((LayerTile)build.tile).layer);return tile==null?null:tile.build;}
    static boolean valid(LogicBuild build,Building target){return target!=null&&target.tile instanceof LayerTile a&&build.tile instanceof LayerTile b&&a.layer==b.layer&&!target.dead&&target.team==build.team&&target!=build&&build.within(target,((LogicBlock)build.block).range+target.block.size*Vars.tilesize/2f);}
    static boolean configure(LayerBuildingAdapter owner,LogicBuild build,Object value){
        if(value instanceof byte[] bytes)return compressed(owner,build,bytes,true);
        if(value instanceof Integer pos){Building target=lookup(owner,build,Point2.x(pos),Point2.y(pos));if(!valid(build,target))return false;
            int previous=build.links.size;build.links.removeAll(link->lookup(owner,build,link.x,link.y)==target);if(previous==build.links.size)build.links.add(new LogicLink(target.tileX(),target.tileY(),build.findLinkName(target.block),true));
            assemble(owner,build,build.code,true,null);return true;
        }return false;
    }
    static boolean compressed(LayerBuildingAdapter owner,LogicBuild build,byte[] compressed,boolean relative){
        if(compressed==null||compressed.length>MAX_CONFIG)return false;
        try(var inflater=new InflaterInputStream(new ByteArrayInputStream(compressed))){
            byte[] data=inflater.readNBytes(MAX_CODE+MAX_LINKS*48+16);if(inflater.read()!=-1)return false;
            DataInputStream input=new DataInputStream(new ByteArrayInputStream(data));int version=input.readUnsignedByte();if(version!=1)return false;
            int length=input.readInt();if(length<0||length>MAX_CODE||length>input.available())return false;
            String code=new String(input.readNBytes(length),StandardCharsets.UTF_8);int count=input.readInt();if(count<0||count>MAX_LINKS)return false;
            Seq<LogicLink> links=new Seq<>();Set<String> names=new HashSet<>();
            for(int i=0;i<count;i++){String name=input.readUTF();int x=input.readShort(),y=input.readShort();if(name.length()>32||!name.matches("[A-Za-z][A-Za-z0-9_-]*")||!names.add(name))return false;if(relative){x+=build.tileX();y+=build.tileY();}links.add(new LogicLink(x,y,name,false));}
            if(input.available()!=0)return false;
            LAssembler.assemble(code,false); // Reject malformed source before changing live links/state.
            build.links=links;assemble(owner,build,code,false,null);return true;
        }catch(IOException|RuntimeException invalid){return false;}
    }
    static void assemble(LayerBuildingAdapter owner,LogicBuild build,String code,boolean keep,Cons<LAssembler> inject){
        if(code==null)return;if(code.length()>MAX_CODE)throw new IllegalArgumentException("Upper logic source too large");
        var assembler=LAssembler.assemble(code,false);Object oldUnit=build.executor.unit==null?null:build.executor.unit.objval;
        if(keep)for(LVar variable:build.executor.vars)if(!variable.constant){LVar destination=assembler.getVar(variable.name);if(destination!=null&&!destination.constant)destination.set(variable);}
        build.linkMap=null;build.code=code;ArrayList<Building> connected=new ArrayList<>();
        for(LogicLink link:build.links){Building target=lookup(owner,build,link.x,link.y);link.valid=valid(build,target);link.lastBuild=target;if(link.valid){connected.add(target);link.logicVar=assembler.putConst(link.name,target);}}
        build.executor.links=connected.toArray(Building[]::new);build.executor.linkIds.clear();for(Building target:connected)build.executor.linkIds.add(target.id);
        build.linksVar=assembler.putConst("@links",connected.size());assembler.putConst("@ipt",build.ipt);assembler.putConst("@this",build);assembler.putConst("@thisx",World.conv(build.x));assembler.putConst("@thisy",World.conv(build.y));assembler.putConst("@thisz",((LayerTile)build.tile).layer);
        if(inject!=null)inject.get(assembler);build.executor.load(assembler);build.executor.team=build.team;
        if(keep&&oldUnit!=null)build.executor.unit.setconst(oldUnit);
        for(int i=0;i<build.executor.instructions.length;i++)if(build.executor.instructions[i] instanceof UnitControlI instruction)build.executor.instructions[i]=executor->unitControl(owner,build,instruction,executor);
    }
    static void update(LayerBuildingAdapter owner,LogicBuild build){
        if(owner.restoringState())return;build.checkReadCode();build.executor.team=build.team;
        boolean changed=false;for(LogicLink link:build.links){Building target=lookup(owner,build,link.x,link.y);if(target!=link.lastBuild||link.valid!=valid(build,target)){changed=true;break;}}
        if(changed)assemble(owner,build,build.code,true,null);
        for(LVar variable:build.executor.vars)if(variable.objval instanceof Ref ref)variable.setobj(resolve(owner,ref));
        if(build.executor.unit!=null&&build.executor.unit.objval instanceof Ref ref)build.executor.unit.setconst(resolve(owner,ref));
        if(!build.enabled||!build.executor.initialized())return;
        LogicBlock type=(LogicBlock)build.block;build.accumulator=Math.min(build.accumulator,type.maxInstructionScale*build.ipt);
        while(build.accumulator>=1){build.executor.runOnce();if(build.executor.yield){build.executor.yield=false;break;}build.accumulator--;}
        build.accumulator+=build.edelta()*build.ipt;
    }
    private static void unitControl(LayerBuildingAdapter owner,LogicBuild processor,UnitControlI instruction,LExecutor executor){
        Unit unit=executor.unit.obj() instanceof Unit value?value:null;
        if(unit==null||unit.team!=processor.team||unit.dead||!unit.isAdded())return;
        switch(instruction.type){
            case itemTake,itemDrop -> {
                if(!Vars.state.rules.logicUnitControl||arc.util.Time.time-lastTransfer.getOrDefault(unit,-1e9f)<mindustry.ai.types.LogicAI.transferDelay)return;
                if(instruction.type==LUnitControl.itemDrop&&instruction.p1.obj()==Blocks.air){unit.clearItem();return;}
                Building target=instruction.p1.building();if(target==null||target.team!=unit.team||target.dead)return;
                double dz=(owner.unitAltitude(unit)-owner.buildingAltitude(target))*Vars.tilesize,range=Vars.logicItemTransferRange+target.block.size*Vars.tilesize*.5;
                if(unit.dst2(target)+dz*dz>range*range)return;
                if(instruction.type==LUnitControl.itemTake&&target.items!=null&&instruction.p2.obj() instanceof Item item){int amount=Math.max(0,Math.min(instruction.p3.numi(),Math.min(target.items.get(item),unit.maxAccepted(item))));if(amount>0){target.removeStack(item,amount);unit.addItem(item,amount);lastTransfer.put(unit,arc.util.Time.time);}}
                else if(instruction.type==LUnitControl.itemDrop&&target.allowDeposit()&&unit.stack.amount>0){int amount=Math.max(0,target.acceptStack(unit.item(),Math.min(unit.stack.amount,Math.max(0,instruction.p2.numi())),unit));if(amount>0){target.handleStack(unit.item(),amount,unit);unit.stack.amount-=amount;lastTransfer.put(unit,arc.util.Time.time);}}
            }
            case getBlock -> {
                int x=Math.round(instruction.p1.numf()),y=Math.round(instruction.p2.numf());var tile=owner.tile(x,y,((LayerTile)processor.tile).layer);
                double dz=(owner.unitAltitude(unit)-owner.buildingAltitude(processor))*Vars.tilesize,range=Math.max(unit.range(),unit.type.buildRange);
                boolean inRange=unit.dst2(x*Vars.tilesize,y*Vars.tilesize)+dz*dz<=range*range;
                instruction.p3.setobj(inRange&&tile!=null?tile.block():null);instruction.p4.setobj(inRange&&tile!=null?tile.build:null);instruction.p5.setobj(inRange&&tile!=null?tile.floor():null);
            }
            case build -> {
                if(!Vars.state.rules.logicUnitControl||!Vars.state.rules.logicUnitBuild||!unit.canBuild()||!(instruction.p3.obj() instanceof Block block))return;
                int x=Math.round(instruction.p1.numf()-block.offset/Vars.tilesize),y=Math.round(instruction.p2.numf()-block.offset/Vars.tilesize);
                owner.logicBuild(processor,unit,x,y,block,Math.floorMod(instruction.p4.numi(),4),instruction.p5.obj(),false);
            }
            case deconstruct -> {
                if(Vars.state.rules.logicUnitControl&&Vars.state.rules.logicUnitDeconstruct&&unit.canBuild())owner.logicBuild(processor,unit,Math.round(instruction.p1.numf()),Math.round(instruction.p2.numf()),null,0,null,true);
            }
            case mine -> {
                if(!Vars.state.rules.logicUnitControl)return;
                var tile=owner.tile(Math.round(instruction.p1.numf()),Math.round(instruction.p2.numf()),((LayerTile)processor.tile).layer);
                unit.mineTile=tile!=null&&unit.validMine(tile)?tile:null;
            }
            case payDrop,payTake,payEnter -> {
                LayerLogicPayloadBehavior.run(owner,processor,unit,instruction.type,instruction.p1.bool());
            }
            default -> instruction.run(executor);
        }
    }
    static void write(LogicBuild build,Writes out){
        if(build.links.size>MAX_LINKS||build.executor.vars.length>4000)throw new IllegalArgumentException("Upper logic state count");
        for(LVar variable:build.executor.vars)if(variable.name.length()>256)throw new IllegalArgumentException("Upper logic variable name");
        byte[] source=build.code.getBytes(StandardCharsets.UTF_8);if(source.length>MAX_CODE)throw new IllegalArgumentException("Upper logic source size");out.i(source.length);out.b(source);out.s(build.links.size);for(LogicLink link:build.links){out.s(link.x);out.s(link.y);out.str(link.name);}
        out.f(build.accumulator);out.s(build.ipt);out.s(build.executor.vars.length);
        for(LVar variable:build.executor.vars){out.str(variable.name);writeValue(out,variable.isobj?variable.objval:variable.numval);}
        writeValue(out,build.executor.unit==null?null:build.executor.unit.objval);
    }
    static void read(LayerBuildingAdapter owner,LogicBuild build,Reads in){
        int length=in.i();if(length<0||length>MAX_CODE)throw new IllegalArgumentException("Upper logic source length");byte[] bytes=new byte[length];in.b(bytes);String code=new String(bytes,StandardCharsets.UTF_8);int links=in.us();if(links>MAX_LINKS)throw new IllegalArgumentException("Upper logic links");build.links.clear();
        for(int i=0;i<links;i++){int x=in.s(),y=in.s();String name=in.str();if(name.length()>32)throw new IllegalArgumentException("Upper link name");build.links.add(new LogicLink(x,y,name,false));}
        float accumulator=in.f();int ipt=in.us(),variables=in.us();if(!Float.isFinite(accumulator)||accumulator<0||variables>4000||ipt<1||ipt>((LogicBlock)build.block).instructionsPerTick)throw new IllegalArgumentException("Upper logic execution state");
        String[] names=new String[variables];Object[] values=new Object[variables];for(int i=0;i<variables;i++){names[i]=in.str();if(names[i].length()>256)throw new IllegalArgumentException("Variable name");values[i]=readValue(in);}
        Object unit=readValue(in);build.ipt=ipt;build.accumulator=accumulator;
        assemble(owner,build,code,false,assembler->{for(int i=0;i<variables;i++){LVar variable=assembler.getVar(names[i]);if(variable!=null&&!variable.constant){if(values[i] instanceof Number number)variable.setnum(number.doubleValue());else variable.setobj(values[i]);}}});
        build.executor.unit.setconst(unit);
    }
    private static void writeValue(Writes out,Object value){
        if(value==null){out.b(0);}else if(value instanceof Number number){out.b(1);out.d(number.doubleValue());}
        else if(value instanceof Ref ref){if(ref.unit){out.b(4);out.i((int)ref.id);}else{out.b(3);out.s((int)(ref.id>>>16)&65535);out.s((int)ref.id&65535);out.s((int)(ref.id>>>32));}}
        else if(value instanceof String text){out.b(2);out.str(text.length()>4096?text.substring(0,4096):text);}
        else if(value instanceof Building building){out.b(3);out.s(building.tileX());out.s(building.tileY());out.s(building.tile instanceof LayerTile layer?layer.layer:0);}
        else if(value instanceof Unit unit){out.b(4);out.i(unit.id);}
        else if(value instanceof mindustry.ctype.Content content){out.b(5);out.b(content.getContentType().ordinal());out.s(content.id);}
        else out.b(0);
    }
    private static Object readValue(Reads in){return switch(in.ub()){
        case 0->null;case 1->{double number=in.d();if(!Double.isFinite(number))number=0;yield number;}
        case 2->{String text=in.str();if(text.length()>4096)throw new IllegalArgumentException("Upper logic string");yield text;}
        case 3->{int x=in.us(),y=in.us(),layer=in.us();yield new Ref(false,((long)layer<<32)|((long)x<<16)|y);}
        case 4->new Ref(true,in.i());
        case 5->{int type=in.ub(),id=in.us();var types=mindustry.ctype.ContentType.all;if(type>=types.length)throw new IllegalArgumentException("Upper logic content");yield Vars.content.getByID(types[type],id);}
        default->throw new IllegalArgumentException("Upper logic value type");};}
    private static Object resolve(LayerBuildingAdapter owner,Ref ref){if(ref.unit)return Groups.unit.getByID((int)ref.id);int layer=(int)(ref.id>>>32),x=(int)(ref.id>>>16)&65535,y=(int)ref.id&65535;return layer==0?Vars.world.build(x,y):owner.tile(x,y,layer)==null?null:owner.tile(x,y,layer).build;}
    static Object presentationValue(LayerBuildingAdapter owner,Object value){return value instanceof Ref ref?resolve(owner,ref):value;}
    static void writeMemory(mindustry.world.blocks.logic.MemoryBlock.MemoryBuild build,Writes out){
        int capacity=((mindustry.world.blocks.logic.MemoryBlock)build.block).memoryCapacity;out.s(capacity);LVar position=new LVar("position"),value=new LVar("value");
        for(int i=0;i<capacity;i++){position.setnum(i);build.read(position,value);writeValue(out,value.isobj?value.objval:value.numval);}
    }
    static void readMemory(mindustry.world.blocks.logic.MemoryBlock.MemoryBuild build,Reads in){
        int count=in.us(),capacity=((mindustry.world.blocks.logic.MemoryBlock)build.block).memoryCapacity;if(count!=capacity||count>4096)throw new IllegalArgumentException("Upper memory capacity");
        LVar position=new LVar("position"),value=new LVar("value");for(int i=0;i<count;i++){position.setnum(i);Object item=readValue(in);if(item instanceof Number number)value.setnum(number.doubleValue());else value.setobj(item);build.write(position,value);}
    }
    static void resolveMemory(LayerBuildingAdapter owner,mindustry.world.blocks.logic.MemoryBlock.MemoryBuild build){
        int capacity=((mindustry.world.blocks.logic.MemoryBlock)build.block).memoryCapacity;LVar position=new LVar("position"),value=new LVar("value");
        for(int i=0;i<capacity;i++){position.setnum(i);build.read(position,value);if(value.objval instanceof Ref ref){value.setobj(resolve(owner,ref));build.write(position,value);}}
    }
}
