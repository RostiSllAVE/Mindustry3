from pathlib import Path
import hashlib,json,zipfile,sys
root=Path(__file__).resolve().parents[1]
label,source=sys.argv[1:3]
folder=root/'local'/'visual-profile-staging'/label
folder.mkdir(parents=True,exist_ok=False)
source=Path(source)
if not source.is_absolute():source=root/source
changes={n:(root/'local'/'visual-012-classes'/n).read_bytes() for n in ['fpv/SmokeHarness.class','fpv/VisualBenchmarkHarness.class','fpv/profile/VisualGpuTimer.class']}
target=folder/'profiled-mod.jar'
with zipfile.ZipFile(source) as old,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as new:
    for info in old.infolist():
        if info.filename not in changes:new.writestr(info,old.read(info))
    for name,data in changes.items():new.writestr(name,data)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest={'originalJar':str(source),'originalSha256':sha(source),'overlayJar':str(target),'overlaySha256':sha(target),'overlayClassSha256':{n:hashlib.sha256(b).hexdigest() for n,b in changes.items()},'scope':'Exact production jar with three lab-only entry/profiler classes; no renderer, physics, or assets replaced.'}
(folder/'manifest.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps(manifest,indent=2))
