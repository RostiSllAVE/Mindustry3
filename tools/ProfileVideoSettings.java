import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import java.nio.file.*;
import java.util.*;
import arc.util.serialization.Jval;

/** One-time, backed-up fullscreen repair; isolated experiments never touch the player's profile. */
public final class ProfileVideoSettings {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),profile=Path.of(args[1]).toAbsolutePath().normalize();
        String mode=args.length>2?args[2]:"vsync";
        boolean production=profile.equals(root.resolve("play/saves"));
        if(!(production||profile.startsWith(root.resolve("local/camera-profile")))||!profile.getFileName().toString().equals("saves"))throw new IllegalArgumentException("Named player profile or cloned camera profile only");
        if(production&&!Set.of("repair","windowed","restore").contains(mode))throw new IllegalArgumentException("Explicit player repair mode required");
        if(!Set.of("repair","windowed","restore","fullscreen","vsync").contains(mode))throw new IllegalArgumentException("Unknown presentation mode");
        Path file=profile.resolve("settings.bin"),backup=profile.getParent().resolve(production?"settings-before-presentation-fix.bin":"settings-before-present-sync.bin"),marker=profile.getParent().resolve("presentation-mode.json");
        if(mode.equals("repair")&&Files.exists(marker)){System.out.println("Presentation preference already initialized; keeping current user choice.");return;}
        if(mode.equals("restore")&&!Files.exists(marker))throw new IllegalStateException("No presentation repair to restore");
        if(Files.exists(file)&&!Files.exists(backup))Files.copy(file,backup);
        Core.app=new MockApplication();Core.files=new MockFiles();
        if(Files.exists(file)){Settings readable=new Settings();readable.loadValues(new Fi(file.toFile()));}
        Settings settings=new Settings();settings.setDataDirectory(new Fi(profile.toFile()));Core.settings=settings;settings.load();
        Map<String,Object> beforeValues=new HashMap<>();for(String key:settings.keys())beforeValues.put(key,settings.get(key,null));
        String key=mode.equals("vsync")?"vsync":"fullscreen";
        boolean before=settings.getBool(key,key.equals("vsync"));int cap=settings.getInt("fpscap",120);
        Jval policy=Files.exists(marker)?Jval.read(Files.readString(marker)):Jval.newObject();
        if(!Files.exists(marker)){policy.add("beforeFullscreen",Jval.valueOf(settings.getBool("fullscreen",false)));policy.add("hadFullscreen",Jval.valueOf(beforeValues.containsKey("fullscreen")));policy.add("backup",backup.toString());}
        boolean desired=!mode.equals("windowed");
        if(mode.equals("restore")){desired=policy.getBool("beforeFullscreen",false);if(policy.getBool("hadFullscreen",false))settings.put(key,desired);else{settings.remove(key);if(settings.keySize()==0)settings.put(key,desired);}}
        else settings.put(key,desired);
        settings.forceSave();
        Settings check=new Settings();check.setDataDirectory(new Fi(profile.toFile()));check.load();
        if(check.getBool(key,key.equals("vsync"))!=desired||check.getInt("fpscap",120)!=cap)throw new IllegalStateException("Presentation setting verification failed");
        Set<String> keys=new HashSet<>(beforeValues.keySet());for(String name:check.keys())keys.add(name);
        for(String name:keys)if(!name.equals(key)&&!Objects.deepEquals(beforeValues.get(name),check.get(name,null)))throw new IllegalStateException("Unrelated preference changed: "+name);
        policy.add("mode",mode);policy.add("updatedUtc",java.time.Instant.now().toString());
        if(production||Set.of("repair","windowed","restore").contains(mode))Files.writeString(marker,policy.toString(Jval.Jformat.formatted));
        String report="{\"key\":\""+key+"\",\"before\":"+before+",\"after\":"+desired+",\"fpsCap\":"+cap+",\"scope\":\"One native presentation setting; all other preferences verified unchanged\"}";
        Files.writeString(profile.getParent().resolve("presentation-setting-change.json"),report);
        System.out.println(report);
    }
}
