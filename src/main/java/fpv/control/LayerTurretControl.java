package fpv.control;

import fpv.construction.*;
import java.io.*;
import java.util.*;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.net.Administration.ActionType;
import mindustry.world.blocks.defense.turrets.Turret;

/** Transient possession identities and barrel poses on the existing reliable construction/state channels. */
public final class LayerTurretControl {
    private record Owned(long component,Unit proxy,Unit previous){}
    private record Remote(long component,int unit,int team,int player,double yaw,double pitch){}
    private final LayerRuntime runtime;
    private final IdentityHashMap<Player,Owned> owners=new IdentityHashMap<>();
    private final LinkedHashMap<Long,Remote> remote=new LinkedHashMap<>();
    private boolean closed;
    public LayerTurretControl(LayerRuntime runtime){this.runtime=runtime;}
    public boolean owns(Player p){if(Vars.net.client()){for(var v:remote.values())if(v.player==p.id)return true;return false;}return owners.containsKey(p);}
    public LayerActions.Result possess(Player player,long id){
        var b=runtime.building(id);
        if(player==null||player.dead()||!Vars.state.rules.possessionAllowed||!(b instanceof Turret.TurretBuild t)||!t.isValid()||!t.canControl()||b.team!=player.team())return LayerActions.Result.rejected("A friendly live controllable turret and living player are required");
        var proxy=t.unit();if(!proxy.isAI()||!proxy.playerControllable())return LayerActions.Result.rejected("This turret is already controlled or unavailable");
        if(Vars.net.server()&&!Vars.netServer.admins.allowAction(player,ActionType.control,a->a.unit=proxy))return LayerActions.Result.rejected("Server denied turret possession");
        var old=owners.remove(player);Unit previous=old==null?player.unit():old.previous;
        player.justSwitchFrom=player.justSwitchTo=null;player.unit(proxy);proxy.vel.setZero();t.target=null;t.logicControlTime=0;t.logicShooting=false;
        owners.put(player,new Owned(id,proxy,previous));return new LayerActions.Result(true,false,"Controlling turret on Z"+((LayerTile)b.tile).layer,id);
    }
    public LayerActions.Result release(Player p){
        if(p==null||!owners.containsKey(p))return LayerActions.Result.rejected("No elevated turret is controlled");
        if(Vars.net.server()&&!Vars.netServer.admins.allowAction(p,ActionType.respawn,a->{}))return LayerActions.Result.rejected("Server denied releasing turret control");
        restore(p,owners.remove(p));return new LayerActions.Result(true,false,"Turret AI restored",0);
    }
    private void restore(Player p,Owned owned){
        if(p.unit()!=owned.proxy){owned.proxy.controlWeapons(false,false);return;}
        owned.proxy.controlWeapons(false,false);p.shooting=false;p.justSwitchFrom=p.justSwitchTo=null;
        Unit prior=owned.previous;
        if(prior!=null&&!prior.dead()&&prior.isAdded()&&prior.team==p.team()&&prior.isAI()&&prior.playerControllable())p.unit(prior);
        else {p.clearUnit();if(!Vars.net.client())p.checkSpawn();}
    }
    /** Runs before player/native simulation; invalid/dead/detached owners never keep firing. */
    public void update(){
        if(closed)return;if(Vars.net.client()){restoreClient();return;}
        for(var it=owners.entrySet().iterator();it.hasNext();){var e=it.next();var player=e.getKey();var o=e.getValue();var b=runtime.building(o.component);
            if(!player.isAdded()||!(b instanceof Turret.TurretBuild t)||t.dead||!t.isValid()||b.team!=player.team()||player.unit()!=o.proxy){it.remove();restore(player,o);}
        }
    }
    public void clear(){for(var e:new ArrayList<>(owners.entrySet()))restore(e.getKey(),e.getValue());owners.clear();remote.clear();}
    public void close(){clear();closed=true;}
    public double pitch(Turret.TurretBuild build){
        if(Vars.net.client()&&build.tile instanceof LayerTile tile){var c=runtime.grid().at(tile.x,tile.y,tile.layer,LayerComponent.Kind.BLOCK);var v=c==null?null:remote.get(c.id());if(v!=null)return v.pitch;}
        var direction=runtime.turretDirection(build);return direction==null?0:Math.toDegrees(Math.atan2(direction.z(),Math.hypot(direction.x(),direction.y())));
    }
    public byte[] replication(){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);int count=0;for(var e:runtime.nativeBuildings().entries())if(e.building() instanceof Turret.TurretBuild)count++;out.writeInt(count);
            for(var e:runtime.nativeBuildings().entries())if(e.building() instanceof Turret.TurretBuild t){int player=-1;for(var owned:owners.entrySet())if(owned.getValue().component==e.id())player=owned.getKey().id;
                double yaw=Float.isFinite(t.rotation)?arc.math.Mathf.mod(t.rotation,360):0,pitch=pitch(t);if(!Double.isFinite(pitch))pitch=0;
                out.writeLong(e.id());out.writeInt(t.unit().id);out.writeByte(t.team.id);out.writeInt(player);out.writeDouble(yaw);out.writeDouble(pitch);
            }return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public void applyReplication(byte[] bytes){
        if(!Vars.net.client())return;try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){int count=in.readInt();if(count<0||count>32768)throw new IOException("Turret pose limit");var next=new LinkedHashMap<Long,Remote>();var players=new HashSet<Integer>();
            for(int i=0;i<count;i++){long id=in.readLong();int unit=in.readInt(),team=in.readUnsignedByte(),player=in.readInt();double yaw=in.readDouble(),pitch=in.readDouble();
                if(id<=0||unit<0||player< -1||!Double.isFinite(yaw+pitch)||yaw<0||yaw>=360||pitch< -90||pitch>90||next.containsKey(id)||player>=0&&!players.add(player))throw new IOException("Invalid turret control/pose");next.put(id,new Remote(id,unit,team,player,yaw,pitch));
            }if(in.available()!=0)throw new IOException("Trailing turret data");
            for(var v:remote.values())if(v.player>=0&&next.values().stream().noneMatch(n->n.player==v.player&&n.component==v.component)){Player p=Groups.player.getByID(v.player);if(p!=null&&LayerTurretUnit.actual(p.unit())==runtime.building(v.component))p.clearUnit();}
            remote.clear();remote.putAll(next);restoreClient();
        }catch(IOException|RuntimeException bad){throw new IllegalArgumentException("Invalid upper turret state",bad);}
    }
    /** Called after native entity snapshots too: their BlockUnit reference deliberately decodes to null. */
    public void restoreClient(){if(!Vars.net.client()||closed)return;for(var v:remote.values()){
        var b=runtime.building(v.component);if(!(b instanceof Turret.TurretBuild t)||!t.isValid()||b.team.id!=v.team)continue;
        t.rotation=(float)v.yaw;Unit proxy=t.unit();proxy.id=v.unit;proxy.rotation=(float)v.yaw;
        if(v.player>=0){Player p=Groups.player.getByID(v.player);if(p!=null&&p.team()==b.team){p.justSwitchFrom=p.justSwitchTo=null;if(p.unit()!=proxy)p.unit(proxy);}}
    }}
}
