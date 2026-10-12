package fpv.construction;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Small, bounded requests. Identity, costs, support and damage are never supplied by a client. */
public final class LayerActions {
    private LayerActions(){}
    public enum Op { PLACE, REMOVE, ROTATE, DEPOSIT, WITHDRAW, LINK, UNLINK, CONFIGURE, RESPAWN, PASTE, CANCEL_PASTE, POSSESS_TURRET, RELEASE_TURRET }
    public record Action(Op op,int x,int y,int layer,LayerComponent.Kind kind,String blockName,
                         int rotation,LayerComponent.Mode mode,long componentId,int itemId,int amount,long targetId,byte[] configuration){
        public Action(Op op,int x,int y,int layer,LayerComponent.Kind kind,String blockName,int rotation,LayerComponent.Mode mode,long componentId,int itemId,int amount,long targetId){this(op,x,y,layer,kind,blockName,rotation,mode,componentId,itemId,amount,targetId,new byte[0]);}
        public Action(Op op,int x,int y,int layer,LayerComponent.Kind kind,String blockName,int rotation,LayerComponent.Mode mode,long componentId){this(op,x,y,layer,kind,blockName,rotation,mode,componentId,-1,0,0);}
        public Action{
            if(configuration==null||configuration.length>LayerConfiguration.MAX_BYTES||op!=Op.CONFIGURE&&op!=Op.PASTE&&configuration.length!=0||(op==Op.CONFIGURE||op==Op.PASTE)&&configuration.length==0)throw new IllegalArgumentException("Invalid configuration payload");
            configuration=configuration.clone();
            if(op==null||kind==null||mode==null||blockName==null||!blockName.matches("[a-zA-Z0-9_.-]{0,96}")||
                x<0||y<0||x>32767||y>32767||layer<0||layer>512||rotation<0||rotation>3||componentId< -4294967296L||targetId< -4294967296L||itemId< -1||itemId>32767||amount<0||amount>1000)
                throw new IllegalArgumentException("Invalid construction request");
            if(op==Op.PLACE&&(layer==0||componentId!=0||kind==LayerComponent.Kind.BLOCK&&blockName.isEmpty()))
                throw new IllegalArgumentException("Upper-layer placement required");
            if(op==Op.PASTE&&(layer==0||componentId!=0))throw new IllegalArgumentException("Upper-layer schematic required");
            if(op!=Op.PLACE&&op!=Op.PASTE&&op!=Op.CANCEL_PASTE&&op!=Op.RELEASE_TURRET&&componentId==0)throw new IllegalArgumentException("Component identity required");
            if((op==Op.DEPOSIT||op==Op.WITHDRAW)&&(itemId<0||amount<1))throw new IllegalArgumentException("Bounded item transfer required");
            if(op!=Op.DEPOSIT&&op!=Op.WITHDRAW&&(itemId!=-1||amount!=0))throw new IllegalArgumentException("Unexpected item payload");
            if(op==Op.LINK||op==Op.UNLINK){if(componentId==0||targetId==0||componentId==targetId||!blockName.matches("ITEMS|LIQUIDS|POWER|BRIDGE_ITEMS|BRIDGE_LIQUIDS"))throw new IllegalArgumentException("Explicit vertical endpoints required");}
            else if(targetId!=0||componentId<0&&op!=Op.DEPOSIT&&op!=Op.WITHDRAW)throw new IllegalArgumentException("Unexpected vertical endpoint");
        }
        @Override public byte[] configuration(){return configuration.clone();}
        @Override public boolean equals(Object other){return other instanceof Action a&&op==a.op&&x==a.x&&y==a.y&&layer==a.layer&&kind==a.kind&&blockName.equals(a.blockName)&&rotation==a.rotation&&mode==a.mode&&componentId==a.componentId&&itemId==a.itemId&&amount==a.amount&&targetId==a.targetId&&java.util.Arrays.equals(configuration,a.configuration);}
        @Override public int hashCode(){return 31*java.util.Objects.hash(op,x,y,layer,kind,blockName,rotation,mode,componentId,itemId,amount,targetId)+java.util.Arrays.hashCode(configuration);}
        public static Action configure(long id,Object value){return new Action(Op.CONFIGURE,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id,-1,0,0,LayerConfiguration.encode(value));}
        public static Action respawn(long id){return new Action(Op.RESPAWN,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id);}
        public static Action paste(LayerSchematic.Stamp stamp,int layer,LayerComponent.Mode mode){return new Action(Op.PASTE,stamp.x(),stamp.y(),layer,LayerComponent.Kind.BLOCK,"",0,mode,0,-1,0,0,LayerSchematic.encode(stamp.schematic()));}
        public static Action possessTurret(long id){return new Action(Op.POSSESS_TURRET,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id);}
        public static Action releaseTurret(){return new Action(Op.RELEASE_TURRET,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,0);}
        public static Action cancelPaste(){return new Action(Op.CANCEL_PASTE,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,0);}
        public static Action place(int x,int y,int layer,String name,boolean platform,int rotation,LayerComponent.Mode mode){
            return new Action(Op.PLACE,x,y,layer,platform?LayerComponent.Kind.PLATFORM:LayerComponent.Kind.BLOCK,
                platform?LayerCatalog.PLATFORM:name,Math.floorMod(rotation,4),mode,0);
        }
        public static Action remove(long id){return new Action(Op.REMOVE,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id);}
        public static Action rotate(long id,int rotation){return new Action(Op.ROTATE,0,0,0,LayerComponent.Kind.BLOCK,"",Math.floorMod(rotation,4),LayerComponent.Mode.STRUCTURAL,id);}
        public static Action deposit(long id,int item,int amount){return new Action(Op.DEPOSIT,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id,item,amount,0);}
        public static Action withdraw(long id,int item,int amount){return new Action(Op.WITHDRAW,0,0,0,LayerComponent.Kind.BLOCK,"",0,LayerComponent.Mode.STRUCTURAL,id,item,amount,0);}
        public static Action link(long from,long to,LayerVerticalTransfers.Kind kind,boolean remove){return new Action(remove?Op.UNLINK:Op.LINK,0,0,0,LayerComponent.Kind.BLOCK,kind.name(),0,LayerComponent.Mode.STRUCTURAL,from,-1,0,to);}
    }
    public record Result(boolean accepted,boolean pending,String reason,long componentId){
        public Result{if(reason==null||reason.length()>256||componentId<0||accepted&&pending)throw new IllegalArgumentException("Invalid construction result");}
        public static Result ok(long id){return new Result(true,false,"Placed",id);}
        public static Result rejected(String reason){return new Result(false,false,reason,0);}
        public static Result queued(){return new Result(false,true,"Waiting for server",0);}
    }
    public static void write(DataOutput out,Action action)throws IOException{
        out.writeByte(action.op.ordinal());out.writeShort(action.x);out.writeShort(action.y);out.writeShort(action.layer);
        out.writeByte(action.kind.ordinal());out.writeUTF(action.blockName);out.writeByte(action.rotation);out.writeByte(action.mode.ordinal());out.writeLong(action.componentId);out.writeShort(action.itemId);out.writeShort(action.amount);out.writeLong(action.targetId);
        if(action.op==Op.CONFIGURE||action.op==Op.PASTE){out.writeShort(action.configuration.length);out.write(action.configuration);}
    }
    public static Action read(DataInput in)throws IOException{
        try{Op op=enumAt(Op.values(),in.readUnsignedByte());int x=in.readUnsignedShort(),y=in.readUnsignedShort(),layer=in.readUnsignedShort();var kind=enumAt(LayerComponent.Kind.values(),in.readUnsignedByte());String block=in.readUTF();int rotation=in.readUnsignedByte();var mode=enumAt(LayerComponent.Mode.values(),in.readUnsignedByte());long id=in.readLong();int item=in.readShort(),amount=in.readUnsignedShort();long target=in.readLong();byte[] config=new byte[0];if(op==Op.CONFIGURE||op==Op.PASTE){int length=in.readUnsignedShort();if(length<1||length>LayerConfiguration.MAX_BYTES)throw new IOException("Configuration limit");config=new byte[length];in.readFully(config);}return new Action(op,x,y,layer,kind,block,rotation,mode,id,item,amount,target,config);}
        catch(IllegalArgumentException bad){throw new IOException("Invalid construction action",bad);}
    }
    public static void writeResult(DataOutput out,Result result)throws IOException{out.writeBoolean(result.accepted);out.writeBoolean(result.pending);out.writeUTF(result.reason);out.writeLong(result.componentId);}
    public static Result readResult(DataInput in)throws IOException{
        try{return new Result(in.readBoolean(),in.readBoolean(),in.readUTF(),in.readLong());}catch(IllegalArgumentException bad){throw new IOException("Invalid construction result",bad);}
    }
    private static <T>T enumAt(T[] values,int i)throws IOException{if(i<0||i>=values.length)throw new IOException("Unknown construction enum");return values[i];}
}
