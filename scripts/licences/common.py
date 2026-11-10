import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "licence-policy" / "policy.json"

TEXT_RULES = [
    (r"apache.*(2\.0|v2)|\bASL 2", "Apache-2.0"),
    (r"apache.*1\.1", "Apache-1.1"),
    (r"eclipse public license.*(2\.0|v2)|\bEPL[- ]?2", "EPL-2.0"),
    (r"eclipse public license.*(1\.0|v1)|\bEPL[- ]?1", "EPL-1.0"),
    (r"eclipse distribution license|\bEDL\b|new bsd|bsd[- ]3|3[- ]clause|revised bsd", "BSD-3-Clause"),
    (r"bsd[- ]2|2[- ]clause|simplified bsd", "BSD-2-Clause"),
    (r"^bsd( license)?$|^the bsd license$", "BSD-3-Clause"),
    (r"^(the )?mit( license)?$|^mit-0$|expat", "MIT"),
    (r"mozilla public license.*2|\bMPL[- ]?2", "MPL-2.0"),
    (r"mozilla public license.*1\.1|\bMPL[- ]?1\.1", "MPL-1.1"),
    (r"cddl.*1\.1|common development and distribution license.*1\.1", "CDDL-1.1"),
    (r"cddl.*1\.0|common development and distribution license.*1\.0", "CDDL-1.0"),
    (r"cddl.*gpl|gpl.*cddl", "CDDL-1.1"),
    (r"gpl.*classpath|classpath exception|\bGPL2 w/ ?CPE", "GPL-2.0-with-classpath-exception"),
    (r"(lesser general public license|lgpl).*3", "LGPL-3.0"),
    (r"lesser general public license|\blgpl", "LGPL-2.1"),
    (r"affero|\bagpl", "AGPL-3.0"),
    (r"(general|gnu) public license.*3|\bgpl[- ]?3", "GPL-3.0"),
    (r"(general|gnu) public license|\bgpl", "GPL-2.0"),
    (r"bouncy ?castle", "MIT"),
    (r"\bbsd\b", "BSD-3-Clause"),
    (r"public domain|cc0", "CC0-1.0"),
    (r"unlicense", "Unlicense"),
    (r"^isc", "ISC"),
    (r"zlib", "Zlib"),
]
URL_RULES = [
    (r"apache\.org/licenses/LICENSE-2\.0", "Apache-2.0"),
    (r"eclipse\.org/legal/epl-2\.0|eclipse\.org/legal/epl-v20", "EPL-2.0"),
    (r"eclipse\.org/legal/epl-v10|eclipse\.org/legal/epl-1\.0", "EPL-1.0"),
    (r"eclipse\.org/org/documents/edl-v10", "BSD-3-Clause"),
    (r"opensource\.org/licenses/mit|mit-license", "MIT"),
]


def load_policy():
    with open(POLICY_PATH, encoding="utf-8") as f:
        return json.load(f)


def normalise(name, url=""):
    name = (name or "").strip()
    if not name and not url:
        return "unknown"
    for pattern, spdx in TEXT_RULES:
        if re.search(pattern, name, re.I):
            return spdx
    for pattern, spdx in URL_RULES:
        if re.search(pattern, url or "", re.I):
            return spdx
    if name and not re.search(r"\s", name):
        return name
    return "unknown" if not name else "unknown:" + name


def _matches(patterns, value):
    return any(re.fullmatch(p.replace("*", ".*"), value, re.I) for p in patterns)


def classify_one(policy, spdx, package):
    if spdx in policy["allowed"]:
        return "ok"
    if package in policy["allowedByName"].get(spdx, []):
        return "ok"
    if spdx.startswith("unknown"):
        return "unknown"
    if _matches(policy["blocked"]["licences"], spdx) or spdx.startswith("LGPL"):
        return "blocked"
    return "unknown"


def eval_expression(policy, expr, package):
    tokens = re.findall(r"\(|\)|[^\s()]+", expr)
    pos = [0]

    def parse_or():
        results = [parse_and()]
        while pos[0] < len(tokens) and tokens[pos[0]].upper() == "OR":
            pos[0] += 1
            results.append(parse_and())
        return best(results)

    def parse_and():
        results = [parse_atom()]
        while pos[0] < len(tokens) and tokens[pos[0]].upper() == "AND":
            pos[0] += 1
            results.append(parse_atom())
        return worst(results)

    def parse_atom():
        tok = tokens[pos[0]]
        pos[0] += 1
        if tok == "(":
            value = parse_or()
            pos[0] += 1
            return value
        if tok.endswith("+"):
            tok = tok[:-1]
        return classify_one(policy, tok, package)

    order = ["ok", "unknown", "blocked"]

    def best(r):
        return min(r, key=order.index)

    def worst(r):
        return max(r, key=order.index)

    if not tokens:
        return "unknown"
    return parse_or()


def find_entry(entries, package, version, ecosystem):
    for e in entries:
        if e.get("ecosystem", ecosystem) != ecosystem or e["package"] != package:
            continue
        if e.get("version") in (None, "*", version):
            return e
    return None


def judge(policy, ecosystem, package, version, spdx_list=None, expression=None):
    """Returns (status, licence_text, note). Status: ok, curated, baseline, blocked, unknown."""
    cur = find_entry(policy["curations"], package, version, ecosystem)
    if cur:
        status = eval_expression(policy, cur["licence"], package)
        return ("curated" if status == "ok" else status), cur["licence"], "curated"
    if expression is not None:
        text = expression
        status = eval_expression(policy, expression, package) if expression else "unknown"
    else:
        text = " OR ".join(spdx_list) if spdx_list else "unknown"
        statuses = [classify_one(policy, s, package) for s in spdx_list or []]
        status = "unknown" if not statuses else min(statuses, key=["ok", "unknown", "blocked"].index)
    if status == "ok":
        return "ok", text, ""
    if _matches(policy["blocked"]["names"], package):
        return "blocked", text, "blocked name"
    if status == "unknown":
        base = find_entry(policy["baseline"], package, version, ecosystem)
        if base:
            return "baseline", text, base["reason"]
    return status, text, ""


def print_report(rows, title):
    counts = {}
    for r in rows:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    for r in rows:
        if r["status"] in ("blocked", "unknown", "baseline"):
            print(f'{r["status"].upper():9} {r["package"]}:{r["version"]}  [{r["licence"]}]  {r["note"]}')
    print(f"{title}: {len(rows)} checked, " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 1 if counts.get("blocked") or counts.get("unknown") else 0
