"""Windows release-launcher prerequisites, without launching the game or touching user profiles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--game-dir', required=True)
parser.add_argument('--java-home', required=True)
args = parser.parse_args()
fixture = Path(tempfile.mkdtemp(prefix='alpha launcher tests ', dir=root/'local'))
shutil.copy2(root/'tools/Launch-Alpha.ps1', fixture/'Launch-Alpha.ps1')
shutil.copy2(root/'dist/mindustry-fpv-0.18.27.jar', fixture/'mindustry-fpv-0.18.27.jar')
common = ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture/'Launch-Alpha.ps1')]
cases = []

def snapshot(profile):
    if not profile.exists(): return None
    return {p.relative_to(profile).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in profile.rglob('*') if p.is_file()}

def check(label, extra, success, expected_text, profile):
    before = snapshot(profile)
    options={'-GameDir':args.game_dir,'-JavaHome':args.java_home}
    options.update(dict(zip(extra[::2],extra[1::2])))
    environment=os.environ.copy()
    # Do not pass PowerShell 7's incompatible module paths into Windows PS 5.1.
    environment.pop('PSModulePath',None)
    command=common+[part for item in options.items() for part in item]+['-ValidateOnly']
    result = subprocess.run(command,capture_output=True,text=True,env=environment)
    passed = (result.returncode==0)==success and before==snapshot(profile) and expected_text in result.stdout+result.stderr
    cases.append({'case':label,'passed':passed,'exitCode':result.returncode,'profileUnchanged':before==snapshot(profile)})
    if not passed: print(result.stdout,result.stderr)

fresh = fixture/'fresh profile'
check('Spaces and default bundled JAR', ['-ProfileDirectory',str(fresh)],True,'PASS:',fresh)
check('Default bundle profile', [],True,'PASS:',fixture/'alpha-profile')
check('Missing Java rejected', ['-JavaHome',str(fixture/'absent'),'-ProfileDirectory',str(fresh)],False,'Required file missing',fresh)
check('Bundled stripped/non-17 runtime rejected', ['-JavaHome',str(Path(args.game_dir)/'jre'),'-ProfileDirectory',str(fresh)],False,'64-bit Java 17',fresh)
wrong = fixture/'wrong.jar'; wrong.write_bytes(b'not the accepted mod')
check('Wrong checksum rejected', ['-ModJar',str(wrong),'-ProfileDirectory',str(fresh)],False,'checksum does not match',fresh)
(fresh/'mods').mkdir(parents=True); (fresh/'mods/other.zip').write_bytes(b'preserve this archive')
check('Existing mods rejected without overwrite', ['-ProfileDirectory',str(fresh)],False,'fresh Alpha profile',fresh)
matching = fixture/'matching profile'; (matching/'mods').mkdir(parents=True)
shutil.copy2(fixture/'mindustry-fpv-0.18.27.jar', matching/'mods/mindustry-fpv-0.18.27.jar')
check('Matching existing mod accepted', ['-ProfileDirectory',str(matching)],True,'PASS:',matching)
check('Missing host rejected', ['-GameDir',str(fixture/'missing-game'),'-ProfileDirectory',str(fresh)],False,'Required file missing',fresh)
report = {'cases':cases,'passed':all(c['passed'] for c in cases),'physicalGameLaunch':False}
(root/'local/release-launcher-results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['passed'] else 1)
