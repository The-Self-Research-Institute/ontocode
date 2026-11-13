import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import common

PROJECTS = ["electron-app", "ontology-vscode-extension", "ontology-vscode-extension/webview-src"]


def projects():
    found = [common.ROOT / p for p in PROJECTS]
    found += sorted((common.ROOT / "plugins").glob("*/package-lock.json"))
    return [p.parent if p.name == "package-lock.json" else p for p in found if (p / "package-lock.json").exists() or p.name == "package-lock.json"]


def declared(entry, installed):
    value = entry.get("license")
    if value is None and installed.exists():
        value = json.loads(installed.read_text(encoding="utf-8")).get("license")
    if isinstance(value, dict):
        value = value.get("type")
    if isinstance(value, list):
        value = " OR ".join(v.get("type", "") if isinstance(v, dict) else v for v in value)
    return value or ""


def check_project(policy, project, include_dev):
    lock = json.loads((project / "package-lock.json").read_text(encoding="utf-8"))
    rows = []
    for key, entry in lock.get("packages", {}).items():
        if not key or entry.get("link") or (entry.get("dev") and not include_dev):
            continue
        name = key.split("node_modules/")[-1]
        expr = declared(entry, project / key / "package.json").replace("SEE LICENSE IN", "").strip()
        status, licence, note = common.judge(policy, "npm", name, entry.get("version", ""), expression=expr)
        rows.append({"package": name, "version": entry.get("version", ""), "licence": licence or "unknown",
                     "status": status, "note": note or project.relative_to(common.ROOT).as_posix()})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="include dev dependencies")
    args = ap.parse_args()
    policy = common.load_policy()
    rows = []
    seen = set()
    for project in projects():
        for r in check_project(policy, project, args.all):
            key = (r["package"], r["version"], r["note"])
            if key not in seen:
                seen.add(key)
                rows.append(r)
    sys.exit(common.print_report(rows, "npm"))


if __name__ == "__main__":
    main()
