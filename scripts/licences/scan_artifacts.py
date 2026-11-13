import argparse
import io
import json
import re
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import common
import check_maven

OWN_GROUP = "self.research"


def pom_info(zf):
    names = [n for n in zf.namelist() if re.fullmatch(r"META-INF/maven/[^/]+/[^/]+/pom\.xml", n)]
    if not names:
        return None
    root = ET.fromstring(zf.read(names[0]))
    parent = check_maven.child(root, "parent")
    group = check_maven.text(root, "groupId") or (check_maven.text(parent, "groupId") if parent is not None else "")
    version = check_maven.text(root, "version") or (check_maven.text(parent, "version") if parent is not None else "")
    licences = []
    lic = check_maven.child(root, "licenses")
    if lic is not None:
        licences = [common.normalise(check_maven.text(l, "name"), check_maven.text(l, "url")) for l in lic]
    if not licences and group and version:
        licences = check_maven.pom_licences(group, check_maven.text(root, "artifactId"), version) or []
    return group, check_maven.text(root, "artifactId"), version, licences


_boot, _m2 = {}, {}


def group_of(name, version):
    if not _boot:
        for f in common.ROOT.glob("**/target/licence-deplist.txt"):
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                m = check_maven.DEP_LINE.match(line)
                if m:
                    _boot.setdefault((m.group(2), m.group(3)), set()).add(m.group(1))
        for pom in check_maven.M2.glob("**/*.pom"):
            group = pom.parent.parent.parent.relative_to(check_maven.M2).as_posix().replace("/", ".")
            _m2.setdefault((pom.parent.parent.name, pom.parent.name), set()).add(group)
    for index in (_boot, _m2):
        groups = index.get((name, version), set())
        if len(groups) == 1:
            return next(iter(groups))
    return None


def file_parts(entry_name):
    stem = Path(entry_name).name[:-4]
    m = re.match(r"(.+?)-(\d[\w.+-]*)$", stem)
    return (m.group(1), m.group(2)) if m else (stem, "")


def scan_inner(policy, jar, entry_name, data):
    name, version = file_parts(entry_name)
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        info = pom_info(zf)
        shaded = [p for p in policy["blocked"]["classPrefixes"] if any(n.startswith(p) for n in zf.namelist())]
    licences = info[3] if info else []
    package = f"{info[0]}:{info[1]}" if info else name
    if info and info[2] and "$" not in info[2]:
        version = info[2]
    if info and info[0].startswith(OWN_GROUP):
        return None
    if not licences:
        group = group_of(name, version)
        if group:
            package = f"{group}:{name}"
            licences = check_maven.pom_licences(group, name, version) or []
    status, licence, note = common.judge(policy, "maven", package, version, licences)
    if common._matches(policy["blocked"]["names"], entry_name) or shaded:
        status, note = "blocked", "banned name or classes inside"
    return {"jar": jar, "package": package, "version": version, "licence": licence, "status": status, "note": note}


def scan_jar(policy, path):
    rows = []
    with zipfile.ZipFile(path) as zf:
        for entry in zf.namelist():
            if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                row = scan_inner(policy, path.name, entry, zf.read(entry))
                if row:
                    rows.append(row)
        top = [p for p in policy["blocked"]["classPrefixes"] if any(n.startswith(p) for n in zf.namelist())]
        if top:
            rows.append({"jar": path.name, "package": "(top-level classes)", "version": "", "licence": "-",
                         "status": "blocked", "note": "contains " + ",".join(top)})
    return rows


def expand(paths):
    for p in map(Path, paths):
        if not p.exists():
            sys.exit(f"path not found: {p}")
        yield from sorted(p.rglob("*.jar")) if p.is_dir() else [p]


def make_jar(path, nested):
    with zipfile.ZipFile(path, "w") as outer:
        for name, pom in nested.items():
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as inner:
                if pom:
                    inner.writestr("META-INF/maven/x/y/pom.xml", pom)
            outer.writestr("BOOT-INF/lib/" + name, buf.getvalue())


def pom(group, artifact, version, licence):
    return (f"<project><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>"
            f"<licenses><license><name>{licence}</name></license></licenses></project>")


def self_test():
    policy = common.load_policy()
    with tempfile.TemporaryDirectory() as tmp:
        cases = {
            "pellet.jar": ({"openllet-core-2.6.5.jar": None}, True),
            "gpl.jar": ({"bad-1.0.jar": pom("x", "bad", "1.0", "GNU General Public License v3.0")}, True),
            "mit.jar": ({"good-1.0.jar": pom("x", "good", "1.0", "MIT License")}, False),
        }
        ok = True
        for name, (nested, should_fail) in cases.items():
            path = Path(tmp) / name
            make_jar(path, nested)
            failed = common.print_report(scan_jar(policy, path), name) == 1
            print(f"  expected {'FAIL' if should_fail else 'PASS'}, got {'FAIL' if failed else 'PASS'}")
            ok = ok and failed == should_fail
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--report", help="write name, version and licence of every nested jar as JSON")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    policy = common.load_policy()
    rows = []
    for jar in expand(args.paths):
        rows += scan_jar(policy, jar)
    if args.report:
        Path(args.report).write_text(json.dumps(rows, indent=2), encoding="utf-8")
    sys.exit(common.print_report(rows, "artifacts"))


if __name__ == "__main__":
    main()
