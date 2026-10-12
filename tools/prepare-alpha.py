"""Prepare local Alpha assets from the accepted binary; never rebuild, publish or alter refs."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = '556df2a8f2f3a7dc8dac2392bc825c39235c8cb5'
HASH = 'd4f136ab7803966970ab50ef0d0150d895514aebff26dd343bd674c729582bc0'
NAME = 'mindustry-fpv-0.18.27.jar'
OUT = ROOT / 'dist/alpha-1.0.0'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def git(*args):
    return subprocess.check_output(['git', '-c', 'safe.directory='+ROOT.as_posix(), *args], cwd=ROOT)

def archive(path, files):
    # Stable ZIP metadata for reproducible release-preparation outputs.
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 12, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)

def main():
    branch = git('branch', '--show-current').decode().strip()
    if branch != 'Alpha-1.0.0':
        raise SystemExit('Run on the existing Alpha-1.0.0 release branch; no branch is switched automatically.')
    subprocess.run(['git', '-c', 'safe.directory='+ROOT.as_posix(), 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
    jar = (ROOT/'dist'/NAME).read_bytes()
    if digest(jar) != HASH or len(jar) != 4140308:
        raise SystemExit('The accepted JAR changed; release preparation stopped.')
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        if z.testzip() is not None: raise SystemExit('Invalid JAR CRC')
        for item in z.infolist():
            if not item.filename.startswith(('fpv/', 'com/sun/jna/', 'licenses/', 'design/')) and item.filename != 'mod.hjson':
                raise SystemExit('Unexpected package entry: '+item.filename)
        entries = {item.filename: digest(z.read(item)) for item in z.infolist()}
        assert 'version: 0.18.27' in z.read('mod.hjson').decode()
    OUT.mkdir(parents=True, exist_ok=True)
    common = {name: (ROOT/name).read_bytes() for name in ('README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md')}
    common.update({'licenses/'+p.name:p.read_bytes() for p in sorted((ROOT/'licenses').glob('*.txt'))})
    for name in ('ALPHA-1.0.0.md', 'BUILDING.md', 'PUBLICATION-READINESS.md', 'VALIDATION.json'):
        common['docs/releases/'+name] = (ROOT/'docs/releases'/name).read_bytes()
    common['docs/performance/camera-profiling.json'] = (ROOT/'docs/performance/camera-profiling.json').read_bytes()
    common['Launch-Alpha.ps1'] = (ROOT/'tools/Launch-Alpha.ps1').read_bytes()
    source = dict(common)
    # Strict path selection: no working-tree binaries, Git identity data or local profiles.
    with zipfile.ZipFile(io.BytesIO(git('archive', '--format=zip', BASE, 'src', 'design', 'tools/build.py', 'tools/preflight.py'))) as z:
        for name in z.namelist():
            if not name.endswith('/'): source[name] = z.read(name)
    helper = ROOT/'docs/releases/provenance/legacy-helper/fpv/render/NativeLimbRenderer.java'
    source['provenance/legacy-helper/fpv/render/NativeLimbRenderer.java'] = helper.read_bytes()
    source_manifest = {'gameplayCommit':BASE, 'testedJarSha256':HASH,
        'legacyHelper':'Documented reconstruction; compile separately, never replace the outer renderer.',
        'files':{name:digest(data) for name,data in sorted(source.items())}}
    source['SOURCE-MANIFEST.json'] = (json.dumps(source_manifest, indent=2)+'\n').encode()
    source_path = OUT/'mindustry-fpv-0.18.27-corresponding-source.zip'
    archive(source_path, source)
    bundle = dict(common)
    bundle[NAME] = jar
    bundle['JAR-ENTRIES.json'] = (json.dumps({'sha256':HASH, 'entries':entries}, indent=2)+'\n').encode()
    bundle_path = OUT/'mindustry3-alpha-1.0.0-windows.zip'
    archive(bundle_path, bundle)
    # Loose JAR is for inspection only; publish the notice-bearing bundle instead.
    (OUT/NAME).write_bytes(jar)
    (OUT/'RELEASE-NOTES.md').write_bytes((ROOT/'docs/releases/ALPHA-1.0.0.md').read_bytes())
    manifest = {'label':'Alpha 1.0.0', 'internalVersion':'0.18.27', 'branch':branch,
        'gameplayCommit':BASE, 'preparationHead':git('rev-parse','HEAD').decode().strip(),
        'publishAssets':[bundle_path.name,source_path.name,'SHA256SUMS.txt'],
        'doNotPublishLooseJar':True, 'jar':{'name':NAME,'bytes':len(jar),'sha256':HASH,'entries':len(entries)},
        'archives':{p.name:{'bytes':p.stat().st_size,'sha256':digest(p.read_bytes())} for p in (bundle_path,source_path)},
        'status':'Local preparation only; see PUBLICATION-READINESS.md before any publication.'}
    (OUT/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    checksummed = [bundle_path,source_path,OUT/NAME,OUT/'RELEASE-NOTES.md',OUT/'release-manifest.json']
    (OUT/'SHA256SUMS.txt').write_text(''.join(digest(p.read_bytes())+'  '+p.name+'\n' for p in checksummed),encoding='ascii')
    # Verify complete ZIP contents and source-file checksums after writing.
    for path, expected in ((bundle_path,bundle),(source_path,source)):
        with zipfile.ZipFile(path) as z:
            assert z.testzip() is None
            assert set(z.namelist()) == set(expected)
            assert all(z.read(name)==data for name,data in expected.items())
    print(json.dumps(manifest,indent=2))

if __name__ == '__main__':
    main()
