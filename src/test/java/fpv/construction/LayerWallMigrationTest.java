package fpv.construction;

import arc.Core;
import arc.mock.MockSettings;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.core.ContentLoader;

public final class LayerWallMigrationTest {
    private static int checks;
    public static void main(String[] args){
        Core.settings=new MockSettings();Vars.content=new ContentLoader();Vars.content.createBaseContent();
        LayerWorld world=new LayerWorld(LayerCoordinates.DEFAULT);
        var profile=new LayerComponent.SupportProfile(7,160);
        var wall=new LayerComponent(1,LayerComponent.Kind.BLOCK,10,10,1,"titanium-wall-large",2,0,1,LayerComponent.Mode.STRUCTURAL,70,100,8,1.4,profile,new byte[]{1,2},new byte[]{3,4});world.add(wall);
        world.add(new LayerComponent(2,LayerComponent.Kind.PLATFORM,10,10,3,"fpv-platform",1,0,1,LayerComponent.Mode.STRUCTURAL,80,100,1,.16,LayerComponent.SupportProfile.NONE,null,null));
        var obstructed=new LayerComponent(3,LayerComponent.Kind.BLOCK,20,20,1,"titanium-wall-large",2,0,1,LayerComponent.Mode.STRUCTURAL,70,100,8,1.4,profile,null,null);world.add(obstructed);
        world.add(new LayerComponent(4,LayerComponent.Kind.BLOCK,20,20,2,"conveyor",1,0,1,LayerComponent.Mode.STRUCTURAL,100,100,1,.2,LayerComponent.SupportProfile.NONE,null,null));
        var result=LayerWallMigration.upgradeLegacyWalls(world);require(result.upgraded()==1&&result.occupied()==1,"empty cap space upgrades; occupied next-floor room keeps old wall shape");
        var upgraded=world.get(1);require(Math.abs(upgraded.height()-2.8)<1e-9,"exposed legacy wall becomes a two-storey physical column");
        require(upgraded.health()==70&&upgraded.mass()==8&&upgraded.support().equals(profile)&&Arrays.equals(upgraded.configuration(),wall.configuration())&&Arrays.equals(upgraded.state(),wall.state()),"health, load, recipe/config and native state unchanged");
        require(world.get(3).equals(obstructed)&&world.get(4).layer()==2&&world.coordinates().plane(2)==2.8,"occupied wall and all floor positions preserved");
        var hit=world.sweep(8,10,3.05,10,10,3.05,.01,.01);require(hit!=null&&hit.component().id()==1,"collision uses upgraded physical cap");
        try(var solver=new StructuralSolver(world,(x,y,team)->x>=10&&x<=11&&y>=10&&y<=11?new StructuralSolver.Foundation(99,team,1.4,7,1000):null)){
            solver.solveDirty();require(solver.support(1).supported()&&solver.support(2).supported(),"taller cap retains column-to-platform structural support");
        }
        LayerWorld loaded=LayerCodec.decode(LayerCodec.encode(world));require(loaded.snapshot().equals(world.snapshot()),"mixed old/new walls save and reload with exact chosen geometry");
        var cap=new LayerComponent(5,LayerComponent.Kind.BLOCK,30,30,1,"titanium-wall-large",2,0,1,LayerComponent.Mode.STRUCTURAL,70,100,8,1.8,profile,new byte[]{8},new byte[]{9});
        loaded.add(cap);loaded.add(new LayerComponent(6,LayerComponent.Kind.PLATFORM,30,30,2,"fpv-platform",1,0,1,LayerComponent.Mode.STRUCTURAL,100,100,1,.16,LayerComponent.SupportProfile.NONE,null,null));
        loaded.add(new LayerComponent(7,LayerComponent.Kind.BLOCK,30,30,2,"graphite-press",2,0,1,LayerComponent.Mode.STRUCTURAL,100,100,4,.5,LayerComponent.SupportProfile.NONE,null,null));
        LayerWallMigration.upgradeLegacyWalls(loaded);
        require(loaded.get(5).height()==1.4&&loaded.get(7).layer()==2,"intersecting old decorative cap trims without moving its occupied upper room");
        require(Arrays.equals(loaded.get(5).state(),cap.state())&&Arrays.equals(loaded.get(5).configuration(),cap.configuration()),"repair preserves original wall state");
        require(LayerWallMigration.upgradeLegacyWalls(loaded).upgraded()==0,"migration is idempotent and never repeatedly stretches walls");
        System.out.println("LayerWallMigration: "+checks+" assertions passed.");
    }
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
