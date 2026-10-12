package fpv.render;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.Gl;
import arc.graphics.Color;
import fpv.construction.LayerRuntime;
import java.util.*;
import mindustry.gen.Building;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;

/** Native display command processing, cached live texture faces, and ownership-safe GPU cleanup. */
final class LayerDisplayRenderer implements AutoCloseable {
    private final IdentityHashMap<Building,TextureRegion> textures=new IdentityHashMap<>();
    private final Set<Building> present=Collections.newSetFromMap(new IdentityHashMap<>());
    private LayerRuntime runtime;
    private java.util.function.Consumer<arc.graphics.Texture> released=texture->{};
    void onRelease(java.util.function.Consumer<arc.graphics.Texture> callback){released=callback;}
    private final Color oldColor=new Color();
    private final java.nio.FloatBuffer oldClear=arc.util.Buffers.newFloatBuffer(4);
    void prepare(LayerRuntime value){
        if(value!=runtime){close();runtime=value;}if(value==null)return;
        prepareRegistry(value.nativeBuildings());
    }
    void prepareRegistry(fpv.construction.LayerBuildingAdapter registry){
        boolean depth=Gl.isEnabled(Gl.depthTest),scissor=Gl.isEnabled(Gl.scissorTest),cull=Gl.isEnabled(Gl.cullFace);
        boolean blend=Gl.isEnabled(Gl.blend);int sr=Gl.getInt(Gl.blendSrcRgb),dr=Gl.getInt(Gl.blendDstRgb),sa=Gl.getInt(Gl.blendSrcAlpha),da=Gl.getInt(Gl.blendDstAlpha);
        int er=Gl.getInt(Gl.blendEquationRgb),ea=Gl.getInt(Gl.blendEquationAlpha),program=Gl.getInt(Gl.currentProgram);
        oldClear.clear();Gl.getFloatv(Gl.colorClearValue,oldClear);
        oldColor.set(Draw.getColor());float mix=Draw.getMixColorPacked();
        Gl.disable(Gl.depthTest);Gl.disable(Gl.scissorTest);Gl.disable(Gl.cullFace);
        try{
        present.clear();
        for(var entry:registry.entries()){
            Building building=entry.building();
            if(building.dead||!(building instanceof LogicDisplayBuild||building instanceof CanvasBuild))continue;
            present.add(building);TextureRegion region=textures.computeIfAbsent(building,b->new TextureRegion());
            if(building instanceof LogicDisplayBuild display){
                display.ensureBuffer();display.processCommands();Draw.flush();display.getBufferRegion(region);
            }else if(building instanceof CanvasBuild canvas){canvas.updateTexture();if(canvas.texture!=null)region.set(canvas.texture);}
        }
        var iterator=textures.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(!present.contains(entry.getKey())){release(entry.getKey());iterator.remove();}}
        }finally{
            Draw.color(oldColor);Draw.mixcol(mix);enabled(Gl.depthTest,depth);enabled(Gl.scissorTest,scissor);enabled(Gl.cullFace,cull);
            Gl.blendEquationSeparate(er,ea);Gl.blendFuncSeparate(sr,dr,sa,da);enabled(Gl.blend,blend);Gl.useProgram(program);
            Gl.clearColor(oldClear.get(0),oldClear.get(1),oldClear.get(2),oldClear.get(3));
        }
    }
    private static void enabled(int cap,boolean value){if(value)Gl.enable(cap);else Gl.disable(cap);}
    TextureRegion texture(Building building){return textures.get(building);}
    private void release(Building building){
        if(building instanceof LogicDisplayBuild display&&display.buffer!=null){released.accept(display.buffer.getTexture());display.buffer.dispose();display.buffer=null;}
        else if(building instanceof CanvasBuild canvas&&canvas.texture!=null){released.accept(canvas.texture);canvas.texture.dispose();canvas.texture=null;}
    }
    @Override public void close(){for(Building building:textures.keySet())release(building);textures.clear();present.clear();runtime=null;}
}
