package fpv.render;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;

/** Actual native water classes and both vanilla progression lines; no GPU dependency. */
public final class NavalNativeModelTest {
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.state=new GameState();Vars.net=new mindustry.net.Net(null);Vars.content=new ContentLoader();Vars.content.createBaseContent();Groups.init();Vars.content.init();
        var renderer=new NavalUnitRenderer();var unit=UnitTypes.risso.create(Team.sharded);int checks=0;
        assert unit instanceof WaterMovec&&!unit.type.flying&&!unit.type.canBoost;checks++;
        assert unit.mounts.length==3&&unit.mounts[0].weapon.x==-unit.mounts[1].weapon.x&&unit.mounts[2].weapon.x==0;checks++;
        var p=renderer.profile(unit);assert p==renderer.profile(UnitTypes.risso.create(Team.sharded));checks++;
        assert Math.abs(p.body().bodyThickness()-.44)<1e-6&&p.body().bodyScale()==1;checks++;
        assert ProceduralUnitModelProfile.forType(unit.type)==null;checks++;
        for(var type:Vars.content.units()){
            var candidate=type.create(Team.sharded);boolean ship=NavalHullProfile.types().contains(type);assert NavalUnitRenderer.supports(candidate)==ship;checks++;
            if(!ship){assert renderer.profile(candidate)==null;checks++;}
            else{
                var profile=renderer.profile(candidate);assert candidate instanceof WaterMovec&&!type.flying&&!type.canBoost;checks++;
                assert profile==renderer.profile(type.create(Team.sharded))&&profile.body().bodyScale()==1;checks++;
                assert profile.body().hull().optimized()&&profile.body().hull().bands().size()==3&&profile.sections().size()<=3;checks++;
                assert Math.abs(profile.body().hull().depth()-profile.body().bodyThickness())<1e-6;checks++;
                assert ProceduralUnitModelProfile.forType(type)==null;checks++;
                assert profile.minimumPitch()>=-26&&profile.maximumPitch()<=85&&profile.keel()<0&&profile.deck()>0;checks++;
                assert Math.abs(profile.waterline(.2,.2,0,true))<1e-7;checks++;
            }
        }
        assert unit.type.speed==1.1f&&unit.type.health==280&&unit.type.hitSize==10&&unit.type.drag==.13f;checks++;
        // Native UnitType.init doubles the alternating mirrored pair's per-mount reload.
        assert unit.mounts[0].weapon.reload==26&&unit.mounts[2].weapon.reload==25;checks++;
        assert unit.mounts[0].weapon.bullet.damage==9&&unit.mounts[2].weapon.bullet.damage==12;checks++;
        assert renderer.hullDraws()==0&&renderer.weaponDraws()==0&&NavalUnitRenderer.supports(null)==false;checks++;
        assert NavalHullProfile.types().size()==10;checks++;
        var attack=java.util.List.of(UnitTypes.risso,UnitTypes.minke,UnitTypes.bryde,UnitTypes.sei,UnitTypes.omura);
        for(int i=1;i<attack.size();i++){assert NavalHullProfile.forType(attack.get(i)).body().bodyThickness()>NavalHullProfile.forType(attack.get(i-1)).body().bodyThickness();checks++;}
        System.out.println("NavalNativeModelTest: "+checks+" native factory/mount/cache/ten-ship dispatch/unchanged stats checks passed");
    }
}
