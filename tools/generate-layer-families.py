"""Generate explicit native subclasses with identical layer-isolation guards.

Only our adapters are generated; game code/classes are compile-only dependencies.
Family-specific logic remains readable in the declaration below and LayerNativeBehavior.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
families={
"sandbox.ItemSource.ItemSourceBuild":"",
"sandbox.ItemVoid.ItemVoidBuild":"",
"sandbox.LiquidSource.LiquidSourceBuild":"",
"sandbox.LiquidVoid.LiquidVoidBuild":"",
"sandbox.PowerSource.PowerSourceBuild":"@Override public void configured(Unit unit,Object value){owner.configureValue(this,value);}",
"distribution.ArmoredConveyor.ArmoredConveyorBuild":"@Override public void updateTile(){if(!owner.restoringState())super.updateTile();}",
"distribution.Router.RouterBuild":"",
"distribution.Junction.JunctionBuild":"",
"distribution.Sorter.SorterBuild":"@Override public void configured(Unit unit,Object value){owner.configureValue(this,value);}",
"distribution.OverflowGate.OverflowGateBuild":"",
"distribution.Duct.DuctBuild":"",
"distribution.DuctRouter.DuctRouterBuild":"",
"distribution.OverflowDuct.OverflowDuctBuild":"",
"distribution.DuctJunction.DuctJunctionBuild":"",
"distribution.DirectionalUnloader.DirectionalUnloaderBuild":"",
"distribution.StackRouter.StackRouterBuild":"",
"distribution.StackConveyor.StackConveyorBuild":"@Override public void onProximityUpdate(){LayerNativeBehavior.stackProximity(this);}",
"distribution.DuctBridge.DuctBridgeBuild":"@Override public void updateTile(){if(owner.externalBridge(this))lastLink=null;else super.updateTile();} @Override public boolean acceptItem(Building source,Item item){return owner.externalBridge(this)?CrossLayerBridgeHooks.acceptDirectionalItem(this,source):super.acceptItem(source,item);}",
"distribution.DirectionLiquidBridge.DuctBridgeBuild":"@Override public void updateTile(){if(owner.externalBridge(this))lastLink=null;else super.updateTile();} @Override public boolean acceptLiquid(Building source,Liquid liquid){return owner.externalBridge(this)?CrossLayerBridgeHooks.acceptDirectionalLiquid(this,source,liquid):super.acceptLiquid(source,liquid);}",
"storage.Unloader.UnloaderBuild":"",
"liquid.ArmoredConduit.ArmoredConduitBuild":"@Override public float moveLiquidForward(boolean leaks,Liquid liquid){return owner.moveLiquidForward(this,leaks,liquid);}",
"liquid.LiquidRouter.LiquidRouterBuild":"",
"liquid.LiquidJunction.LiquidJunctionBuild":"",
"production.AttributeCrafter.AttributeCrafterBuild":"@Override public void onProximityUpdate(){super.onProximityUpdate();attrsum=owner.sumAttribute(this,((mindustry.world.blocks.production.AttributeCrafter)block).attribute);}",
"production.Separator.SeparatorBuild":"",
"production.HeatCrafter.HeatCrafterBuild":"",
"production.Incinerator.IncineratorBuild":"",
"production.ItemIncinerator.ItemIncineratorBuild":"",
"production.Drill.DrillBuild":"",
"production.BurstDrill.BurstDrillBuild":"",
"production.BeamDrill.BeamDrillBuild":"@Override protected void updateFacing(){LayerNativeBehavior.beamFacing(owner,this);}",
"production.WallCrafter.WallCrafterBuild":"@Override public void updateTile(){LayerNativeBehavior.wallCraft(owner,this);}",
"production.Pump.PumpBuild":"",
"production.SolidPump.SolidPumpBuild":"@Override public void onProximityUpdate(){super.onProximityUpdate();boost=owner.sumAttribute(this,((mindustry.world.blocks.production.SolidPump)block).attribute)/(block.size*block.size);}",
"production.Fracker.FrackerBuild":"@Override public void onProximityUpdate(){super.onProximityUpdate();boost=owner.sumAttribute(this,((mindustry.world.blocks.production.SolidPump)block).attribute)/(block.size*block.size);}",
"heat.HeatProducer.HeatProducerBuild":"",
"heat.HeatConductor.HeatConductorBuild":"",
"power.ConsumeGenerator.ConsumeGeneratorBuild":"",
"power.HeaterGenerator.HeaterGeneratorBuild":"",
"power.NuclearReactor.NuclearReactorBuild":"",
"power.ImpactReactor.ImpactReactorBuild":"",
"power.VariableReactor.VariableReactorBuild":"",
"power.ThermalGenerator.ThermalGeneratorBuild":"@Override public void onProximityUpdate(){super.onProximityUpdate();sum=owner.sumAttribute(this,((mindustry.world.blocks.power.ThermalGenerator)block).attribute);}",
"power.PowerDiode.PowerDiodeBuild":"",
"power.BeamNode.BeamNodeBuild":"@Override public void updateTile(){} @Override public void updateDirections(){owner.refreshBeam(this);} @Override public void onProximityUpdate(){owner.refreshBeam(this);}",
"power.LightBlock.LightBuild":"",
"power.LongPowerNode.LongPowerNodeBuild":"@Override public void created(){} @Override public void placed(){} @Override public void configured(Unit unit,Object value){owner.configureValue(this,value);} @Override public void updateTile(){warmup=arc.math.Mathf.lerpDelta(warmup,owner.hasPowerLink(this)?1:0,.05f);}",
"logic.MessageBlock.MessageBuild":"",
"logic.MemoryBlock.MemoryBuild":"""
private boolean referencesPending;
@Override public void updateTile(){if(referencesPending){LayerLogicBehavior.resolveMemory(owner,this);referencesPending=false;}}
@Override public byte version(){return 100;}
@Override public void write(arc.util.io.Writes out){LayerLogicBehavior.writeMemory(this,out);}
@Override public void read(arc.util.io.Reads in,byte revision){if(revision!=100)throw new IllegalArgumentException("Upper memory version");LayerLogicBehavior.readMemory(this,in);referencesPending=true;}
""",
"logic.LogicBlock.LogicBuild":"""
@Override public boolean validLink(Building target){return LayerLogicBehavior.valid(this,target);}
@Override public void updateTile(){LayerLogicBehavior.update(owner,this);}
@Override public void updateCode(String code,boolean keep,arc.func.Cons<mindustry.logic.LAssembler> inject){LayerLogicBehavior.assemble(owner,this,code,keep,inject);}
@Override public void readCompressed(byte[] data,boolean relative){LayerLogicBehavior.compressed(owner,this,data,relative);}
@Override public void updateLinks(){LayerLogicBehavior.assemble(owner,this,code,true,null);}
@Override public byte version(){return 100;}
@Override public void write(arc.util.io.Writes out){LayerLogicBehavior.write(this,out);}
@Override public void read(arc.util.io.Reads in,byte revision){if(revision!=100)throw new IllegalArgumentException("Upper logic state version");LayerLogicBehavior.read(owner,this,in);}
""",
"logic.SwitchBlock.SwitchBuild":"",
"logic.LogicDisplay.LogicDisplayBuild":"",
"logic.TileableLogicDisplay.TileableLogicDisplayBuild":"",
"logic.CanvasBlock.CanvasBuild":"@Override public void onProximityUpdate(){blending=0;for(int i=0;i<4;i++){Tile other=tile.nearby(Geometry.d4x(i)*block.size,Geometry.d4y(i)*block.size);if(other!=null&&other.block()==block&&other.team()==team)blending|=1<<i;}}",
"defense.Door.DoorBuild":"@Override public void configured(Unit unit,Object value){owner.configureValue(this,value);} @Override public void tapped(){configure(!open);} @Override public void onProximityUpdate(){updateChained();}",
"defense.Thruster.ThrusterBuild":"",
"defense.ShieldWall.ShieldWallBuild":"@Override public void damage(float amount){float before=shield;owner.damage(this,amount);if(shield<before)hit=1;}",
"defense.AutoDoor.AutoDoorBuild":"@Override public void updateTile(){LayerAreaBehavior.autoDoor(owner,this);} @Override public void setOpen(boolean value){open=value;}",
"defense.BaseShield.BaseShieldBuild":"@Override public void updateTile(){owner.defenseUpdate(this);}",
"defense.MendProjector.MendBuild":"@Override public void updateTile(){LayerAreaBehavior.mend(owner,this);}",
"defense.OverdriveProjector.OverdriveBuild":"@Override public void updateTile(){LayerAreaBehavior.overdrive(owner,this);}",
"defense.RegenProjector.RegenProjectorBuild":"@Override public void updateTile(){LayerAreaBehavior.regen(owner,this);} @Override public void updateTargets(){LayerAreaBehavior.regenTargets(owner,this);}",
"defense.ShockwaveTower.ShockwaveTowerBuild":"@Override public void updateTile(){owner.defenseUpdate(this);}",
"defense.ShockMine.ShockMineBuild":"@Override public void updateTile(){owner.defenseUpdate(this);} @Override public void unitOn(Unit unit){} @Override public void triggered(){}",
"defense.ForceProjector.ForceBuild":"@Override public void deflectBullets(){}",
"defense.Radar.RadarBuild":"@Override public void updateTile(){LayerRadarVisibility.update(this);}",
"defense.BuildTurret.BuildTurretBuild":"@Override public boolean canControl(){return false;} @Override public void updateTile(){owner.rebuild(this);} @Override public boolean shouldConsume(){return owner.hasRebuildWork(this);}",
"defense.turrets.PointDefenseTurret.PointDefenseBuild":"@Override public void updateTile(){owner.defenseUpdate(this);}",
"defense.turrets.TractorBeamTurret.TractorBeamBuild":"@Override public void updateTile(){owner.defenseUpdate(this);}",
"units.RepairTurret.RepairPointBuild":"@Override public void updateTile(){LayerAreaBehavior.repair(owner,this);}",
"units.RepairTower.RepairTowerBuild":"@Override public void updateTile(){LayerAreaBehavior.tower(owner,this);}",
}
turret='''
@Override public boolean canControl(){return false;}
@Override public void updateTile(){if(owner.prepareTurret(this))super.updateTile();}
@Override protected Posc findEnemy(float range){return owner.turretEnemy(this,range);}
@Override protected void findTarget(){target=findEnemy(range());if(target==null&&trackingRange()!=range())target=findEnemy(trackingRange());}
@Override protected boolean validateTarget(){return owner.turretValid(this,target);}
@Override public void targetPosition(Posc target){if(owner.liveTurret(this)&&!owner.turretAim(this,target))super.targetPosition(target);}
@Override protected void bullet(mindustry.entities.bullet.BulletType type,float xOffset,float yOffset,float angleOffset,mindustry.entities.Mover mover){if(!owner.liveTurret(this)){queuedBullets=Math.max(0,queuedBullets-1);return;}super.bullet(type,xOffset,yOffset,angleOffset,mover);}
'''
for name in ('defense.turrets.LaserTurret.LaserTurretBuild','defense.turrets.ContinuousTurret.ContinuousTurretBuild','defense.turrets.ContinuousLiquidTurret.ContinuousLiquidTurretBuild'):
    families[name]=turret
bridge='''
@Override public void updateTile(){LayerNativeBehavior.updateBridge(owner,this);}
@Override public void checkIncoming(){LayerNativeBehavior.checkIncoming(owner,this);}
@Override public boolean acceptItem(Building source,Item item){return LayerNativeBehavior.acceptBridgeItem(owner,this,source,item);}
@Override public boolean acceptLiquid(Building source,Liquid liquid){return LayerNativeBehavior.acceptBridgeLiquid(owner,this,source,liquid);}
@Override protected boolean checkDump(Building to){return LayerNativeBehavior.bridgeDump(owner,this,to);}
@Override public void placed(){}
'''
for name in ('distribution.ItemBridge.ItemBridgeBuild','distribution.BufferedItemBridge.BufferedItemBridgeBuild','liquid.LiquidBridge.LiquidBridgeBuild'):
    families[name]=bridge
guard='''
@Override public Building nearby(int dx,int dy){return owner.nearby(this,dx,dy);}
@Override public Building nearby(int rotation){return nearby(Geometry.d4x(rotation),Geometry.d4y(rotation));}
@Override public void eachEdge(arc.func.Cons<Tile> action){for(var edge:block.getEdges()){Tile other=tile.nearby(edge.x,edge.y);if(other!=null)action.get(other);}}
@Override public void updateProximity(){owner.refresh(this);}
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
@Override public void damage(float amount){owner.damage(this,amount);}
@Override public void kill(){owner.damage(this,Float.MAX_VALUE);}
@Override public void killed(){kill();}
@Override public void onDestroyed(){}
@Override public void afterDestroyed(){}
@Override public void onDeconstructed(Unit builder){}
@Override public void configure(Object value){owner.requestConfiguration(this,value);}
@Override public void configureAny(Object value){configure(value);}
@Override public void changeTeam(Team team,boolean updatePower){throw new UnsupportedOperationException("Use layer-authoritative team change");}
@Override public void changeTeam(Team team){changeTeam(team,false);}
@Override public int moduleBitmask(){return super.moduleBitmask()&~(1<<5);}
'''
head='''package fpv.construction;
// Generated by tools/generate-layer-families.py; edit the generator instead.
import arc.math.geom.Geometry;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.type.*;
import mindustry.world.*;

/** Explicit native families retain original behavior behind identical layer-local lifecycle guards. */
final class LayerNativeFamilies {
    static boolean supports(Class<?> type){return
'''
full=lambda n:'mindustry.world.blocks.'+n
text=head+' ||\n'.join('        type=='+full(n)+'.class' for n in families)+';\n    }\n'
text+='    static Building create(LayerBuildingAdapter owner,Block block,Class<?> type){\n'
text+='        if(type==Building.class&&block instanceof mindustry.world.blocks.sandbox.PowerVoid)return new Building(){\n'
text+='\n'.join('            '+line for line in guard.strip().splitlines())+'\n        };\n'
for n,extra in families.items():
    outer,inner=n.rsplit('.',1)
    text+='        if(type=='+full(n)+'.class)return (('+full(outer)+')block).new '+inner+'(){\n'
    family_guard=guard.replace('@Override public void damage(float amount){owner.damage(this,amount);}','') if n=='defense.ShieldWall.ShieldWallBuild' else guard
    text+='\n'.join('            '+line for line in (family_guard+'\n'+extra).strip().splitlines())+'\n        };\n'
text+='        throw new IllegalArgumentException("Unsupported upper native family: "+type.getName());\n    }\n}\n'
(ROOT/'src/main/java/fpv/construction/LayerNativeFamilies.java').write_text('\n'.join(line.rstrip() for line in text.splitlines())+'\n',encoding='utf-8')
print(f'Generated {len(families)} explicit native layer adapters')
