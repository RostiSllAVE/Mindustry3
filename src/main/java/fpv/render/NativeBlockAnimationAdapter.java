package fpv.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.UnitType;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.blocks.liquid.LiquidRouter;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.*;

/** Read-only native dynamic layers shared by Z0 and upper floors. No simulated unit previews. */
final class NativeBlockAnimationAdapter {
    private final TextureRegion clipped=new TextureRegion();
    private final java.util.IdentityHashMap<Building,ReleaseVisual> releases=new java.util.IdentityHashMap<>();
    private Object tiles;
    private float pruneAt;
    private static final class ReleaseVisual {mindustry.gen.Unit payload;float flashUntil;}
    void clear(){releases.clear();tiles=null;pruneAt=0;}
    boolean releaseFlash(Building b){var state=releases.get(b);return state!=null&&state.flashUntil>Time.time;}
    static boolean supports(Building b){return b instanceof Conveyor.ConveyorBuild||b.block instanceof UnitBlock||b.block instanceof LiquidBlock&&!(b.block instanceof mindustry.world.blocks.liquid.Conduit);}
    static int conveyorFrame(Conveyor.ConveyorBuild b){
        var type=(Conveyor)b.block;
        return b.enabled&&b.clogHeat<=.5f&&!Vars.state.rules.editor?Math.floorMod((int)(Time.time*type.speed*8*b.timeScale()*b.efficiency),4):0;
    }
    static UnitType assemblyType(Building b){
        if(b instanceof UnitFactory.UnitFactoryBuild f)return f.currentPlan>=0&&f.currentPlan<((UnitFactory)b.block).plans.size?((UnitFactory)b.block).plans.get(f.currentPlan).unit:null;
        if(b instanceof Reconstructor.ReconstructorBuild r&&r.constructing()&&r.hasArrived())return r.upgrade(r.payload.unit.type);
        return null;
    }
    static float progress(Building b){
        if(b instanceof UnitFactory.UnitFactoryBuild f)return Mathf.clamp(f.fraction());
        if(b instanceof Reconstructor.ReconstructorBuild r)return Mathf.clamp(r.progress/((Reconstructor)b.block).constructTime);
        return 0;
    }
    boolean render(Building b,NativeAtlasMesh.Builder out,float x,float base,float z,float size,float height){
        float top=base+height+.006f;
        if(b instanceof Conveyor.ConveyorBuild belt){
            var type=(Conveyor)b.block;
            out.top(type.regions[belt.blendbits][conveyorFrame(belt)],x,top,z,size*belt.blendsclx,size*belt.blendscly,-b.rotation*90,1,1,1,false);
            float dx=Mathf.cosDeg(b.rotation*90),dy=Mathf.sinDeg(b.rotation*90);
            for(int i=0;i<belt.len;i++)if(belt.ids[i]!=null){float along=belt.ys[i]-.5f,across=belt.xs[i]*.5f;
                out.top(belt.ids[i].fullIcon,x+dx*along-dy*across,top+.05f,z-dy*along-dx*across,.3f,.3f,0,1,1,1,false);
            }
            return true;
        }
        if(b.block instanceof UnitBlock block){
            out.top(block.region,x,top,z,size,size,0,1,1,1,false);
            out.top(block.outRegion,x,top+.002f,z,size,size,-b.rotation*90,1,1,1,false);
            var build=(UnitBlock.UnitBuild)b;var target=assemblyType(b);
            if(tiles!=Vars.world.tiles){clear();tiles=Vars.world.tiles;}
            if(releases.size()>512&&Time.time>=pruneAt){releases.entrySet().removeIf(e->e.getKey().dead||e.getKey().tile==null||e.getKey().tile.build!=e.getKey());pruneAt=Time.time+120;}
            var release=releases.get(b);
            if(release==null){if(releases.size()>=4096)releases.clear();release=new ReleaseVisual();releases.put(b,release);}
            if(build.payload==null&&release.payload!=null){
                var live=mindustry.gen.Groups.unit.getByID(release.payload.id);
                if(live!=null&&!live.dead&&live.type==release.payload.type&&live.team==b.team)release.flashUntil=Time.time+24;
            }
            release.payload=build.payload==null?null:build.payload.unit;
            if(target!=null&&progress(b)>0){
                float p=progress(b);sprite(out,target.fullIcon,x,top+.012f,z,b.rotdeg()-90,p,size);
                float scan=Mathf.sin(build.time*.1f)*.5f+.5f;
                out.top(Core.atlas.white(),x,top+.016f,z+(scan-.5f)*size*.8f,size*.75f,.025f,-b.rotation*90,b.team.color.r,b.team.color.g,b.team.color.b,false);
            }
            if(build.payload!=null){
                UnitPayload payload=build.payload;
                float px=payload.x()/Vars.tilesize,pz=-payload.y()/Vars.tilesize;
                // During reconstruction, the old unit is still a payload, never a second actor.
                if(target==null||progress(b)<.5f)sprite(out,payload.unit.type.fullIcon,px,top+.01f,pz,payload.rotation()-90,1,size);
                if(payload.overlayTime>0)out.top(payload.overlayRegion==null?mindustry.gen.Icon.warning.getRegion():payload.overlayRegion,x,top+.02f,z,.45f,.45f,0,1,.15f,.1f,false);
            }
            out.top(block.topRegion,x,top+.025f,z,size,size,0,1,1,1,false);
            if(release.flashUntil>Time.time){
                float span=size*(.4f+(1-(release.flashUntil-Time.time)/24)*.3f);
                out.top(Core.atlas.white(),x,top+.03f,z,span,.025f,0,b.team.color.r,b.team.color.g,b.team.color.b,false);
                out.top(Core.atlas.white(),x,top+.03f,z,.025f,span,0,b.team.color.r,b.team.color.g,b.team.color.b,false);
            }
            return true;
        }
        if(b.block instanceof LiquidBlock block&&!(block instanceof mindustry.world.blocks.liquid.Conduit)){
            out.top(block.bottomRegion,x,top,z,size,size,block.rotate?-b.rotation*90:0,1,1,1,false);
            float fill=b.liquids==null?0:Mathf.clamp(b.liquids.currentAmount()/block.liquidCapacity);
            if(fill>.001f){
                var liquid=b.liquids.current();var tint=liquid.color;
                TextureRegion surface=block.liquidRegion;
                if(block instanceof LiquidRouter&&Vars.renderer!=null)surface=Vars.renderer.fluidFrames[liquid.gas?1:0][liquid.getAnimationFrame()];
                float width=size;
                if(block instanceof LiquidRouter router)width=Math.max(.05f,size-router.liquidPadding*2/Vars.tilesize);
                // The world atlas pass is opaque: fill uses native amount/color as brightness,
                // plus a filled region rather than incorrectly changing the material alpha channel.
                out.top(surface,x,top+.003f,z,width,width,block.rotate?-b.rotation*90:0,tint.r*fill,tint.g*fill,tint.b*fill,false);
            }
            out.top(block instanceof LiquidRouter?block.region:block.topRegion,x,top+.007f,z,size,size,block.rotate?-b.rotation*90:0,1,1,1,false);
            return true;
        }
        return false;
    }
    private void sprite(NativeAtlasMesh.Builder out,TextureRegion region,float x,float y,float z,float nativeAngle,float fraction,float limit){
        if(!NativeAtlasMesh.valid(region)||fraction<=0)return;
        float w=region.width/(4f*Vars.tilesize),d=region.height/(4f*Vars.tilesize),scale=Math.min(1,limit*.88f/Math.max(w,d));w*=scale;d*=scale;
        clipped.set(region);clipped.v2=Mathf.lerp(region.v,region.v2,fraction);
        float offset=d*(1-fraction)*.5f,c=Mathf.cosDeg(-nativeAngle),s=Mathf.sinDeg(-nativeAngle);
        out.top(clipped,x+offset*s,y,z-offset*c,w,d*fraction,-nativeAngle,1,1,1,false);
    }
}
