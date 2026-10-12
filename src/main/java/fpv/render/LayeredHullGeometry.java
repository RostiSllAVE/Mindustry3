package fpv.render;

import java.util.ArrayList;

/** Existing alpha extrusion assembled into one cached hull mesh; no circles, boxes or per-frame generation. */
public final class LayeredHullGeometry {
    private LayeredHullGeometry(){}
    public static SpriteExtrusionGeometry.Result generate(byte[] alpha,int width,int height,LayeredHullProfile profile,
            int maxSide,int minSide,int threshold,double coverage,int maxVertices){
        if(alpha==null||width<=0||height<=0||(long)width*height!=alpha.length||profile==null)
            throw new IllegalArgumentException("Native silhouette and hull profile required");
        if(profile.optimized())return OptimizedHullGeometry.generate(alpha,width,height,profile,maxSide,minSide,threshold,coverage,maxVertices);
        var shapes=new ArrayList<SpriteExtrusionGeometry.Result>(profile.bands().size());
        int vertices=0,tops=0,bottoms=0,sides=0,cells=0;
        for(var band:profile.bands()){
            byte[] mask=band.scale()==1?alpha:inset(alpha,width,height,band.scale(),threshold);
            var shape=SpriteExtrusionGeometry.generate(mask,width,height,maxSide,minSide,threshold,coverage,maxVertices/profile.bands().size());
            shapes.add(shape);vertices+=shape.vertexCount();tops+=shape.topQuads();bottoms+=shape.bottomQuads();sides+=shape.sideQuads();cells+=shape.opaqueCells();
        }
        if(vertices>maxVertices)throw new IllegalArgumentException("Layered hull exceeds shared vertex budget");
        float[] data=new float[vertices*SpriteExtrusionGeometry.STRIDE];int offset=0;double bottom=0,total=profile.depth();
        for(int layer=0;layer<shapes.size();layer++){
            var band=profile.bands().get(layer);float[] original=shapes.get(layer).vertices();
            for(int v=0;v<original.length;v+=SpriteExtrusionGeometry.STRIDE){
                System.arraycopy(original,v,data,offset,SpriteExtrusionGeometry.STRIDE);
                data[offset+1]=(float)((bottom+(original[v+1]+.5)*band.height())/total-.5);
                offset+=SpriteExtrusionGeometry.STRIDE;
            }
            bottom+=band.height();
        }
        var top=shapes.get(shapes.size()-1);
        return new SpriteExtrusionGeometry.Result(data,top.gridWidth(),top.gridHeight(),cells,tops,bottoms,sides);
    }
    /** Scaled lower silhouette is intersected with the original mask, so holes/empty top pixels remain empty. */
    static byte[] inset(byte[] original,int width,int height,float scale,int threshold){
        byte[] result=new byte[original.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int index=x+y*width;if((original[index]&255)<threshold)continue;
            int sx=(int)Math.floor((x+.5-width*.5)/scale+width*.5),sy=(int)Math.floor((y+.5-height*.5)/scale+height*.5);
            if(sx>=0&&sx<width&&sy>=0&&sy<height&&(original[sx+sy*width]&255)>=threshold)result[index]=original[index];
        }
        return result;
    }
}
