package fpv.construction;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import java.io.*;
import java.util.*;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.gen.Posc;
import mindustry.entities.Mover;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.power.Battery;
import mindustry.world.blocks.power.SolarGenerator;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.defense.turrets.*;

/**
 * Explicit layer-local native behaviour adapters; not an arbitrary Block.newBuilding() runner.
 * Call only on the authoritative simulation thread. This registry never installs itself globally.
 * The construction owner remains responsible for placement costs, support, health and networking.
 */
public final class LayerBuildingAdapter {
    public interface TurretAiming {
        Posc enemy(Turret.TurretBuild build,float nativeRange);
        boolean valid(Turret.TurretBuild build,Posc target);
        boolean aim(Turret.TurretBuild build,Posc target);
        default fpv.world.WorldCoordinates.Point direction(Turret.TurretBuild build){return null;}
    }
    public interface Events {
        default void damaged(long id,float health){}
        default void destroyed(Building building){}
        default void spilled(long id,Liquid liquid,float amount){}
        /** Layer owner supplies physical walls/other unsupported pieces for liquid blocking and insulation. */
        default Block blockAt(int x,int y,int layer){return mindustry.content.Blocks.air;}
        /** UI requests still travel through the construction authority, never native tile RPCs. */
        default void configureRequested(long id,Object value){}
        default double unitAltitude(Unit unit){return 0;}
        default double buildingAltitude(Building building){return building.tile instanceof LayerTile tile?tile.layer*LayerCoordinates.DEFAULT.spacing():0;}
        default boolean releaseUnit(Building source,Unit unit,double nativeX,double nativeY){return false;}
        default void defenseUpdate(Building source){}
        default void requestCoreSpawn(mindustry.world.blocks.storage.CoreBlock.CoreBuild source,mindustry.gen.Player player){}
        default boolean logicBuild(mindustry.world.blocks.logic.LogicBlock.LogicBuild processor,Unit unit,int x,int y,Block block,int rotation,Object config,boolean breaking){return false;}
        default void rebuild(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){}
        default boolean hasRebuildWork(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){return false;}
    }
    public record Entry(long id,int layer,Building building){}
    public record PowerLink(long first,long second,boolean vertical){
        public PowerLink{if(first>second){long swap=first;first=second;second=swap;}}
    }
    private static final int STATE_VERSION=1, MAX_STATE_BYTES=1<<20,MAX_WIRE_STATE_BYTES=LayerComponent.MAX_STATE_BYTES;
    public static final class StateLimitException extends IllegalStateException {StateLimitException(){super("Upper block state exceeds the bounded save/network budget");}}
    private static final class BoundedStateBytes extends ByteArrayOutputStream {
        private final int maximum;BoundedStateBytes(int maximum){this.maximum=maximum;}
        @Override public synchronized void write(int value){if(count>=maximum)throw new StateLimitException();super.write(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){if(length<0||length>maximum-count)throw new StateLimitException();super.write(bytes,offset,length);}
    }
    private record NativeType(arc.func.Prov<Building> factory,Class<?> type){}
    private static final IdentityHashMap<Block,NativeType> nativeTypes=new IdentityHashMap<>();
    private final NavigableMap<Long,Entry> entries=new TreeMap<>();
    private final Collection<Entry> entriesView=Collections.unmodifiableCollection(entries.values());
    private final IdentityHashMap<Building,Entry> identities=new IdentityHashMap<>();
    private final HashMap<Long,LayerTile> tiles=new HashMap<>();
    private final Events events;
    private final LayerTile.Lookup lookup=this::tile;
    private final LinkedHashSet<PowerLink> powerLinks=new LinkedHashSet<>();
    private final LayerPowerNetwork powerNetwork=new LayerPowerNetwork(this);
    public long powerRebuilds(){return powerNetwork.rebuilds();}
    private Entry[] tickEntries=new Entry[0];
    private boolean powerDirty=true,restoringState,entriesDirty=true;
    private long powerRevision;
    public long powerRevision(){return powerRevision;}
    private long powerIndexRevision=Long.MIN_VALUE;
    private final HashMap<Long,HashSet<Long>> powerNeighbours=new HashMap<>();
    private void powerIndex(){
        if(powerIndexRevision==powerRevision)return;
        powerNeighbours.clear();for(PowerLink link:powerLinks){powerNeighbours.computeIfAbsent(link.first,k->new HashSet<>()).add(link.second);powerNeighbours.computeIfAbsent(link.second,k->new HashSet<>()).add(link.first);}
        powerIndexRevision=powerRevision;
    }
    private TurretAiming turretAiming;
    private int turretCount;
    public boolean hasTurrets(){return turretCount>0;}
    private int maxVerticalLayerGap=16;
    private java.util.function.Predicate<Building> externalBridge=building->false;
    private static final java.lang.reflect.Field turretRangeChange=turretRangeChangeField();
    public LayerBuildingAdapter(Events events){this.events=events==null?new Events(){}:events;}

    /** Exact native behaviour families only; inherited speciality builds are not silently downgraded. */
    public static boolean supports(Block block){
        if(block==null||block.buildType==null)return false;
        if(turretFamily(block)!=null)return turretRangeChange!=null;
        Class<?> type=nativeType(block);
        return LayerNativeFamilies.supports(type)||LayerPayloadFamilies.supports(type)||LayerMassDriverFamilies.supports(type)||LayerCoreFamilies.supports(type)||LayerCampaignFamilies.supports(type)||LayerTargetDummyFamilies.supports(type)||type==Conveyor.ConveyorBuild.class||type==Conduit.ConduitBuild.class||
            type==StorageBlock.StorageBuild.class||type==GenericCrafter.GenericCrafterBuild.class||
            type==PowerNode.PowerNodeBuild.class||type==Battery.BatteryBuild.class||type==SolarGenerator.SolarGeneratorBuild.class||plainPowerVoid(block,type);
    }
    public Collection<Entry> entries(){return entriesView;}
    public void reconcilePayloadReferences(){LayerPayloadFamilies.reconcileReferences(this);LayerTargetDummyFamilies.reconcileReferences(this);}
    public Entry entry(long id){return entries.get(id);}
    public void setTurretAiming(TurretAiming aiming){turretAiming=aiming;}
    public Building building(long id){Entry entry=entries.get(id);return entry==null?null:entry.building;}
    public LayerTile tile(int x,int y,int layer){
        if(layer<1||x<0||y<0||Vars.world==null||x>=Vars.world.width()||y>=Vars.world.height())return null;
        LayerTile tile=tiles.computeIfAbsent(key(x,y,layer),k->new LayerTile(x,y,layer,lookup));
        if(tile.build==null){Block physical=events.blockAt(x,y,layer);tile.attach(physical==null?mindustry.content.Blocks.air:physical,null);}
        return tile;
    }
    public Building create(long id,int x,int y,int layer,Block block,Team team,int rotation){
        if(id<=0||entries.containsKey(id)||!supports(block)||team==null)throw new IllegalArgumentException("Unsupported or duplicate upper building");
        int offset=-(block.size-1)/2;
        for(int dx=0;dx<block.size;dx++)for(int dy=0;dy<block.size;dy++){
            LayerTile cell=tile(x+offset+dx,y+offset+dy,layer);
            if(cell==null||cell.build!=null)throw new IllegalArgumentException("Upper native footprint outside world or occupied");
        }
        Building value;
        Class<?> nativeType=turretFamily(block);if(nativeType==null)nativeType=nativeType(block);
        if(nativeType==Conveyor.ConveyorBuild.class){Conveyor original=(Conveyor)block;value=original.new ConveyorBuild(){
            @Override public void updateTile(){if(!restoringState)super.updateTile();}
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}

        };}
        else if(nativeType==Conduit.ConduitBuild.class){Conduit original=(Conduit)block;value=original.new ConduitBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}

            @Override public float moveLiquidForward(boolean leaks,Liquid liquid){
                Tile next=tile.nearby(rotation);
                if(next==null)return 0;
                if(next.build!=null)return moveLiquid(next.build,liquid);
                if(leaks&&!next.block().solid&&!next.block().hasLiquids&&liquids.get(liquid)>0){
                    float amount=liquids.get(liquid)/1.5f;liquids.remove(liquid,amount);
                    Entry entry=identities.get(this);if(entry!=null)events.spilled(entry.id,liquid,amount);
                }
                return 0;
            }
        };}
        else if(nativeType==StorageBlock.StorageBuild.class){StorageBlock original=(StorageBlock)block;value=original.new StorageBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}

        };}
        else if(nativeType==PowerNode.PowerNodeBuild.class){PowerNode original=(PowerNode)block;value=original.new PowerNodeBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
            @Override public void created(){}
            @Override public void placed(){}
            @Override public void configured(Unit builder,Object value){throw new UnsupportedOperationException("Use layer power links");}
            @Override public Point2[] config(){return new Point2[0];}
        };}
        else if(nativeType==Battery.BatteryBuild.class){Battery original=(Battery)block;value=original.new BatteryBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
        };}
        else if(nativeType==SolarGenerator.SolarGeneratorBuild.class){SolarGenerator original=(SolarGenerator)block;value=original.new SolarGeneratorBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
            @Override public void createExplosion(){}
            @Override public void onExplosion(){}
        };}
        else if(nativeType==ItemTurret.ItemTurretBuild.class){ItemTurret original=(ItemTurret)block;value=original.new ItemTurretBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
            @Override public boolean canControl(){return liveTurret(this)&&super.canControl();}
            @Override public void updateTile(){if(prepareTurret(this))super.updateTile();}
            @Override protected Posc findEnemy(float range){return liveTurret(this)?turretAiming.enemy(this,range):null;}
            @Override protected void findTarget(){
                target=findEnemy(range());if(target==null&&trackingRange()!=range())target=findEnemy(trackingRange());
            }
            @Override protected boolean validateTarget(){return turretValid(this,target);}
            @Override public void targetPosition(Posc target){if(liveTurret(this)&&!turretAiming.aim(this,target))super.targetPosition(target);}
            @Override protected void bullet(BulletType type,float xOffset,float yOffset,float angleOffset,Mover mover){
                if(!liveTurret(this)){queuedBullets=Math.max(0,queuedBullets-1);return;}
                super.bullet(type,xOffset,yOffset,angleOffset,mover);
            }
        };}
        else if(nativeType==PowerTurret.PowerTurretBuild.class){PowerTurret original=(PowerTurret)block;value=original.new PowerTurretBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
            @Override public boolean canControl(){return liveTurret(this)&&super.canControl();}
            @Override public void updateTile(){if(prepareTurret(this))super.updateTile();}
            @Override protected Posc findEnemy(float range){return liveTurret(this)?turretAiming.enemy(this,range):null;}
            @Override protected void findTarget(){
                target=findEnemy(range());if(target==null&&trackingRange()!=range())target=findEnemy(trackingRange());
            }
            @Override protected boolean validateTarget(){return turretValid(this,target);}
            @Override public void targetPosition(Posc target){if(liveTurret(this)&&!turretAiming.aim(this,target))super.targetPosition(target);}
            @Override protected void bullet(BulletType type,float xOffset,float yOffset,float angleOffset,Mover mover){
                if(!liveTurret(this)){queuedBullets=Math.max(0,queuedBullets-1);return;}
                super.bullet(type,xOffset,yOffset,angleOffset,mover);
            }
        };}
        else if(nativeType==LiquidTurret.LiquidTurretBuild.class){LiquidTurret original=(LiquidTurret)block;value=original.new LiquidTurretBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
            @Override public boolean canControl(){return liveTurret(this)&&super.canControl();}
            @Override public void updateTile(){if(prepareTurret(this))super.updateTile();}
            @Override protected Posc findEnemy(float range){return liveTurret(this)?turretAiming.enemy(this,range):null;}
            @Override protected void findTarget(){
                target=findEnemy(range());if(target==null&&trackingRange()!=range())target=findEnemy(trackingRange());
            }
            @Override protected boolean validateTarget(){return turretValid(this,target);}
            @Override public void targetPosition(Posc target){if(liveTurret(this)&&!turretAiming.aim(this,target))super.targetPosition(target);}
            @Override protected void bullet(BulletType type,float xOffset,float yOffset,float angleOffset,Mover mover){
                if(!liveTurret(this)){queuedBullets=Math.max(0,queuedBullets-1);return;}
                super.bullet(type,xOffset,yOffset,angleOffset,mover);
            }
        };}
        else if(LayerNativeFamilies.supports(nativeType)||plainPowerVoid(block,nativeType)){value=LayerNativeFamilies.create(this,block,nativeType);}
        else if(LayerPayloadFamilies.supports(nativeType)){value=LayerPayloadFamilies.create(this,block,nativeType);}
        else if(LayerMassDriverFamilies.supports(nativeType)){value=LayerMassDriverFamilies.create(this,block,nativeType);}
        else if(LayerCoreFamilies.supports(nativeType)){value=LayerCoreFamilies.create(this,block,nativeType);}
        else if(LayerCampaignFamilies.supports(nativeType)){value=LayerCampaignFamilies.create(this,block,nativeType);}
        else if(LayerTargetDummyFamilies.supports(nativeType)){value=LayerTargetDummyFamilies.create(this,block,nativeType);}
        else{GenericCrafter original=(GenericCrafter)block;value=original.new GenericCrafterBuild(){
            @Override public Building nearby(int dx,int dy){return LayerBuildingAdapter.this.nearby(this,dx,dy);}
            @Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
            @Override public void updateProximity(){LayerBuildingAdapter.this.refresh(this);}
            @Override public void removeFromProximity(){proximity.clear();}
            @Override public void onProximityAdded(){}
            @Override public void onProximityRemoved(){}
            @Override public void powerGraphRemoved(){}
            @Override public void updatePowerGraph(){}
            @Override public void sleep(){}
            @Override public void noSleep(){}
            @Override public void recache(){}
            @Override public void add(){throw new IllegalStateException("Upper building cannot enter native Groups.build");}
            @Override public void remove(){throw new IllegalStateException("Remove upper buildings through LayerBuildingAdapter");}
            @Override public void healthChanged(){}
            @Override public void damage(float amount){LayerBuildingAdapter.this.damage(this,amount);}
            @Override public void kill(){LayerBuildingAdapter.this.damage(this,Float.MAX_VALUE);}
            @Override public void killed(){kill();}
            @Override public void onDestroyed(){}
            @Override public void afterDestroyed(){}
            @Override public void onDeconstructed(Unit builder){}
            @Override public void configure(Object value){LayerBuildingAdapter.this.requestConfiguration(this,value);}
            @Override public void configureAny(Object value){configure(value);}
            @Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
            @Override public void changeTeam(Team team){changeTeam(team,false);}
            @Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}

        };}
        LayerTile anchor=tile(x,y,layer);anchor.attach(block,value);
        try{
            value.init(anchor,team,false,Math.floorMod(rotation,4));
            // create() allocates a native graph but does not add its updater. Replace with explicit no-entity graph.
            if(value.power!=null){value.power.graph=new PowerGraph(true);value.power.graph.add(value);value.power.status=0;}
            for(int dx=0;dx<block.size;dx++)for(int dy=0;dy<block.size;dy++)
                tile(x+offset+dx,y+offset+dy,layer).attach(block,value);
            if(value instanceof Turret.TurretBuild t)t.unit=new fpv.control.LayerTurretUnit(t);
            Entry entry=new Entry(id,layer,value);entries.put(id,entry);identities.put(value,entry);
            entriesDirty=true;powerDirty=true;refreshArea(value);autoConnectPower(entry);if(value instanceof Turret.TurretBuild)turretCount++;return value;
        }catch(RuntimeException|Error failure){
            anchor.getLinkedTilesAs(block,t->{if(t.build==value)((LayerTile)t).clearView();});
            entries.remove(id);identities.remove(value);throw failure;
        }
    }
    public void remove(long id){
        Entry entry=entries.remove(id);if(entry==null)return;
        Building value=entry.building;identities.remove(value);
        if(value instanceof Turret.TurretBuild)turretCount--;
        if(value instanceof mindustry.world.blocks.defense.Radar.RadarBuild radar)LayerRadarVisibility.remove(radar);
        if(value instanceof Turret.TurretBuild turret&&turret.soundLoop!=null)turret.soundLoop.stop();
        value.tile.getLinkedTiles(t->{if(t.build==value)((LayerTile)t).clearView();});
        value.proximity.clear();value.dead=true;if(powerLinks.removeIf(link->link.first==id||link.second==id))powerRevision++;entriesDirty=true;powerDirty=true;refreshArea(value);
    }
    /** Native timers, consumers, craft recipes, conveyor item movement and conduit flow run unchanged. */
    public void tick(){
        if(!Float.isFinite(arc.util.Time.delta)||arc.util.Time.delta<=0)return;
        if(entriesDirty){tickEntries=entries.values().toArray(Entry[]::new);entriesDirty=false;}
        if(powerDirty||powerNetwork.changed())rebuildPowerGraphs();
        for(Entry entry:tickEntries){
            Building value=entry.building;
            if(identities.get(value)==entry&&!value.dead&&value.health>0)value.update();
        }
        if(powerDirty||powerNetwork.changed())rebuildPowerGraphs();
        powerNetwork.update();
    }
    public void clear(){for(long id:new ArrayList<>(entries.keySet()))remove(id);powerLinks.clear();powerRevision++;powerNetwork.clear();tiles.clear();tickEntries=new Entry[0];entriesDirty=true;}
    public Collection<PowerLink> powerLinks(){return Collections.unmodifiableCollection(powerLinks);}
    /** Call after physical insulating walls or other layer-owned adjacency changes. */
    public void invalidateTopology(){powerDirty=true;for(Entry entry:entries.values())refresh(entry.building);}
    public boolean connectPower(long first,long second){return connectPower(first,second,false);}
    public boolean connectVerticalPower(long first,long second){return connectPower(first,second,powerLayer(first)!=powerLayer(second));}
    public void setMaxVerticalLayerGap(int layers){if(layers<1||layers>64)throw new IllegalArgumentException("Vertical layer gap must be 1–64");maxVerticalLayerGap=layers;}
    public void disconnectPower(long first,long second){if(powerLinks.removeIf(link->link.first==Math.min(first,second)&&link.second==Math.max(first,second))){powerDirty=true;powerRevision++;}}
    public byte[] writeState(long id){
        Building value=required(id);
        Building disabler=value.lastDisabler;value.lastDisabler=null;
        try{
            ByteArrayOutputStream bytes=new BoundedStateBytes(MAX_STATE_BYTES);DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(STATE_VERSION);out.writeByte(value.version());value.writeAll(new Writes(out));out.flush();
            byte[] result=bytes.toByteArray();
            if(result.length<=MAX_WIRE_STATE_BYTES)return result;
            ByteArrayOutputStream compressed=new BoundedStateBytes(MAX_WIRE_STATE_BYTES);DataOutputStream header=new DataOutputStream(compressed);header.writeByte(2);header.writeInt(result.length);
            try(var deflater=new java.util.zip.DeflaterOutputStream(compressed)){deflater.write(result);}
            return compressed.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        finally{value.lastDisabler=disabler;}
    }
    public void readState(long id,byte[] bytes){
        if(bytes==null||bytes.length==0)return;
        if(bytes.length>MAX_WIRE_STATE_BYTES)throw new IllegalArgumentException("Upper native state too large");
        bytes=inflateState(bytes);
        // Versioned native headers are deliberately restricted to the formats verified on 160.5/160.7.
        // A lastDisabler reference is packed XY and must never be resolved into the vanilla world.
        if(bytes.length<11||(bytes[6]&128)==0||(bytes[8]!=3&&bytes[8]!=4)||(bytes[10]&(1<<5))!=0)
            throw new IllegalArgumentException("Unsupported or XY-linked upper native state header");
        Building value=required(id);Team expectedTeam=value.team;int expectedRotation=value.rotation;
        // Conveyor.read calls updateTile; detach all outputs while decoding to prevent replaying item transfers.
        Seq<Building> saved=value.proximity.copy();value.proximity.clear();restoringState=true;
        if(value instanceof Conveyor.ConveyorBuild conveyor){conveyor.next=null;conveyor.nextc=null;conveyor.aligned=false;}
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readUnsignedByte()!=STATE_VERSION)throw new IOException("Unsupported upper native state version");
            byte nativeVersion=in.readByte();value.readAll(new Reads(in),nativeVersion);
            if(in.available()!=0)throw new IOException("Trailing upper native state");
            validateNativeState(value);
            value.team=expectedTeam;value.rotation=expectedRotation;value.lastDisabler=null;
            if(value.power!=null){value.power.links.clear();value.power.graph=new PowerGraph(true);value.power.graph.add(value);}
        }catch(IOException failure){throw new UncheckedIOException(failure);}
        finally{restoringState=false;powerDirty=true;value.proximity.clear();value.proximity.addAll(saved);refreshArea(value);}
    }
    private Building required(long id){Building value=building(id);if(value==null)throw new IllegalArgumentException("Unknown upper building");return value;}
    private static byte[] inflateState(byte[] bytes){
        if(bytes[0]!=2)return bytes;
        if(bytes.length<6)throw new IllegalArgumentException("Truncated upper compressed state");
        int length=((bytes[1]&255)<<24)|((bytes[2]&255)<<16)|((bytes[3]&255)<<8)|(bytes[4]&255);
        if(length<11||length>MAX_STATE_BYTES)throw new IllegalArgumentException("Upper state inflation bound");
        var inflater=new java.util.zip.Inflater();
        try{inflater.setInput(bytes,5,bytes.length-5);byte[] raw=new byte[length];int written=0;
            while(written<length&&!inflater.finished()){int read=inflater.inflate(raw,written,length-written);if(read==0)break;written+=read;}
            if(written!=length||!inflater.finished()||inflater.getRemaining()!=0||raw[0]!=STATE_VERSION)throw new IllegalArgumentException("Invalid upper compressed state");return raw;
        }catch(java.util.zip.DataFormatException invalid){throw new IllegalArgumentException("Invalid upper compressed state",invalid);}finally{inflater.end();}
    }
    Building nearby(Building value,int dx,int dy){Entry entry=identities.get(value);if(entry==null)return null;LayerTile cell=tile(value.tile.x+dx,value.tile.y+dy,entry.layer);return cell==null?null:cell.build;}
    void refresh(Building value){
        Entry entry=identities.get(value);if(entry==null)return;value.proximity.clear();
        for(Point2 edge:Edges.getEdges(value.block.size)){Building other=nearby(value,edge.x,edge.y);if(other!=null&&other!=value&&other.team==value.team)value.proximity.addUnique(other);}
        value.onProximityUpdate();
    }
    private void refreshArea(Building value){
        // Placement/removal only; no map-wide or per-frame adjacency rebuilding.
        Entry entry=identities.get(value);int layer=entry==null?((LayerTile)value.tile).layer:entry.layer;
        HashSet<Building> changed=new HashSet<>();if(entry!=null)changed.add(value);
        for(Point2 edge:Edges.getEdges(value.block.size)){LayerTile cell=tile(value.tile.x+edge.x,value.tile.y+edge.y,layer);if(cell!=null&&cell.build!=null)changed.add(cell.build);}
        for(Building other:changed)refresh(other);
    }
    void damage(Building value,float amount){
        if(!Float.isFinite(amount)||amount<=0||value.dead)return;
        if(value instanceof mindustry.world.blocks.defense.ShieldWall.ShieldWallBuild shield){amount=LayerAreaBehavior.shieldWallDamage(shield,amount);if(amount<=0)return;}
        float multiplier=Vars.state.rules.blockHealth(value.team);
        float applied=multiplier==0?value.health+1:amount/multiplier;
        value.health=Math.max(0,value.health-value.handleDamage(applied));
        value.dead=value.health<=0;if(value.dead)powerDirty=true;Entry entry=identities.get(value);if(entry!=null){events.damaged(entry.id,value.health);if(value.dead&&!restoringState)events.destroyed(value);}
    }
    Building powerEndpoint(long id){
        if(id>0)return building(id);
        if(id==0||Vars.world==null)return null;
        long packed=-id-1;if(packed<0||packed>0xffffffffL)return null;
        Building value=Vars.world.build((int)packed);
        return value!=null&&Integer.toUnsignedLong(value.pos())==packed?value:null;
    }
    private int powerLayer(long id){Entry entry=entries.get(id);return entry==null?0:entry.layer;}
    public boolean linkedPower(long first,long second){powerIndex();var neighbours=powerNeighbours.get(first);return neighbours!=null&&neighbours.contains(second);}
    public String powerLinkReason(long first,long second){return powerLinkReason(first,second,false);}
    private String powerLinkReason(long first,long second,boolean existing){
        if(first<0&&second<0)return "Use native Z0 node configuration for ground-only links";
        Building a=powerEndpoint(first),b=powerEndpoint(second);
        if(a==null||b==null||a==b||a.dead||b.dead||a.health<=0||b.health<=0)return "Choose two live power buildings";
        if(a.team!=b.team)return "Power endpoints must belong to the same team";
        if(a.power==null||b.power==null||!a.block.connectedPower||!b.block.connectedPower)return "Both blocks must support power connections";
        boolean node=a.block instanceof PowerNode||b.block instanceof PowerNode;
        if(!node){
            if(existing&&powerLayer(first)==powerLayer(second)&&(a.block instanceof mindustry.world.blocks.power.BeamNode||b.block instanceof mindustry.world.blocks.power.BeamNode))return null;
            // Existing explicit risers between upper power buildings retain their shaft/gap semantics.
            if(first<=0||second<=0||Math.abs(powerLayer(first)-powerLayer(second))<1||Math.abs(powerLayer(first)-powerLayer(second))>maxVerticalLayerGap||a.x!=b.x||a.y!=b.y)return "A native Power Node is required (or an aligned upper power riser)";
            return null;
        }
        double dz=(powerAltitude(first,a)-powerAltitude(second,b))*Vars.tilesize;
        if(!nodeReachOne(a,b,dz)&&!nodeReachOne(b,a,dz))return "Outside native power-link range in XYZ";
        if(insulated(first,second,a,b))return "An insulated wall blocks the power link";
        if(existing&&(nodeOverfull(first,a)||nodeOverfull(second,b))||!existing&&!linkedPower(first,second)&&(nodeFull(first,a)||nodeFull(second,b)))return "Native Power Node connection limit reached";
        return null;
    }
    private double powerAltitude(long id,Building b){return id>0?powerLayer(id)*layerSpacing:events.buildingAltitude(b);}
    public void replacePowerLinks(Collection<PowerLink> links){
        powerLinks.clear();powerDirty=true;powerRevision++;
        if(links.size()>65536)throw new IllegalArgumentException("XYZ power link count");
        powerIndex();var unique=new HashSet<PowerLink>();
        for(PowerLink link:links){
            String reason=powerLinkReason(link.first,link.second,true);
            if(reason!=null||!unique.add(new PowerLink(link.first,link.second,false)))throw new IllegalArgumentException("Invalid saved XYZ power link: "+reason);
            powerLinks.add(link);
        }
        powerRevision++; // Build the endpoint index once for the complete restored edge list.
        for(PowerLink link:powerLinks)if(nodeOverfull(link.first,powerEndpoint(link.first))||nodeOverfull(link.second,powerEndpoint(link.second)))throw new IllegalArgumentException("Saved node connection limit exceeded");
    }
    private boolean connectPower(long first,long second,boolean vertical){
        if(powerLinkReason(first,second)!=null)return false;
        if(linkedPower(first,second))return true;
        if(powerLinks.size()>=65536)return false;
        powerLinks.add(new PowerLink(first,second,vertical));powerDirty=true;powerRevision++;return true;
    }
    private boolean nodeFull(long id,Building value){
        if(!(value.block instanceof PowerNode node))return false;
        return nodeConnections(id,value)>=node.maxNodes;
    }
    private boolean nodeOverfull(long id,Building value){return value!=null&&value.block instanceof PowerNode node&&nodeConnections(id,value)>node.maxNodes;}
    private int nodeConnections(long id,Building value){
        int count=id<0?value.power.links.size:0;
        powerIndex();var neighbours=powerNeighbours.get(id);if(neighbours!=null)count+=neighbours.size();
        return count;
    }
    private static boolean nodeReachOne(Building a,Building b,double dz){
        if(!(a.block instanceof PowerNode node)||(node.sameBlockConnection&&a.block!=b.block))return false;
        // Vanilla measures to the target's XY footprint; add actual height in native pixel units.
        double dx=Math.max(0,Math.abs(a.x-b.x)-b.block.size*Vars.tilesize*.5),dy=Math.max(0,Math.abs(a.y-b.y)-b.block.size*Vars.tilesize*.5);
        return dx*dx+dy*dy+dz*dz<=Math.pow(node.laserRange*Vars.tilesize,2)+1e-6;
    }
    private boolean insulated(long first,long second,Building a,Building b){
        int layer=powerLayer(first);
        if(layer==0&&powerLayer(second)==0)return PowerNode.insulated(a,b);
        // Native insulation, evaluated along the actual XYZ segment, not through hidden Z0 walls.
        double az=powerAltitude(first,a),bz=powerAltitude(second,b);
        int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(a.tile.x-b.tile.x),Math.abs(a.tile.y-b.tile.y))*2+Math.abs(az-bz)*2));
        for(int i=0;i<=steps;i++){
            double t=i/(double)steps,z=az+(bz-az)*t;
            int x=(int)Math.round(a.tile.x+(b.tile.x-a.tile.x)*t),y=(int)Math.round(a.tile.y+(b.tile.y-a.tile.y)*t);
            // Only insulating blocks intersecting this floor segment participate; ordinary solids do not.
            int floor=(int)Math.floor(z/layerSpacing+1e-6);
            Tile cell=floor==0?Vars.world.tile(x,y):tile(x,y,floor);
            if(cell!=null&&cell.block().insulated&&cell.build!=a&&cell.build!=b)return true;
        }
        return false;
    }
    private double layerSpacing=LayerCoordinates.DEFAULT.spacing();
    public void setPowerLayerSpacing(double spacing){if(!Double.isFinite(spacing)||spacing<=0)throw new IllegalArgumentException("Power layer spacing");layerSpacing=spacing;}
    private void autoConnectPower(Entry added){
        if(added.building.power==null)return;
        for(Entry other:entries.values())if(other!=added&&other.layer==added.layer)connectPower(added.id,other.id,false);
    }
    /** Rebuilds only after topology changes; steady-state work updates existing native graphs once. */
    public void pruneInvalidPowerLinks(){
        for(var iterator=powerLinks.iterator();iterator.hasNext();){var link=iterator.next();if(powerLinkReason(link.first,link.second,true)!=null){
            iterator.remove();long previous=powerRevision++;powerDirty=true;
            if(powerIndexRevision==previous){var a=powerNeighbours.get(link.first);var b=powerNeighbours.get(link.second);if(a!=null)a.remove(link.second);if(b!=null)b.remove(link.first);powerIndexRevision=powerRevision;}
        }}
    }
    private void rebuildPowerGraphs(){
        pruneInvalidPowerLinks();powerDirty=false;powerNetwork.rebuild();
    }
    static boolean conducts(Building a,Building b){
        return a.team==b.team&&a.power!=null&&b.power!=null&&a.block.connectedPower&&b.block.connectedPower&&a.conductsTo(b)&&b.conductsTo(a)&&
            !(a.block.consumesPower&&b.block.consumesPower&&!a.block.outputsPower&&!b.block.outputsPower&&!a.block.conductivePower&&!b.block.conductivePower);
    }
    private static long key(int x,int y,int layer){return((long)layer<<32)|((long)(x&65535)<<16)|(y&65535L);}
    private static Class<?> nativeType(Block block){
        NativeType cached=nativeTypes.get(block);
        if(cached==null||cached.factory!=block.buildType){Class<?> type=block.newBuilding().getClass();while(type.isAnonymousClass())type=type.getSuperclass();cached=new NativeType(block.buildType,type);nativeTypes.put(block,cached);}
        return cached.type;
    }
    boolean restoringState(){return restoringState;}
    double unitAltitude(Unit unit){return events.unitAltitude(unit);}
    double buildingAltitude(Building building){return events.buildingAltitude(building);}
    boolean releaseUnit(Building source,Unit unit,double nativeX,double nativeY){return events.releaseUnit(source,unit,nativeX,nativeY);}
    void defenseUpdate(Building source){events.defenseUpdate(source);}
    void requestCoreSpawn(mindustry.world.blocks.storage.CoreBlock.CoreBuild source,mindustry.gen.Player player){events.requestCoreSpawn(source,player);}
    boolean logicBuild(mindustry.world.blocks.logic.LogicBlock.LogicBuild processor,Unit unit,int x,int y,Block block,int rotation,Object config,boolean breaking){return events.logicBuild(processor,unit,x,y,block,rotation,config,breaking);}
    void rebuild(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){events.rebuild(tower);}
    boolean hasRebuildWork(mindustry.world.blocks.defense.BuildTurret.BuildTurretBuild tower){return events.hasRebuildWork(tower);}
    boolean hasPowerLink(Building source){Entry entry=identities.get(source);if(entry==null)return false;for(PowerLink link:powerLinks)if(link.first==entry.id||link.second==entry.id)return true;return false;}
    void requestConfiguration(Building value,Object config){Entry entry=identities.get(value);if(entry!=null)events.configureRequested(entry.id,config);}
    public void setExternalBridge(java.util.function.Predicate<Building> predicate){externalBridge=predicate==null?building->false:predicate;}
    boolean externalBridge(Building value){return externalBridge.test(value);}
    public boolean configureNative(long id,Object config){
        Building value=building(id);if(value==null)return false;byte[] previous;
        try{previous=writeState(id);}catch(RuntimeException invalidPrevious){previous=null;}
        try{if(configureValue(value,config)){writeState(id);return true;}}
        catch(RuntimeException invalidConfiguration){/* Native handlers and serialization must form one transaction. */}
        if(previous!=null){try{readState(id,previous);}catch(RuntimeException invalidRestore){value.enabled=false;}}
        else value.enabled=false;
        return false;
    }
    /** Bounded native configuration schemas; never dispatch packed-XY native RPC handlers. */
    boolean configureValue(Building value,Object config){
        if(value!=null&&LayerTargetDummyFamilies.supports(nativeType(value.block)))return LayerTargetDummyFamilies.configure(value,config);
        if(value==null||(!LayerNativeFamilies.supports(nativeType(value.block))&&!LayerPayloadFamilies.supports(nativeType(value.block))&&!LayerMassDriverFamilies.supports(nativeType(value.block))&&!LayerCampaignFamilies.supports(nativeType(value.block))))return false;
        if(config!=null&&!(config instanceof mindustry.type.Item)&&!(config instanceof Liquid)&&!(config instanceof Boolean)&&!(config instanceof Integer)&&!(config instanceof String)&&!(config instanceof Point2)&&!(config instanceof Block)&&!(config instanceof mindustry.type.UnitType)&&!(config instanceof mindustry.ai.UnitCommand)&&!(config instanceof byte[]))return false;
        if(config instanceof String text&&text.length()>4096)return false;
        if(config instanceof Point2 point){
            if(!LayerConfiguration.validOffset(point))return false;
            long x=(long)value.tileX()+point.x,y=(long)value.tileY()+point.y;
            if(x<0||y<0||x>=Vars.world.width()||y>=Vars.world.height())return false;
        }
        if(value.block instanceof mindustry.world.blocks.units.UnitFactory factory){
            if(config instanceof Integer index&&(index< -1||index>=factory.plans.size))return false;
            if(config instanceof mindustry.type.UnitType unit&&!factory.plans.contains(plan->plan.unit==unit))return false;
        }
        if(config instanceof Block block&&value.block instanceof mindustry.world.blocks.payloads.Constructor constructor&&!constructor.canProduce(block))return false;
        if(value.block instanceof mindustry.world.blocks.payloads.PayloadRouter router){
            if(config instanceof Block block&&!router.canSort(block))return false;
            if(config instanceof mindustry.type.UnitType unit&&!router.canSort(unit))return false;
        }
        if(value instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild)return LayerMassDriverFamilies.configure(this,value,config);
        if(value instanceof mindustry.world.blocks.logic.LogicBlock.LogicBuild logic)return LayerLogicBehavior.configure(this,logic,config);
        if(config instanceof byte[] bytes&&(bytes.length>12288||!(value instanceof mindustry.world.blocks.logic.CanvasBlock.CanvasBuild canvas)||bytes.length!=canvas.data.length))return false;
        if(value instanceof PowerNode.PowerNodeBuild)return false; // Layer power links have their own authority path.
        if(value instanceof mindustry.world.blocks.defense.Door.DoorBuild door){
            if(!(config instanceof Boolean open))return false;
            return LayerAreaBehavior.door(this,door,open);
        }
        if(value instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild bridge){
            int packed=config instanceof Point2 point?Point2.pack(value.tileX()+point.x,value.tileY()+point.y):config instanceof Integer integer?integer:-1;
            if(config!=null&&!(config instanceof Point2)&&!(config instanceof Integer))return false;
            if(packed!=-1&&!LayerNativeBehavior.validBridge(this,bridge,packed,false))return false;
            bridge.link=packed;return true;
        }
        Class<?> type=config==null?void.class:config instanceof mindustry.type.Item?mindustry.type.Item.class:config instanceof Liquid?Liquid.class:config instanceof Block?Block.class:config instanceof mindustry.type.UnitType?mindustry.type.UnitType.class:config instanceof mindustry.ai.UnitCommand?mindustry.ai.UnitCommand.class:config.getClass();
        if(!value.block.configurations.containsKey(type))return false;
        value.block.configurations.get(type).get(value,config);return true;
    }
    float sumAttribute(Building value,mindustry.world.meta.Attribute attribute){
        if(attribute==null)return 0;final float[] sum={0};value.tile.getLinkedTiles(cell->sum[0]+=cell.floor().attributes.get(attribute));return sum[0];
    }
    float moveLiquidForward(Building value,boolean leaks,Liquid liquid){
        Tile next=value.tile.nearby(value.rotation);if(next==null)return 0;
        if(next.build!=null)return value.moveLiquid(next.build,liquid);
        if(leaks&&!next.block().solid&&!next.block().hasLiquids&&value.liquids.get(liquid)>0){
            float amount=value.liquids.get(liquid)/1.5f;value.liquids.remove(liquid,amount);Entry entry=identities.get(value);if(entry!=null)events.spilled(entry.id,liquid,amount);
        }return 0;
    }
    void refreshBeam(mindustry.world.blocks.power.BeamNode.BeamNodeBuild beam){
        Entry source=identities.get(beam);if(source==null)return;
        boolean changed=powerLinks.removeIf(link->!link.vertical&&(link.first==source.id||link.second==source.id));
        var type=(mindustry.world.blocks.power.BeamNode)beam.block;
        java.util.Arrays.fill(beam.links,null);java.util.Arrays.fill(beam.dests,null);
        for(int direction=0;direction<4;direction++)for(int distance=1+type.size/2;distance<=type.range+type.size/2;distance++){
            LayerTile cell=tile(beam.tileX()+Geometry.d4x(direction)*distance,beam.tileY()+Geometry.d4y(direction)*distance,source.layer);
            if(cell==null||cell.block().insulated)break;Building target=cell.build;
            if(target!=null&&target!=beam&&target.power!=null&&target.block.connectedPower&&target.team==beam.team&&!(target.block instanceof PowerNode)){
                Entry entry=identities.get(target);if(entry!=null){changed|=powerLinks.add(new PowerLink(source.id,entry.id,false));beam.links[direction]=target;beam.dests[direction]=cell;}break;
            }
        }if(changed){powerDirty=true;powerRevision++;}
    }
    private static void validateNativeState(Building value)throws IOException{
        if(!Float.isFinite(value.health)||value.health<0||!Float.isFinite(value.timeScale())||value.timeScale()<0)throw new IOException("Invalid upper native health/time scale");
        if(value.items!=null){long total=0;for(var item:Vars.content.items()){int amount=value.items.get(item);if(amount<0)throw new IOException("Negative upper item inventory");total+=amount;}if(total>Integer.MAX_VALUE)throw new IOException("Upper item inventory overflow");}
        if(value.liquids!=null)for(var liquid:Vars.content.liquids()){float amount=value.liquids.get(liquid);if(!Float.isFinite(amount)||amount<0)throw new IOException("Invalid upper liquid inventory");}
        if(value.power!=null&&(!Float.isFinite(value.power.status)||value.power.status<0||value.power.status>1.0001f))throw new IOException("Invalid upper power state");
        if(value instanceof Conveyor.ConveyorBuild conveyor)for(int i=0;i<conveyor.len;i++)if(conveyor.ids[i]==null||!Float.isFinite(conveyor.xs[i])||!Float.isFinite(conveyor.ys[i]))throw new IOException("Invalid upper conveyor item state");
        if(value instanceof GenericCrafter.GenericCrafterBuild crafter&&(!Float.isFinite(crafter.progress)||!Float.isFinite(crafter.warmup)||crafter.progress<0||crafter.warmup<0))throw new IOException("Invalid upper crafter progress");
        if(value instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild generator&&(!Float.isFinite(generator.productionEfficiency)||!Float.isFinite(generator.generateTime)||generator.productionEfficiency<0))throw new IOException("Invalid upper generator state");
        if(value instanceof Turret.TurretBuild turret){if(!Float.isFinite(turret.rotation)||!Float.isFinite(turret.reloadCounter)||turret.reloadCounter<0||turret.totalAmmo<0)throw new IOException("Invalid upper turret state");for(var ammo:turret.ammo)if(ammo.amount<0)throw new IOException("Invalid upper turret ammo");}
    }
    private static Class<?> turretFamily(Block block){
        // The existing true-3D targeting adapter replaces buildType with anonymous native subclasses.
        // Determine the original content family, never mistake those wrappers for unsupported content.
        Class<?> type=block.getClass();while(type.isAnonymousClass())type=type.getSuperclass();
        if(!(block instanceof Turret turret))return null;
        if(type==ItemTurret.class)return ItemTurret.ItemTurretBuild.class;
        if(type==LiquidTurret.class)return LiquidTurret.LiquidTurretBuild.class;
        if(type==PowerTurret.class)return PowerTurret.PowerTurretBuild.class;
        return null;
    }
    private static boolean plainPowerVoid(Block block,Class<?> type){Class<?> family=block.getClass();while(family.isAnonymousClass())family=family.getSuperclass();return type==Building.class&&family==mindustry.world.blocks.sandbox.PowerVoid.class;}
    private static java.lang.reflect.Field turretRangeChangeField(){
        try{var field=Turret.TurretBuild.class.getDeclaredField("lastRangeChange");field.setAccessible(true);return field;}
        catch(ReflectiveOperationException|RuntimeException unsupported){return null;}
    }
    boolean liveTurret(Turret.TurretBuild build){return turretAiming!=null&&!build.dead&&build.health>0&&identities.containsKey(build);}
    Posc turretEnemy(Turret.TurretBuild build,float range){return liveTurret(build)?turretAiming.enemy(build,range):null;}
    boolean turretValid(Turret.TurretBuild build,Posc target){return liveTurret(build)&&(build.controlled()||build.logicControlled()||target!=null&&turretAiming.valid(build,target));}
    boolean turretAim(Turret.TurretBuild build,Posc target){return liveTurret(build)&&turretAiming.aim(build,target);}
    boolean prepareTurret(Turret.TurretBuild build){
        if(!liveTurret(build)){build.target=null;return false;}
        // Native fog caches are indexed by Z0 XY. Upper fog/occlusion is owned by the 3D renderer.
        try{turretRangeChange.setFloat(build,build.hasAmmo()&&build.peekAmmo()!=null?build.peekAmmo().rangeChange:0);return true;}
        catch(IllegalAccessException failure){throw new IllegalStateException("Unsupported native turret fog field",failure);}
    }
}
