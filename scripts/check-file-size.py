#!/usr/bin/env python3
"""Section-3 file-size gate for staged/changed files against baseline.json.

Classifies each given file into a section-3 category, counts its
nonblank physical lines from the given content (not necessarily the working
tree, so this works against staged blobs for partially-staged files), and
fails (nonzero exit) when:
  - the file exceeds its review threshold and has no baseline.json entry, or
  - the file has a baseline.json entry but now measures higher than the
    entry's recorded value (baseline must not worsen, per section 14).

Usage: check-file-size.py <repo-root> <file1> [file2 ...]
Each <fileN> is read from stdin-independent sources: pass "-" delimited
path=content pairs is not supported here; instead this script re-reads each
file from disk relative to <repo-root>. Callers that need staged (not
working-tree) content should write the staged blob to the real path first
(the pre-commit hook does this via a temporary checkout of the index).
"""
import json
import re
import sys
from pathlib import Path

TEST_SUFFIXES = (".test.ts", ".test.tsx", ".spec.ts", ".spec.tsx", "Test.java", "Tests.java")

CATEGORY_RULES = [
    ("test-file", 600, lambda p, d: p.name.endswith(TEST_SUFFIXES)),
    ("react-component", 300, lambda p, d: p.suffix == ".tsx"
        and "/webview-src/" in d and "/hooks/" not in d and "/custom-hook/" not in d),
    ("custom-hook", 150, lambda p, d: p.suffix in (".ts", ".tsx")
        and ("/hooks/" in d or "/custom-hook/" in d)),
    ("java-controller", 250, lambda p, d: p.suffix == ".java" and "/controller/" in d),
    ("java-service", 400, lambda p, d: p.suffix == ".java" and "/service/" in d),
    ("utility-api-client", 300, lambda p, d: (
        p.suffix in (".ts", ".tsx") and ("/utils/" in d or "/services/" in d or "/config/" in d)
    ) or (p.suffix == ".java" and "/util/" in d)),
]


def classify(rel_path: str):
    p = Path(rel_path)
    marked = "/" + p.as_posix() + "/"
    for category, threshold, predicate in CATEGORY_RULES:
        if predicate(p, marked):
            return category, threshold
    return None, None


def nonblank_lines(text: str) -> int:
    return sum(1 for line in text.splitlines() if line.strip())


def load_baseline(repo_root: Path):
    baseline_path = repo_root / "baseline.json"
    if not baseline_path.exists():
        return {}
    data = json.loads(baseline_path.read_text(encoding="utf-8"))
    return {e["file"]: e for e in data.get("entries", [])}


def main(argv):
    if len(argv) < 3:
        print("usage: check-file-size.py <repo-root> <file1> [file2 ...]", file=sys.stderr)
        return 2

    repo_root = Path(argv[1]).resolve()
    baseline = load_baseline(repo_root)
    failures = []

    for rel in argv[2:]:
        rel_posix = Path(rel).as_posix()
        category, threshold = classify(rel_posix)
        if category is None:
            continue
        full = repo_root / rel_posix
        if not full.exists():
            continue
        try:
            text = full.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        measured = nonblank_lines(text)
        if measured <= threshold:
            continue

        entry = baseline.get(rel_posix)
        if entry is None:
            failures.append(
                f"NEW violation: {rel_posix} is {measured} nonblank lines "
                f"({category}, review threshold {threshold}) and has no baseline.json entry. "
                f"Either bring it under {threshold} or add a reviewer-approved baseline entry."
            )
        elif measured > entry.get("measuredNonblankLines", threshold):
            failures.append(
                f"WORSENED: {rel_posix} grew from {entry.get('measuredNonblankLines')} to "
                f"{measured} nonblank lines. baseline.json entries may not worsen without "
                f"explicit review (section 14)."
            )

    if failures:
        print("File-size gate failed:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
