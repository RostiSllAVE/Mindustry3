"""Exercise the actual Arc settings writer against disposable copies; never launch a game."""
from pathlib import Path
import hashlib, json, subprocess, uuid

root=Path(__file__).resolve().parents[1]
folder=root/'local/presentation-settings-tests'/uuid.uuid4().hex
folder.mkdir(parents=True)
java=Path(r'D:\Linux\tools\jdk17\jdk-17.0.20.1+1\bin')
host=Path(r'D:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar')
classes=folder/'classes';classes.mkdir()
temporary=folder/'tmp';temporary.mkdir()
subprocess.run([str(java/'javac.exe'),'-J-Djava.io.tmpdir='+str(temporary),'--release','17','-encoding','UTF-8','-cp',str(host),'-d',str(classes),str(root/'tools/ProfileVideoSettings.java')],check=True)
fixture=folder/'project';profile=fixture/'play/saves';profile.mkdir(parents=True)
original=(root/'play/saves/settings.bin').read_bytes();(profile/'settings.bin').write_bytes(original)
results=[]
def invoke(mode, target=None, valid=True):
    if target is None: target=profile
    p=subprocess.run([str(java/'java.exe'),'-Djava.io.tmpdir='+str(temporary),'-cp',str(classes)+';'+str(host),'ProfileVideoSettings',str(fixture),str(target),mode],capture_output=True,text=True)
    assert (p.returncode==0)==valid, (mode,p.stdout,p.stderr)
    results.append({'mode':mode,'expectedSuccess':valid,'exit':p.returncode,'output':p.stdout.strip()})
    return p.stdout
invoke('repair')
assert (profile.parent/'settings-before-presentation-fix.bin').read_bytes()==original
after=(profile/'settings.bin').read_bytes()
invoke('repair');assert (profile/'settings.bin').read_bytes()==after
invoke('windowed');windowed=(profile/'settings.bin').read_bytes()
invoke('repair');assert (profile/'settings.bin').read_bytes()==windowed
invoke('restore');assert json.loads((profile.parent/'presentation-mode.json').read_text())['mode']=='restore'
after_restore=(profile/'settings.bin').read_bytes()
invoke('repair');assert (profile/'settings.bin').read_bytes()==after_restore
outside=fixture/'unrelated/saves';outside.mkdir(parents=True);(outside/'settings.bin').write_bytes(original)
invoke('repair',outside,False);assert (outside/'settings.bin').read_bytes()==original
bad_fixture=fixture/'local/camera-profile/bad/saves';bad_fixture.mkdir(parents=True);(bad_fixture/'settings.bin').write_bytes(b'broken')
invoke('fullscreen',bad_fixture,False);assert (bad_fixture/'settings.bin').read_bytes()==b'broken'
new_fixture=folder/'empty';new_profile=new_fixture/'play/saves';new_profile.mkdir(parents=True)
fixture=new_fixture;profile=new_profile
invoke('repair');invoke('restore')
(folder/'results.json').write_text(json.dumps(results,indent=2))
print(json.dumps({'status':'passed','checks':len(results),'results':str(folder/'results.json'),'playerSourceUnchanged':(root/'play/saves/settings.bin').read_bytes()==original}))
