package fpv.render;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.game.Team;
import mindustry.gen.*;

/** Native metadata, non-mutating profile cache, exterior topology and compatibility exclusions. */
public final class GreenHullNativeTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.state=new GameState();Vars.net=new mindustry.net.Net(null);Vars.content=new ContentLoader();Vars.content.createBaseContent();Groups.init();Vars.content.init();
        double previous=0;
        for(var type:new mindustry.type.UnitType[]{UnitTypes.nova,UnitTypes.pulsar,UnitTypes.quasar,UnitTypes.corvus}){
            var unit=type.create(Team.sharded);float speed=type.speed,health=type.health,hit=type.hitSize,boost=type.boostMultiplier;
            boolean eligibility=type.canBoost;int legs=type.legCount;
            var hull=GreenHullProfiles.forName(type.name,hit/Vars.tilesize);
            check(hull.optimized()&&hull.cameraReferenceDepth()==0,"one exterior mesh, existing camera");
            check(hull.depth()>previous,"tier-specific increasing depth");previous=hull.depth();
            byte[] alpha=new byte[40*32];for(int y=3;y<30;y++)for(int x=3;x<37;x++)if(y>10||x<15||x>24)alpha[x+y*40]=(byte)255;
            var optimized=LayeredHullGeometry.generate(alpha,40,32,hull,40,8,32,.13,65520);
            var stacked=LayeredHullGeometry.generate(alpha,40,32,new LayeredHullProfile(hull.bands(),hull.chassisScale(),0),40,8,32,.13,65520);
            check(optimized.vertexCount()<stacked.vertexCount()&&optimized.sideQuads()>4,"no redundant stacked caps");
            for(int i=0;i<optimized.vertices().length;i+=8){
                var v=optimized.vertices();check(Float.isFinite(v[i]+v[i+1]+v[i+2]),"finite exterior");
                if(v[i+4]>.9)check(v[i+1]==.5,"textured upper faces only on roof");
            }
            var profile=ProceduralUnitModelProfile.forType(type);
            if(profile!=null){
                check(profile.hull().equals(hull)&&profile.bodyScale()==1,"native silhouette and proportions");
                check(profile==ProceduralUnitModelProfile.forType(type),"instances share cached profile");
                check(profile.cameraTopOffset(.25,2,2,0,0)==0,"no eye offset change");
            }
            check(ProceduralUnitModelProfile.aimingProfile(type)==null,"previous muzzle and pitch solution preserved");
            check(type.speed==speed&&type.health==health&&type.hitSize==hit&&type.boostMultiplier==boost&&type.canBoost==eligibility&&type.legCount==legs,"no native gameplay mutation");
            check(type==UnitTypes.corvus?unit instanceof Legsc&&!(unit instanceof Mechc)&&legs==4&&!eligibility:unit instanceof Mechc&&eligibility,"native movement/boost capabilities");
        }
        check(ProceduralUnitModelProfile.forType(UnitTypes.vela).hull().equals(LayeredHullProfile.vela(3,.32f)),"Vela accepted hull untouched");
        check(ProceduralUnitModelProfile.forType(UnitTypes.toxopid)==null&&ProceduralUnitModelProfile.forType(UnitTypes.flare)==null,"other families unchanged");
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            boolean rejected=false;try{GreenHullProfiles.forName("nova",invalid);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid dimension rejected");
        }
        System.out.println("GreenHullNativeTest: "+checks+" checks, native capabilities/cache/topology/unchanged aiming and existing models");
    }
}
