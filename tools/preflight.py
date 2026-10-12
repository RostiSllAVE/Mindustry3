"""Validate the explicitly scoped prototype design before any build and generate descriptors."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACCEPTED = {"design-verified", "source", "request", "user-approved"}

def run(generate=True):
    issues = []
    sheets = {}
    for name in ("flight", "render", "input", "platform", "runtime", "environment", "hud", "terrain", "units", "extrusion", "crash", "performance", "world", "navigation", "combat", "bridges", "network", "defense", "force", "support-effects", "ground"):
        path = ROOT / "design" / (name + ".sheet.json")
        if not path.exists():
            issues.append(f"{name}: missing sheet")
            continue
        sheets[name] = json.loads(path.read_text(encoding="utf-8-sig"))
    for name in ("flight", "input", "render", "runtime", "environment", "hud", "terrain", "units", "extrusion", "crash", "performance", "world", "navigation", "combat", "bridges", "network", "defense", "force", "support-effects", "ground"):
        sheet = sheets.get(name, {})
        seen = set()
        for row in sheet.get("parameters", []):
            for column in ("name", "value", "verification"):
                if column not in row or row[column] is None or row[column] == "":
                    issues.append(f"{name}.{row.get('name','?')}.{column}: unfilled")
            if row.get("verification") not in ACCEPTED:
                issues.append(f"{name}.{row.get('name')}: unverified design")
            if row.get("name") in seen:
                issues.append(f"{name}: duplicate parameter {row['name']}")
            seen.add(row.get("name"))
    architecture = json.loads((ROOT / "design/architecture.sheet.json").read_text(encoding="utf-8-sig"))
    hooks = {h["id"] for h in architecture["hooks"]}
    for row in architecture["systems"]:
        for ref in row["hookRefs"]:
            if ref not in hooks:
                issues.append(f"architecture.{row['id']}: missing hook {ref}")
    for name, sheet in sheets.items():
        def walk(value, path):
            if value is None:
                issues.append(f"{path}: unfilled")
            elif isinstance(value, dict):
                for k, v in value.items():
                    if k not in {"acceptance", "tests", "runtimeVerified", "testResults"}:
                        walk(v, f"{path}.{k}")
            elif isinstance(value, list):
                for i, v in enumerate(value):
                    walk(v, f"{path}[{i}]")
        walk(sheet, name)
    report = {"scope":"native walker articulation, shared control, creator flight and nearest projected surfaces milestone0.10", "designPassed":not issues, "issues":issues,
              "runtimeAcceptance":"pending until tests execute; never inferred from design preflight",
              "roadmapOnlyDecisions":[r["id"] for r in architecture["decisions"] if r["verification"] == "pending"]}
    (ROOT / "build").mkdir(exist_ok=True)
    (ROOT / "build/preflight.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if issues:
        raise SystemExit("Preflight failed:\n" + "\n".join(issues))
    if generate:
        out = ROOT / "build/generated/fpv/generated"
        out.mkdir(parents=True, exist_ok=True)
        args = ",\n            ".join(str(p["value"]) for p in sheets["flight"]["parameters"])
        (out / "FlightProfiles.java").write_text("package fpv.generated;\nimport fpv.flight.FlightController;\npublic final class FlightProfiles {\n public static FlightController.Profile create(){\n return new FlightController.Profile(" + args + ");\n }\n}\n", encoding="utf-8")
        input_args = []
        for p in sheets["input"]["parameters"]:
            v = p["value"]
            input_args.append("new String[]{" + ",".join(json.dumps(x) for x in v) + "}" if isinstance(v,list) else str(v))
        (out / "InputProfiles.java").write_text("package fpv.generated;\nimport fpv.input.FpvDesktopInput;\npublic final class InputProfiles {\n public static FpvDesktopInput.Policy create(){return new FpvDesktopInput.Policy(" + ",".join(input_args) + ");}\n}\n", encoding="utf-8")
        constants = "\n".join(" public static final double " + p["name"] + " = " + str(p["value"]) + ";" for p in sheets["runtime"]["parameters"])
        constants += "\n public static final int[] TIME_PERCENT = {" + ",".join(map(str,sheets["runtime"]["buildTimePercent"]["values"])) + "};"
        (out / "RuntimeConfig.java").write_text("package fpv.generated;\npublic final class RuntimeConfig {\n" + constants + "\n}\n", encoding="utf-8")
        constants = "\n".join(" public static final double " + p["name"] + " = " + str(p["value"]) + ";" for p in sheets["render"]["parameters"])
        (out / "RenderConfig.java").write_text("package fpv.generated;\npublic final class RenderConfig {\n" + constants + "\n}\n", encoding="utf-8")
        for name, classname in (("environment", "EnvironmentConfig"), ("hud", "HudConfig"), ("terrain", "TerrainConfig"), ("units", "UnitsConfig"), ("extrusion", "ExtrusionConfig"), ("crash", "CrashConfig"), ("performance", "PerformanceConfig"), ("navigation", "NavigationConfig"), ("network", "NetworkConfig"), ("defense", "DefenseConfig"), ("force", "ForceConfig"), ("support-effects", "SupportEffectsConfig"), ("ground", "GroundConfig")):
            constants = "\n".join(" public static final double " + p["name"] + " = " + str(float(p["value"])) + ";" for p in sheets[name]["parameters"])
            (out / (classname + ".java")).write_text("package fpv.generated;\npublic final class " + classname + " {\n" + constants + "\n}\n", encoding="utf-8")
        records = []
        for row in sheets["runtime"]["systems"]:
            records.append(" new SystemDescriptor(" + ",".join(json.dumps(row[c]) for c in ("id","descriptor","hook","policy")) + ")")
        for name in ("render","input","platform","environment","hud","terrain","units","extrusion","crash","performance","world","navigation","combat","bridges","network","defense","force","support-effects","ground"):
            # A typed descriptor for each component row retains the complete reviewed source sheet.
            records.append(" new SystemDescriptor(" + ",".join(json.dumps(v) for v in (name,name+"Policy",name+".sheet.json",json.dumps(sheets[name],separators=(",",":")))) + ")")
        (out / "DesignManifest.java").write_text("package fpv.generated;\npublic final class DesignManifest {\n public record SystemDescriptor(String id,String type,String hook,String policy){}\n public static final SystemDescriptor[] SYSTEMS={\n" + ",\n".join(records) + "\n };\n}\n", encoding="utf-8")
    print("Design preflight passed: scoped sheets filled, parameter verification and references valid. Runtime checks remain separate.")
    return sheets

if __name__ == "__main__":
    run()
