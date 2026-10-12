package fpv.render;

import arc.graphics.Color;
import arc.graphics.g3d.Camera3D;
import arc.math.Angles;
import arc.struct.IntMap;
import arc.util.Time;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.entities.Puddles;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Puddle;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Liquid;
import mindustry.type.weapons.RepairBeamWeapon;
import mindustry.world.Tile;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;

import java.util.ArrayList;
import java.util.function.DoubleBinaryOperator;

/** Native support state to read-only 3D effects. All public positions use gameplay XYZ. */
public final class SupportEffectsRenderer {
    /** Same rendering boundary as combat; disk is a flat surface pool, never a simulated puddle. */
    public interface Sink extends CombatVisualRenderer.Sink {
        void disk(float x,float y,float z,float radiusX,float radiusZ,float r,float g,float b);
    }
    public record Stats(int leakingConduits,int puddles,int healingBeams,int repairPulses,int primitives) {}
    public record HealingBeam(double ax,double ay,double az,double bx,double by,double bz,int targetId) {}
    private static final int MAX_PRIMITIVES=24000,MAX_PUDDLES=4096;
    private final ArrayList<Building> candidates=new ArrayList<>();
    private final IntMap<Puddle> puddles=new IntMap<>();
    private final IntMap<Building> leakSubmitted=new IntMap<>(),healSubmitted=new IntMap<>(),towerSubmitted=new IntMap<>();
    private final IntMap<Unit> unitHealSubmitted=new IntMap<>();
    private final IntMap<HealingBeam> beamEnds=new IntMap<>();
    private Object worldTiles;
    private int tileChanges=-1,leaks,pools,heals,pulses,primitives;
    private Camera3D camera;
    private UnitAltitudeSystem altitudes;
    private DoubleBinaryOperator surface;
    private Sink sink;
    private SupportEffectsState authoritativeState;
    private fpv.construction.LayerRuntime construction;
    public void setConstruction(fpv.construction.LayerRuntime value){construction=value;}

    public void setState(SupportEffectsState state){authoritativeState=state;}

    public Stats stats(){return new Stats(leaks,pools,heals,pulses,primitives);}
    public boolean wasLeakSubmitted(Building source){return source!=null&&leakSubmitted.get(source.id)==source;}
    public boolean wasHealingSubmitted(Building source){return source!=null&&healSubmitted.get(source.id)==source;}
    public boolean wasHealingSubmitted(Unit source){return source!=null&&unitHealSubmitted.get(source.id)==source;}
    public boolean wasTowerSubmitted(Building source){return source!=null&&towerSubmitted.get(source.id)==source;}
    public HealingBeam healingBeam(Building source){return source==null?null:beamEnds.get(source.id);}
    public HealingBeam healingBeam(Unit source){return source==null?null:beamEnds.get(source.id);}
    public void clear(){candidates.clear();puddles.clear();worldTiles=null;tileChanges=-1;clearFrame();}
    public void clearFrame(){leakSubmitted.clear();healSubmitted.clear();unitHealSubmitted.clear();towerSubmitted.clear();beamEnds.clear();leaks=pools=heals=pulses=primitives=0;}

    public void render(Camera3D camera,Iterable<Building> placed,UnitAltitudeSystem altitudes,
                       DoubleBinaryOperator rendererSurface,Sink sink){
        clearFrame();
        if(Vars.world==null||Vars.state==null||Vars.player==null)return;
        if(worldTiles!=Vars.world.tiles){clear();worldTiles=Vars.world.tiles;}
        if(tileChanges!=Vars.world.tileChanges){
            candidates.clear();
            for(Building b:placed)if(b instanceof Conduit.ConduitBuild||b instanceof RepairTurret.RepairPointBuild||b instanceof RepairTower.RepairTowerBuild)candidates.add(b);
            tileChanges=Vars.world.tileChanges;
        }
        this.camera=camera;this.altitudes=altitudes;this.surface=rendererSurface;this.sink=sink;
        for(Building b:candidates){
            if(primitives>=MAX_PRIMITIVES)break;
            if(!attached(b)||b.inFogTo(Vars.player.team()))continue;
            if(b instanceof Conduit.ConduitBuild pipe)conduit(pipe);
            else if(!Vars.net.client()){
                if(b instanceof RepairTurret.RepairPointBuild repair)repair(repair);
                else if(b instanceof RepairTower.RepairTowerBuild tower)tower(tower);
            }
        }
        if(construction!=null&&!Vars.net.client())for(var entry:construction.nativeBuildings().entries()){
            Building b=entry.building();if(primitives>=MAX_PRIMITIVES)break;
            if(!attached(b)||b.inFogTo(Vars.player.team()))continue;
            if(b instanceof RepairTurret.RepairPointBuild repair)repair(repair);
            else if(b instanceof RepairTower.RepairTowerBuild tower)tower(tower);
        }
        var entries=puddles.entries();
        while(entries.hasNext()){
            Puddle puddle=entries.next().value;
            if(!puddle.isAdded()||puddle.tile==null||puddle.liquid==null||Puddles.get(puddle.tile)!=puddle||puddle.amount<=0){entries.remove();continue;}
            if(primitives<MAX_PRIMITIVES)pool(puddle);
        }
        if(Vars.net.client()){
            if(authoritativeState!=null)for(var effect:authoritativeState.remote())remote(effect);
            return;
        }
        for(Unit unit:Groups.unit){
            if(primitives>=MAX_PRIMITIVES)break;
            if(unit.dead||!unit.isAdded()||unit.type==null||unit.inFogTo(Vars.player.team())||!unit.canShoot())continue;
            for(var mount:unit.mounts)if(mount.weapon instanceof RepairBeamWeapon weapon&&mount instanceof RepairBeamWeapon.HealBeamMount heal)repair(unit,weapon,heal);
        }
    }

    /** Matches native Building.moveLiquidForward; presentation additionally requires native deposit evidence. */
    public static boolean openLiquidEnd(Conduit.ConduitBuild pipe){
        if(!attached(pipe)||!(pipe.block instanceof Conduit type)||!type.leaks)return false;
        Tile next=pipe.tile.nearby(pipe.rotation);
        return next!=null&&next.build==null&&!next.block().solid&&!next.block().hasLiquids;
    }
    private void conduit(Conduit.ConduitBuild pipe){
        if(!(pipe.block instanceof Conduit type)||!type.leaks)return;
        Tile next=pipe.tile.nearby(pipe.rotation);
        if(next==null)return;
        // Deposits can spread after the pipe empties; only native puddle identities are retained.
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            Puddle p=Puddles.get(Vars.world.tile(next.x+dx,next.y+dy));
            if(p!=null&&p.liquid!=null&&puddles.size<MAX_PUDDLES)puddles.put(p.id,p);
        }
        if(!openLiquidEnd(pipe)||pipe.liquids==null||pipe.liquids.currentAmount()<=.0001f)return;
        Liquid liquid=pipe.liquids.current();Puddle puddle=Puddles.get(next);
        boolean receivingPool=puddle!=null&&puddle.liquid==liquid&&puddle.amount>0;
        boolean receivingLiquidFloor=next.floor().isLiquid&&next.floor().liquidDrop!=null;
        if(liquid==null||liquid.gas||liquid.willBoil()||Vars.state.rules.hasEnv(mindustry.world.meta.Env.space)||(!receivingPool&&!receivingLiquidFloor))return;
        double dx=Math.cos(Math.toRadians(pipe.rotation*90)),dy=Math.sin(Math.toRadians(pipe.rotation*90));
        double x=pipe.x/(double)Vars.tilesize,y=pipe.y/(double)Vars.tilesize;
        double top=Math.max(.16,FpvWorldRenderer.heightFor(pipe.block)*.8);
        double ex=x+dx*.92,ey=y+dy*.92,ground=height(ex,ey)+.045;
        if(!bounds(x+dx*.7,y+dy*.7,(top+ground)*.5,.8,.8,Math.max(.4,top)))return;
        double fullness=Math.min(1,pipe.liquids.currentAmount()/Math.max(.001,pipe.block.liquidCapacity));
        float width=(float)(.025+.08*Math.sqrt(fullness));
        Color color=liquid.color;
        int start=primitives;
        for(int i=0;i<6;i++){
            double a=i/6.0,b=(i+1)/6.0;
            line(x+dx*(.45+.47*a),y+dy*(.45+.47*a),top+(ground-top)*a*a,
                x+dx*(.45+.47*b),y+dy*(.45+.47*b),top+(ground-top)*b*b,width,color);
        }
        for(int i=0;i<3;i++){
            double phase=fract(Time.time/18.0+i/3.0+pipe.id*.137);
            marker(x+dx*(.46+.46*phase),y+dy*(.46+.46*phase),top+(ground-top)*phase*phase,width*1.25f,color);
        }
        ring(ex,ey,ground+.008,.06+fract(Time.time/32.0+pipe.id*.071)*.29,.017f,color,12);
        if(primitives>start){leakSubmitted.put(pipe.id,pipe);leaks++;}
    }
    private void pool(Puddle p){
        double x=p.x/(double)Vars.tilesize,y=p.y/(double)Vars.tilesize;
        if(!visible(x,y)||p.liquid.gas||p.liquid.willBoil())return;
        double radius=.06+Math.min(1,p.amount/(Puddles.maxLiquid/1.5))*.74;
        double z=height(x,y)+.021;
        if(!bounds(x,y,z,radius,radius,.1))return;
        Color color=p.liquid.color;
        // Several overlapping ellipses retain the native irregular pool appearance with no texture copies.
        disk(x,y,z,radius,radius*.81,color,.78f);
        double angle=p.id*2.399963229728653;
        disk(x+Math.cos(angle)*radius*.35,y+Math.sin(angle)*radius*.35,z+.003,radius*.68,radius*.62,color,.85f);
        double phase=fract(Time.time/80.0+p.id*.071);
        ring(x,y,z+.009,radius*(.18+.75*phase),(float)(.014*(1-phase)),color,16);
        pools++;
    }
    private void repair(RepairTurret.RepairPointBuild build){
        if(!(build.block instanceof RepairTurret)||build.target==null||build.target.dead||
            !build.target.isAdded()||build.target.team!=build.team||build.strength<=.01||build.efficiency<=0||!build.enabled||
            build.target.inFogTo(Vars.player.team()))return;
        repair(build,build.target,build.strength,build.rotation);
    }
    private void repair(RepairTurret.RepairPointBuild build,Unit target,float strength,float rotation){
        if(!(build.block instanceof RepairTurret type)||target.dead||!target.isAdded()||target.team!=build.team||target.inFogTo(Vars.player.team()))return;
        Unit3DState pose=altitudes==null?null:altitudes.unitState(target);if(pose==null)return;
        double ax=(build.x+Angles.trnsx(rotation,type.length))/(double)Vars.tilesize;
        double ay=(build.y+Angles.trnsy(rotation,type.length))/(double)Vars.tilesize;
        double az=buildingTop(build)+.065;
        if(healing(ax,ay,az,pose.x(),pose.y(),pose.z(),type.beamWidth*strength/Vars.tilesize,type.laserColor,build.id)){
            healSubmitted.put(build.id,build);beamEnds.put(build.id,new HealingBeam(ax,ay,az,pose.x(),pose.y(),pose.z(),target.id));
        }
    }
    private void repair(Unit unit,RepairBeamWeapon weapon,RepairBeamWeapon.HealBeamMount heal){
        if(heal.strength<=.01||!heal.shoot||heal.target==null||heal.target.team()!=unit.team)return;
        repair(unit,weapon,heal.target,heal.strength,heal.rotation);
    }
    private void repair(Unit unit,RepairBeamWeapon weapon,Teamc targetObject,float strength,float rotation){
        Unit3DState pose=altitudes==null?null:altitudes.unitState(unit);if(pose==null)return;
        double bx,by,bz;int targetId;
        if(targetObject instanceof Unit target){
            if(target.dead||!target.isAdded()||target.inFogTo(Vars.player.team()))return;
            Unit3DState endpoint=altitudes.unitState(target);if(endpoint==null)return;
            bx=endpoint.x();by=endpoint.y();bz=endpoint.z();targetId=target.id;
        }else if(targetObject instanceof Building target){
            if(!attached(target)||target.inFogTo(Vars.player.team()))return;
            bx=target.x/(double)Vars.tilesize;by=target.y/(double)Vars.tilesize;
            bz=buildingTop(target)+.045;targetId=target.id;
        }else return;
        double yaw=Math.toRadians(pose.yaw()),pitch=Math.toRadians(pose.pitch()),roll=Math.toRadians(pose.roll());
        double cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch),cr=Math.cos(roll),sr=Math.sin(roll);
        double rx=sy*cr-cy*sp*sr,ry=-cy*cr-sy*sp*sr,rz=cp*sr;
        double fx=cy*cp,fy=sy*cp,fz=sp;
        double mountAngle=Math.toRadians(rotation),mx=(weapon.x+Math.sin(mountAngle)*weapon.shootY)/Vars.tilesize,
            my=(weapon.y+Math.cos(mountAngle)*weapon.shootY)/Vars.tilesize;
        double ax=pose.x()+rx*mx+fx*my,ay=pose.y()+ry*mx+fy*my,az=pose.z()+rz*mx+fz*my;
        if(healing(ax,ay,az,bx,by,bz,weapon.beamWidth*strength/Vars.tilesize,weapon.laserColor,unit.id)){
            unitHealSubmitted.put(unit.id,unit);beamEnds.put(unit.id,new HealingBeam(ax,ay,az,bx,by,bz,targetId));
        }
    }
    private boolean healing(double ax,double ay,double az,double bx,double by,double bz,double width,Color color,int id){
        double distance=Math.sqrt(square(bx-camera.position.x)+square(bz-camera.position.y)+square(-by-camera.position.z));
        float visibleWidth=(float)Math.max(.04,Math.max(width,distance*Math.tan(Math.toRadians(camera.fov)*.5)*1.7/Math.max(1,camera.height)));
        if(!bounds((ax+bx)*.5,(ay+by)*.5,(az+bz)*.5,Math.abs(bx-ax)*.5+visibleWidth,Math.abs(by-ay)*.5+visibleWidth,Math.abs(bz-az)*.5+visibleWidth))return false;
        int before=primitives;line(ax,ay,az,bx,by,bz,visibleWidth,color,true);
        marker(bx,by,bz,visibleWidth*1.1f,color);
        double phase=fract(Time.time/65.0+id*.13);
        ring(bx,by,bz,.08+phase*.5,(float)(visibleWidth*.4*(1-phase)),color,16);
        if(primitives>before){heals++;return true;}return false;
    }
    private void tower(RepairTower.RepairTowerBuild build){
        if(!(build.block instanceof RepairTower)||build.warmup<=.001||build.efficiency<=0||!build.enabled)return;
        tower(build,build.warmup,(float)fract(build.totalProgress));
        for(Unit target:build.targets)towerTarget(build,target,build.warmup,(float)fract(build.totalProgress));
    }
    private void tower(RepairTower.RepairTowerBuild build,float warmup,float phase){
        RepairTower type=(RepairTower)build.block;
        double x=build.x/(double)Vars.tilesize,y=build.y/(double)Vars.tilesize,z=buildingTop(build)+.07;
        double radius=type.range/Vars.tilesize;
        if(!bounds(x,y,z,radius,radius,1))return;
        int before=primitives;
        ring(x,y,z,radius*phase,(float)(.05*warmup*(1-phase)),type.circleColor,40);
        marker(x,y,z,.12f*warmup,type.circleColor);
        if(primitives>before){towerSubmitted.put(build.id,build);pulses++;}
    }
    private void towerTarget(RepairTower.RepairTowerBuild build,Unit target,float warmup,float phase){
        if(target.dead||!target.isAdded()||target.team!=build.team||target.inFogTo(Vars.player.team()))return;
        Unit3DState pose=altitudes==null?null:altitudes.unitState(target);if(pose==null)return;
        ring(pose.x(),pose.y(),pose.z(),pose.radius()*(.3+.7*phase),.03f*warmup,((RepairTower)build.block).circleColor,12);
    }
    private void remote(SupportEffectsState.Effect e){
        if(primitives>=MAX_PRIMITIVES)return;
        Teamc target=null;
        if(e.targetKind()==SupportEffectsState.UNIT_TARGET){
            Unit unit=Groups.unit.getByID(e.targetRef());
            if(unit==null||unit.type.id!=e.targetType()||unit.team.id!=e.teamId())return;target=unit;
        }else if(e.targetKind()==SupportEffectsState.BUILDING_TARGET||e.targetKind()==SupportEffectsState.UPPER_BUILDING_TARGET){
            Building build=e.targetKind()==SupportEffectsState.UPPER_BUILDING_TARGET?(construction==null?null:construction.building(e.targetRef())):Vars.world.build(e.targetRef());
            if(!attached(build)||build.block.id!=e.targetType()||build.team.id!=e.teamId())return;target=build;
        }
        if(e.kind()==SupportEffectsState.UNIT){
            Unit unit=Groups.unit.getByID(e.sourceRef());
            if(unit==null||unit.dead||!unit.isAdded()||unit.type.id!=e.sourceType()||unit.team.id!=e.teamId()||unit.inFogTo(Vars.player.team())||
                e.mount()<0||e.mount()>=unit.mounts.length||!(unit.mounts[e.mount()].weapon instanceof RepairBeamWeapon weapon)||target==null)return;
            repair(unit,weapon,target,e.strength(),e.rotation());return;
        }
        boolean upper=e.kind()>=SupportEffectsState.UPPER_TURRET;
        Building build=upper?(construction==null?null:construction.building(e.sourceRef())):Vars.world.build(e.sourceRef());
        if(!attached(build)||build.block.id!=e.sourceType()||build.team.id!=e.teamId()||build.inFogTo(Vars.player.team()))return;
        if((e.kind()==SupportEffectsState.TURRET||e.kind()==SupportEffectsState.UPPER_TURRET)&&build instanceof RepairTurret.RepairPointBuild repair&&target instanceof Unit unit)
            repair(repair,unit,e.strength(),e.rotation());
        else if(build instanceof RepairTower.RepairTowerBuild tower){
            if(e.kind()==SupportEffectsState.TOWER||e.kind()==SupportEffectsState.UPPER_TOWER)tower(tower,e.strength(),e.progress());
            else if((e.kind()==SupportEffectsState.TOWER_TARGET||e.kind()==SupportEffectsState.UPPER_TOWER_TARGET)&&target instanceof Unit unit)towerTarget(tower,unit,e.strength(),e.progress());
        }
    }
    private void ring(double x,double y,double z,double radius,float width,Color color,int pieces){
        if(radius<=.001||width<=.001)return;
        for(int i=0;i<pieces;i++){
            double a=i*Math.PI*2/pieces,b=(i+1)*Math.PI*2/pieces;
            line(x+Math.cos(a)*radius,y+Math.sin(a)*radius,z,x+Math.cos(b)*radius,y+Math.sin(b)*radius,z,width,color);
        }
    }
    /** Clip spans against native fog without exposing hidden source/target information. */
    private void line(double ax,double ay,double az,double bx,double by,double bz,float width,Color color){
        line(ax,ay,az,bx,by,bz,width,color,false);
    }
    private void line(double ax,double ay,double az,double bx,double by,double bz,float width,Color color,boolean laser){
        if(primitives>=MAX_PRIMITIVES||width<=.001)return;
        int pieces=Vars.state.rules.fog?Math.max(1,Math.min(128,(int)Math.ceil(Math.hypot(bx-ax,by-ay)*2))):1;
        boolean opened=false;double start=0;
        for(int i=0;i<=pieces;i++){
            boolean allowed=i<pieces&&visible(ax+(bx-ax)*(i+.5)/pieces,ay+(by-ay)*(i+.5)/pieces);
            if(allowed&&!opened){start=i/(double)pieces;opened=true;}
            if(!allowed&&opened){
                double end=i/(double)pieces;
                if(primitives++<MAX_PRIMITIVES){
                    if(laser)sink.laser((float)(ax+(bx-ax)*start),(float)(az+(bz-az)*start),(float)-(ay+(by-ay)*start),
                        (float)(ax+(bx-ax)*end),(float)(az+(bz-az)*end),(float)-(ay+(by-ay)*end),width,color.r,color.g,color.b);
                    else sink.beam((float)(ax+(bx-ax)*start),(float)(az+(bz-az)*start),(float)-(ay+(by-ay)*start),
                        (float)(ax+(bx-ax)*end),(float)(az+(bz-az)*end),(float)-(ay+(by-ay)*end),width,color.r,color.g,color.b);
                }
                opened=false;
            }
        }
    }
    private void marker(double x,double y,double z,float radius,Color color){
        if(primitives>=MAX_PRIMITIVES||!visible(x,y))return;
        sink.marker((float)x,(float)z,(float)-y,radius,color.r,color.g,color.b);primitives++;
    }
    private void disk(double x,double y,double z,double rx,double ry,Color color,float shade){
        if(primitives>=MAX_PRIMITIVES||!visible(x,y))return;
        sink.disk((float)x,(float)z,(float)-y,(float)rx,(float)ry,color.r*shade,color.g*shade,color.b*shade);primitives++;
    }
    private boolean visible(double x,double y){return !Vars.state.rules.fog||Vars.fogControl==null||Vars.fogControl.isVisible(Vars.player.team(),(float)x*Vars.tilesize,(float)y*Vars.tilesize);}
    private boolean bounds(double x,double y,double z,double hx,double hy,double hz){return camera.frustum.containsBounds((float)x,(float)z,(float)-y,(float)hx,(float)hz,(float)hy);}
    private double height(double x,double y){return surface.applyAsDouble(x,-y);}
    private double buildingTop(Building b){
        if(construction!=null&&b.tile instanceof fpv.construction.LayerTile layer){var c=construction.grid().at(layer.x,layer.y,layer.layer,fpv.construction.LayerComponent.Kind.BLOCK);if(c!=null)return construction.grid().coordinates().bounds(c).maxZ();}
        return FpvWorldRenderer.heightFor(b.block);
    }
    private static boolean attached(Building b){return b!=null&&!b.dead&&b.tile!=null&&b.tile.build==b&&(b.tile instanceof fpv.construction.LayerTile||Vars.world.tile(b.tile.x,b.tile.y)==b.tile);}
    private static double fract(double value){return value-Math.floor(value);}
    private static double square(double value){return value*value;}
}
