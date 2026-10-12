package fpv.render;

import arc.graphics.Color;
import arc.graphics.g3d.Camera3D;
import arc.math.geom.Position;
import arc.struct.IntMap;
import arc.struct.LongMap;
import fpv.combat.Combat3DSystem;
import fpv.combat.CombatVisual;
import fpv.generated.RenderConfig;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.entities.Damage;
import mindustry.entities.bullet.*;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

import java.util.function.DoubleBinaryOperator;

/** Emissive presentation only: no native damage, collision, bullet metadata or atlas mutation. */
public final class CombatVisualRenderer {
    /** Renderer-coordinate sink; canonical XYZ is converted at this one presentation boundary. */
    public interface Sink {
        void beam(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b);
        default void laser(float ax,float ay,float az,float bx,float by,float bz,float width,float r,float g,float b){beam(ax,ay,az,bx,by,bz,width,r,g,b);}
        void marker(float x,float y,float z,float radius,float r,float g,float b);
    }
    public record Stats(int projectiles,int beams,int hits,int nativeProjectiles,int nativeBeams) {}
    private final IntMap<NativeShot> nativeShots=new IntMap<>();
    private final LongMap<Boolean> submitted=new LongMap<>();
    private final LongMap<VisualMuzzle> muzzles=new LongMap<>();
    private Object worldTiles;
    private long frame;
    private int projectiles,beams,hits,nativeProjectiles,nativeBeams;
    private Camera3D camera;
    private Sink sink;
    private UnitAltitudeSystem altitudes;
    private DoubleBinaryOperator surface;
    private boolean firstPerson;
    private int loweredMuzzles;
    public void setFirstPerson(boolean value){firstPerson=value;}
    public int loweredMuzzles(){return loweredMuzzles;}

    public Stats stats(){return new Stats(projectiles,beams,hits,nativeProjectiles,nativeBeams);}
    public boolean wasSubmitted(long id){return submitted.containsKey(id);}
    public boolean wasNativeSubmitted(Bullet bullet){
        NativeShot shot=bullet==null?null:nativeShots.get(bullet.id);
        return shot!=null&&shot.bullet==bullet&&shot.rendered==frame;
    }
    public void clear(){nativeShots.clear();submitted.clear();muzzles.clear();worldTiles=null;projectiles=beams=hits=nativeProjectiles=nativeBeams=loweredMuzzles=0;}

    public void render(Camera3D camera,Combat3DSystem combat,UnitAltitudeSystem altitudes,
                       DoubleBinaryOperator surface,Sink sink){
        if(worldTiles!=Vars.world.tiles){clear();worldTiles=Vars.world.tiles;}
        this.camera=camera;this.sink=sink;this.altitudes=altitudes;this.surface=surface;
        frame++;submitted.clear();projectiles=beams=hits=nativeProjectiles=nativeBeams=loweredMuzzles=0;
        if(combat!=null)for(CombatVisual visual:combat.visuals()){
            MuzzlePresentation.Offset muzzle=null;
            if(visual.kind()==CombatVisual.Kind.PROJECTILE||visual.kind()==CombatVisual.Kind.BEAM){
                VisualMuzzle saved=muzzles.get(visual.id());
                if(saved==null&&muzzles.size<8192){saved=new VisualMuzzle(offset(visual.ax(),visual.ay(),visual.az(),visual.bx(),visual.by(),visual.bz(),visual.radius(),visual.teamId()));muzzles.put(visual.id(),saved);}
                if(saved!=null){saved.seen=frame;muzzle=saved.offset;}
            }
            if(draw(visual.kind(),visual.ax(),visual.ay(),visual.az(),visual.bx(),visual.by(),visual.bz(),
                visual.radius(),visual.r(),visual.g(),visual.b(),visual.alpha(),muzzle)){
                submitted.put(visual.id(),Boolean.TRUE);
                switch(visual.kind()){case PROJECTILE->projectiles++;case BEAM->beams++;case HIT,EXPLOSION,HEAL->hits++;}
            }
        }
        for(Bullet bullet:Groups.bullet){
            if(bullet.type==null||(combat!=null&&combat.managed(bullet)))continue;
            NativeShot shot=nativeShots.get(bullet.id);
            if(shot==null||shot.bullet!=bullet||shot.type!=bullet.type){
                shot=new NativeShot(bullet,launchHeight(bullet));
                shot.muzzle=offset(shot.x,shot.y,shot.height,shot.x+bullet.vel.x/Vars.tilesize,shot.y+bullet.vel.y/Vars.tilesize,shot.height,bullet.type.hitSize/(2.0*Vars.tilesize),bullet.team.id);
                if(nativeShots.size<(int)RenderConfig.COMBAT_NATIVE_BULLET_CACHE)nativeShots.put(bullet.id,shot);
            }
            shot.seen=frame;
            if(nativeBullet(bullet,shot))shot.rendered=frame;
            shot.x=bullet.x/Vars.tilesize;shot.y=bullet.y/Vars.tilesize;
        }
        prune();
    }
    private void prune(){
        var entries=nativeShots.entries();
        while(entries.hasNext()){
            NativeShot shot=entries.next().value;
            if(shot.seen!=frame||!shot.bullet.isAdded()||shot.bullet.type!=shot.type)entries.remove();
        }
        var visuals=muzzles.entries();while(visuals.hasNext())if(visuals.next().value.seen<frame-60)visuals.remove();
    }
    private MuzzlePresentation.Offset offset(double ax,double ay,double az,double bx,double by,double bz,double radius,int team){
        return MuzzlePresentation.create(ax,ay,az,camera.position.x,-camera.position.z,camera.position.y,
            camera.up.x,-camera.up.z,camera.up.y,camera.direction.x,-camera.direction.z,camera.direction.y,
            bx,by,bz,radius,firstPerson,Vars.player!=null&&Vars.player.team().id==team);
    }

    private double launchHeight(Bullet bullet){
        Object owner=bullet.owner;
        for(int depth=0;depth<4;depth++){
            if(owner instanceof Unit unit&&altitudes!=null){
                var state=altitudes.unitState(unit);
                if(state!=null)return state.z();
            }
            if(owner instanceof Building building)
                return FpvWorldRenderer.heightFor(building.block)+RenderConfig.BULLET_MIN_SIZE;
            if(owner instanceof Bullet parent){
                NativeShot parentShot=nativeShots.get(parent.id);
                if(parentShot!=null&&parentShot.bullet==parent)return parentShot.height;
                owner=parent.owner;
            }else break;
        }
        if(bullet.shooter instanceof Unit unit&&altitudes!=null){
            var state=altitudes.unitState(unit);if(state!=null)return state.z();
        }
        return surface.applyAsDouble(bullet.x/Vars.tilesize,-bullet.y/Vars.tilesize)+RenderConfig.BULLET_HEIGHT;
    }

    private boolean nativeBullet(Bullet bullet,NativeShot shot){
        BulletType type=bullet.type;
        double ax=bullet.x/(double)Vars.tilesize,ay=bullet.y/(double)Vars.tilesize,az=shot.height;
        double bx=ax,by=ay,bz=az,length=-1,radius=type.hitSize/(2.0*Vars.tilesize);
        Color color=type instanceof BasicBulletType basic?basic.frontColor:type.hitColor;
        if(color==null)color=bullet.team.color;
        boolean beam=true;
        if(type instanceof LaserBulletType laser){length=bullet.fdata>0?bullet.fdata/Vars.tilesize:laser.length/Vars.tilesize;radius=laser.width/(2.0*Vars.tilesize);}
        else if(type instanceof ContinuousBulletType continuous){
            length=Damage.findLength(bullet,continuous.currentLength(bullet),type.laserAbsorb,type.pierceCap)/Vars.tilesize;
            if(type instanceof ContinuousLaserBulletType laser)radius=laser.width/(2.0*Vars.tilesize);
        }else if(type instanceof ShrapnelBulletType shrapnel){length=bullet.fdata>0?bullet.fdata/Vars.tilesize:shrapnel.length/Vars.tilesize;radius=shrapnel.width/(2.0*Vars.tilesize);}
        else if(type instanceof RailBulletType rail){length=bullet.fdata>0?bullet.fdata/Vars.tilesize:rail.length/Vars.tilesize;}
        else if(type instanceof SapBulletType sap){
            color=sap.color;radius=sap.width*.5;
            if(!(bullet.data instanceof Position target))return false;
            bx=target.getX()/Vars.tilesize;by=target.getY()/Vars.tilesize;
            if(target instanceof Unit unit&&altitudes!=null){var pose=altitudes.unitState(unit);if(pose!=null)bz=pose.z();}
            else if(target instanceof Building building)bz=FpvWorldRenderer.heightFor(building.block);
        }else if(type instanceof PointLaserBulletType laser){bx=bullet.aimX/Vars.tilesize;by=bullet.aimY/Vars.tilesize;color=laser.color;}
        else beam=false;
        if(beam){
            if(length>=0){double angle=Math.toRadians(bullet.rotation());bx=ax+Math.cos(angle)*length;by=ay+Math.sin(angle)*length;}
            boolean visible=draw(CombatVisual.Kind.BEAM,ax,ay,az,bx,by,bz,radius,color.r,color.g,color.b,Math.max(.25f,bullet.fout()),shot.muzzle);
            if(visible)nativeBeams++;
            return visible;
        }
        double tx=shot.x,ty=shot.y;
        if(shot.seen==frame&&shot.first){tx=ax-bullet.vel.x/Vars.tilesize;ty=ay-bullet.vel.y/Vars.tilesize;shot.first=false;}
        boolean visible=draw(CombatVisual.Kind.PROJECTILE,tx,ty,az,ax,ay,az,radius,color.r,color.g,color.b,1,shot.muzzle);
        if(visible)nativeProjectiles++;
        return visible;
    }

    private boolean draw(CombatVisual.Kind kind,double ax,double ay,double az,double bx,double by,double bz,
                         double physicalRadius,float red,float green,float blue,float alpha,MuzzlePresentation.Offset muzzle){
        if(alpha<=0||!finite(ax,ay,az,bx,by,bz,physicalRadius))return false;
        if(kind==CombatVisual.Kind.HEAL)return healingFlash(ax,ay,az,bz,physicalRadius,alpha);
        boolean pilot=muzzle!=null&&muzzle.pilot()&&firstPerson;
        if(muzzle!=null){
            double start=kind==CombatVisual.Kind.BEAM?1:muzzle.strength(ax,ay,az),end=kind==CombatVisual.Kind.BEAM?0:muzzle.strength(bx,by,bz);
            // First-person correction is camera-specific; switching to chase retains only the modest world offset.
            double drop=pilot?muzzle.drop():Math.min(.24,muzzle.drop());
            ax-=muzzle.upX()*drop*start;ay-=muzzle.upY()*drop*start;az-=muzzle.upZ()*drop*start;
            bx-=muzzle.upX()*drop*end;by-=muzzle.upY()*drop*end;bz-=muzzle.upZ()*drop*end;
            if(start+end>.001)loweredMuzzles++;
        }
        float visibility=Math.max(0,Math.min(1,alpha));
        double distance=Math.sqrt(square(bx-camera.position.x)+square(bz-camera.position.y)+square(-by-camera.position.z));
        double pixelRadius=distance*Math.tan(Math.toRadians(camera.fov)*.5)*RenderConfig.COMBAT_MIN_PIXELS/Math.max(1,camera.height);
        float radius=(float)Math.min(RenderConfig.BULLET_MAX_SIZE,Math.max(RenderConfig.BULLET_MIN_SIZE,
            Math.max(Math.max(0,physicalRadius)*RenderConfig.COMBAT_WIDTH_SCALE,pixelRadius)));
        radius*=Math.max(.3f,visibility);
        radius=(float)MuzzlePresentation.nearRadius(radius,distance,pilot);
        if(kind==CombatVisual.Kind.BEAM)radius*=3;
        if(kind==CombatVisual.Kind.EXPLOSION)radius=(float)Math.max(.2,physicalRadius*(.25+.75*(1-visibility)));
        if(!camera.frustum.containsBounds((float)(ax+bx)*.5f,(float)(az+bz)*.5f,(float)(-ay-by)*.5f,
            (float)Math.abs(bx-ax)*.5f+radius,(float)Math.abs(bz-az)*.5f+radius,(float)Math.abs(by-ay)*.5f+radius))return false;
        float r=Math.min(1,.22f+Math.max(0,red)*.78f),g=Math.min(1,.22f+Math.max(0,green)*.78f),b=Math.min(1,.22f+Math.max(0,blue)*.78f);
        boolean rendered=false;
        if(kind==CombatVisual.Kind.BEAM){
            float width=Math.max((float)RenderConfig.COMBAT_BEAM_MIN_WIDTH*3,radius*2);
            if(pilot){
                // Perspective-friendly short muzzle segment joins the full-width world beam;
                // the real endpoint and everything beyond the pilot's immediate vicinity are unchanged.
                double length=Math.sqrt(square(bx-ax)+square(by-ay)+square(bz-az));
                double fraction=Math.min(1,2.5/Math.max(.001,length));
                double mx=ax+(bx-ax)*fraction,my=ay+(by-ay)*fraction,mz=az+(bz-az)*fraction;
                rendered=line(ax,ay,az,mx,my,mz,Math.min(width,.11f),r,g,b,true);
                if(fraction<1)rendered|=line(mx,my,mz,bx,by,bz,width,r,g,b,true);
            }else rendered=line(ax,ay,az,bx,by,bz,width,r,g,b,true);
        }else{
            if(kind==CombatVisual.Kind.PROJECTILE){
                double length=Math.sqrt(square(bx-ax)+square(by-ay)+square(bz-az));
                if(length>RenderConfig.COMBAT_MAX_TRAIL){double fraction=RenderConfig.COMBAT_MAX_TRAIL/length;ax=bx+(ax-bx)*fraction;ay=by+(ay-by)*fraction;az=bz+(az-bz)*fraction;}
                rendered=line(ax,ay,az,bx,by,bz,radius,r,g,b,false);
            }
            if(visible(bx,by)){
                sink.marker((float)bx,(float)bz,(float)-by,radius,r,g,b);
                if(kind==CombatVisual.Kind.HIT||kind==CombatVisual.Kind.EXPLOSION){
                    // Six bounded ballistic sparks and an expanding flash; no particle entities.
                    double age=1-visibility,spread=kind==CombatVisual.Kind.EXPLOSION?radius:Math.max(.12,radius)*4;
                    for(int i=0;i<6;i++){
                        double angle=i*Math.PI/3+.37,length=spread*(.35+age);
                        double dx=Math.cos(angle)*length,dy=Math.sin(angle)*length,dz=length*(i%2==0?.65:-.35)-age*age*.35;
                        line(bx+dx*.65,by+dy*.65,bz+dz*.65,bx+dx,by+dy,bz+dz,
                            Math.max(.018f,radius*.045f)*visibility,r,g,b,false);
                    }
                }
                rendered=true;
            }
        }
        return rendered;
    }

    /** Brief ore-independent block outline, faded by the authoritative event lifetime. */
    private boolean healingFlash(double x,double y,double bottom,double top,double half,float alpha){
        if(!visible(x,y)||!camera.frustum.containsBounds((float)x,(float)((bottom+top)*.5),(float)-y,(float)half+.04f,(float)((top-bottom)*.5)+.04f,(float)half+.04f))return false;
        double size=Math.max(.05,half)+.025,lo=bottom+.02,hi=Math.max(lo+.03,top+.025);float glow=Math.min(1,Math.max(0,alpha));
        for(int i=0;i<4;i++){
            double ax=x+(i==0||i==3?-size:size),ay=y+(i<2?-size:size);
            int next=(i+1)%4;double bx=x+(next==0||next==3?-size:size),by=y+(next<2?-size:size);
            line(ax,ay,lo,bx,by,lo,.055f,.28f*glow,glow,.45f*glow,false);
            line(ax,ay,hi,bx,by,hi,.055f,.28f*glow,glow,.45f*glow,false);
            line(ax,ay,lo,ax,ay,hi,.055f,.28f*glow,glow,.45f*glow,false);
        }
        return true;
    }
    /** Fog clips spans into visible runs; an unseen source cannot reveal its entire firing path. */
    private boolean line(double ax,double ay,double az,double bx,double by,double bz,float width,float r,float g,float b,boolean core){
        double length=Math.sqrt(square(bx-ax)+square(by-ay)+square(bz-az));
        if(length<.001)return false;
        int pieces=Vars.state.rules.fog?Math.max(1,Math.min((int)RenderConfig.COMBAT_MAX_FOG_SAMPLES,(int)Math.ceil(Math.hypot(bx-ax,by-ay)*2))):1;
        boolean opened=false,rendered=false;double start=0;
        for(int i=0;i<=pieces;i++){
            boolean allowed=i<pieces&&visible(ax+(bx-ax)*(i+.5)/pieces,ay+(by-ay)*(i+.5)/pieces);
            if(allowed&&!opened){start=i/(double)pieces;opened=true;}
            if(!allowed&&opened){
                double end=i/(double)pieces;
                float sx=(float)(ax+(bx-ax)*start),sy=(float)(az+(bz-az)*start),sz=(float)-(ay+(by-ay)*start);
                float ex=(float)(ax+(bx-ax)*end),ey=(float)(az+(bz-az)*end),ez=(float)-(ay+(by-ay)*end);
                if(core)sink.laser(sx,sy,sz,ex,ey,ez,width,r,g,b);else sink.beam(sx,sy,sz,ex,ey,ez,width,r,g,b);
                // One emissive beam surface: a narrower coplanar overlay causes depth fighting.
                opened=false;rendered=true;
            }
        }
        return rendered;
    }
    private boolean visible(double x,double y){
        return !Vars.state.rules.fog||Vars.fogControl==null||Vars.fogControl.isVisible(Vars.player.team(),(float)x*Vars.tilesize,(float)y*Vars.tilesize);
    }
    private static double square(double x){return x*x;}
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static final class NativeShot {
        final Bullet bullet;final BulletType type;final double height;
        MuzzlePresentation.Offset muzzle;
        double x,y;long seen,rendered;boolean first=true;
        NativeShot(Bullet bullet,double height){this.bullet=bullet;this.type=bullet.type;this.height=height;x=bullet.x/Vars.tilesize;y=bullet.y/Vars.tilesize;}
    }
    private static final class VisualMuzzle {final MuzzlePresentation.Offset offset;long seen;VisualMuzzle(MuzzlePresentation.Offset offset){this.offset=offset;}}
}
