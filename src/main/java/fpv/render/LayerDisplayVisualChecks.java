package fpv.render;

import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.util.ScreenUtils;
import fpv.construction.LayerBuildingAdapter;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.DisplayCmd;
import mindustry.world.blocks.logic.LogicDisplay;
import mindustry.world.blocks.logic.CanvasBlock;

public final class LayerDisplayVisualChecks {
    private LayerDisplayVisualChecks(){}
    public static String run(){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Explicit isolated lab required");
        var registry=new LayerBuildingAdapter(null);var view=new LayerDisplayRenderer();FrameBuffer outer=new FrameBuffer(80,80,true);int checks=0;
        try{
            var display=(LogicDisplay.LogicDisplayBuild)registry.create(1,10,10,1,Blocks.logicDisplay,Team.sharded,0);
            var canvas=(CanvasBlock.CanvasBuild)registry.create(2,16,10,1,Blocks.canvas,Team.sharded,0);
            display.commands.addLast(DisplayCmd.get(LogicDisplay.commandClear,210,20,30,0,0,0));
            canvas.setPixel(0,1);Draw.flush();outer.begin();
            try{
                int fbo=Gl.getInt(Gl.framebufferBinding);Gl.enable(Gl.depthTest);view.prepareRegistry(registry);
                require(Gl.getInt(Gl.framebufferBinding)==fbo&&Gl.isEnabled(Gl.depthTest),"nested native display restores framebuffer/depth state");checks++;
                require(display.commands.isEmpty()&&view.texture(display).texture==display.buffer.getTexture(),"native commands feed the actual cached 3D face texture");checks++;
                display.buffer.begin();try{byte[] rgba=ScreenUtils.getFrameBufferPixels(0,0,1,1,false);require((rgba[0]&255)>180&&(rgba[1]&255)<40,"native clear command renders actual red pixels");checks++;}finally{display.buffer.end();}
                require(canvas.texture!=null&&view.texture(canvas).texture==canvas.texture,"canvas native data has a GPU texture");checks++;
                var previous=display.buffer;view.prepareRegistry(registry);require(previous==display.buffer,"unchanged display reuses framebuffer");checks++;
                registry.remove(1);registry.remove(2);view.prepareRegistry(registry);require(display.buffer==null&&canvas.texture==null,"removed upper displays release GPU buffers");checks++;
            }finally{outer.end();}
            require(Gl.getError()==0,"display/canvas GL operations remain valid");checks++;
            return "PASS: "+checks+" GPU checks; native display pixels, canvas texture, framebuffer/depth restore, cache reuse and cleanup";
        }finally{view.close();registry.clear();outer.dispose();}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Upper display GPU: "+message);}
}
