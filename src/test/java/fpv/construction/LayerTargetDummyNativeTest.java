package fpv.construction;

import arc.Core;
import arc.mock.*;
import arc.util.*;
import fpv.terrain.TerrainElevation;
import fpv.units.*;
import fpv.world.World3DAdapter;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.world.*;
import mindustry.world.blocks.defense.TargetDummy;

/** Native specialized target/DPS persists by layer identity rather than ground Tile RPC aliases. */
public final class LayerTargetDummyNativeTest {
    private static int checks;
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();Vars.content.createBaseContent();Vars.state=new GameState();Vars.net=new Net(null);Groups.init();
        Vars.world=new World();Vars.world.tiles=new Tiles(64,64);Vars.world.setGenerating(true);Vars.world.tiles.fill();Vars.emptyTile=new Tile(0,0);Vars.state.set(GameState.State.playing);Blocks.targetDummy.init();
        var altitude=new UnitAltitudeSystem((x,y)->0);var world=new World3DAdapter(new TerrainElevation(),(x,y)->0,altitude);var safety=new FactorySpawnSafety(world,altitude);var grid=new LayerWorld(LayerCoordinates.DEFAULT);
        var owner=new LayerBuildingAdapter(new LayerBuildingAdapter.Events(){@Override public boolean releaseUnit(Building source,Unit unit,double x,double y){return safety.releaseUpper(source,unit,x,y);}});world.setConstruction(new LayerPhysicalQueries(()->grid,(id,damage)->{},owner::building));
        Tile tile=Vars.world.tile(20,20);tile.updateBlockReference(Blocks.targetDummy);var lower=(TargetDummy.TargetDummyBuild)Blocks.targetDummy.newBuilding();tile.build=lower;lower.init(tile,Team.sharded,false,0);
        var upper=(TargetDummy.TargetDummyBuild)owner.create(1,20,20,10,Blocks.targetDummy,Team.sharded,0);grid.add(new LayerComponent(1,LayerComponent.Kind.BLOCK,20,20,10,Blocks.targetDummy.name,1,0,Team.sharded.id,LayerComponent.Mode.FREE,upper.health,upper.maxHealth,1,.25,LayerComponent.SupportProfile.NONE,null,null));
        upper.updateTile();require(upper.unit instanceof TargetDummyUnit&&upper.unit.isAdded(),"upper native dummy creates its real specialized target unit");
        TargetDummyUnit target=(TargetDummyUnit)upper.unit;require(target.building()==upper&&lower.unit==null,"upper tether spawn cannot modify same-XY ground dummy");
        require(altitude.unitState(target).z()>14,"native target unit has actual upper spawn altitude");
        target.rawDamage(40);target.rawDamage(60);require(upper.total==100&&upper.hits==2&&lower.total==0,"actual native target hits feed upper DPS counter only");
        float health=upper.health;upper.damage(1000);require(upper.health==health,"native target base retains its original damage immunity");
        int[] settings={1,1,7,180,Float.floatToIntBits(18)};require(owner.configureNative(1,settings),"original target dummy controls accepted on upper layer");upper.updateTile();
        require(target.team==Team.crux&&target.armor()==7&&target.hitSize==18,"native target team/armor/body size follow bounded configuration");
        require(Arrays.equals(settings,(int[])LayerConfiguration.decode(LayerConfiguration.encode(settings))),"target config exact network codec roundtrip");
        require(!owner.configureNative(1,new int[]{1,1,7,180,Float.floatToIntBits(Float.NaN)})&&upper.dummySize==18,"nonfinite target body configuration cannot enter 3D collision");
        byte[] save=owner.writeState(1);target.building(lower);upper.unit=null;owner.readState(1,save);owner.reconcilePayloadReferences();
        require(upper.unit==target&&target.building()==upper&&Groups.unit.size()==1,"saved target unit ID restores actual upper tether without duplicate spawn");
        require(Vars.world.tile(20,20).build==lower&&Groups.build.isEmpty(),"native target lifecycle preserves original Z0 building and groups");
        target.remove();owner.clear();safety.close();altitude.close();Groups.clear();System.out.println("LayerTargetDummyNative: "+checks+" assertions passed.");
    }
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
