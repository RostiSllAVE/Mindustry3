package fpv.render;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.entities.part.*;
import mindustry.world.draw.DrawTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import java.util.*;

/** Native draw commands supply origins, mirrors and recoil; cached silhouettes supply volume.
 * Geometry is shared across instances and transformed into the existing atlas stream, not rebuilt.
 * No draw callback updates buildings or executes combat. Non-region drawers retain their old path.
 */
final class TurretModelRenderer {
    private record Entry(Texture texture,float u,float v,float u2,float v2,int width,int height,SpriteExtrusionGeometry.Result shape){
        boolean matches(TextureRegion r){return texture==r.texture&&u==r.u&&v==r.v&&u2==r.u2&&v2==r.v2&&width==r.width&&height==r.height;}
    }
    private final IdentityHashMap<TextureRegion,Entry> cache=new IdentityHashMap<>();
    private final IdentityHashMap<Turret,Boolean> supported=new IdentityHashMap<>();
    private final Capture capture=new Capture();
    private final DrawPart.PartParams params=new DrawPart.PartParams();
    private final double[] basis=new double[9],gunBasis=new double[9];
    private Object atlas;
    private long bytes,builds;
    int entries(){return cache.size();} long bytes(){return bytes;} long builds(){return builds;}
    int vertices(TextureRegion art){var e=cache.get(art);return e==null?0:e.shape.vertexCount();}
    void clear(){cache.clear();supported.clear();atlas=null;bytes=0;}
    boolean supports(Turret type){
        if(atlas!=Core.atlas){clear();atlas=Core.atlas;}
        return supported.computeIfAbsent(type,t->{
            if(!(t.drawer instanceof DrawTurret d)||!NativeAtlasMesh.valid(d.base))return false;
            for(var p:d.parts)if(!regionParts(p))return false;
            for(var parts:d.ammoParts.values())for(var p:parts)if(!regionParts(p))return false;
            return true;
        });
    }
    private static boolean regionParts(DrawPart p){
        if(!(p instanceof RegionPart r))return false;
        for(var child:r.children)if(!regionParts(child))return false;
        return true;
    }
    boolean render(Turret.TurretBuild build,NativeAtlasMesh.Builder out,float x,float base,float z,float height,float pitch){
        var type=(Turret)build.block;if(!supports(type))return false;
        var drawer=(DrawTurret)type.drawer;var profile=TurretModelProfile.forTurret(type);
        float thickness=profile.thickness(type.size),pivot=base+height+.006f;
        float baseHeight=Math.max(.06f,height-thickness*.55f-.035f);
        SpriteExtrusionGeometry.basis(90,0,0,basis);
        emit(out,drawer.base,x,base+baseHeight*.5f,z,drawer.base.width*drawer.base.scl()/Vars.tilesize,baseHeight,
            drawer.base.height*drawer.base.scl()/Vars.tilesize,basis,1,1,1);
        Batch old=Core.batch;float xs=Draw.xscl,ys=Draw.yscl;
        capture.out=out;capture.build=build;capture.x=x;capture.z=z;capture.pivot=pivot;capture.pitch=pitch;capture.depth=thickness;capture.lift=0;
        SpriteExtrusionGeometry.basis(build.rotation,pitch,0,gunBasis);
        Draw.flush();Core.batch=capture;
        try{
            Draw.scl();Draw.color();Draw.mixcol();Draw.z(drawer.turretLayer);
            drawer.drawTurret(type,build);
            // Heat overlays keep native hue/animation, with a thin separated surface.
            capture.lift=thickness*.51f;capture.depth=.003f;drawer.drawHeat(type,build);
            for(var part:drawer.parts)drawPart(part,build,thickness);
            var ammo=build.getAmmoContent();var parts=ammo==null?null:drawer.ammoParts.get(ammo);
            if(parts!=null)for(var part:parts)drawPart(part,build,thickness);
        }finally{Core.batch=old;Draw.scl(xs,ys);capture.out=null;capture.build=null;}
        return true;
    }
    private void drawPart(DrawPart part,Turret.TurretBuild build,float thickness){
        float progress=1-build.progress();
        params.set(build.warmup(),progress,progress,build.heat,build.curRecoil,build.charge,build.x+build.recoilOffset.x,build.y+build.recoilOffset.y,build.rotation);
        params.setRecoil(part.recoilIndex>=0&&build.curRecoils!=null?build.curRecoils[part.recoilIndex]:build.curRecoil);
        capture.lift=TurretModelProfile.partLift((Turret)build.block,part,thickness);
        capture.depth=thickness*.65f;Draw.color();Draw.mixcol();part.draw(params);
    }
    private void emit(NativeAtlasMesh.Builder out,TextureRegion art,float x,float y,float z,float width,float depth,float length,double[] transform,float r,float g,float b){
        if(!NativeAtlasMesh.valid(art)||width==0||length==0)return;
        Entry e=cache.get(art);
        if(e==null||!e.matches(art)){
            if(e!=null)bytes-=e.shape.vertexBytes();
            if(cache.size()>=256||bytes>16L*1024*1024){cache.clear();bytes=0;}
            // Flipped atlas regions are sampled with their signed UVs. Rotated packed regions
            // are not present in the vanilla atlas; preserve a plane if an external atlas uses one.
            if(art instanceof TextureAtlas.AtlasRegion ar&&ar.rotate){out.top(art,x,y,z,Math.abs(width),Math.abs(length),0,r,g,b,false);return;}
            var pixels=Core.atlas.getPixmaps().get(art.texture,()->art.texture.getTextureData().getPixmap());
            if(pixels==null||pixels.isDisposed()||(long)art.width*art.height>1024*1024)return;
            byte[] alpha=new byte[art.width*art.height];
            for(int yy=0;yy<art.height;yy++)for(int xx=0;xx<art.width;xx++){
                int sx=art.isFlipX()?art.width-1-xx:xx,sy=art.isFlipY()?art.height-1-yy:yy;
                alpha[xx+yy*art.width]=(byte)pixels.getA(Math.round(Math.min(art.u,art.u2)*art.texture.width)+sx,Math.round(Math.min(art.v,art.v2)*art.texture.height)+sy);
            }
            var shape=SpriteExtrusionGeometry.generate(alpha,art.width,art.height,24,6,32,.2,16380);
            e=new Entry(art.texture,art.u,art.v,art.u2,art.v2,art.width,art.height,shape);cache.put(art,e);bytes+=shape.vertexBytes();builds++;
        }
        out.extrusion(art,e.shape.vertices(),x,y,z,width,depth,length,transform,r,g,b);
    }
    private final class Capture extends Batch {
        NativeAtlasMesh.Builder out;Turret.TurretBuild build;float x,z,pivot,pitch,depth,lift;
        @Override protected void flush(){}
        @Override protected void draw(Texture texture,float[] vertices,int offset,int count){
            // No RegionPart uses raw vertex commands; specialized procedural drawers are preflighted out.
        }
        @Override protected void draw(TextureRegion region,float bx,float by,float ox,float oy,float width,float length,float rotation){
            if(out==null||!NativeAtlasMesh.valid(region))return;
            // Separate outline masks would make coincident opaque volumes over the same gun.
            // The main vanilla region already contains its readable pixel-art contour.
            if(region instanceof TextureAtlas.AtlasRegion ar&&ar.name.endsWith("-outline"))return;
            float c=Mathf.cosDeg(rotation),s=Mathf.sinDeg(rotation);
            float cx=bx+ox+(width*.5f-ox)*c-(length*.5f-oy)*s;
            float cy=by+oy+(width*.5f-ox)*s+(length*.5f-oy)*c;
            float dx=(cx-build.x)/Vars.tilesize,dy=(cy-build.y)/Vars.tilesize;
            float ca=Mathf.cosDeg(build.rotation),sa=Mathf.sinDeg(build.rotation),cp=Mathf.cosDeg(pitch),sp=Mathf.sinDeg(pitch);
            float forward=dx*ca+dy*sa,side=-dx*sa+dy*ca;
            TurretPartPose.basis(gunBasis,rotation+90-build.rotation,basis);
            // Native top/liquid/heat are overlays, not additional complete thick hull copies.
            var drawer=(DrawTurret)((Turret)build.block).drawer;
            boolean overlay=region==drawer.top||region==drawer.liquid||region==drawer.heat||blending==Blending.additive;
            float h=overlay?lift+depth*.51f:lift,d=overlay?.003f:depth;
            int rgba=Color.floatToIntColor(colorPacked);float alpha=((rgba>>>24)&255)/255f;
            if(alpha<.02f)return;
            emit(out,region,x+ca*forward*cp-sa*side+(float)gunBasis[3]*h,pivot+forward*sp+(float)gunBasis[4]*h,
                z-sa*forward*cp-ca*side+(float)gunBasis[5]*h,width/Vars.tilesize,d,length/Vars.tilesize,basis,
                (rgba&255)/255f*alpha,((rgba>>>8)&255)/255f*alpha,((rgba>>>16)&255)/255f*alpha);
        }
    }
}
