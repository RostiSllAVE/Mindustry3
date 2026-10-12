"""Local reproducible build against the user's installed Mindustry; never bundles host classes."""
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import zipfile
from preflight import ROOT, run

run()
jdk = Path(os.environ.get("FPV_JDK", r"D:\Linux\tools\jdk17\jdk-17.0.20.1+1"))
host = Path(os.environ.get("FPV_MINDUSTRY", r"D:\SteamLibrary\steamapps\common\Mindustry")) / "jre/desktop.jar"
jna = ROOT / "lib/jna-5.19.1.jar"
for path in (jdk / "bin/javac.exe", host, jna):
    if not path.is_file():
        raise SystemExit(f"Missing required local dependency: {path}")
build = ROOT / "build"
classes = build / "classes"
classes.mkdir(parents=True, exist_ok=True)
sources = sorted((ROOT / "src/main/java").rglob("*.java")) + sorted((build / "generated").rglob("*.java"))
args = ["--release", "17", "-encoding", "UTF-8", "-classpath", str(host) + os.pathsep + str(jna), "-d", str(classes)] + [str(p) for p in sources]
argfile = build / "javac.args"
argfile.write_text("\n".join('"' + a.replace("\\", "/") + '"' for a in args), encoding="utf-8")
subprocess.run([str(jdk / "bin/javac.exe"), "@" + str(argfile)], check=True)
tests = sorted((ROOT / "src/test/java").rglob("*.java"))
if tests:
    testclasses = build / "test-classes"
    testclasses.mkdir(exist_ok=True)
    cp = str(classes) + os.pathsep + str(host) + os.pathsep + str(jna)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "17", "-encoding", "UTF-8", "-classpath", cp, "-d", str(testclasses)] + [str(p) for p in tests], check=True)
    for test in tests:
        name = test.relative_to(ROOT / "src/test/java").with_suffix("").as_posix().replace("/", ".")
        subprocess.run([str(jdk / "bin/java.exe"), "-ea", "-cp", str(testclasses) + os.pathsep + cp, name], check=True)
dist = ROOT / "dist"
dist.mkdir(exist_ok=True)
version = re.search(r"^version:\s*(\S+)", (ROOT / "src/main/resources/mod.hjson").read_text(encoding="utf-8"), re.M).group(1)
output = dist / ("mindustry-fpv-" + version + ".jar")
entries = []
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
    for base in (classes, ROOT / "src/main/resources"):
        for p in sorted(base.rglob("*")):
            if p.is_file():
                rel = p.relative_to(base).as_posix()
                z.write(p, rel)
    for p in (ROOT / "licenses").glob("*.txt"):
        z.write(p, "licenses/" + p.name)
    # Include only the redistributable dependency. The player's game JAR is compile-only.
    with zipfile.ZipFile(jna) as dep:
        for entry in dep.infolist():
            if entry.is_dir() or entry.filename.startswith("META-INF/"):
                continue
            z.writestr(entry.filename, dep.read(entry))
    for p in (ROOT / "design").glob("*.sheet.json"):
        z.write(p, "design/" + p.name)
with zipfile.ZipFile(output) as z:
    entries = [{"path":e.filename,"size":e.file_size} for e in z.infolist()]
    forbidden = [e["path"] for e in entries if e["path"].startswith(("arc/", "mindustry/")) or "Assembly-CSharp" in e["path"]]
    if forbidden:
        raise SystemExit("Unexpected game classes in package: " + str(forbidden))
manifest = {"fileName":output.name,"bytes":output.stat().st_size,"sha256":hashlib.sha256(output.read_bytes()).hexdigest(),"entries":entries,"status":"local prototype; not Melty validated"}
(dist / "package-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print(json.dumps({k:v for k,v in manifest.items() if k != "entries"}, indent=2))
