package fpv.construction;

import fpv.combat.CombatGeometry;
import fpv.combat.ProjectedDefense;
import fpv.physics.SweptBodies;
import fpv.world.HeightRaycast;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Unit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import mindustry.gen.Building;
import mindustry.entities.bullet.BulletType;

/** Real bounded volumes: a roof never turns the air beneath it into a solid heightfield. */
public final class LayerPhysicalQueries implements ProjectedDefense {
    private final Supplier<LayerWorld> source;
    private final BiConsumer<Long,Double> damage;
    private final LongFunction<Building> buildings;
    public LayerPhysicalQueries(Supplier<LayerWorld> source,BiConsumer<Long,Double> damage){this(source,damage,id->null);}
    public LayerPhysicalQueries(Supplier<LayerWorld> source,BiConsumer<Long,Double> damage,LongFunction<Building> buildings){this.source=source;this.damage=damage;this.buildings=buildings;}
    public LayerWorld grid(){return source.get();}
    public Building building(long id){return buildings.apply(id);}
    public record Hit(LayerComponent component,double fraction,double nx,double ny,double nz){}
    private boolean openDoor(LayerComponent component){var b=buildings.apply(component.id());return b instanceof mindustry.world.blocks.defense.Door.DoorBuild door&&door.open
        ||b instanceof mindustry.world.blocks.defense.AutoDoor.AutoDoorBuild autoDoor&&autoDoor.open
        ||b!=null&&b.block instanceof mindustry.world.blocks.defense.TargetDummy;}
    public boolean solid(LayerComponent component){return component!=null&&component.health()>0&&!openDoor(component);}
    public Hit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        return sweep(ax,ay,az,bx,by,bz,radius,halfHeight,null);
    }
    public Hit sweep(double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight,Building emitter){
        var grid=source.get();if(grid==null||grid.components().isEmpty())return null;
        var area=new LayerCoordinates.Bounds(Math.min(ax,bx)-radius,Math.min(ay,by)-radius,Math.min(az,bz)-halfHeight,
            Math.max(ax,bx)+radius,Math.max(ay,by)+radius,Math.max(az,bz)+halfHeight);
        Hit best=null;
        for(var component:grid.query(area)){
            if(openDoor(component)||emitter!=null&&buildings.apply(component.id())==emitter)continue;
            var b=grid.coordinates().bounds(component);
            var h=SweptBodies.box(ax,ay,az,bx-ax,by-ay,bz-az,b.minX()-radius,b.minY()-radius,b.minZ()-halfHeight,
                b.maxX()+radius,b.maxY()+radius,b.maxZ()+halfHeight);
            if(h!=null&&(best==null||h.time()<best.fraction()))best=new Hit(component,h.time(),h.nx(),h.ny(),h.nz());
        }
        return best;
    }
    public SweptBodies.Hit sweepUnit(Unit unit,double ax,double ay,double az,double bx,double by,double bz,double radius,double halfHeight){
        Hit hit=sweep(ax,ay,az,bx,by,bz,radius,halfHeight);
        return hit==null?null:new SweptBodies.Hit(hit.fraction(),hit.nx(),hit.ny(),hit.nz());
    }
    public HeightRaycast.Hit ray(double ax,double ay,double az,double bx,double by,double bz,double radius){
        Hit h=sweep(ax,ay,az,bx,by,bz,radius,radius);
        return h==null?HeightRaycast.Hit.NONE:new HeightRaycast.Hit(true,h.fraction(),ax+(bx-ax)*h.fraction(),ay+(by-ay)*h.fraction(),az+(bz-az)*h.fraction(),
            h.nx(),h.ny(),h.nz(),h.component().x(),h.component().y());
    }
    /** Feet may use a physical top they can reach from the current stance; overhead roofs are excluded. */
    public double supportBelow(double x,double y,double maximumZ,double fallback){
        var grid=source.get();if(grid==null)return fallback;
        double top=fallback;
        for(var c:grid.query(new LayerCoordinates.Bounds(x-.001,y-.001,-1,x+.001,y+.001,Math.max(0,maximumZ)))){
            if(openDoor(c))continue;
            var b=grid.coordinates().bounds(c);if(b.maxZ()<=maximumZ+1e-6)top=Math.max(top,b.maxZ());
        }
        return top;
    }
    public double ceilingAbove(double x,double y,double minimumZ,double radius){
        var grid=source.get();if(grid==null)return Double.POSITIVE_INFINITY;
        double ceiling=Double.POSITIVE_INFINITY;
        if(minimumZ>grid.coordinates().plane(grid.coordinates().maxLayers())+128)return ceiling;
        for(var c:grid.query(new LayerCoordinates.Bounds(x-radius,y-radius,minimumZ,x+radius,y+radius,grid.coordinates().plane(grid.coordinates().maxLayers())+128))){
            if(openDoor(c))continue;
            var b=grid.coordinates().bounds(c);if(b.minZ()>=minimumZ-1e-6)ceiling=Math.min(ceiling,b.minZ());
        }
        return ceiling;
    }
    public double obstructionTop(double x,double y,double bodyTop,double fallback){
        var grid=source.get();if(grid==null||bodyTop<0)return fallback;
        double top=fallback;
        for(var c:grid.query(new LayerCoordinates.Bounds(x-.001,y-.001,0,x+.001,y+.001,bodyTop))){
            if(openDoor(c))continue;
            var b=grid.coordinates().bounds(c);if(b.minZ()<bodyTop-1e-5)top=Math.max(top,b.maxZ());
        }
        return top;
    }
    public LayerCoordinates.Bounds buildingBounds(mindustry.gen.Building building){
        if(building==null||!(building.tile instanceof LayerTile tile))return null;
        var grid=source.get();if(grid==null)return null;
        var c=grid.at(tile.x,tile.y,tile.layer,LayerComponent.Kind.BLOCK);
        return c==null?null:grid.coordinates().bounds(c);
    }
    /** Friendly floors stop shots too, but ordinary friendly fire never damages them. */
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius){
        return traceEmitter(team,ax,ay,az,bx,by,bz,radius,null);
    }
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,Bullet bullet){return traceEmitter(team,ax,ay,az,bx,by,bz,radius,bullet);}
    @Override public Contact traceLaser(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,boolean absorbLaserWalls,Bullet bullet){return traceEmitter(team,ax,ay,az,bx,by,bz,radius,bullet);}
    private Contact traceEmitter(Team team,double ax,double ay,double az,double bx,double by,double bz,double radius,Bullet bullet){
        var grid=source.get();if(grid==null||grid.components().isEmpty())return null;
        double best=Double.POSITIVE_INFINITY;LayerComponent target=null;
        for(var c:grid.query(new LayerCoordinates.Bounds(Math.min(ax,bx)-radius,Math.min(ay,by)-radius,Math.min(az,bz)-radius,
            Math.max(ax,bx)+radius,Math.max(ay,by)+radius,Math.max(az,bz)+radius))){
            if(openDoor(c)||bullet!=null&&bullet.owner instanceof Building owner&&buildings.apply(c.id())==owner)continue;
            var b=grid.coordinates().bounds(c);
            double t=CombatGeometry.box(ax,ay,az,bx,by,bz,b.minX()-radius,b.minY()-radius,b.minZ()-radius,b.maxX()+radius,b.maxY()+radius,b.maxZ()+radius);
            if(t<best){best=t;target=c;}
        }
        return target==null||best>1?null:new Contact(best,ax+(bx-ax)*best,ay+(by-ay)*best,az+(bz-az)*best,target.id());
    }
    @Override public boolean hit(Contact contact,Bullet bullet,float amount){
        if(contact==null||!(contact.token() instanceof Long id)||source.get()==null)return false;
        var c=source.get().get(id);if(c==null)return false;
        if(!Vars.net.client()&&bullet!=null&&bullet.type.collidesGround){
            if(bullet.team.id==c.team()){if(bullet.type.collidesTeam&&bullet.type.heals())heal(c,bullet.type);}
            else if(Float.isFinite(amount)&&amount>0){
                float proposed=amount*bullet.buildingDamageMultiplier;
                var block=c.kind()==LayerComponent.Kind.PLATFORM?null:Vars.content.<mindustry.world.Block>getByName(mindustry.ctype.ContentType.block,c.blockName());
                if(block!=null&&!bullet.type.pierceArmor)proposed=mindustry.entities.Damage.applyArmor(proposed,block.armor*bullet.type.armorMultiplier*bullet.type.blockArmorMultiplier);
                applyDamage(id,proposed);
            }
        }
        return true;
    }
    private void applyDamage(long id,double amount){
        if(!Double.isFinite(amount)||amount<=0)return;
        Building b=buildings.apply(id);
        if(b!=null)b.damage((float)Math.min(Float.MAX_VALUE,amount));else{
            var c=source.get().get(id);if(c==null)return;
            double scale=c.kind()==LayerComponent.Kind.PLATFORM?1:Vars.state.rules.blockHealth(Team.get(c.team()));
            damage.accept(id,Math.abs(scale)<.000001?c.health()+1:amount/Math.max(.000001,scale));
        }
    }
    private void heal(LayerComponent c,BulletType type){
        Building b=buildings.apply(c.id());double amount=type.healPercent*.01*c.maxHealth()+type.healAmount;
        if(!Double.isFinite(amount)||amount<=0)return;
        if(b!=null)b.heal((float)amount);else source.get().replace(c.withHealth(Math.min(c.maxHealth(),c.health()+amount)));
    }
    public void healArea(Team team,double x,double y,double z,double radius,BulletType type){
        healArea(team,x,y,z,radius,type,null);
    }
    /** Optional presentation notification after the unchanged authoritative healing operation. */
    public void healArea(Team team,double x,double y,double z,double radius,BulletType type,java.util.function.Consumer<Building> healed){
        if(Vars.net.client()||!type.heals()||radius<=0)return;var grid=source.get();if(grid==null)return;
        for(var c:grid.query(new LayerCoordinates.Bounds(x-radius,y-radius,z-radius,x+radius,y+radius,z+radius))){
            if(c.team()!=team.id)continue;var b=grid.coordinates().bounds(c);
            if(fpv.combat.ProjectileDynamics.boxDistance(x,y,z,b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ())<=radius){
                Building target=buildings.apply(c.id());float before=target==null?0:target.health;
                heal(c,type);if(healed!=null&&target!=null&&target.health>before)healed.accept(target);
            }
        }
    }
    @Override public boolean physicalSurface(Contact contact){return contact!=null&&contact.token() instanceof Long;}
    @FunctionalInterface public interface AreaLimit{double accept(LayerComponent component,double proposed);}
    public void area(Team team,double x,double y,double z,double radius,double power,double minimumFraction){
        area(team,x,y,z,radius,power,minimumFraction,null);
    }
    public void area(Team team,double x,double y,double z,double radius,double power,double minimumFraction,AreaLimit limit){
        if(Vars.net.client()||radius<=0||power<=0||!Double.isFinite(radius+power))return;
        var grid=source.get();if(grid==null)return;
        for(var c:grid.query(new LayerCoordinates.Bounds(x-radius,y-radius,z-radius,x+radius,y+radius,z+radius))){
            if(c.team()==team.id)continue;var b=grid.coordinates().bounds(c);
            double distance=fpv.combat.ProjectileDynamics.boxDistance(x,y,z,b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
            if(distance<radius){double raw=power*Math.max(minimumFraction,1-(1-minimumFraction)*distance/radius),accepted=limit==null?raw:limit.accept(c,raw);
                if(Double.isFinite(accepted)&&accepted>0)applyDamage(c.id(),Math.min(raw,accepted));
            }
        }
    }
}
