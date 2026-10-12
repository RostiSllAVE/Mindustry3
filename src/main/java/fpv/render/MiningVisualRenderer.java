package fpv.render;

import arc.graphics.g3d.Camera3D;
import arc.struct.IntMap;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Tile;
import java.util.function.DoubleBinaryOperator;

/** Read-only native mining presentation; no resource transfer, timers, damage or gameplay effects. */
public final class MiningVisualRenderer {
    private static final int MAX_MINERS=256;
    public static final class Sample {
        public Unit owner;public Tile target;public Item item;
        public double ax,ay,az,bx,by,bz,progress;
        private long seen,submitted;
    }
    private final IntMap<Sample> samples=new IntMap<>();
    private long frame;
    private int miners,primitives;
    public record Stats(int miners,int primitives,int cachedSamples){}
    public Stats stats(){return new Stats(miners,primitives,samples.size);}
    public boolean wasSubmitted(Unit u){var s=u==null?null:samples.get(u.id);return s!=null&&s.owner==u&&s.submitted==frame;}
    public Sample sample(Unit u){return wasSubmitted(u)?samples.get(u.id):null;}
    public void clear(){samples.clear();miners=primitives=0;frame++;}

    /** Surface callback uses renderer coordinates (X,-nativeY), matching existing support effects. */
    public static boolean read(Unit u,Unit3DState pose,DoubleBinaryOperator surface,Sample out){
        if(u==null||pose==null||u.dead||!u.isAdded()||u.type==null||!u.mining()||!u.canMine()||!u.validMine(u.mineTile))return false;
        Item ore=u.getMineResult(u.mineTile);if(ore==null||!Float.isFinite(u.mineTimer))return false;
        double offset=u.type.mineBeamOffset/Vars.tilesize;
        if(!Double.isFinite(offset))offset=u.hitSize/(2d*Vars.tilesize);
        double yaw=Math.toRadians(pose.yaw()),pitch=Math.toRadians(pose.pitch()),cp=Math.cos(pitch);
        out.owner=u;out.target=u.mineTile;out.item=ore;
        out.ax=pose.x()+Math.cos(yaw)*cp*offset;out.ay=pose.y()+Math.sin(yaw)*cp*offset;
        out.az=pose.z()+Math.sin(pitch)*offset-Math.min(.12,pose.halfHeight()*.35);
        out.bx=u.mineTile.worldx()/(double)Vars.tilesize;out.by=u.mineTile.worldy()/(double)Vars.tilesize;
        out.bz=surface.applyAsDouble(out.bx,-out.by)+.028;
        out.progress=Math.max(0,Math.min(1,u.mineTimer/(50d+(u.type.mineHardnessScaling?ore.hardness*15d:15d))));
        return Double.isFinite(out.ax+out.ay+out.az+out.bx+out.by+out.bz);
    }
    public void render(Camera3D camera,Iterable<Unit> units,UnitAltitudeSystem altitudes,DoubleBinaryOperator surface,SupportEffectsRenderer.Sink sink){
        frame++;miners=primitives=0;
        if(camera==null||altitudes==null||Vars.player==null)return;
        for(Unit u:units){
            if(miners>=MAX_MINERS)break;
            if(!u.mining()||u.inFogTo(Vars.player.team()))continue;
            var s=samples.get(u.id);
            if(s==null||s.owner!=u){if(samples.size>=MAX_MINERS)continue;s=new Sample();s.owner=u;samples.put(u.id,s);}
            s.seen=frame;
            if(!read(u,altitudes.unitState(u),surface,s))continue;
            if(Vars.state.rules.fog&&Vars.fogControl!=null&&!Vars.fogControl.isVisible(Vars.player.team(),s.target.x,s.target.y))continue;
            float cx=(float)((s.ax+s.bx)*.5),cz=(float)((s.az+s.bz)*.5),cy=(float)(-(s.ay+s.by)*.5);
            if(camera.position.dst2(cx,cz,cy)>160*160||!camera.frustum.containsBounds(cx,cz,cy,(float)Math.abs(s.ax-s.bx)*.5f+.5f,(float)Math.abs(s.az-s.bz)*.5f+.5f,(float)Math.abs(s.ay-s.by)*.5f+.5f))continue;
            s.submitted=frame;miners++;
            var color=s.item.color;float pulse=(float)(.68+.32*s.progress);
            if(u.type.drawMineBeam){
                sink.beam((float)s.ax,(float)s.az,(float)-s.ay,(float)s.bx,(float)s.bz,(float)-s.by,.08f,color.r*.65f,color.g*.65f,color.b*.65f);
                sink.beam((float)s.ax,(float)s.az,(float)-s.ay,(float)s.bx,(float)s.bz,(float)-s.by,.032f,.82f+.18f*pulse,.84f+.16f*pulse,.88f+.12f*pulse);primitives+=2;
            }
            sink.disk((float)s.bx,(float)s.bz,(float)-s.by,.12f+.05f*pulse,.12f+.05f*pulse,color.r*pulse,color.g*pulse,color.b*pulse);primitives++;
            // A small ore-colored surface ring and deterministic sparks follow actual cycle progress.
            // All stop on the very next rendered frame; no trailing native particle entities are spawned.
            double phase=s.progress*Math.PI*2+u.id*.73;
            for(int i=0;i<8;i++){
                double a=phase+i*Math.PI/4,b=phase+(i+1)*Math.PI/4,r=.28+.035*s.progress;
                double ax=s.bx+Math.cos(a)*r,ay=s.by+Math.sin(a)*r,bx=s.bx+Math.cos(b)*r,by=s.by+Math.sin(b)*r;
                sink.beam((float)ax,(float)(surface.applyAsDouble(ax,-ay)+.031),(float)-ay,(float)bx,(float)(surface.applyAsDouble(bx,-by)+.031),(float)-by,.02f,color.r*pulse,color.g*pulse,color.b*pulse);primitives++;
            }
            for(int i=0;i<3;i++){
                double age=(s.progress+i/3d)%1,a=phase+i*2.39996,r=.08+age*.24;
                double px=s.bx+Math.cos(a)*r,py=s.by+Math.sin(a)*r,pz=s.bz+Math.sin(age*Math.PI)*.24;
                sink.marker((float)px,(float)pz,(float)-py,.025f,color.r,color.g,color.b);primitives++;
            }
        }
        for(var it=samples.entries().iterator();it.hasNext();){var e=it.next();if(e.value.owner.dead||!e.value.owner.isAdded()||frame-e.value.seen>120)it.remove();}
    }
}
