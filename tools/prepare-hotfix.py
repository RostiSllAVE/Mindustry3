"""Package an already validated hotfix and exact public source; never publish or alter refs."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VERSION='0.18.28'
SHA='aa350793618674349c9b6d99784e365838d5b119227fd4d086f0174a916b1a82'

def digest(data):return hashlib.sha256(data).hexdigest()
def git(*args):return subprocess.check_output(['git','--no-optional-locks','-c','safe.directory='+ROOT.as_posix(),'-c','core.autocrlf=false',*args],cwd=ROOT)
def write_zip(path,files):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,10,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            z.writestr(info,data)

def main():
    if git('status','--porcelain=v1').strip():raise SystemExit('Commit the tested source before creating its corresponding-source package.')
    head=git('rev-parse','HEAD').decode().strip()
    jar_name='mindustry-fpv-'+VERSION+'.jar'
    jar=(ROOT/'dist'/jar_name).read_bytes()
    if digest(jar)!=SHA:raise SystemExit('Unexpected hotfix JAR; do not package an unverified build.')
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        assert z.testzip() is None
        required=['LICENSE','THIRD_PARTY_NOTICES.md','licenses/JNA-LICENSE.txt','licenses/JNA-APACHE-2.0.txt','licenses/LIBFFI-LICENSE.txt']
        assert all(p in z.namelist() for p in required)
        assert 'version: '+VERSION in z.read('mod.hjson').decode()
        assert not any(n.startswith(('arc/','mindustry/')) or 'Assembly-CSharp' in n for n in z.namelist())
        jar_entries={n:digest(z.read(n)) for n in z.namelist()}
    out=ROOT/'dist/alpha-1.0.1'
    if out.exists():raise SystemExit('Hotfix output directory already exists; preserve it and inspect before creating another package.')
    out.mkdir()
    source={}
    with zipfile.ZipFile(io.BytesIO(git('archive','--format=zip',head))) as z:
        for n in z.namelist():
            if n.endswith('/'):continue
            assert not n.startswith(('.git/','local/','play/','lab/','research/','build/','dist/','lib/'))
            assert not n.lower().endswith(('.jar','.zip','.class','.dll','.exe','.msav','.log','.etl','.jfr'))
            source[n]=z.read(n)
    source['SOURCE-MANIFEST.json']=(json.dumps({'version':VERSION,'sourceCommit':head,'jarSha256':SHA,'files':{n:digest(b) for n,b in sorted(source.items())}},indent=2)+'\n').encode()
    source_path=out/('mindustry-fpv-'+VERSION+'-corresponding-source.zip')
    write_zip(source_path,source)
    (out/jar_name).write_bytes(jar)
    (out/'RELEASE-NOTES.md').write_bytes((ROOT/'docs/releases/ALPHA-1.0.1-HOTFIX.md').read_bytes())
    (out/'JAR-ENTRIES.json').write_text(json.dumps(jar_entries,indent=2)+'\n')
    manifest={'label':'Alpha 1.0.1','version':VERSION,'sourceCommit':head,'branch':git('branch','--show-current').decode().strip(),'jar':{'name':jar_name,'bytes':len(jar),'sha256':SHA},'source':{'name':source_path.name,'bytes':source_path.stat().st_size,'sha256':digest(source_path.read_bytes())},'uploadAssets':[jar_name,source_path.name,'SHA256SUMS.txt'],'nativeImport':True,'oldAlphaPreserved':True,'published':False}
    (out/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    checksummed=[out/jar_name,source_path,out/'RELEASE-NOTES.md',out/'release-manifest.json',out/'JAR-ENTRIES.json']
    (out/'SHA256SUMS.txt').write_text(''.join(digest(p.read_bytes())+'  '+p.name+'\n' for p in checksummed),encoding='ascii')
    with zipfile.ZipFile(source_path) as z:
        assert z.testzip() is None and set(z.namelist())==set(source)
        assert all(z.read(n)==b for n,b in source.items())
    print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
