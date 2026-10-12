package fpv.construction;

import fpv.combat.ProjectedDefense;
import fpv.defense.ForceGeometry;
import fpv.world.World3DAdapter;
import java.util.*;
import java.util.function.*;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.blocks.defense.BaseShield;
import mindustry.world.blocks.defense.BaseShield.BaseShieldBuild;

/** Layer-local campaign/sandbox base shields; no native XY bullet consumer is called. */
public final class LayerBaseShieldSystem implements ProjectedDefense {
    public record Volume(Building building,double x,double y,double bottom,double top,double radius,int sides,double rotation,float flash){}
    private final World3DAdapter world;
    private final Supplier<Collection<LayerBuildingAdapter.Entry>> source;
    private final LongSupplier revision;
    private long seen=Long.MIN_VALUE;
    private Collection<LayerBuildingAdapter.Entry> identity;
    private final ArrayList<BaseShieldBuild> shields=new ArrayList<>();
    public LayerBaseShieldSystem(World3DAdapter world,Supplier<Collection<LayerBuildingAdapter.Entry>> source,LongSupplier revision){this.world=world;this.source=source;this.revision=revision;}
    private void sync(){var entries=source.get();long next=revision.getAsLong();if(identity==entries&&seen==next)return;identity=entries;seen=next;shields.clear();for(var e:entries)if(e.building() instanceof BaseShieldBuild shield)shields.add(shield);}
    private Volume volume(BaseShieldBuild b){if(b.dead||b.broken||b.tile==null||b.tile.build!=b)return null;double r=b.radius()/Vars.tilesize;if(!Double.isFinite(r)||r<=.001)return null;double base=world.buildingBase(b);return new Volume(b,b.x/(double)Vars.tilesize,b.y/(double)Vars.tilesize,base,base+r*2,r,Math.min(64,Math.max(3,((BaseShield)b.block).sides)),0,Math.max(0,Math.min(1,b.hit)));}
    public List<Volume> volumes(){sync();var result=new ArrayList<Volume>();for(var b:shields){var v=volume(b);if(v!=null)result.add(v);}return result;}
    public void update(BaseShieldBuild b){
        if(Vars.net.client())return;var type=(BaseShield)b.block;b.smoothRadius=arc.math.Mathf.lerpDelta(b.smoothRadius,type.radius*b.efficiency,.05f);b.hit=Math.max(0,b.hit-arc.util.Time.delta*.1f);var v=volume(b);if(v==null)return;
        for(Unit unit:Groups.unit){if(unit.dead||unit.team==b.team)continue;var p=world.unitState(unit);if(p==null||p.z()-p.halfHeight()>=v.top||p.z()+p.halfHeight()<=v.bottom)continue;
            double dx=p.x()-v.x,dy=p.y()-v.y,d=Math.hypot(dx,dy),overlap=v.radius+p.radius()-d;if(overlap<=0)continue;
            if(overlap>p.radius()*3){unit.kill();continue;}
            if(d<1e-7){dx=1;dy=0;d=1;}world.addVelocity(unit,dx/d*Math.min(30,overlap*60),dy/d*Math.min(30,overlap*60),0);
        }
    }
    @Override public Contact trace(Team team,double ax,double ay,double az,double bx,double by,double bz,double padding){
        sync();double nearest=Double.POSITIVE_INFINITY;BaseShieldBuild selected=null;
        for(var b:shields){if(b.team==team)continue;var v=volume(b);if(v==null)continue;double t=ForceGeometry.sweep(ax,ay,az,bx,by,bz,v.x,v.y,v.bottom,v.top,v.radius,v.sides,v.rotation,padding);if(t>=0&&t<=1&&t<nearest){nearest=t;selected=b;}}
        return selected==null?null:new Contact(nearest,ax+(bx-ax)*nearest,ay+(by-ay)*nearest,az+(bz-az)*nearest,selected);
    }
    @Override public boolean hit(Contact contact,Bullet bullet,float damage){if(Vars.net.client()||contact==null||!(contact.token() instanceof BaseShieldBuild b)||volume(b)==null||bullet!=null&&bullet.team==b.team)return false;b.hit=1;return true;}
}
