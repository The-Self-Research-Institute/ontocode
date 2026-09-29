#!/usr/bin/env bash
# Single documented section 14 pre-flight entry point: run this
# before requesting review and before merge.
#
# Requires Git Bash on Windows (the same shell this repo's other *.sh
# scripts and CI already assume) plus Maven, Node/npm, and Python 3 on PATH.
# From a clean checkout: `git config core.hooksPath .githooks` once, then
# `bash scripts/preflight.sh` before every PR.
#
# What it does, for every sub-project with changes between base-ref and
# head-ref (base-ref defaults to origin/develop, head-ref to HEAD):
#   1. Compiles/packages affected Java modules and runs their tests (mvn).
#   2. Typechecks, lints, and builds affected TypeScript sub-projects (npm).
#   3. Runs scripts/coverage-wrapper.sh for patch coverage.
#   4. Runs scripts/check-file-size.py against every changed file (not just
#      staged — this is the full pre-merge gate, pre-commit is the fast
#      staged-only version).
#
# A missing tool, a missing test suite, or a missing coverage report is a
# FAIL here, not a skip — see section 14 ("missing tools or
# skipped suites must be reported explicitly").
set -uo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

base_ref="${1:-origin/develop}"
head_ref="${2:-HEAD}"
merge_base=$(git merge-base "$base_ref" "$head_ref" 2>/dev/null)
head_sha=$(git rev-parse "$head_ref" 2>/dev/null)

if [ -z "$merge_base" ] || [ -z "$head_sha" ]; then
    echo "preflight: could not resolve base=$base_ref / head=$head_ref against this checkout." >&2
    exit 1
fi

changed_files=$(git diff --name-only "$merge_base" "$head_ref")
overall_status=0
summary=()

section() {
    echo "" >&2
    echo "=== $1 ===" >&2
}

record() {
    summary+=("$1")
}

section "pre-flight: base=$merge_base ($base_ref) head=$head_sha ($head_ref)"

java_modules=$(echo "$changed_files" | grep -E '\.java$' | sed -E 's#^(([^/]+/)?[^/]+)/src/.*#\1#' | sort -u || true)
for module in $java_modules; do
    [ -d "$module" ] || continue
    section "Java: $module (compile + test)"
    if mvn -q -pl "$module" -am test; then
        record "PASS  java:$module (compile+test)"
    else
        record "FAIL  java:$module (compile+test)"
        overall_status=1
    fi
done

if echo "$changed_files" | grep -qE '^ontology-vscode-extension/src/'; then
    section "TypeScript: ontology-vscode-extension (extension host)"
    if command -v npm >/dev/null 2>&1; then
        if (cd ontology-vscode-extension && npm run compile && npm run lint); then
            record "PASS  extension-host (compile+lint)"
        else
            record "FAIL  extension-host (compile+lint)"
            overall_status=1
        fi
    else
        record "FAIL  extension-host (compile+lint) — npm not on PATH"
        overall_status=1
    fi
fi

if echo "$changed_files" | grep -qE '^ontology-vscode-extension/webview-src/'; then
    section "TypeScript: ontology-vscode-extension/webview-src"
    if command -v npm >/dev/null 2>&1; then
        if (cd ontology-vscode-extension/webview-src && npm run typecheck && npm run lint && npm run build); then
            record "PASS  webview-src (typecheck+lint+build)"
        else
            record "FAIL  webview-src (typecheck+lint+build)"
            overall_status=1
        fi
    else
        record "FAIL  webview-src (typecheck+lint+build) — npm not on PATH"
        overall_status=1
    fi
fi

section "File-size gate (all changed files, not just staged)"
if command -v python3 >/dev/null 2>&1; then
    if python3 scripts/check-file-size.py "$repo_root" $changed_files; then
        record "PASS  file-size gate"
    else
        record "FAIL  file-size gate"
        overall_status=1
    fi
else
    record "FAIL  file-size gate — python3 not on PATH"
    overall_status=1
fi

section "Patch coverage"
if bash scripts/coverage-wrapper.sh "$base_ref" "$head_ref"; then
    record "PASS  patch coverage"
else
    record "FAIL  patch coverage"
    overall_status=1
fi

section "Summary"
for line in "${summary[@]}"; do
    echo "$line" >&2
done
echo "" >&2
echo "base:  $merge_base ($base_ref)" >&2
echo "head:  $head_sha ($head_ref)" >&2
if [ "$overall_status" -eq 0 ]; then
    echo "preflight: ALL CHECKS PASSED" >&2
else
    echo "preflight: FAILED — see FAIL lines above. This is the reviewer-visible verification evidence section 14 asks for; re-run after any further change." >&2
fi
exit "$overall_status"
