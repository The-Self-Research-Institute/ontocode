import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import common

M2 = Path(os.environ.get("M2_REPO", Path.home() / ".m2" / "repository"))
OWN_GROUP = "self.research"
DEP_LINE = re.compile(r"^\s+([^:\s]+):([^:\s]+):[^:\s]+:(?:[^:\s]+:)?([^:\s]+):(\w+)")


def local(tag):
    return tag.split("}")[-1]


def child(el, name):
    return next((c for c in el if local(c.tag) == name), None)


def text(el, name):
    c = child(el, name)
    return (c.text or "").strip() if c is not None else ""


def pom_path(group, artifact, version):
    return M2 / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.pom"


def pom_licences(group, artifact, version, depth=0):
    path = pom_path(group, artifact, version)
    if not path.exists() or depth > 8:
        return None
    root = ET.parse(path).getroot()
    lic = child(root, "licenses")
    found = []
    if lic is not None:
        for l in lic:
            found.append(common.normalise(text(l, "name"), text(l, "url")))
    if found:
        return found
    parent = child(root, "parent")
    if parent is None:
        return []
    return pom_licences(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"), depth + 1)


def run_dependency_list(extra_args, pom, out_name):
    cmd = ["mvn", "-B", "-q", "--offline" if os.environ.get("LICENCE_OFFLINE", "1") == "1" else "-B",
           "dependency:list", "-DincludeScope=runtime", f"-DoutputFile=target/{out_name}", "-Dsort=true"]
    if pom:
        cmd += ["-f", pom]
    subprocess.run(cmd + extra_args, cwd=common.ROOT, check=True, shell=os.name == "nt")


def collect(skip_run):
    files = []
    if not skip_run:
        run_dependency_list([], None, "licence-deplist.txt")
        run_dependency_list([], "ontology-desktop/pom.xml", "licence-deplist.txt")
    for path in common.ROOT.glob("**/target/licence-deplist.txt"):
        files.append(path)
    deps = {}
    for f in files:
        module = f.parent.parent.relative_to(common.ROOT).as_posix()
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            m = DEP_LINE.match(line)
            if m and not m.group(1).startswith(OWN_GROUP):
                deps.setdefault((m.group(1), m.group(2), m.group(3)), set()).add(module)
    return deps


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-run", action="store_true", help="reuse existing target/licence-deplist.txt files")
    args = ap.parse_args()
    policy = common.load_policy()
    rows = []
    for (group, artifact, version), modules in sorted(collect(args.skip_run).items()):
        lic = pom_licences(group, artifact, version)
        status, licence, note = common.judge(policy, "maven", f"{group}:{artifact}", version, lic)
        rows.append({"package": f"{group}:{artifact}", "version": version, "licence": licence,
                     "status": status, "note": note or ",".join(sorted(modules))})
    sys.exit(common.print_report(rows, "maven"))


if __name__ == "__main__":
    main()
