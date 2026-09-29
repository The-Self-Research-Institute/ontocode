"""Patch-coverage calculation: intersects an LCOV report with the set of
added/modified lines in each changed file, relative to a git merge-base.

Kept separate from coverage-wrapper.sh's orchestration so it can be unit
tested without invoking any real test runner (Playwright/Maven are slow and
not always available in every environment this repo is checked out into).
"""
import re
import subprocess
from pathlib import Path


def changed_lines(repo_root: Path, base: str, head: str, rel_path: str):
    """Returns the set of line numbers added or modified by `git diff` for a
    single file, computed from unified=0 hunk headers (the '+' side only —
    the lines that exist in `head` and need coverage there)."""
    out = subprocess.run(
        ["git", "diff", "--unified=0", base, head, "--", rel_path],
        cwd=repo_root, capture_output=True, text=True, check=False,
    ).stdout
    lines = set()
    for m in re.finditer(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", out, re.M):
        start = int(m.group(1))
        count = int(m.group(2)) if m.group(2) is not None else 1
        if count == 0:
            continue
        lines.update(range(start, start + count))
    return lines


def parse_lcov(lcov_text: str):
    """Returns {source_file_path: {line_number: hit_count}}."""
    records = {}
    current_file = None
    for line in lcov_text.splitlines():
        if line.startswith("SF:"):
            current_file = line[3:].strip().replace("\\", "/")
            records[current_file] = {}
        elif line.startswith("DA:") and current_file is not None:
            parts = line[3:].split(",")
            if len(parts) >= 2:
                try:
                    ln, hits = int(parts[0]), int(parts[1])
                    records[current_file][ln] = hits
                except ValueError:
                    continue
        elif line.strip() == "end_of_record":
            current_file = None
    return records


def match_lcov_file(lcov_records, rel_path_from_repo_root, lcov_base_dir_from_repo_root):
    """LCOV SF paths are typically relative to the coverage tool's own cwd
    (e.g. webview-src), not the repo root git diff uses. Strip the known
    base-dir prefix from the git-relative path to align them."""
    if lcov_base_dir_from_repo_root and rel_path_from_repo_root.startswith(lcov_base_dir_from_repo_root):
        candidate = rel_path_from_repo_root[len(lcov_base_dir_from_repo_root):].lstrip("/")
    else:
        candidate = rel_path_from_repo_root
    for sf in lcov_records:
        if sf == candidate or sf.endswith("/" + candidate) or candidate.endswith("/" + sf):
            return sf
    return None


def compute_patch_coverage(repo_root: Path, base: str, head: str, changed_files, lcov_text: str, lcov_base_dir: str):
    lcov_records = parse_lcov(lcov_text)
    per_file = {}
    total_eligible = 0
    total_covered = 0

    for rel_path in changed_files:
        added = changed_lines(repo_root, base, head, rel_path)
        sf = match_lcov_file(lcov_records, rel_path, lcov_base_dir)
        if sf is None:
            per_file[rel_path] = {"eligible": 0, "covered": 0, "note": "not in coverage report"}
            continue
        da = lcov_records[sf]
        eligible_lines = added & set(da.keys())
        covered_lines = {ln for ln in eligible_lines if da[ln] > 0}
        per_file[rel_path] = {
            "eligible": len(eligible_lines),
            "covered": len(covered_lines),
            "note": None if eligible_lines else "no instrumented lines among changed lines",
        }
        total_eligible += len(eligible_lines)
        total_covered += len(covered_lines)

    percentage = (100.0 * total_covered / total_eligible) if total_eligible > 0 else None
    return {
        "totalEligibleChangedLines": total_eligible,
        "totalCoveredChangedLines": total_covered,
        "patchCoveragePercent": percentage,
        "perFile": per_file,
    }


def _main(argv):
    import argparse
    import json
    import sys

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--lcov-file", required=True)
    parser.add_argument("--lcov-base-dir", default="")
    parser.add_argument("--changed-file", action="append", default=[], dest="changed_files")
    parser.add_argument("--min-percent", type=float, default=None)
    args = parser.parse_args(argv)

    repo_root = Path(args.repo_root).resolve()
    lcov_path = Path(args.lcov_file)
    if not lcov_path.exists():
        print(f"patch_coverage: LCOV report not found at {lcov_path}", file=sys.stderr)
        return 1
    lcov_text = lcov_path.read_text(encoding="utf-8", errors="replace")

    result = compute_patch_coverage(
        repo_root, args.base, args.head, args.changed_files, lcov_text, args.lcov_base_dir
    )
    print(json.dumps(result, indent=2))

    if result["totalEligibleChangedLines"] == 0:
        print("patch_coverage: 0 eligible changed lines — not applicable, not treated as 100%.", file=sys.stderr)
        return 0

    if args.min_percent is not None and (result["patchCoveragePercent"] or 0) < args.min_percent:
        print(
            f"patch_coverage: {result['patchCoveragePercent']:.1f}% is below the "
            f"required {args.min_percent}% patch coverage target.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    import sys
    raise SystemExit(_main(sys.argv[1:]))
