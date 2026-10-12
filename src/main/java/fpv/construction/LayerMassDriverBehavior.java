package fpv.construction;

import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.distribution.MassDriver.MassDriverBuild;
import mindustry.world.blocks.distribution.MassDriver.DriverState;

/** Native driver timing/queues with a saved layer-local cargo flight, never a ground-world bolt. */
public final class LayerMassDriverBehavior {
    private LayerMassDriverBehavior(){}
    public static final class Pod {
        public long target;
        public float remaining,duration,x0,y0,z0,x1,y1,z1;
        public int[] items=new int[0];
        public boolean active(){return target>0&&remaining>0;}
        public float progress(){return duration<=0?1:Mathf.clamp(1-remaining/duration);}
        void clear(){target=0;remaining=duration=0;java.util.Arrays.fill(items,0);}
        void write(Writes out){out.l(target);out.f(remaining);out.f(duration);out.f(x0);out.f(y0);out.f(z0);out.f(x1);out.f(y1);out.f(z1);out.s(items.length);for(int value:items)out.i(value);}
        void read(Reads in,int capacity){
            target=in.l();remaining=in.f();duration=in.f();x0=in.f();y0=in.f();z0=in.f();x1=in.f();y1=in.f();z1=in.f();int count=in.us();
            if(count>Vars.content.items().size||count>4096)throw new IllegalArgumentException("Mass driver item count");items=new int[count];long total=0;
            for(int i=0;i<count;i++){items[i]=in.i();if(items[i]<0)throw new IllegalArgumentException("Negative cargo");total+=items[i];}
            if(target<0||total>capacity||!Float.isFinite(remaining)||!Float.isFinite(duration)||remaining<0||duration<remaining||duration>36000||
                !Float.isFinite(x0)||!Float.isFinite(y0)||!Float.isFinite(z0)||!Float.isFinite(x1)||!Float.isFinite(y1)||!Float.isFinite(z1))throw new IllegalArgumentException("Invalid driver cargo");
        }
    }
    public static Pod pod(Building building){return building instanceof LayerMassDriverFamilies.UpperMassDriver driver?driver.pod:null;}
    static MassDriverBuild link(LayerBuildingAdapter owner,MassDriverBuild source){
        if(source.link==-1)return null;var tile=owner.tile(Point2.x(source.link),Point2.y(source.link),((LayerTile)source.tile).layer);
        return tile!=null&&tile.build instanceof MassDriverBuild other&&other!=source&&other.block==source.block&&other.team==source.team&&source.within(other,((MassDriver)source.block).range)?other:null;
    }
    static boolean shooter(LayerBuildingAdapter owner,MassDriverBuild receiver,Building candidate){
        return candidate instanceof MassDriverBuild from&&!from.dead&&from.tile.build==from&&from.efficiency>0&&link(owner,from)==receiver;
    }
    static boolean configure(LayerBuildingAdapter owner,Building building,Object value){
        if(!(building instanceof MassDriverBuild source))return false;
        int target;if(value instanceof Point2 point)target=Point2.pack(source.tile.x+point.x,source.tile.y+point.y);else if(value instanceof Integer position)target=position;else return false;
        int previous=source.link;source.link=target;if(target!=-1&&link(owner,source)==null){source.link=previous;return false;}return true;
    }
    static void update(LayerBuildingAdapter owner,LayerMassDriverFamilies.UpperMassDriver build){
        if(owner.restoringState())return;
        MassDriver type=(MassDriver)build.block;Pod pod=build.pod;
        if(pod.active()){
            pod.remaining=Math.max(0,pod.remaining-Time.delta);
            if(pod.remaining==0){
                Building resolved=owner.building(pod.target);
                if(resolved instanceof MassDriverBuild receiver&&receiver.block==build.block&&receiver.team==build.team&&!receiver.dead){
                    int space=Math.max(0,type.itemCapacity*2-receiver.items.total());
                    for(int i=0;i<pod.items.length&&space>0;i++){int amount=Math.min(space,pod.items[i]);receiver.items.add(Vars.content.item(i),amount);space-=amount;}
                    receiver.waitingShooters.remove(build);receiver.state=DriverState.idle;receiver.reloadCounter=1;
                    if(!Vars.headless)type.receiveSound.at(receiver.x,receiver.y,1,type.shootSoundVolume);
                }
                pod.clear();
            }
        }
        MassDriverBuild target=link(owner,build);
        build.reloadCounter=Mathf.clamp(build.reloadCounter-build.edelta()/type.reload);
        Building waiting=build.currentShooter();if(waiting!=null&&!shooter(owner,build,waiting))build.waitingShooters.remove(waiting);
        if(build.state==DriverState.idle){
            if(!build.waitingShooters.isEmpty()&&type.itemCapacity-build.items.total()>=type.minDistribute)build.state=DriverState.accepting;
            else if(target!=null)build.state=DriverState.shooting;
        }
        if(build.state==DriverState.idle||build.state==DriverState.accepting)build.dumpAccumulate();
        if(build.efficiency<=0)return;
        if(build.state==DriverState.accepting){
            waiting=build.currentShooter();if(waiting==null||type.itemCapacity-build.items.total()<type.minDistribute){build.state=DriverState.idle;return;}
            build.rotation=Angles.moveToward(build.rotation,build.angleTo(waiting),type.rotateSpeed*build.efficiency);return;
        }
        if(build.state!=DriverState.shooting)return;
        if(target==null||!build.waitingShooters.isEmpty()&&type.itemCapacity-build.items.total()>=type.minDistribute){build.state=DriverState.idle;return;}
        if(pod.active()||build.items.total()<type.minDistribute||type.itemCapacity-target.items.total()<type.minDistribute)return;
        target.waitingShooters.add(build);if(build.reloadCounter>.0001f)return;
        float angle=build.angleTo(target);build.rotation=Angles.moveToward(build.rotation,angle,type.rotateSpeed*build.efficiency);
        if(target.currentShooter()!=build||target.state!=DriverState.accepting||!Angles.near(build.rotation,angle,2)||!Angles.near(target.rotation,angle+180,2))return;
        if(!(target.tile instanceof LayerTile tile))return;
        LayerBuildingAdapter.Entry entry=null;for(var candidate:owner.entries())if(candidate.building()==target){entry=candidate;break;}if(entry==null)return;
        pod.target=entry.id();pod.duration=pod.remaining=Math.max(1,Math.min(type.bulletLifetime,build.dst(target)/type.bulletSpeed)/Math.max(.001f,build.timeScale()));
        pod.x0=build.x/Vars.tilesize;pod.y0=build.y/Vars.tilesize;pod.z0=(float)owner.buildingAltitude(build);
        pod.x1=target.x/Vars.tilesize;pod.y1=target.y/Vars.tilesize;pod.z1=(float)owner.buildingAltitude(target);
        if(pod.items.length!=Vars.content.items().size)pod.items=new int[Vars.content.items().size];
        int capacity=type.itemCapacity;for(int i=0;i<pod.items.length;i++){int count=Math.min(capacity,build.items.get(Vars.content.item(i)));pod.items[i]=count;build.items.remove(Vars.content.item(i),count);capacity-=count;}
        build.reloadCounter=1;build.state=DriverState.idle;if(!Vars.headless)type.shootSound.at(build.x,build.y,1,type.shootSoundVolume);
    }
}
