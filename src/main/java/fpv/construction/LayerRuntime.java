package fpv.construction;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import fpv.net.FpvNetwork;
import fpv.net.NetProtocol;
import fpv.physics.RamDamageSettings;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;
import mindustry.net.Administration.ActionType;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import java.io.*;
import java.util.*;

/** CPU-only, authoritative layered construction. Never writes the original tile grid. */
public final class LayerRuntime implements AutoCloseable {
    public static final String FREE_PERMISSION="fpv-layer-free-permission";
    private static final int FORMAT=5,MAX_BYTES=16*1024*1024,MAX_FALLING=32;
    private final World3DAdapter physical;
    private final LayerCatalog catalog=new LayerCatalog();
    private final LayerPasteQueue pastes=new LayerPasteQueue(this);
    private final fpv.control.LayerTurretControl turretControls=new fpv.control.LayerTurretControl(this);
    public fpv.control.LayerTurretControl turretControls(){return turretControls;}
    public fpv.world.WorldCoordinates.Point turretDirection(mindustry.world.blocks.defense.turrets.Turret.TurretBuild build){return turretAiming==null?null:turretAiming.direction(build);}
    public LayerActions.Result requestPossess(long id){return submit(LayerActions.Action.possessTurret(id));}
    public LayerActions.Result requestReleaseTurret(){return submit(LayerActions.Action.releaseTurret());}
    public LayerPasteQueue pastes(){return pastes;}
    public LayerActions.Result requestPaste(LayerSchematic.Stamp stamp,int layer,LayerComponent.Mode mode){try{return submit(LayerActions.Action.paste(stamp,layer,mode));}catch(IllegalArgumentException invalid){return lastResult=LayerActions.Result.rejected(invalid.getMessage());}}
    private LayerWorld grid=new LayerWorld(LayerCoordinates.DEFAULT);
    private LayerRebuildQueue rebuilds=new LayerRebuildQueue(LayerCoordinates.DEFAULT);
    private long cachedRebuildRevision=Long.MIN_VALUE;
    public LayerRebuildQueue rebuilds(){return rebuilds;}
    private StructuralSolver solver;
    private LayerBuildingAdapter nativeBuildings;
    private LayerBaseShieldSystem baseShields;
    public LayerBaseShieldSystem baseShields(){if(baseShields==null)baseShields=new LayerBaseShieldSystem(physical,()->nativeBuildings.entries(),()->grid.geometryRevision());return baseShields;}
    private final LayerCoreLifecycle coreLifecycle=new LayerCoreLifecycle();
    private LayerVerticalTransfers transfers;
    private final CrossLayerBridgeHooks groundBridges=new CrossLayerBridgeHooks(b->transfers!=null&&transfers.externalSource(b));
    private int connectorMaxGap=16;
    private LayerBuildingAdapter.TurretAiming turretAiming;
    @FunctionalInterface public interface UnitRelease {boolean release(Building source,Unit unit,double x,double y);}
    private UnitRelease unitRelease=(source,unit,x,y)->false;
    private java.util.function.Consumer<Building> defenseUpdate=b->{};
    private java.util.function.Consumer<LayerDestruction.Blast> destructionEffect=b->{};
    private final ArrayDeque<LayerDestruction.Blast> destructionQueue=new ArrayDeque<>();
    public void setUnitRelease(UnitRelease callback){unitRelease=Objects.requireNonNull(callback);}
    public void setDefenseUpdate(java.util.function.Consumer<Building> callback){defenseUpdate=Objects.requireNonNull(callback);}
    public void setDestructionEffect(java.util.function.Consumer<LayerDestruction.Blast> callback){destructionEffect=Objects.requireNonNull(callback);}
    private record PaidItem(String name,int amount){}
    private final Map<Long,List<PaidItem>> purchases=new HashMap<>();
    private final Map<LayerVerticalTransfers.Link,List<PaidItem>> connectorPurchases=new HashMap<>();
    private final Map<Long,Float> pendingNativeDamage=new HashMap<>();
    private double nextNativeSnapshot;
    private FpvNetwork network;
    private boolean installed,closed,remoteFree;
    private LayerActions.Result lastResult=LayerActions.Result.rejected("Select an upper construction layer");
    private final HashMap<Long,Double> warnings=new HashMap<>();
    private final ArrayList<FallingChunk> falling=new ArrayList<>();
    private double time;
    private long nextChunk=1,fallRevision,cachedGridRevision=Long.MIN_VALUE,cachedFallRevision=Long.MIN_VALUE;
    private long compositionRevision,budgetGeometry=Long.MIN_VALUE,budgetComposition=Long.MIN_VALUE,cachedPayloadBudget;
    private String replicationFailure;
    private final Set<Long> stateLimitWarnings=new HashSet<>();
    private byte[] cachedGrid,cachedSnapshot;
    private long cachedPowerRevision=Long.MIN_VALUE;
    private boolean cachedFree;
    private final Cons<ResetEvent> resetHook=e->reset();
    private final Cons<WorldLoadEvent> worldHook=e->reset();
    private final Cons<TileChangeEvent> tileHook=e->{if(solver!=null&&e.tile!=null)solver.invalidateColumn(e.tile.x,e.tile.y);coreLifecycle.reconcile();};

    public static final class FallingChunk {
        private final long id;
        private final List<LayerComponent> components;
        private final double mass;
        private final LayerCoordinates.Bounds bounds;
        private double offsetZ,velocity;
        private final HashSet<Integer> hitUnits=new HashSet<>();
        private FallingChunk(long id,List<LayerComponent> components,double offsetZ,double velocity,LayerCoordinates coordinates){
            this.id=id;this.components=List.copyOf(components);this.offsetZ=offsetZ;this.velocity=velocity;
            mass=components.stream().mapToDouble(LayerComponent::mass).sum();
            double x=Double.POSITIVE_INFINITY,y=x,z=x,x2=Double.NEGATIVE_INFINITY,y2=x2,z2=x2;
            for(var component:components){var b=coordinates.bounds(component);x=Math.min(x,b.minX());y=Math.min(y,b.minY());z=Math.min(z,b.minZ());x2=Math.max(x2,b.maxX());y2=Math.max(y2,b.maxY());z2=Math.max(z2,b.maxZ());}
            bounds=new LayerCoordinates.Bounds(x,y,z,x2,y2,z2);
        }
        public long id(){return id;}
        public List<LayerComponent> components(){return components;}
        public double offsetZ(){return offsetZ;}
        public double velocity(){return velocity;}
        public double mass(){return mass;}
        public LayerCoordinates.Bounds bounds(){return bounds;}
    }
    public LayerRuntime(World3DAdapter physical){this.physical=Objects.requireNonNull(physical);newSolver();}
    public void install(FpvNetwork network){
        if(installed)return;this.network=Objects.requireNonNull(network);network.registerConstruction(this);
        groundBridges.install();
        SaveVersion.addCustomChunk("fpv-layered-world",new SaveFileReader.CustomChunk(){
            public boolean shouldWrite(){return !closed&&(!grid.components().isEmpty()||!falling.isEmpty()||rebuilds.size()>0);}
            public boolean writeNet(){return false;}
            public void write(DataOutput out)throws IOException{if(!Vars.net.client())snapshotNative();byte[] bytes=replication();out.writeInt(bytes.length);out.write(bytes);}
            public void read(DataInput in)throws IOException{int n=in.readInt();if(n<12||n>MAX_BYTES)throw new IOException("Layer save size");byte[] bytes=new byte[n];in.readFully(bytes);restore(bytes,false);}
            public void read(DataInput in,int length)throws IOException{if(length<16||length>MAX_BYTES+4)throw new IOException("Layer chunk size");read(in);}
        });
        Events.on(ResetEvent.class,resetHook);Events.on(WorldLoadEvent.class,worldHook);Events.on(TileChangeEvent.class,tileHook);installed=true;
        Events.run(mindustry.game.EventType.Trigger.update,turretControls::update);
    }
    public LayerWorld grid(){return grid;}
    public StructuralSolver solver(){return solver;}
    public LayerCatalog catalog(){return catalog;}
    public void replicationFailed(String reason){replicationFailure="Construction paused: "+reason;lastResult=LayerActions.Result.rejected(replicationFailure);}
    public LayerBuildingAdapter nativeBuildings(){return nativeBuildings;}
    public Building building(long id){return endpoint(id);}
    public LayerVerticalTransfers transfers(){return transfers;}
    public void setTurretAiming(LayerBuildingAdapter.TurretAiming value){turretAiming=value;nativeBuildings.setTurretAiming(value);}
    /** Explicit host action; lowering a limit never removes existing structures or falling pieces. */
    public LayerActions.Result applyHostSettings(){
        if(Vars.net.client())return LayerActions.Result.rejected("Only the host can apply construction rules");
        int highest=1;for(var c:grid.components())highest=Math.max(highest,c.layer());for(var chunk:falling)for(var c:chunk.components)highest=Math.max(highest,c.layer());for(var job:rebuilds.snapshot())highest=Math.max(highest,job.component().layer());
        int maximum=Math.max(highest,LayerCatalog.boundedInt("fpv-layer-max",64,1,512));grid.setMaximumLayers(maximum);rebuilds.setCoordinates(grid.coordinates());Core.settings.put("fpv-layer-max",maximum);
        connectorMaxGap=LayerCatalog.boundedInt("fpv-vertical-max-gap",16,1,64);transfers.setMaxLayerGap(connectorMaxGap);solver.invalidateAll();fallRevision++;invalidateCache();
        return lastResult=new LayerActions.Result(true,false,"Host rules applied; existing upper support profiles retained",0);
    }
    public double planeHeight(int layer){return grid.coordinates().plane(layer);}
    public List<FallingChunk> falling(){return Collections.unmodifiableList(falling);}
    public boolean warning(long id){return warnings.containsKey(id);}
    public boolean allowedFree(){return Vars.net!=null&&Vars.net.client()?remoteFree:Core.settings!=null&&Core.settings.getBool(FREE_PERMISSION,false);}
    public LayerActions.Result lastResult(){return lastResult;}
    public String status(){return stateLimitWarnings.isEmpty()?lastResult.reason():"Upper block state limit reached: "+stateLimitWarnings.size()+" block(s) paused; last valid saved state retained";}
    public void acceptResult(LayerActions.Result result){lastResult=Objects.requireNonNull(result);}
    public LayerActions.Result requestPlace(int x,int y,int layer,String blockName,boolean platform,int rotation,LayerComponent.Mode mode){
        try{return submit(LayerActions.Action.place(x,y,layer,blockName,platform,rotation,mode));}catch(IllegalArgumentException invalid){return lastResult=LayerActions.Result.rejected(invalid.getMessage());}
    }
    public LayerActions.Result requestRemove(long id){return submit(LayerActions.Action.remove(id));}
    public LayerActions.Result requestRotate(long id,int rotation){return submit(LayerActions.Action.rotate(id,rotation));}
    public LayerActions.Result requestDeposit(long id){
        if(Vars.player==null||Vars.player.dead()||Vars.player.unit().stack.amount<1)return lastResult=LayerActions.Result.rejected("Carry items in your unit first");
        return submit(LayerActions.Action.deposit(id,Vars.player.unit().stack.item.id,Math.min(1000,Vars.player.unit().stack.amount)));
    }
    public LayerActions.Result requestWithdraw(long id,int itemId,int amount){return submit(LayerActions.Action.withdraw(id,itemId,Math.max(1,Math.min(1000,amount))));}
    public LayerActions.Result requestConfigure(long id,Object value){try{return submit(LayerActions.Action.configure(id,value));}catch(IllegalArgumentException invalid){return lastResult=LayerActions.Result.rejected(invalid.getMessage());}}
    public LayerActions.Result requestLink(long from,long to,LayerVerticalTransfers.Kind kind){return submit(LayerActions.Action.link(from,to,kind,false));}
    public LayerActions.Result requestUnlink(long from,long to,LayerVerticalTransfers.Kind kind){return submit(LayerActions.Action.link(from,to,kind,true));}
    public LayerActions.Result submit(LayerActions.Action action){
        return lastResult=Vars.net!=null&&Vars.net.client()?network.submitConstruction(action):apply(Vars.player,action);
    }
    public LayerActions.Result preview(Player player,LayerActions.Action action){return validate(player,action,false);}
    /** Read-only gate for native Z0 build queues; the native server still validates and executes each plan. */
    public LayerActions.Result previewGroundRemoval(Player player,Building building){
        if(player==null||player.dead()||!player.isBuilder()||!player.unit().canBuild())return LayerActions.Result.rejected("A living builder unit is required");
        if(building==null||building.dead||Vars.world.build(building.pos())!=building||!Build.validBreak(player.team(),building.tileX(),building.tileY()))return LayerActions.Result.rejected("Native dismantling permission denied");
        if(!within(player,building.tileX(),building.tileY(),building.block.size,0))return LayerActions.Result.rejected("Outside builder range in XYZ");
        return LayerActions.Result.ok(0);
    }
    public LayerActions.Result apply(Player player,LayerActions.Action action){
        return apply(player,action,true);
    }
    private LayerActions.Result apply(Player player,LayerActions.Action action,boolean filters){
        if(Vars.net!=null&&Vars.net.client())return LayerActions.Result.rejected("Server authority required");
        if(action!=null&&action.op()==LayerActions.Op.POSSESS_TURRET)return turretControls.possess(player,action.componentId());
        if(action!=null&&action.op()==LayerActions.Op.RELEASE_TURRET)return turretControls.release(player);
        var valid=validate(player,action,filters);if(!valid.accepted())return valid;
        if(action.op()==LayerActions.Op.PASTE)return pastes.enqueue(player,action);
        if(action.op()==LayerActions.Op.CANCEL_PASTE){pastes.cancel(player);return new LayerActions.Result(true,false,"Schematic queue cleared",0);}
        if(action.op()==LayerActions.Op.RESPAWN){
            Building core=endpoint(action.componentId());var type=(mindustry.world.blocks.storage.CoreBlock)core.block;
            Unit unit=type.unitType.create(player.team());unit.spawnedByCore(true);for(var mount:unit.mounts)mount.reload=mount.weapon.reload;
            if(!unitRelease.release(core,unit,core.x,core.y))return LayerActions.Result.rejected("Core exit is obstructed");
            unit.controller(player);player.unit(unit);player.set(unit);player.deathTimer=0;
            return new LayerActions.Result(true,false,"Respawned at upper core",action.componentId());
        }
        if(action.op()==LayerActions.Op.LINK||action.op()==LayerActions.Op.UNLINK){
            var kind=LayerVerticalTransfers.Kind.valueOf(action.blockName());var link=new LayerVerticalTransfers.Link(action.componentId(),action.targetId(),kind);
            if(action.op()==LayerActions.Op.LINK){
                if(transfers.links().contains(link))return new LayerActions.Result(true,false,"Connector already exists",Math.max(0,action.targetId()));
                if(!transfers.configureLink(link.from(),link.to(),kind))return LayerActions.Result.rejected("Endpoints cannot form this vertical connection");
                var receipt=ordinaryPower(link.from(),link.to(),kind)?List.<PaidItem>of():receipt(player,connectorCost());pay(player,receipt);connectorPurchases.put(link,receipt);
            }else if(transfers.links().contains(link)||kind==LayerVerticalTransfers.Kind.POWER&&nativeBuildings.linkedPower(link.from(),link.to())){
                transfers.removeLink(link.from(),link.to(),kind);
                if(kind==LayerVerticalTransfers.Kind.POWER)nativeBuildings.disconnectPower(link.from(),link.to());
                refund(player,connectorPurchases.remove(link));
            }
            fallRevision++;return new LayerActions.Result(true,false,action.op()==LayerActions.Op.LINK?"Vertical connector installed":"Vertical connector removed",Math.max(0,action.targetId()));
        }
        if(action.op()==LayerActions.Op.PLACE){
            var entry=catalog.entry(action.blockName(),action.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing());
            LayerComponent component=component(action,entry,grid.nextId(),player.team().id);
            // The final check and mutation are one native game-thread operation; no client mutates items.
            var paid=receipt(player,entry.requirements());pay(player,paid);
            try{grid.add(component);}catch(RuntimeException rejected){
                grid.remove(component.id());
                rollbackPayment(player,paid);
                return LayerActions.Result.rejected("Placement changed; try again");
            }
            purchases.put(component.id(),paid);
            rebuilds.removeAt(component.x(),component.y(),component.layer(),component.kind());
            return LayerActions.Result.ok(component.id());
        }
        LayerComponent existing=grid.get(action.componentId());
        if(action.op()==LayerActions.Op.DEPOSIT||action.op()==LayerActions.Op.WITHDRAW){
            Building b=endpoint(action.componentId());var item=Vars.content.item(action.itemId());Unit unit=player.unit();int moved;
            if(action.op()==LayerActions.Op.DEPOSIT){int offered=Math.min(action.amount(),unit.stack.amount);moved=Math.min(offered,b.acceptStack(item,offered,unit));if(moved<=0)return LayerActions.Result.rejected("Building does not accept these items");b.handleStack(item,moved,unit);unit.stack.amount-=moved;}
            else {moved=Math.min(action.amount(),Math.min(unit.maxAccepted(item),b.items.get(item)));if(moved<=0)return LayerActions.Result.rejected("No items available or unit cargo is full");moved=Math.max(0,Math.min(moved,b.removeStack(item,moved)));if(moved<=0)return LayerActions.Result.rejected("Native inventory did not release the requested items");unit.addItem(item,moved);}
            snapshotNative();return new LayerActions.Result(true,false,"Transferred "+moved+" "+item.localizedName,Math.max(0,action.componentId()));
        }
        if(action.op()==LayerActions.Op.CONFIGURE){
            try{if(!nativeBuildings.configureNative(existing.id(),LayerConfiguration.decode(action.configuration())))return LayerActions.Result.rejected("Configuration is not valid for this block");}
            catch(IllegalArgumentException invalid){return LayerActions.Result.rejected("Invalid configuration value");}
            snapshotNative();return new LayerActions.Result(true,false,"Configured",existing.id());
        }
        if(action.op()==LayerActions.Op.ROTATE){grid.replace(new LayerComponent(existing.id(),existing.kind(),existing.x(),existing.y(),existing.layer(),existing.blockName(),existing.size(),action.rotation(),existing.team(),existing.mode(),existing.health(),existing.maxHealth(),existing.mass(),existing.height(),existing.support(),existing.configuration(),existing.state()));return new LayerActions.Result(true,false,"Rotated",existing.id());}
        var paid=purchases.get(existing.id());grid.remove(existing.id());warnings.remove(existing.id());refund(player,paid);
        return new LayerActions.Result(true,false,"Removed",existing.id());
    }
    private LayerActions.Result validate(Player player,LayerActions.Action action,boolean filters){
        return validate(player,action,filters,grid,solver,true);
    }
    LayerActions.Result previewSchematicCell(Player player,LayerActions.Action action,LayerWorld trial){return validate(player,action,false,trial,null,false);}
    LayerComponent schematicComponent(LayerActions.Action action,long id,int team){return component(action,catalog.entry(action.blockName(),action.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing()),id,team);}
    boolean schematicBudget(java.util.List<LayerComponent> added){long sum=payloadBudget();for(var c:added)sum+=componentBudget(c);return sum<=MAX_BYTES;}
    boolean schematicPowerLink(Player player,long from,long to){return validate(player,LayerActions.Action.configure(from,null),true).accepted()&&validate(player,LayerActions.Action.configure(to,null),true).accepted()&&nativeBuildings.connectPower(from,to);}
    private LayerActions.Result validate(Player player,LayerActions.Action action,boolean filters,LayerWorld grid,StructuralSolver solver,boolean costs){
        if(!closed&&Vars.state.isGame()&&player!=null&&action!=null&&action.op()==LayerActions.Op.RESPAWN){
            Building b=endpoint(action.componentId());
            if(!(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild)||b.dead||b.team!=player.team()||!player.dead()||player.deathTimer<Player.deathDelay)return LayerActions.Result.rejected("A dead player and friendly live core are required");
            var type=(mindustry.world.blocks.storage.CoreBlock)b.block;if(!type.allowSpawn||!type.unitType.supportsEnv(Vars.state.rules.env))return LayerActions.Result.rejected("Core spawning is unavailable");
            return LayerActions.Result.ok(action.componentId());
        }
        if(closed||!Vars.state.isGame()||player==null||player.dead())return LayerActions.Result.rejected("A living unit is required");
        if(action==null)return LayerActions.Result.rejected("Missing construction action");
        if(action.op()==LayerActions.Op.DEPOSIT||action.op()==LayerActions.Op.WITHDRAW)return validateInventory(player,action,filters);
        if(action.op()!=LayerActions.Op.CONFIGURE&&(!player.isBuilder()||!player.unit().canBuild()))return LayerActions.Result.rejected("A living builder unit is required");
        if(action.op()==LayerActions.Op.CANCEL_PASTE)return LayerActions.Result.ok(0);
        if(action.op()==LayerActions.Op.PASTE){try{var p=pastes.preview(player,action);return p.ready()>0?LayerActions.Result.ok(0):LayerActions.Result.rejected(p.reason().isEmpty()?"No valid schematic positions":p.reason());}catch(IllegalArgumentException invalid){return LayerActions.Result.rejected("Invalid or unsupported schematic");}}
        if(action.op()==LayerActions.Op.LINK||action.op()==LayerActions.Op.UNLINK)return validateLink(player,action,filters);
        if(action.op()!=LayerActions.Op.PLACE){
            LayerComponent c=grid.get(action.componentId());if(c==null)return LayerActions.Result.rejected("Structure no longer exists");
            if(c.team()!=player.team().id)return LayerActions.Result.rejected("This structure belongs to another team");
            if(!within(player,c.x(),c.y(),c.size(),c.layer()))return LayerActions.Result.rejected("Outside builder range in XYZ");
            if(action.op()==LayerActions.Op.DEPOSIT||action.op()==LayerActions.Op.WITHDRAW){
                Building b=nativeBuildings.building(c.id());if(b==null||b.items==null)return LayerActions.Result.rejected("This structure has no item inventory");
                if(action.itemId()<0||action.itemId()>=Vars.content.items().size)return LayerActions.Result.rejected("Unknown item");
                if(action.op()==LayerActions.Op.DEPOSIT&&(player.unit().stack.amount<1||player.unit().stack.item.id!=action.itemId()||!b.allowDeposit()))return LayerActions.Result.rejected("Unit does not carry a depositable item");
            }
            if(filters&&!allow(player,action,c.x(),c.y(),catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing())))return LayerActions.Result.rejected("Server action filter denied construction");
            return LayerActions.Result.ok(c.id());
        }
        if(action.layer()<1||action.layer()>grid.coordinates().maxLayers())return LayerActions.Result.rejected("Construction layer is outside the configured range");
        if(action.mode()==LayerComponent.Mode.FREE&&!allowedFree())return LayerActions.Result.rejected("Free construction must be enabled by the host");
        if(replicationFailure!=null)return LayerActions.Result.rejected(replicationFailure);
        if(componentCount()+grid.size()-this.grid.size()>=LayerCatalog.boundedInt("fpv-layer-max-components",16384,1,32768))return LayerActions.Result.rejected("Upper construction limit reached (including falling sections)");
        var entry=catalog.entry(action.blockName(),action.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing());
        if(entry==null)return LayerActions.Result.rejected("This block needs a layer-aware adapter; unsupported category");
        if(payloadBudget()+componentBudget(component(action,entry,Long.MAX_VALUE-2,player.team().id))>MAX_BYTES)return LayerActions.Result.rejected("Construction save/network budget reached; remove existing structures first");
        Block block=entry.block();
        if(block!=null&&!Vars.state.isEditor()&&(!block.unlockedNowHost()||!block.environmentBuildable()||!block.isPlaceable()))return LayerActions.Result.rejected("Block is locked or unavailable in this environment");
        if(!within(player,action.x(),action.y(),entry.size(),action.layer()))return LayerActions.Result.rejected("Outside builder range in XYZ");
        var c=component(action,entry,Long.MAX_VALUE-2,player.team().id);var bounds=grid.coordinates().bounds(c);
        int minX=LayerCoordinates.minCell(c.x(),c.size()),minY=LayerCoordinates.minCell(c.y(),c.size());
        if(minX<0||minY<0||minX+c.size()>Vars.world.width()||minY+c.size()>Vars.world.height())return LayerActions.Result.rejected("Outside map bounds");
        if(!coreProtected(player,entry,c.x(),c.y()))return LayerActions.Result.rejected("Enemy core protection prevents placement");
        for(int x=minX;x<minX+c.size();x++)for(int y=minY;y<minY+c.size();y++){
            if(grid.at(x,y,c.layer(),c.kind())!=null)return LayerActions.Result.rejected("This layer cell is occupied");
            if(Vars.state.rules.staticFog&&Vars.state.rules.fog&&!Vars.fogControl.isDiscovered(player.team(),x,y))return LayerActions.Result.rejected("This map area has not been discovered");
            if((block==null||!block.ignoreBuildDarkness)&&Vars.world.getDarkness(x,y)>=3)return LayerActions.Result.rejected("This map area cannot be built on");
            // The slab and a supporting wall share a small joint at the floor plane.
            Tile nativeTile=Vars.world.tile(x,y);double top=physical.surfaceHeight(x,y),terrain=physical.terrainHeight(x,y);
            for(double dx:new double[]{-.49,.49})for(double dy:new double[]{-.49,.49})terrain=Math.max(terrain,physical.terrainHeight(x+dx,y+dy));
            // Only a supporting platform's thickness may embed into a ground wall's top.
            boolean joint=c.kind()==LayerComponent.Kind.PLATFORM&&nativeTile.build!=null&&nativeTile.build.block instanceof Wall&&nativeTile.build.team==player.team()&&Math.abs(top-planeHeight(c.layer()))<1e-5;
            if(terrain>bounds.minZ()+1e-5||top>bounds.minZ()+1e-5&&!joint)return LayerActions.Result.rejected("Intersects terrain or a ground structure");
        }
        if(!grid.canAdd(c))return LayerActions.Result.rejected("Intersects another upper structure");
        for(var other:grid.query(bounds))if(bounds.overlaps(grid.coordinates().bounds(other))){
            boolean slabJoint=c.kind()==LayerComponent.Kind.PLATFORM&&other.column()&&c.team()==other.team()&&Math.abs(grid.coordinates().bounds(other).maxZ()-bounds.maxZ())<1e-7;
            if(!slabJoint)return LayerActions.Result.rejected("Intersects solid wall geometry; aim at its aligned top surface");
        }
        for(Unit unit:Groups.unit){var pose=physical.unitState(unit);if(pose!=null&&pose.z()+pose.halfHeight()>bounds.minZ()&&pose.z()-pose.halfHeight()<bounds.maxZ()&&circleRect(pose.x(),pose.y(),pose.radius(),bounds))return LayerActions.Result.rejected("A unit occupies this volume");}
        if(solver!=null&&c.mode()==LayerComponent.Mode.STRUCTURAL&&!solver.supports(c))return LayerActions.Result.rejected("Unsupported: add a connected wall column or reduce span/load");
        if(!infinite(player)){
            if(player.core()==null)return LayerActions.Result.rejected("A team core is required");
            if(costs)for(ItemStack cost:entry.requirements())if(!player.core().items.has(cost.item,price(cost.amount)))return LayerActions.Result.rejected("Insufficient "+cost.item.localizedName);
        }
        if(filters&&!allow(player,action,c.x(),c.y(),entry))return LayerActions.Result.rejected("Server action filter denied construction");
        return LayerActions.Result.ok(0);
    }
    private LayerComponent component(LayerActions.Action a,LayerCatalog.Entry entry,long id,int team){
        return new LayerComponent(id,a.kind(),a.x(),a.y(),a.layer(),entry.name(),entry.size(),a.rotation(),team,a.mode(),entry.health(),entry.health(),entry.mass(),entry.height(),entry.support(),new byte[0],new byte[0]);
    }
    private LayerActions.Result validateInventory(Player player,LayerActions.Action action,boolean filters){
        Building b=endpoint(action.componentId());
        if(b==null||b.dead||b.items==null||b.team!=player.team())return LayerActions.Result.rejected("Select a friendly building with an item inventory");
        if(action.itemId()<0||action.itemId()>=Vars.content.items().size)return LayerActions.Result.rejected("Unknown item");
        var pose=physical.unitState(player.unit());if(pose==null)return LayerActions.Result.rejected("Unit position unavailable");
        double dx=b.x/(double)Vars.tilesize-pose.x(),dy=b.y/(double)Vars.tilesize-pose.y(),dz=planeHeight(endpointLayer(action.componentId()))-pose.z(),reach=Vars.itemTransferRange/(double)Vars.tilesize;
        if(dx*dx+dy*dy+dz*dz>reach*reach)return LayerActions.Result.rejected("Outside cargo transfer range in XYZ");
        if(action.op()==LayerActions.Op.DEPOSIT&&(player.unit().stack.amount<1||player.unit().stack.item.id!=action.itemId()||!b.allowDeposit()))return LayerActions.Result.rejected("Unit does not carry a depositable item");
        if(filters&&Vars.net.server()&&!Vars.netServer.admins.allowAction(player,action.op()==LayerActions.Op.DEPOSIT?ActionType.depositItem:ActionType.withdrawItem,Vars.world.tile(b.tileX(),b.tileY()),a->{a.block=b.block;a.item=Vars.content.item(action.itemId());a.itemAmount=action.amount();}))return LayerActions.Result.rejected("Server action filter denied cargo transfer");
        return LayerActions.Result.ok(Math.max(0,action.componentId()));
    }
    private boolean within(Player player,int x,int y,int size,int layer){
        if(player instanceof TowerActor actor){double dx=LayerCoordinates.center(x,size)-actor.tower.x/Vars.tilesize,dy=LayerCoordinates.center(y,size)-actor.tower.y/Vars.tilesize,dz=planeHeight(layer)-physical.buildingBase(actor.tower),range=((mindustry.world.blocks.defense.BuildTurret)actor.tower.block).range/Vars.tilesize;return dx*dx+dy*dy+dz*dz<=range*range;}
        if(infinite(player))return true;var pose=physical.unitState(player.unit());if(pose==null)return false;
        double dx=LayerCoordinates.center(x,size)-pose.x(),dy=LayerCoordinates.center(y,size)-pose.y(),dz=planeHeight(layer)-pose.z();
        double reach=player.unit().type.buildRange/Vars.tilesize;return dx*dx+dy*dy+dz*dz<=reach*reach;
    }
    private boolean coreProtected(Player player,LayerCatalog.Entry entry,int x,int y){
        if(Vars.state.isEditor())return true;double wx=LayerCoordinates.center(x,entry.size())*Vars.tilesize,wy=LayerCoordinates.center(y,entry.size())*Vars.tilesize;
        if(Vars.state.rules.polygonCoreProtection){Building closest=null;double distance=Double.POSITIVE_INFINITY;
            for(var team:Vars.state.teams.active)if(team.team.rules().protectCores)for(var core:team.cores){double d=core.dst2((float)wx,(float)wy);if(d<distance){distance=d;closest=core;}}
            if(closest!=null&&closest.team!=player.team())return false;
        }else if(Vars.state.teams.anyEnemyCoresWithinBuildRadius(player.team(),(float)wx,(float)wy))return false;
        return entry.block()==null||!Vars.state.rules.placeRangeCheck||Build.getEnemyOverlap(entry.block(),player.team(),x,y)==null;
    }
    private boolean allow(Player player,LayerActions.Action action,int x,int y,LayerCatalog.Entry entry){
        if(!Vars.net.server())return true;ActionType type=switch(action.op()){case PLACE->ActionType.placeBlock;case REMOVE->ActionType.breakBlock;case ROTATE->ActionType.rotate;case DEPOSIT->ActionType.depositItem;case WITHDRAW->ActionType.withdrawItem;case LINK,UNLINK,CONFIGURE,RESPAWN,PASTE,CANCEL_PASTE,POSSESS_TURRET,RELEASE_TURRET->ActionType.configure;};
        return Vars.netServer.admins.allowAction(player,type,Vars.world.tile(x,y),a->{a.block=entry==null?null:entry.block();a.rotation=action.rotation();if(action.itemId()>=0){a.item=Vars.content.item(action.itemId());a.itemAmount=action.amount();}});
    }
    private static int price(int amount){return Math.max(0,(int)Math.min(Integer.MAX_VALUE,Math.round(amount*(double)Vars.state.rules.buildCostMultiplier)));}
    private static boolean infinite(Player player){return Vars.state.rules.infiniteResources||player.team().rules().infiniteResources;}
    private static List<PaidItem> receipt(Player player,ItemStack[] requirements){
        if(infinite(player))return List.of();var paid=new ArrayList<PaidItem>();for(var cost:requirements){int amount=price(cost.amount);if(amount>0)paid.add(new PaidItem(cost.item.name,amount));}return List.copyOf(paid);
    }
    private static void pay(Player player,List<PaidItem> paid){for(var cost:paid){var item=(mindustry.type.Item)Vars.content.getByName(mindustry.ctype.ContentType.item,cost.name);player.core().items.remove(item,cost.amount);}}
    private static void rollbackPayment(Player player,List<PaidItem> paid){if(player.core()!=null)for(var cost:paid){var item=(mindustry.type.Item)Vars.content.getByName(mindustry.ctype.ContentType.item,cost.name);if(item!=null)player.core().items.add(item,cost.amount);}}
    private static void refund(Player player,List<PaidItem> paid){
        if(paid==null||infinite(player)||player.core()==null)return;
        for(var cost:paid){var item=(mindustry.type.Item)Vars.content.getByName(mindustry.ctype.ContentType.item,cost.name);if(item==null)continue;
            int amount=(int)Math.min(Integer.MAX_VALUE,Math.round(cost.amount*(double)Vars.state.rules.deconstructRefundMultiplier));
            player.core().items.add(item,Math.max(0,Math.min(amount,player.core().storageCapacity-player.core().items.get(item))));}
    }
    private ItemStack[] connectorCost(){return new ItemStack[]{new ItemStack(mindustry.content.Items.copper,LayerCatalog.boundedInt("fpv-vertical-copper",4,0,1000)),new ItemStack(mindustry.content.Items.lead,LayerCatalog.boundedInt("fpv-vertical-lead",2,0,1000))};}
    private Building endpoint(long id){if(id==0)return null;if(id>0)return nativeBuildings.building(id);long packed=-id-1;Building b=Vars.world!=null&&packed>=0&&packed<=0xffffffffL?Vars.world.build((int)packed):null;return b!=null&&Integer.toUnsignedLong(b.pos())==packed?b:null;}
    private int endpointLayer(long id){var c=id>0?grid.get(id):null;return c==null?0:c.layer();}
    private boolean ordinaryPower(long from,long to,LayerVerticalTransfers.Kind kind){return kind==LayerVerticalTransfers.Kind.POWER&&(endpoint(from)!=null&&endpoint(from).block instanceof mindustry.world.blocks.power.PowerNode||endpoint(to)!=null&&endpoint(to).block instanceof mindustry.world.blocks.power.PowerNode);}
    private LayerActions.Result validateLink(Player player,LayerActions.Action action,boolean filters){
        Building from=endpoint(action.componentId()),to=endpoint(action.targetId());
        if(from==null||to==null||from.team!=player.team()||to.team!=player.team())return LayerActions.Result.rejected("Both connector endpoints must belong to your team");
        if(!within(player,from.tileX(),from.tileY(),from.block.size,endpointLayer(action.componentId()))||!within(player,to.tileX(),to.tileY(),to.block.size,endpointLayer(action.targetId())))return LayerActions.Result.rejected("Connector endpoint is outside builder range in XYZ");
        var kind=LayerVerticalTransfers.Kind.valueOf(action.blockName());var link=new LayerVerticalTransfers.Link(action.componentId(),action.targetId(),kind);
        if(action.op()==LayerActions.Op.LINK&&!transfers.canLink(link.from(),link.to(),kind))return LayerActions.Result.rejected(kind==LayerVerticalTransfers.Kind.POWER?java.util.Objects.requireNonNullElse(nativeBuildings.powerLinkReason(link.from(),link.to()),"Power link limit reached"):kind.bridge()?"Select identical bridges on different floors within their XYZ bridge range":"Use aligned compatible inventories within "+connectorMaxGap+" floors and a clear shaft");
        if(action.op()==LayerActions.Op.LINK&&kind!=LayerVerticalTransfers.Kind.POWER&&!kind.bridge()&&!clearShaft(link.from(),link.to()))return LayerActions.Result.rejected("A solid structure or mountain blocks the vertical shaft");
        if(action.op()==LayerActions.Op.LINK&&!ordinaryPower(link.from(),link.to(),kind)&&!transfers.links().contains(link)&&!infinite(player)){
            if(player.core()==null)return LayerActions.Result.rejected("A core is required to pay for the connector");for(var cost:connectorCost())if(!player.core().items.has(cost.item,price(cost.amount)))return LayerActions.Result.rejected("Insufficient "+cost.item.localizedName+" for connector");
        }
        if(filters&&Vars.net.server()&&(!Vars.netServer.admins.allowAction(player,ActionType.configure,Vars.world.tile(from.tileX(),from.tileY()),a->a.block=from.block)||!Vars.netServer.admins.allowAction(player,ActionType.configure,Vars.world.tile(to.tileX(),to.tileY()),a->a.block=to.block)))return LayerActions.Result.rejected("Server action filter denied connector");
        return LayerActions.Result.ok(0);
    }
    private boolean clearShaft(long from,long to){return clearShaft(grid,nativeBuildings,from,to);}
    private boolean clearShaft(LayerWorld source,LayerBuildingAdapter registry,long from,long to){
        Building a=from>0?registry.building(from):endpoint(from),b=to>0?registry.building(to):endpoint(to);if(a==null||b==null)return false;
        var ac=source.get(from);var bc=source.get(to);
        double az=source.coordinates().plane(ac==null?0:ac.layer()),bz=source.coordinates().plane(bc==null?0:bc.layer());
        double bottom,top;if(az<bz){bottom=az+(ac!=null?ac.height():fpv.net.HeadlessGeometry.heightFor(a.block));top=bz;}else{bottom=bz+(bc!=null?bc.height():fpv.net.HeadlessGeometry.heightFor(b.block));top=az;}
        if(top<bottom)return false;double x=a.x/(double)Vars.tilesize,y=a.y/(double)Vars.tilesize;
        if(physical.terrainHeight(x,y)>bottom+1e-5)return false;
        var shaft=new LayerCoordinates.Bounds(x-.12,y-.12,bottom,x+.12,y+.12,top);
        for(var obstacle:source.query(shaft))if(obstacle.id()!=from&&obstacle.id()!=to&&obstacle.kind()!=LayerComponent.Kind.PLATFORM&&shaft.overlaps(source.coordinates().bounds(obstacle)))return false;
        return true;
    }
    private LayerVerticalTransfers createTransfers(LayerWorld source,LayerBuildingAdapter registry,int gap){
        var result=new LayerVerticalTransfers(registry);result.setMaxLayerGap(gap);result.setLayerSpacing(source.coordinates().spacing());
        record Pair(long from,long to){}
        var cache=new HashMap<Pair,Boolean>();long[] revisions={Long.MIN_VALUE,Long.MIN_VALUE};
        result.setShaftClearance((from,to)->{long geometry=source.geometryRevision(),nativeRevision=Vars.world.tileChanges;
            if(revisions[0]!=geometry||revisions[1]!=nativeRevision){cache.clear();revisions[0]=geometry;revisions[1]=nativeRevision;}
            return cache.computeIfAbsent(new Pair(from,to),key->clearShaft(source,registry,from,to));});return result;
    }
    private void newSolver(){newSolver(null,null);}
    private void newSolver(LayerBuildingAdapter prepared,LayerVerticalTransfers preparedTransfers){
        if(solver!=null)solver.close();solver=new StructuralSolver(grid,this::foundation);
        coreLifecycle.deactivate();
        if(nativeBuildings!=null)nativeBuildings.clear();
        nativeBuildings=prepared==null?createNativeRegistry(grid):prepared;
        transfers=preparedTransfers==null?createTransfers(grid,nativeBuildings,connectorMaxGap):preparedTransfers;
        grid.addListener((before,after)->{
            if(before!=null&&after==null){purchases.remove(before.id());stateLimitWarnings.remove(before.id());}
            if(before!=null&&(after==null||!before.blockName().equals(after.blockName()))){nativeBuildings.remove(before.id());}
            if(after!=null)syncNative(after);
            if(before==null||after==null)nativeBuildings.invalidateTopology();
            if(before==null||after==null||before.team()!=after.team())coreLifecycle.reconcile();
        });
        if(prepared==null)for(var component:grid.components())syncNative(component);
        coreLifecycle.activate(nativeBuildings);
        pendingNativeDamage.clear();nextNativeSnapshot=0;
    }
    private LayerBuildingAdapter createNativeRegistry(LayerWorld source){
        var registry=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){
            @Override public void damaged(long id,float health){pendingNativeDamage.put(id,health);}
            @Override public void destroyed(Building building){if(!closed&&!Vars.net.client()){var blast=LayerDestruction.generator(building,physical.buildingBase(building));if(blast!=null&&destructionQueue.size()<32768)destructionQueue.add(blast);}}
            @Override public void configureRequested(long id,Object value){requestConfigure(id,value);}
            @Override public double unitAltitude(Unit unit){var pose=physical.unitState(unit);return pose==null?0:pose.z();}
            @Override public double buildingAltitude(Building building){return physical.buildingBase(building);}
            @Override public boolean releaseUnit(Building source,Unit unit,double x,double y){return unitRelease.release(source,unit,x,y);}
            @Override public void defenseUpdate(Building building){if(building instanceof mindustry.world.blocks.defense.BaseShield.BaseShieldBuild shield)baseShields().update(shield);else LayerRuntime.this.defenseUpdate.accept(building);}
            @Override public boolean logicBuild(mindustry.world.blocks.logic.LogicBlock.LogicBuild processor,Unit unit,int x,int y,Block block,int rotation,Object config,boolean breaking){return buildFromLogic(processor,unit,x,y,block,rotation,config,breaking);}
            @Override public void requestCoreSpawn(mindustry.world.blocks.storage.CoreBlock.CoreBuild core,Player player){
                if(core.tile instanceof LayerTile tile){var c=grid.at(tile.x,tile.y,tile.layer,LayerComponent.Kind.BLOCK);if(c!=null){if(Vars.net.client()){if(player==Vars.player)submit(LayerActions.Action.respawn(c.id()));}else apply(player,LayerActions.Action.respawn(c.id()));}}
            }
            @Override public boolean hasRebuildWork(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){return !Vars.net.client()&&rebuilds.target(towerState(tower,0),rebuildCallback(tower))!=null;}
            @Override public void rebuild(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){
                if(Vars.net.client())return;var type=(mindustry.world.blocks.defense.BuildTurret)tower.block;
                var work=rebuilds.update(towerState(tower,tower.isHealSuppressed()?0:tower.edelta()*type.buildSpeed*Vars.state.rules.buildSpeed(tower.team)),rebuildCallback(tower));
                tower.warmup=arc.math.Mathf.lerpDelta(tower.warmup,work==null?0:tower.efficiency,.1f);
                if(work!=null)tower.rotation=arc.math.Angles.moveToward(tower.rotation,(float)Math.toDegrees(Math.atan2(LayerCoordinates.center(work.component().y(),work.component().size())-tower.y/Vars.tilesize,LayerCoordinates.center(work.component().x(),work.component().size())-tower.x/Vars.tilesize)),type.rotateSpeed*tower.delta());
            }
            @Override public Block blockAt(int x,int y,int layer){var c=source.at(x,y,layer,LayerComponent.Kind.BLOCK);return c==null?mindustry.content.Blocks.air:Vars.content.getByName(mindustry.ctype.ContentType.block,c.blockName());}
        });
        registry.setTurretAiming(turretAiming);return registry;
    }
    private final java.util.WeakHashMap<Unit,Player> logicActors=new java.util.WeakHashMap<>();
    private static final class TowerActor extends Player {
        final mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower;
        TowerActor(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){this.tower=tower;team(tower.team);set(tower);}
        @Override public Unit unit(){return tower.unit();}@Override public boolean dead(){return tower.dead;}@Override public boolean isBuilder(){return true;}
    }
    private LayerRebuildQueue.Tower towerState(Building tower,double work){return new LayerRebuildQueue.Tower(tower.x/(double)Vars.tilesize,tower.y/(double)Vars.tilesize,physical.buildingBase(tower),tower.team.id,((mindustry.world.blocks.defense.BuildTurret)tower.block).range/(double)Vars.tilesize,Math.max(0,work));}
    private LayerActions.Action rebuildAction(LayerComponent c){return LayerActions.Action.place(c.x(),c.y(),c.layer(),c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,c.rotation(),c.mode());}
    private LayerRebuildQueue.Callback rebuildCallback(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){var actor=new TowerActor(tower);return new LayerRebuildQueue.Callback(){
        public boolean canPlace(LayerComponent c){return validate(actor,rebuildAction(c),false).accepted();}
        public double buildTime(LayerComponent c){var entry=catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing());return entry==null||entry.block()==null?60:entry.block().buildTime;}
        public boolean complete(LayerComponent c){var result=apply(actor,rebuildAction(c),false);if(result.accepted()&&c.configuration().length>0)try{apply(actor,LayerActions.Action.configure(result.componentId(),LayerConfiguration.decode(c.configuration())),false);}catch(IllegalArgumentException ignored){}return result.accepted();}
    };}
    private void rememberDestroyed(LayerComponent c){
        if(c==null)return;var entry=catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing());if(entry==null||entry.block()!=null&&!entry.block().rebuildable)return;
        Building b=nativeBuildings.building(c.id());byte[] config=c.configuration();if(b!=null)try{config=LayerConfiguration.encode(b.config());}catch(IllegalArgumentException ignored){}
        rebuilds.remember(new LayerComponent(c.id(),c.kind(),c.x(),c.y(),c.layer(),c.blockName(),c.size(),c.rotation(),c.team(),c.mode(),c.health(),c.maxHealth(),c.mass(),c.height(),c.support(),config,new byte[0]));
    }
    private boolean buildFromLogic(Building processor,Unit unit,int x,int y,Block block,int rotation,Object config,boolean breaking){
        if(Vars.net.client()||processor==null||unit==null||unit.dead||unit.team!=processor.team||!unit.canBuild()||!(processor.tile instanceof LayerTile tile))return false;
        Player actor=logicActors.computeIfAbsent(unit,u->new Player(){@Override public Unit unit(){return u;}@Override public boolean dead(){return u.dead;}@Override public boolean isBuilder(){return u.canBuild();}});
        actor.team(unit.team);actor.set(unit);
        var existing=grid.at(x,y,tile.layer,LayerComponent.Kind.BLOCK);
        if(breaking)return existing==null||apply(actor,LayerActions.Action.remove(existing.id()),false).accepted();
        if(block==null)return false;
        if(existing!=null)return existing.team()==unit.team.id&&existing.blockName().equals(block.name)&&existing.rotation()==Math.floorMod(rotation,4);
        try{var result=apply(actor,LayerActions.Action.place(x,y,tile.layer,block.name,false,rotation,LayerComponent.Mode.STRUCTURAL),false);
            if(result.accepted()&&config!=null)apply(actor,LayerActions.Action.configure(result.componentId(),config),false);return result.accepted();
        }catch(IllegalArgumentException invalid){return false;}
    }
    private void syncNative(LayerComponent c){
        syncNative(c,nativeBuildings,grid);
    }
    private void syncNative(LayerComponent c,LayerBuildingAdapter registry,LayerWorld source){
        if(c.kind()!=LayerComponent.Kind.BLOCK)return;
        var entry=catalog.entry(c.blockName(),false,source.coordinates().spacing());
        if(entry==null||entry.block() instanceof Wall&&!LayerBuildingAdapter.supports(entry.block()))return;
        Building b=registry.building(c.id());
        if(b==null){b=registry.create(c.id(),c.x(),c.y(),c.layer(),entry.block(),mindustry.game.Team.get(c.team()),c.rotation());if(c.state().length>0)registry.readState(c.id(),c.state());}
        b.health=(float)c.health();if(b.rotation!=c.rotation()){b.rotation=c.rotation();b.updateProximity();}
    }
    private void snapshotNative(){
        var destroyed=new ArrayList<Long>();
        for(var entry:nativeBuildings.entries()){
            var c=grid.get(entry.id());if(c==null)continue;
            double health=Float.isFinite(entry.building().health)?Math.max(0,Math.min(c.maxHealth(),entry.building().health)):c.health();
            if(health<=0){destroyed.add(c.id());continue;}
            entry.building().health=(float)health;
            if(health!=c.health())c=c.withHealth(health);
            byte[] state=safeNativeState(c);
            if(!Arrays.equals(c.state(),state)||health!=grid.get(c.id()).health())grid.replace(c.withState(state));
        }
        for(long id:destroyed){rememberDestroyed(grid.get(id));grid.remove(id);warnings.remove(id);}
        // Saving immediately after node destruction must not write a dangling ground endpoint.
        nativeBuildings.pruneInvalidPowerLinks();int count=transfers.links().size();transfers.prunePowerLinks();
        if(count!=transfers.links().size()){connectorPurchases.keySet().removeIf(link->!transfers.links().contains(link));fallRevision++;}
    }
    private byte[] safeNativeState(LayerComponent component){
        try{byte[] state=nativeBuildings.writeState(component.id());stateLimitWarnings.remove(component.id());return state;}
        catch(LayerBuildingAdapter.StateLimitException limit){
            Building building=nativeBuildings.building(component.id());if(building!=null)building.enabled=false;
            if(stateLimitWarnings.add(component.id()))arc.util.Log.warn("[FPV layers] @ at @,@,Z@: state limit reached; block paused and last valid saved state retained",component.blockName(),component.x(),component.y(),component.layer());
            return component.state();
        }
    }
    StructuralSolver.Foundation foundation(int x,int y,int team){
        Tile tile=Vars.world==null?null:Vars.world.tile(x,y);Building build=tile==null?null:tile.build;
        if(build==null||build.dead||build.team.id!=team||!(build.block instanceof Wall))return null;
        var support=catalog.profile(build.block);if(support.capacity()<=0)return null;
        return new StructuralSolver.Foundation(Integer.toUnsignedLong(build.id)+1,team,Math.min(physical.surfaceHeight(x,y),planeHeight(1)),support.span(),support.capacity());
    }
    public void reset(){
        turretControls.clear();pastes.clear();destructionQueue.clear();stateLimitWarnings.clear();LayerClientLogicPresentation.clear();double spacing=1.4;int max=LayerCatalog.boundedInt("fpv-layer-max",64,1,512);
        connectorMaxGap=LayerCatalog.boundedInt("fpv-vertical-max-gap",16,1,64);grid=new LayerWorld(new LayerCoordinates(spacing,max));rebuilds=new LayerRebuildQueue(grid.coordinates());logicActors.clear();newSolver();purchases.clear();connectorPurchases.clear();falling.clear();warnings.clear();time=0;nextChunk=1;fallRevision++;compositionRevision++;invalidateCache();remoteFree=false;replicationFailure=null;
    }
    public void damage(long id,double amount){
        if(Vars.net.client()||!Double.isFinite(amount)||amount<=0)return;var c=grid.get(id);if(c==null)return;
        if(amount>=c.health()){rememberDestroyed(c);grid.remove(id);warnings.remove(id);}else grid.replace(c.withHealth(c.health()-amount));
    }
    public void update(double dt){
        if(!closed&&Vars.net.client()){nativeBuildings.reconcilePayloadReferences();if(Vars.state.isPlaying())LayerClientLogicPresentation.update(nativeBuildings,dt);return;}
        if(closed||Vars.net.client()||!Vars.state.isPlaying()||Vars.state.isPaused()||!Double.isFinite(dt)||dt<=0)return;
        dt=Math.min(.1,dt);time+=dt;logicActors.keySet().removeIf(unit->unit.dead||!unit.isAdded());
        pastes.update(dt);
        nativeBuildings.tick();int previousLinks=transfers.links().size();transfers.update(dt);if(previousLinks!=transfers.links().size()){connectorPurchases.keySet().removeIf(link->!transfers.links().contains(link));fallRevision++;}
        for(var entry:pendingNativeDamage.entrySet()){
            var c=grid.get(entry.getKey());if(c==null)continue;Building current=nativeBuildings.building(c.id());
            // Repair can follow a damage callback in the same frame: the live native value is newer.
            double health=current==null?entry.getValue():current.dead?0:current.health;
            if(!Double.isFinite(health))continue;health=Math.max(0,Math.min(c.maxHealth(),health));
            if(health<=0){rememberDestroyed(c);grid.remove(c.id());warnings.remove(c.id());}else if(health!=c.health())grid.replace(c.withHealth(health));
        }pendingNativeDamage.clear();
        // Drain outside registry/damage-map iteration. Chained explosions enqueue their own next event.
        for(int blastCount=0;blastCount<64&&!destructionQueue.isEmpty();blastCount++)destructionEffect.accept(destructionQueue.remove());
        if(time>=nextNativeSnapshot){nextNativeSnapshot=time+.2;snapshotNative();}
        solver.solveDirty();var unsupported=solver.unsupportedSections();var active=new HashSet<Long>();
        double grace=LayerCatalog.boundedInt("fpv-collapse-grace-ms",2000,0,30000)/1000.0;
        for(var section:unsupported){if(section.isEmpty())continue;double since=time;for(long id:section){active.add(id);since=Math.min(since,warnings.computeIfAbsent(id,key->time));}
            if(time-since>=grace&&falling.size()<MAX_FALLING){var members=new ArrayList<LayerComponent>();for(long id:section){var c=grid.get(id);if(c!=null){if(nativeBuildings.building(id)!=null)c=c.withState(safeNativeState(c));grid.remove(id);members.add(c);warnings.remove(id);}}
                if(!members.isEmpty()){falling.add(new FallingChunk(nextChunk++,members,0,0,grid.coordinates()));fallRevision++;compositionRevision++;}}
        }
        warnings.keySet().retainAll(active);
        if(!warnings.isEmpty())fallRevision++;
        for(var it=falling.iterator();it.hasNext();){FallingChunk chunk=it.next();if(step(chunk,dt)){it.remove();compositionRevision++;}fallRevision++;}
    }
    private boolean step(FallingChunk chunk,double dt){
        chunk.velocity=Math.max(-100,chunk.velocity-18*dt);double next=chunk.offsetZ+chunk.velocity*dt;boolean impact=false;
        Set<Building> hitBuildings=Collections.newSetFromMap(new IdentityHashMap<>());Set<Long> hitUpper=new HashSet<>();
        for(var c:chunk.components){var b=grid.coordinates().bounds(c);double oldBottom=b.minZ()+chunk.offsetZ,newBottom=b.minZ()+next;
            int minX=LayerCoordinates.minCell(c.x(),c.size()),minY=LayerCoordinates.minCell(c.y(),c.size());
            for(int x=minX;x<minX+c.size();x++)for(int y=minY;y<minY+c.size();y++){
                // A map edit may raise terrain into an already detached section; resolve that overlap too.
                double top=physical.surfaceHeight(x,y);if(top>=newBottom){impact=true;Tile tile=Vars.world.tile(x,y);if(tile!=null&&tile.build!=null)hitBuildings.add(tile.build);}
            }
            var swept=new LayerCoordinates.Bounds(b.minX(),b.minY(),newBottom,b.maxX(),b.maxY(),b.maxZ()+chunk.offsetZ);
            for(var target:grid.query(swept)){var targetBounds=grid.coordinates().bounds(target);if(targetBounds.maxZ()>=newBottom&&targetBounds.minZ()<b.maxZ()+chunk.offsetZ){impact=true;hitUpper.add(target.id());}}
        }
        // Broad-phase once per aggregate, then inspect only bodies actually under its moving hull.
        for(Unit unit:Groups.unit){if(unit.dead||chunk.hitUnits.contains(unit.id)||chunk.hitUnits.size()>=16384)continue;var pose=physical.unitState(unit);
            if(pose==null||!circleRect(pose.x(),pose.y(),pose.radius(),chunk.bounds)||pose.z()+pose.halfHeight()<chunk.bounds.minZ()+next||pose.z()-pose.halfHeight()>chunk.bounds.maxZ()+chunk.offsetZ)continue;
            for(var c:chunk.components){var b=grid.coordinates().bounds(c);if(circleRect(pose.x(),pose.y(),pose.radius(),b)&&pose.z()+pose.halfHeight()>=b.minZ()+next&&pose.z()-pose.halfHeight()<=b.maxZ()+chunk.offsetZ){float amount=impactRaw(impactDamage(chunk),RamDamageSettings.healthScale(unit));if(amount>0)RamDamageSettings.damage(unit,amount,false);chunk.hitUnits.add(unit.id);break;}}
        }
        chunk.offsetZ=next;
        if(impact){for(var c:chunk.components)rememberDestroyed(c);double damage=impactDamage(chunk);if(damage>0){for(Building building:hitBuildings){float amount=impactRaw(damage,RamDamageSettings.healthScale(building));if(amount>0)building.damage(amount);}for(long id:hitUpper)damage(id,damage);}return true;}
        return chunk.offsetZ < -20000;
    }
    private double impactDamage(FallingChunk chunk){return Math.min(LayerCatalog.boundedInt("fpv-collapse-max-damage",2500,0,100000),chunk.mass*chunk.velocity*chunk.velocity*.05);}
    /** Preserve native armor/stronger-health rules while keeping the advertised cap in actual HP. */
    private static float impactRaw(double amount,double healthScale){return Double.isFinite(amount)&&Double.isFinite(healthScale)&&amount>0&&healthScale>0?(float)(amount*Math.min(1,healthScale)):0;}
    private static boolean circleRect(double x,double y,double radius,LayerCoordinates.Bounds b){double dx=Math.max(b.minX()-x,Math.max(0,x-b.maxX())),dy=Math.max(b.minY()-y,Math.max(0,y-b.maxY()));return dx*dx+dy*dy<radius*radius;}
    private static boolean intersects(LayerCoordinates.Bounds a,LayerCoordinates.Bounds b){return a.minX()<b.maxX()-1e-6&&a.maxX()>b.minX()+1e-6&&a.minY()<b.maxY()-1e-6&&a.maxY()>b.minY()+1e-6&&a.minZ()<b.maxZ()-1e-6&&a.maxZ()>b.minZ()+1e-6;}
    private int componentCount(){int count=grid.size();for(var chunk:falling)count+=chunk.components.size();return count;}
    /** Reserve future inventory growth, not only today's empty native modules. Idle previews reuse this sum. */
    private long payloadBudget(){
        if(budgetGeometry==grid.geometryRevision()&&budgetComposition==compositionRevision)return cachedPayloadBudget;
        long bytes=5L*1024*1024+65536L*17+4; // Existing ledgers/receipts/rebuild reserve plus bounded XYZ power edges.
        for(var c:grid.components())bytes+=componentBudget(c);for(var chunk:falling)for(var c:chunk.components)bytes+=componentBudget(c);
        budgetGeometry=grid.geometryRevision();budgetComposition=compositionRevision;return cachedPayloadBudget=bytes;
    }
    private long componentBudget(LayerComponent c){
        var entry=catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,grid.coordinates().spacing());
        long state=c.state().length,receipt=12;
        if(entry!=null){for(var cost:entry.requirements())receipt+=6L+3L*cost.item.name.length();
            Block block=entry.block();if(block!=null&&!(block instanceof Wall)){
                long nativeReserve=192L+6L*Vars.content.items().size+6L*Vars.content.liquids().size;
                if(block instanceof mindustry.world.blocks.defense.turrets.ItemTurret turret)nativeReserve+=6L*turret.ammoTypes.size;
                if(block instanceof mindustry.world.blocks.logic.LogicBlock||block instanceof mindustry.world.blocks.logic.MemoryBlock||block instanceof mindustry.world.blocks.payloads.PayloadBlock)nativeReserve=LayerComponent.MAX_STATE_BYTES;
                state=Math.max(state,nativeReserve);
            }
        }
        var paid=purchases.get(c.id());if(paid!=null){long actual=12;for(var cost:paid)actual+=6L+3L*cost.name.length();receipt=Math.max(receipt,actual);}
        return 176L+3L*c.blockName().length()+c.configuration().length+state+receipt;
    }
    public byte[] replication(){
        boolean free=allowedFree();if(cachedSnapshot!=null&&cachedGridRevision==grid.revision()&&cachedFallRevision==fallRevision&&cachedRebuildRevision==rebuilds.revision()&&cachedFree==free&&cachedPowerRevision==nativeBuildings.powerRevision())return cachedSnapshot;
        if(cachedGrid==null||cachedGridRevision!=grid.revision())cachedGrid=LayerCodec.encode(grid);
        cachedSnapshot=NetProtocol.bytes(out->{out.writeInt(FORMAT);out.writeBoolean(free);out.writeInt(connectorMaxGap);out.writeInt(cachedGrid.length);out.write(cachedGrid);out.writeLong(nextChunk);out.writeInt(falling.size());
            for(var c:falling){out.writeLong(c.id);out.writeDouble(c.offsetZ);out.writeDouble(c.velocity);LayerWorld members=new LayerWorld(grid.coordinates());for(var member:c.components)members.add(member);byte[] data=LayerCodec.encode(members);out.writeInt(data.length);out.write(data);out.writeInt(c.hitUnits.size());for(int id:c.hitUnits)out.writeInt(id);}
            out.writeInt(transfers.links().size());for(var link:transfers.links()){out.writeLong(link.from());out.writeLong(link.to());out.writeByte(link.kind().ordinal());}
            out.writeInt(warnings.size());for(var warning:warnings.entrySet()){out.writeLong(warning.getKey());out.writeDouble(Math.max(0,Math.min(60,time-warning.getValue())));}
            out.writeInt(purchases.size());for(var paid:purchases.entrySet()){out.writeLong(paid.getKey());writeReceipt(out,paid.getValue());}
            out.writeInt(connectorPurchases.size());for(var paid:connectorPurchases.entrySet()){out.writeLong(paid.getKey().from());out.writeLong(paid.getKey().to());out.writeByte(paid.getKey().kind().ordinal());writeReceipt(out,paid.getValue());}
            rebuilds.write(out);
            out.writeInt(nativeBuildings.powerLinks().size());for(var link:nativeBuildings.powerLinks()){out.writeLong(link.first());out.writeLong(link.second());out.writeBoolean(link.vertical());}
        });
        cachedGridRevision=grid.revision();cachedFallRevision=fallRevision;cachedRebuildRevision=rebuilds.revision();cachedFree=free;cachedPowerRevision=nativeBuildings.powerRevision();replicationFailure=null;return cachedSnapshot;
    }
    public void applyReplication(byte[] bytes){if(!Vars.net.client())return;try{restore(bytes,true);}catch(IOException bad){throw new IllegalArgumentException("Invalid layer snapshot",bad);}}
    void restore(byte[] bytes,boolean remote)throws IOException{
        if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("Layer snapshot size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int version=in.readInt();if(version<1||version>FORMAT)throw new IOException("Layer runtime version");boolean free=in.readBoolean();int gap=version>=2?in.readInt():16;if(gap<1||gap>64)throw new IOException("Vertical gap rule");LayerWorld loaded=readGrid(in);long next=in.readLong();int count=in.readInt();
            if(next<1||count<0||count>MAX_FALLING)throw new IOException("Layer falling bounds");var chunks=new ArrayList<FallingChunk>();var ids=new HashSet<Long>();
            for(int i=0;i<count;i++){long id=in.readLong();double offset=in.readDouble(),velocity=in.readDouble();LayerWorld members=readGrid(in);
                if(id<1||id>=next||!ids.add(id)||!Double.isFinite(offset+velocity)||offset>0||offset<-20000||velocity>0||velocity<-100||!members.coordinates().equals(loaded.coordinates())||members.components().isEmpty())throw new IOException("Invalid falling chunk");
                var chunk=new FallingChunk(id,new ArrayList<>(members.components()),offset,velocity,loaded.coordinates());int hitCount=in.readInt();if(hitCount<0||hitCount>16384)throw new IOException("Impact ledger size");for(int h=0;h<hitCount;h++)chunk.hitUnits.add(in.readInt());chunks.add(chunk);
            }
            var links=new ArrayList<LayerVerticalTransfers.Link>();var restoredWarnings=new HashMap<Long,Double>();
            if(version>=2){int linksCount=in.readInt();if(linksCount<0||linksCount>8192)throw new IOException("Vertical link count");for(int i=0;i<linksCount;i++){long from=in.readLong(),to=in.readLong();int kind=in.readUnsignedByte();if(kind>=LayerVerticalTransfers.Kind.values().length)throw new IOException("Vertical link kind");links.add(new LayerVerticalTransfers.Link(from,to,LayerVerticalTransfers.Kind.values()[kind]));}
                int warningCount=in.readInt();if(warningCount<0||warningCount>32768)throw new IOException("Collapse warning count");for(int i=0;i<warningCount;i++){long id=in.readLong();double age=in.readDouble();if(loaded.get(id)==null||!Double.isFinite(age)||age<0||age>60||restoredWarnings.put(id,-age)!=null)throw new IOException("Collapse warning");}}
            var paidStructures=new HashMap<Long,List<PaidItem>>();var paidLinks=new HashMap<LayerVerticalTransfers.Link,List<PaidItem>>();
            if(version>=3){int countPaid=in.readInt();if(countPaid<0||countPaid>32768)throw new IOException("Purchase receipt count");for(int i=0;i<countPaid;i++){long id=in.readLong();if(loaded.get(id)==null||paidStructures.put(id,readReceipt(in))!=null)throw new IOException("Unknown or duplicate purchase receipt");}
                int countLinks=in.readInt();if(countLinks<0||countLinks>8192)throw new IOException("Connector receipt count");for(int i=0;i<countLinks;i++){long from=in.readLong(),to=in.readLong();int kind=in.readUnsignedByte();if(kind>=LayerVerticalTransfers.Kind.values().length)throw new IOException("Connector receipt kind");var link=new LayerVerticalTransfers.Link(from,to,LayerVerticalTransfers.Kind.values()[kind]);if(!links.contains(link)||paidLinks.put(link,readReceipt(in))!=null)throw new IOException("Unknown or duplicate connector receipt");}}
            var loadedRebuilds=version>=4?LayerRebuildQueue.read(in,loaded.coordinates()):new LayerRebuildQueue(loaded.coordinates());
            var powerLinks=new ArrayList<LayerBuildingAdapter.PowerLink>();
            if(version>=5){int n=in.readInt();if(n<0||n>65536)throw new IOException("XYZ power link count");var unique=new HashSet<LayerBuildingAdapter.PowerLink>();for(int i=0;i<n;i++){var link=new LayerBuildingAdapter.PowerLink(in.readLong(),in.readLong(),in.readBoolean());if(!unique.add(link)||link.first()==link.second())throw new IOException("Duplicate XYZ power link");powerLinks.add(link);}}
            for(var job:loadedRebuilds.snapshot()){var c=job.component();int x=LayerCoordinates.minCell(c.x(),c.size()),y=LayerCoordinates.minCell(c.y(),c.size());if(x<0||y<0||x+c.size()>Vars.world.width()||y+c.size()>Vars.world.height()||catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,loaded.coordinates().spacing())==null)throw new IOException("Invalid rebuild map/content");}
            if(in.available()!=0)throw new IOException("Trailing layer data");validateLoaded(loaded,chunks);
            if(!remote)LayerWallMigration.upgradeLegacyWalls(loaded);
            // Decode all native modules and connector references before committing any live authority.
            var prepared=createNativeRegistry(loaded);for(var c:loaded.components())syncNative(c,prepared,loaded);
            var preparedTransfers=createTransfers(loaded,prepared,gap);if(version>=5)prepared.replacePowerLinks(powerLinks);for(var iterator=links.iterator();iterator.hasNext();){var link=iterator.next();if(!preparedTransfers.configureLink(link.from(),link.to(),link.kind())){
                if(version<5&&!remote&&link.kind()==LayerVerticalTransfers.Kind.POWER){iterator.remove();paidLinks.remove(link);arc.util.Log.warn("[FPV layers] Removed legacy power riser outside native XYZ range/limits; buildings retained");}
                else throw new IOException("Invalid saved vertical endpoints");
            }}
            connectorMaxGap=gap;transfers.setMaxLayerGap(gap);
            if(remote&&grid.coordinates().equals(loaded.coordinates())){reconcileRemote(loaded,links);if(version>=5)nativeBuildings.replacePowerLinks(powerLinks);prepared.clear();}
            else {grid=loaded;newSolver(prepared,preparedTransfers);}
            if(!remote)turretControls.clear();pastes.clear();purchases.clear();purchases.putAll(paidStructures);connectorPurchases.clear();connectorPurchases.putAll(paidLinks);rebuilds=loadedRebuilds;destructionQueue.clear();stateLimitWarnings.clear();
            falling.clear();falling.addAll(chunks);warnings.clear();warnings.putAll(restoredWarnings);nextChunk=next;fallRevision++;compositionRevision++;time=0;remoteFree=remote&&free;replicationFailure=null;invalidateCache();
        }catch(RuntimeException invalid){throw new IOException("Invalid layer state",invalid);}
    }
    private static void writeReceipt(DataOutput out,List<PaidItem> paid)throws IOException{out.writeInt(paid.size());for(var cost:paid){out.writeUTF(cost.name);out.writeInt(cost.amount);}}
    private static List<PaidItem> readReceipt(DataInput in)throws IOException{
        int count=in.readInt();if(count<0||count>128)throw new IOException("Purchase item count");var paid=new ArrayList<PaidItem>();var names=new HashSet<String>();
        for(int i=0;i<count;i++){String name=in.readUTF();int amount=in.readInt();if(name.length()>128||amount<=0||!names.add(name)||Vars.content.getByName(mindustry.ctype.ContentType.item,name)==null)throw new IOException("Invalid purchase item");paid.add(new PaidItem(name,amount));}return List.copyOf(paid);
    }
    /** Keep the renderer's grid identity and unchanged native objects across inventory-only snapshots. */
    private void reconcileRemote(LayerWorld loaded,List<LayerVerticalTransfers.Link> links){
        var remove=new ArrayList<Long>();
        for(var c:grid.components()){var next=loaded.get(c.id());if(next==null||!sameGeometry(c,next))remove.add(c.id());}
        for(long id:remove)grid.remove(id);
        for(var next:loaded.components()){
            var old=grid.get(next.id());if(old==null)grid.add(next);
            else if(!old.equals(next)){
                Building b=nativeBuildings.building(next.id());
                if(b!=null&&!Arrays.equals(old.state(),next.state()))nativeBuildings.readState(next.id(),next.state());
                grid.replace(next);
            }
        }
        grid.advanceNextId(loaded.snapshot().nextId());
        for(var old:new ArrayList<>(transfers.links()))if(!links.contains(old))transfers.removeLink(old.from(),old.to(),old.kind());
        for(var link:links)transfers.configureLink(link.from(),link.to(),link.kind());
    }
    private static boolean sameGeometry(LayerComponent a,LayerComponent b){return a.kind()==b.kind()&&a.x()==b.x()&&a.y()==b.y()&&a.layer()==b.layer()&&a.blockName().equals(b.blockName())&&a.size()==b.size()&&a.height()==b.height()&&a.team()==b.team();}
    private static LayerWorld readGrid(DataInputStream in)throws IOException{int n=in.readInt();if(n<1||n>MAX_BYTES||n>in.available())throw new IOException("Layer grid length");return LayerCodec.decode(in.readNBytes(n));}
    private void validateLoaded(LayerWorld loaded,List<FallingChunk> chunks)throws IOException{
        var all=new ArrayList<>(loaded.components());for(var chunk:chunks)all.addAll(chunk.components);if(all.size()>32768)throw new IOException("Layer component count");var ids=new HashSet<Long>();
        for(var c:all){int x=LayerCoordinates.minCell(c.x(),c.size()),y=LayerCoordinates.minCell(c.y(),c.size());
            if(!ids.add(c.id())||x<0||y<0||x+c.size()>Vars.world.width()||y+c.size()>Vars.world.height()||catalog.entry(c.blockName(),c.kind()==LayerComponent.Kind.PLATFORM,loaded.coordinates().spacing())==null)throw new IOException("Unsupported layer component or map bounds");}
    }
    private void invalidateCache(){cachedGridRevision=cachedFallRevision=budgetGeometry=budgetComposition=Long.MIN_VALUE;cachedGrid=cachedSnapshot=null;}
    @Override public void close(){if(closed)return;closed=true;turretControls.close();pastes.clear();groundBridges.close();coreLifecycle.deactivate();logicActors.clear();destructionQueue.clear();LayerClientLogicPresentation.clear();Events.remove(ResetEvent.class,resetHook);Events.remove(WorldLoadEvent.class,worldHook);Events.remove(TileChangeEvent.class,tileHook);nativeBuildings.clear();solver.close();falling.clear();warnings.clear();}
}
