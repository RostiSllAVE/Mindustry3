package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.math.geom.Point2;
import arc.util.I18NBundle;
import java.io.*;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.units.UnitFactory;

/** Untrusted network values are bounded before any native configuration handler executes. */
public final class LayerConfigurationSafetyTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.set(GameState.State.playing);
        for(Block block:new Block[]{Blocks.airFactory,Blocks.unitCargoLoader,Blocks.unitCargoUnloadPoint,Blocks.payloadMassDriver,Blocks.itemBridge,Blocks.constructor,Blocks.canvas})block.init();
        var owner=new LayerBuildingAdapter(null);var factory=(UnitFactory.UnitFactoryBuild)owner.create(1,10,10,10,Blocks.airFactory,Team.sharded,0);
        require(owner.configureNative(1,0),"valid native factory plan accepted");int plan=factory.currentPlan;
        for(int index:new int[]{-2,999999,Integer.MIN_VALUE,Integer.MAX_VALUE})require(!owner.configureNative(1,index)&&factory.currentPlan==plan,"invalid plan rejected without mutating live factory");
        require(owner.configureNative(1,-1)&&factory.currentPlan==-1,"native -1 intentionally disables production");factory.updateTile();
        require(!owner.configureNative(1,UnitTypes.eclipse)&&factory.currentPlan==-1,"unsupported unit cannot become a hidden factory plan");
        var loader=owner.create(2,20,20,10,Blocks.unitCargoLoader,Team.sharded,0);var unload=owner.create(3,25,20,10,Blocks.unitCargoUnloadPoint,Team.sharded,0);
        require(!owner.configureNative(2,Items.copper),"cargo loader cannot accept an unload-point schema");require(owner.configureNative(3,Items.copper)&&unload.config()==Items.copper,"native unload item accepted");
        require(!owner.configureNative(3,Liquids.water)&&unload.config()==Items.copper,"wrong cargo content type is rejected without replacing filter");
        owner.create(4,30,30,10,Blocks.payloadMassDriver,Team.sharded,0);owner.create(5,40,30,10,Blocks.payloadMassDriver,Team.sharded,0);
        require(owner.configureNative(4,new Point2(10,0)),"ordinary same-floor relative link accepted");
        require(!owner.configureNative(4,new Point2(65546,0)),"relative link cannot wrap 16-bit coordinates to an unrelated destination");
        for(Point2 point:new Point2[]{new Point2(65536,0),new Point2(0,-65536),new Point2(Integer.MIN_VALUE,Integer.MAX_VALUE)}){
            reject(()->LayerConfiguration.encode(point));var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(4);out.writeInt(point.x);out.writeInt(point.y);reject(()->LayerConfiguration.decode(bytes.toByteArray()));
        }
        require(LayerConfiguration.decode(LayerConfiguration.encode(Integer.MAX_VALUE)).equals(Integer.MAX_VALUE),"codec preserves integers; each native block enforces its own schema");
        byte[] bytes=new byte[12288];Arrays.fill(bytes,(byte)3);require(Arrays.equals(bytes,(byte[])LayerConfiguration.decode(LayerConfiguration.encode(bytes))),"maximum bounded binary config has exact roundtrip");
        reject(()->LayerConfiguration.encode(new byte[12289]));reject(()->LayerConfiguration.decode(new byte[]{7,127,-1}));reject(()->LayerConfiguration.decode(new byte[]{2,0,0,0,1,0}));reject(()->LayerConfiguration.decode(new byte[]{99}));
        var canvas=owner.create(6,45,45,10,Blocks.canvas,Team.sharded,0);require(!owner.configureNative(6,new byte[12288])&&!owner.configureNative(1,new byte[]{1}),"bounded binary payload still requires correct native target and exact length");
        require(canvas!=null&&loader!=null&&Groups.build.isEmpty(),"configuration never installs upper objects in global XY group");owner.clear();Groups.clear();System.out.println("LayerConfigurationSafety: "+checks+" assertions passed.");
    }
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid configuration accepted");}catch(IllegalArgumentException expected){checks++;}}
}
