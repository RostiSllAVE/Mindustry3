"""Build reproducible isolated measurement jars; never install into a player profile."""
from pathlib import Path
import hashlib,json,sys,zipfile,subprocess
root=Path(__file__).resolve().parents[1]
label=sys.argv[1]
folder=root/'local/camera-profile-staging'/label
folder.mkdir(parents=True,exist_ok=False)
sources=sorted((root/'src/main/java').rglob('*.java'))+sorted((root/'build/generated').rglob('*.java'))
classes=folder/'classes';classes.mkdir()
args=['--release','17','-encoding','UTF-8','-classpath',r'D:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar'+';'+str(root/'lib/jna-5.19.1.jar'),'-d',str(classes)]+[str(p) for p in sources]
argfile=folder/'compile.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args))
subprocess.run([r'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin\javac.exe','@'+str(argfile)],check=True)
base=root/'local/backups/before-camera-perf-0.18.27/mindustry-fpv.jar'
manifest={'acceptedBaseSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'sourceHash':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
for native in [False,True]:
    target=folder/('native-probe.jar' if native else 'profiled-mod.jar')
    with zipfile.ZipFile(base) as old,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as new:
        for e in old.infolist():
            if e.filename.startswith('fpv/') and e.filename.endswith('.class'):continue
            data=old.read(e)
            if native and e.filename=='mod.hjson':data=data.decode().replace('main: fpv.FpvMod','main: fpv.CameraProfileProbeMod').encode()
            new.writestr(e,data)
        for p in classes.rglob('*.class'):new.write(p,p.relative_to(classes).as_posix())
    manifest[target.name]={'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size}
(folder/'manifest.json').write_text(json.dumps(manifest,indent=2));print(folder)
