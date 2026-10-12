package fpv.render;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import fpv.world.NaturalProps;
import mindustry.world.Tile;
import java.util.IdentityHashMap;

/** Organic templates are baked into normal terrain chunks and atlas batches; textures remain host-owned. */
public final class OrganicPropRenderer {
    private final IdentityHashMap<TextureRegion,Material> samples=new IdentityHashMap<>();
    private Object atlas;
    private long submissions;
    public long submissions(){return submissions;}
    boolean append(Tile tile,NativeAtlasMesh.Builder batch,float x,float z,float width,float height){
        var kind=NaturalProps.kind(tile.block());if(kind==null)return false;
        TextureRegion region=NativeRegions.wall(tile.block(),tile);if(!NativeAtlasMesh.valid(region))return false;
        if(atlas!=Core.atlas){samples.clear();atlas=Core.atlas;}
        int seed=tile.pos()*0x9e3779b9+tile.blockID()*0x85ebca6b;seed^=seed>>>16;
        Material material=samples.get(region);
        if(material==null){material=new Material(region);samples.put(region,material);}
        // Vegetation alone grows visually around its native tile/base anchor; physical queries
        // still use original dimensions. The same cached templates and atlas chunks are reused.
        float angle=(seed&1023)*(360f/1024f),scale=.91f+((seed>>>10)&31)*(.09f/31f);
        float visualWidth=VegetationVisualScale.dimension(kind,width*scale),visualHeight=VegetationVisualScale.dimension(kind,height);
        batch.template(region,material.template(kind,seed&7),x,0,z,visualWidth,visualHeight,visualWidth,angle,material.center[0],material.center[1]);
        submissions++;return true;
    }
    /** Sparse tree art is color data for closed surfaces, not an alpha cookie punched through every face. */
    private static final class Material{
        final float[] center=new float[2],opaque;
        final float[][] templates=new float[NaturalProps.Kind.values().length*8][];
        Material(TextureRegion region){
            if(region instanceof AtlasRegion ar){
                var pix=Core.atlas.getPixmap(ar);int count=0;
                for(int y=0;y<pix.height;y++)for(int x=0;x<pix.width;x++)if(pix.getA(x,y)>=240)count++;
                int step=Math.max(1,(count+2047)/2048),kept=(count+step-1)/step;
                opaque=new float[Math.max(1,kept)*2];int i=0,seen=0;
                // These UVs go through the atlas builder's half-texel inset: x/(w-1)
                // maps exactly to texel x's center, even beside sparse transparent pixels.
                for(int y=0;y<pix.height;y++)for(int x=0;x<pix.width;x++)if(pix.getA(x,y)>=240&&seen++%step==0){opaque[i++]=x/(float)Math.max(1,pix.width-1);opaque[i++]=y/(float)Math.max(1,pix.height-1);}
                if(count==0){opaque[0]=opaque[1]=.5f;}
            }else opaque=new float[]{.5f,.5f};
            int near=nearest(.5f,.5f);center[0]=opaque[near];center[1]=opaque[near+1];
        }
        int nearest(float u,float v){int best=0;double distance=Double.POSITIVE_INFINITY;
            for(int i=0;i<opaque.length;i+=2){double dx=u-opaque[i],dy=v-opaque[i+1],d=dx*dx+dy*dy;if(d<distance){distance=d;best=i;}}
            return best;
        }
        float[] template(NaturalProps.Kind kind,int variant){
            int slot=kind.ordinal()*8+variant;float[] value=templates[slot];if(value!=null)return value;
            value=OrganicPropGeometry.template(kind,variant).clone();
            for(int i=0;i<value.length;i+=18){
                float u=(value[i+3]+value[i+9]+value[i+15])/3,v=(value[i+4]+value[i+10]+value[i+16])/3;
                int near=nearest(u<0?.5f:u,v<0?.5f:v);
                for(int j=0;j<3;j++){value[i+j*6+3]=opaque[near];value[i+j*6+4]=opaque[near+1];}
            }
            return templates[slot]=value;
        }
    }
    public void clear(){samples.clear();atlas=null;}
}
