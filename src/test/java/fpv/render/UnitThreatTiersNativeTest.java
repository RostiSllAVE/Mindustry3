package fpv.render;

import arc.Core;
import arc.mock.*;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.FileTree;

/** Real installed native content in a fresh JVM, with no graphics, save profile or network. */
public final class UnitThreatTiersNativeTest {
    public static void main(String[] args){
        Core.app=new MockApplication();Core.files=new MockFiles();Core.settings=new MockSettings();
        Core.bundle=I18NBundle.createEmptyBundle();Core.audio=new MockAudio();Core.graphics=new MockGraphics();
        Vars.headless=true;Vars.tree=new FileTree();Vars.content=new ContentLoader();
        Vars.content.createBaseContent();
        System.out.println(UnitThreatTierSmokeChecks.verify());
    }
}
